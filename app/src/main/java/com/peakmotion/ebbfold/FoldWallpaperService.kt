package com.peakmotion.ebbfold

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceHolder
import com.peakmotion.ebbfold.core.AngleMapper
import com.peakmotion.ebbfold.core.FrameAnimator
import com.peakmotion.ebbfold.core.FrameRingCache
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * "Ebb & Fold" — a live wallpaper whose 120-frame animation is scrubbed by the
 * physical hinge of a foldable device.
 *
 * Architecture: ONE shared model lives at the service level — hinge sensor →
 * [AngleMapper] target → [FrameAnimator] (eased, rate-capped chase, stepped
 * once per display vsync via Choreographer) → [FrameRingCache] of decoded
 * bitmaps (3 background decoders, 25-frame window biased 18 ahead). Playback
 * is decode-gated: the displayed frame only ever steps onto decoded frames,
 * and an engine repaints only when the frame on its surface would change.
 * Engines are thin views over that model. This matters on a foldable: the system may create a separate engine
 * per display (and tear engines down on fold/unfold), and per-engine state
 * would reset to defaults at exactly the moment the user is watching — an
 * empty cache (black flash) and a re-zeroed animator (animation starting at
 * the end). Shared state survives engine churn, so a fresh inner-display
 * engine paints the correct in-flight frame immediately.
 *
 * Behavior notes:
 *  - The sensor is registered while any non-preview engine is visible — cover
 *    display included, so the model keeps tracking the hinge while the device
 *    is closed and an unfold starts from the true current frame.
 *  - Cover-display surfaces (aspect ≥ 1.8) always DRAW the settled final
 *    frame, but no longer block model updates.
 *  - A fast fold outruns the cap and the displayed frame sweeps through every
 *    intermediate frame (~1.1 s full sweep) instead of jumping.
 *  - Devices without a hinge sensor show frame 119 statically; never crash.
 *  - Picker preview engines get their own private animator (auto-scrub
 *    45°→180°→45°) so previews never corrupt the real model; they share the
 *    bitmap cache.
 */
class FoldWallpaperService : WallpaperService(), SensorEventListener {

    private companion object {
        const val TAG = "EbbFoldWallpaper"

        /** Frame shown at rest, on the cover display, and when no hinge sensor exists. */
        const val LAST_FRAME = AngleMapper.FRAME_COUNT - 1

        /**
         * Frames kept decoded around the current frame (window = 2r+1 = 25).
         * Sweeping, the window is biased so 18 frames are prefetched ahead —
         * ~110 ms of the 160 f/s burst — enough to ride out the decode dip
         * while the system's own unfold animation competes for CPU.
         */
        const val CACHE_RADIUS = 12

        /** Concurrent JPEG decoders feeding the cache. */
        const val DECODE_THREADS = 3

        /** Surfaces at least this tall-per-wide are treated as the outer cover display. */
        const val COVER_ASPECT_THRESHOLD = 1.8f

        /** Preview auto-scrub: one full 0°→180°→0° sweep takes this long. */
        const val PREVIEW_PERIOD_MS = 4000L

        /** Preview tick interval (~30 fps). */
        const val PREVIEW_TICK_MS = 33L

        /**
         * Eased catch-up: the chase rate is EASE_PER_SECOND x remaining
         * frames, clamped to [MIN_PLAYBACK_FPS, MAX_PLAYBACK_FPS] — a flick
         * open bursts at 160 f/s and glides into the landing (~1 s total,
         * front-loaded, no overshoot). The brief peak above sustained decode
         * is absorbed by the warmed window + nearest-frame fallback.
         */
        const val MAX_PLAYBACK_FPS = 160f
        const val MIN_PLAYBACK_FPS = 40f
        const val EASE_PER_SECOND = 5f

        /**
         * Frame rate requested from the display while a sweep is running. On an
         * adaptive-refresh panel the wallpaper layer otherwise gets the "normal"
         * category (60 Hz here) whenever nothing else is boosting the display,
         * and because the sweep is vsync-driven it would then post at 60 Hz and
         * reinforce that vote — measured on-device as the cadence flipping
         * between 120 and 60 mid-sweep. 0 = no preference (released on settle).
         */
        const val SWEEP_FRAME_RATE = 120f

        /** Deep navy (#101C2C) placeholder, only ever visible briefly at first launch. */
        const val BACKGROUND_COLOR = 0xFF101C2C.toInt()

        /** Asset extensions tried in order when decoding a frame. */
        val FRAME_EXTENSIONS = arrayOf("jpg", "png")

        /** Evicted-bitmap reuse pool cap (window size + headroom). */
        const val BITMAP_POOL_MAX = 28

        // Unfold playback modes — see the state-machine comment on `mode`.
        const val MODE_TRACKING = 0
        const val MODE_INTRO = 1
        const val MODE_HOLD = 2

        /** HOLD → TRACKING when the hinge closes this many frames below its hold peak. */
        const val HOLD_CLOSE_FRAMES = 3
    }

    // ----------------------------------------------------------------------
    // Shared model (service scope; main thread unless noted)
    // ----------------------------------------------------------------------

    private val mainHandler = Handler(Looper.getMainLooper())

    private var sensorManager: SensorManager? = null
    private var hingeSensor: Sensor? = null
    private var sensorRegistered = false

    // NOTE: no low-pass filter in this path. The hinge sensor is on-change and
    // quantized (~5° steps), so an event-driven filter freezes mid-convergence
    // at stale values whenever the hinge pauses — observed on-device as the
    // animation running the wrong way at the start of each fold. The
    // time-driven FrameAnimator below is the smoothing: it glides between the
    // sensor's quantized steps at a capped rate and cannot freeze.
    private val animator = FrameAnimator(
        maxFramesPerSecond = MAX_PLAYBACK_FPS, initialFrame = LAST_FRAME,
        easePerSecond = EASE_PER_SECOND, minFramesPerSecond = MIN_PLAYBACK_FPS,
    )

    private val bitmapPool = java.util.concurrent.ConcurrentLinkedQueue<Bitmap>()

    private lateinit var cacheThread: HandlerThread
    private lateinit var cacheHandler: Handler
    private lateinit var frameCache: FrameRingCache<Bitmap>
    // Decoders run at BACKGROUND priority: at default priority three busy
    // decoders contend with the main and render threads for CPU during a
    // sweep, and the per-frame draw measured 7-8 ms on-device (vs 0.5 ms with
    // the decoders idle) — enough to miss every other vsync. The deep
    // prefetch window absorbs the slower decode.
    private val decodePool = Executors.newFixedThreadPool(DECODE_THREADS) { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "EbbFoldDecode")
    }

    /** Live engines, in creation order. Mutated on the main thread only. */
    private val engines = mutableListOf<FoldEngine>()

    private var animRunning = false

    /** Timestamp of the last animator step, System.nanoTime timebase (same as Choreographer). */
    private var animLastFrameTimeNanos = 0L

    /** Main-thread Choreographer; lazy because getInstance() is per-thread. */
    private val choreographer: Choreographer by lazy { Choreographer.getInstance() }

    /**
     * Unfold playback state machine (main thread only), per the Ebb & Flow
     * behavior: the cover→inner transition plays the FULL sequence 0→119 with
     * hinge input ignored (MODE_INTRO) — starting only once the inner surface
     * is actually VISIBLE, so the user sees every frame. When the intro lands
     * it HOLDS at the final frame (MODE_HOLD): opening further does nothing
     * (the animation is already settled), and hinge tracking (MODE_TRACKING)
     * resumes only when the hinge closes ≥3 frames below its hold peak (a
     * refold — scrub as usual) or reaches the fully-open zone (naturally in
     * sync). Closing to the cover cancels everything back to tracking.
     */
    private var mode = MODE_TRACKING
    private var holdPeak = 0
    private var pendingIntro = false

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SensorManager::class.java)
        hingeSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        if (hingeSensor == null) {
            Log.i(TAG, "No TYPE_HINGE_ANGLE sensor; wallpaper will be static at frame $LAST_FRAME.")
        }
        cacheThread = HandlerThread("EbbFoldFrameCache", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        cacheHandler = Handler(cacheThread.looper)
        frameCache = FrameRingCache(
            frameCount = AngleMapper.FRAME_COUNT,
            radius = CACHE_RADIUS,
            loader = ::decodeFrame,
            // Evicted bitmaps feed the reuse pool instead of the GC: frames
            // are all the same geometry/config, so decode fills them in place
            // (inBitmap) and steady-state sweeps allocate nothing.
            onEvict = { bitmap ->
                if (bitmapPool.size < BITMAP_POOL_MAX) bitmapPool.add(bitmap)
                else bitmap.recycle()
            },
            // The resting/cover frame stays resident no matter where the
            // sliding window goes — the cover display draws it at all times,
            // and a window snap to frame 0 (device closed) must not evict it.
            pinnedIndex = LAST_FRAME,
            loadExecutor = decodePool,
        )
        postCacheUpdate(animator.displayedFrame)
    }

    override fun onDestroy() {
        stopAnim()
        unregisterHingeSensor()
        cacheHandler.post { frameCache.clear() }
        cacheThread.quitSafely()
        decodePool.shutdown()
        super.onDestroy()
    }

    override fun onCreateEngine(): Engine = FoldEngine()

    // ------------------------------------------------------------------
    // Sensor path (shared)
    // ------------------------------------------------------------------

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_HINGE_ANGLE) return
        submitRawAngle(event.values[0])
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    /** Kicks off the deterministic full-sequence intro play (cover→inner). */
    private fun startIntroPlay() {
        mode = MODE_INTRO
        pendingIntro = false
        animator.retarget(0)
        animator.snap()
        animator.retarget(LAST_FRAME)
        postCacheUpdate(0, direction = 1)
        ensureAnimRunning()
    }

    /** Raw angle → target frame directly; the animator chases it rate-capped. */
    private fun submitRawAngle(rawDegrees: Float) {
        lastRawAngle = rawDegrees
        if (mode == MODE_INTRO) return // the unfold intro owns playback until it lands
        val rawIndex = AngleMapper.frameIndexFor(rawDegrees)
        if (mode == MODE_HOLD) {
            if (rawIndex > holdPeak) holdPeak = rawIndex
            val closing = rawIndex <= holdPeak - HOLD_CLOSE_FRAMES
            val fullyOpen = rawIndex >= LAST_FRAME - 2
            if (!closing && !fullyOpen) return // opening further: stay settled
            mode = MODE_TRACKING // refold (or naturally in sync): resume scrub
        }
        val index = rawIndex
        if (index != animator.targetFrame) {
            animator.retarget(index)
            val animatedEngineVisible =
                engines.any { it.isVisible && !it.isPreview && !it.showsPinnedCoverFrame }
            if (animatedEngineVisible) {
                ensureAnimRunning()
            } else {
                // Only the pinned cover (or nothing) is watching: sweeping
                // would decode the whole sequence invisibly. Snap the model and
                // warm the cache once, so the next unfold starts instantly.
                stopAnim()
                animator.snap()
                // Resting at frame 0 (closed): prefetch the opening burst so
                // the next intro starts with its first frames already decoded.
                postCacheUpdate(animator.displayedFrame, direction = if (animator.displayedFrame == 0) 1 else 0)
            }
        }
    }

    /** Register the hinge listener iff some non-preview engine is visible. */
    private fun updateSensorRegistration() {
        val wantRegistered =
            hingeSensor != null && engines.any { it.isVisible && !it.isPreview }
        if (wantRegistered && !sensorRegistered) {
            sensorManager?.registerListener(this, hingeSensor, SensorManager.SENSOR_DELAY_GAME)
            sensorRegistered = true
        } else if (!wantRegistered && sensorRegistered) {
            unregisterHingeSensor()
            stopAnim()
        }
    }

    private fun unregisterHingeSensor() {
        if (sensorRegistered) {
            sensorManager?.unregisterListener(this)
            sensorRegistered = false
        }
    }

    // ------------------------------------------------------------------
    // Catch-up playback (shared)
    // ------------------------------------------------------------------

    private var lastRawAngle = Float.NaN
    private var lastTelemetryUptimeMs = 0L

    // ---- rate-limited telemetry (kept: it is how the 2026-09-02 smoothness fixes were found) ----
    @Volatile private var decodeCount = 0
    @Volatile private var decodeTotalMs = 0L
    @Volatile private var decodeMaxMs = 0L
    private var longFrames = 0
    private var holdCount = 0
    private var gap120 = 0
    private var gap60 = 0
    private var gapOther = 0
    private var drawCount = 0
    private var drawTotalUs = 0L
    private var drawMaxUs = 0L

    // The catch-up sweep rides the display's vsync: Choreographer delivers one
    // callback per refresh with that frame's timestamp, so the animator steps
    // exactly once per displayed frame and each unlockCanvasAndPost lands in
    // its own refresh interval. (A fixed-interval Handler timer beats against
    // the 8.33 ms refresh period — periodic double-posts and skipped frames.)
    private val animFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!animRunning) return
            val before = animator.displayedFrame
            val elapsedNanos = frameTimeNanos - animLastFrameTimeNanos
            if (elapsedNanos > 12_000_000L) longFrames++
            val elapsedMs = elapsedNanos / 1_000_000f
            when {
                elapsedMs >= 7f && elapsedMs <= 10.5f -> gap120++
                elapsedMs >= 15f && elapsedMs <= 18.5f -> gap60++
                else -> gapOther++
            }
            var after = animator.tickNanos(elapsedNanos)
            animLastFrameTimeNanos = frameTimeNanos
            if (after != before) {
                val dir = if (after > before) 1 else -1
                // Decode-gated playback: never step onto a frame that is not
                // decoded. Hold on the last decoded one (the sweep stretches by
                // a vsync or two) instead of drawing a stand-in — a stand-in is
                // what showed up on-device as holds, jumps and flashes of the
                // resting frame.
                var show = after
                while (show != before && frameCache.get(show) == null) show -= dir
                if (show != after) {
                    animator.holdAt(show)
                    holdCount++
                    after = show
                }
                // Bias prefetch in the sweep direction so decode stays ahead.
                postCacheUpdate(after, direction = dir)
                if (after != before) drawVisibleEngines()
            }
            // Rate-limited signal-chain telemetry for on-device debugging.
            val now = SystemClock.uptimeMillis()
            if (now - lastTelemetryUptimeMs >= 150) {
                lastTelemetryUptimeMs = now
                val n = decodeCount
                val avgDecode = if (n > 0) decodeTotalMs / n else 0L
                Log.d(
                    TAG,
                    "chain raw=%.1f target=%d shown=%d | gaps 120hz=%d 60hz=%d other=%d long=%d hold=%d decode n=%d avg=%dms max=%dms | draw n=%d avg=%.1fms max=%.1fms".format(
                        Locale.US, lastRawAngle,
                        animator.targetFrame, animator.displayedFrame,
                        gap120, gap60, gapOther, longFrames, holdCount, n, avgDecode, decodeMaxMs,
                        drawCount, if (drawCount > 0) drawTotalUs / 1000f / drawCount else 0f, drawMaxUs / 1000f
                    )
                )
                gap120 = 0; gap60 = 0; gapOther = 0
                drawCount = 0; drawTotalUs = 0L; drawMaxUs = 0L
                longFrames = 0; holdCount = 0
                decodeCount = 0; decodeTotalMs = 0L; decodeMaxMs = 0L
            }
            if (animator.isSettled) {
                animRunning = false
                requestFrameRate(0f)
                if (mode == MODE_INTRO) {
                    // Intro landed: hold at the final frame; the hinge only
                    // takes over again on a refold or once it's fully open.
                    mode = MODE_HOLD
                    holdPeak = AngleMapper.frameIndexFor(lastRawAngle)
                }
            } else {
                choreographer.postFrameCallback(this)
            }
        }
    }

    private fun ensureAnimRunning() {
        if (animRunning || animator.isSettled) return
        animRunning = true
        animLastFrameTimeNanos = System.nanoTime()
        requestFrameRate(SWEEP_FRAME_RATE)
        choreographer.postFrameCallback(animFrameCallback)
    }

    private fun stopAnim() {
        animRunning = false
        choreographer.removeFrameCallback(animFrameCallback)
        requestFrameRate(0f)
    }

    /** Vote [fps] (0 = no preference) on every surface that shows the animated frame. */
    private fun requestFrameRate(fps: Float) {
        for (engine in engines) {
            if (!engine.isPreview && !engine.showsPinnedCoverFrame) engine.requestFrameRate(fps)
        }
    }

    /**
     * Repaints engines that actually show the animated frame. Cover-display
     * engines are skipped: they draw the pinned final frame, which never
     * changes with the model — repainting them per tick is wasted work and,
     * if the cover flag ever races a surface change, would leak animation
     * onto the closed device's outer screen. They repaint only on their own
     * surface/visibility events.
     */
    private fun drawVisibleEngines(includeCover: Boolean = false, force: Boolean = false) {
        for (engine in engines) {
            if (engine.isVisible && (includeCover || !engine.showsPinnedCoverFrame)) {
                engine.drawCurrentFrame(force)
            }
        }
    }

    // ------------------------------------------------------------------
    // Frame cache + decoding (shared; decode on background thread)
    // ------------------------------------------------------------------

    // Latest-wins cache updates: during a sweep, ticks can outpace decode; if
    // every tick queued a full update the decode thread would grind through
    // STALE window centers (frames the playhead already passed) exactly when
    // throughput is scarce, compounding into visible stutter. Only the most
    // recent request is ever pending.
    @Volatile private var latestCacheCenter = 0
    @Volatile private var latestCacheDirection = 0

    private val decodeCompleteRedraw = Runnable {
        // Cover engines are included here (unlike anim ticks): their pinned
        // frame is stable content, and this heals a cover that drew before
        // its frame decoded. Not forced: an engine whose on-screen frame is
        // already the wanted one paints nothing — during a sweep, updates
        // complete every few ms, and repainting the current frame on each
        // completion doubled the GPU work (measured: draw count ≈ 2× the
        // vsync callbacks, draw time ~8 ms, cadence dropping to 60 Hz).
        drawVisibleEngines(includeCover = true)
    }

    private val cacheUpdateRunnable = Runnable {
        // Loads are vetoed per frame against the NEWEST requested window, so a
        // window the playhead has already left does not keep the decoders busy.
        frameCache.update(latestCacheCenter, latestCacheDirection) { index ->
            index in frameCache.windowFor(latestCacheCenter, latestCacheDirection)
        }
        mainHandler.removeCallbacks(decodeCompleteRedraw)
        mainHandler.post(decodeCompleteRedraw)
    }

    private fun postCacheUpdate(index: Int, direction: Int = 0) {
        latestCacheCenter = index
        latestCacheDirection = direction
        cacheHandler.removeCallbacks(cacheUpdateRunnable)
        cacheHandler.post(cacheUpdateRunnable)
    }

    /** Cache loader; runs on [cacheThread]. Must never throw. */
    private fun decodeFrame(index: Int): Bitmap {
        val decodeStart = SystemClock.elapsedRealtime()
        val options = BitmapFactory.Options().apply {
            // 565: fastest decode of the three configs measured on-device
            // (565 / 8888 / hardware — draw cost was identical), half the
            // memory of 8888; no visible loss on photographic frames.
            inPreferredConfig = Bitmap.Config.RGB_565
            inMutable = true
            inBitmap = bitmapPool.poll()?.takeIf { it.config == inPreferredConfig }
        }
        // Frame sets may ship as JPEG (photographic) or PNG (generated).
        for (ext in FRAME_EXTENSIONS) {
            val assetPath = String.format(Locale.US, "frames/frame_%03d.%s", index, ext)
            val decoded = runCatching {
                assets.open(assetPath).use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                }
            }.getOrElse {
                // inBitmap can reject a mismatched candidate — retry cleanly.
                options.inBitmap = null
                runCatching {
                    assets.open(assetPath).use { stream ->
                        BitmapFactory.decodeStream(stream, null, options)
                    }
                }.getOrNull()
            }
            if (decoded != null) {
                val dt = SystemClock.elapsedRealtime() - decodeStart
                decodeCount++
                decodeTotalMs += dt
                if (dt > decodeMaxMs) decodeMaxMs = dt
                return decoded
            }
        }
        // A missing or corrupt asset must not crash the cache thread; hand
        // back a solid background-colored placeholder instead.
        Log.w(TAG, "Failed to decode frame $index; substituting solid placeholder.")
        return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
            eraseColor(BACKGROUND_COLOR)
        }
    }

    // ----------------------------------------------------------------------
    // Engines: thin views over the shared model
    // ----------------------------------------------------------------------

    private inner class FoldEngine : Engine() {

        private var surfaceReady = false
        private var isCoverDisplay = false

        /** True when this engine draws the static pinned cover frame. */
        val showsPinnedCoverFrame: Boolean
            get() = isCoverDisplay

        // Preview-only state: the picker's auto-scrub must not disturb the
        // real hinge-driven model, so preview engines carry a private
        // animator (the decoded-bitmap cache is still shared).
        private var previewRunning = false
        private var previewLastLogMs = 0L
        private var previewStartUptimeMs = 0L
        private var previewLastUptimeMs = 0L
        private val previewAnimator: FrameAnimator by lazy {
            FrameAnimator(
                maxFramesPerSecond = MAX_PLAYBACK_FPS, initialFrame = LAST_FRAME,
                easePerSecond = EASE_PER_SECOND, minFramesPerSecond = MIN_PLAYBACK_FPS,
            )
        }

        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val srcRect = Rect()
        private val dstRect = Rect()

        private val previewTicker = object : Runnable {
            override fun run() {
                if (!previewRunning) return
                val now = SystemClock.uptimeMillis()
                val elapsed = (now - previewStartUptimeMs) % PREVIEW_PERIOD_MS
                val phase = elapsed.toFloat() / PREVIEW_PERIOD_MS // 0..1
                // Triangle wave across the VISIBLE hinge range (frames are
                // compressed into MIN_ANGLE..MAX_ANGLE): open then close.
                val span = AngleMapper.MAX_ANGLE - AngleMapper.MIN_ANGLE
                val rawAngle = if (phase < 0.5f) {
                    AngleMapper.MIN_ANGLE + phase * 2f * span
                } else {
                    AngleMapper.MIN_ANGLE + (1f - phase) * 2f * span
                }
                previewAnimator.retarget(AngleMapper.frameIndexFor(rawAngle))
                val before = previewAnimator.displayedFrame
                val after = previewAnimator.tick(now - previewLastUptimeMs)
                previewLastUptimeMs = now
                if (after != before) {
                    postCacheUpdate(after)
                    drawCurrentFrame()
                }
                mainHandler.postDelayed(this, PREVIEW_TICK_MS)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            engines.add(this)
            Log.i(TAG, "Engine created (preview=$isPreview, engines=${engines.size})")
        }

        override fun onDestroy() {
            stopPreviewLoop()
            engines.remove(this)
            updateSensorRegistration()
            Log.i(TAG, "Engine destroyed (preview=$isPreview, engines=${engines.size})")
            super.onDestroy()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                if (isPreview) {
                    startPreviewLoop()
                } else {
                    updateSensorRegistration()
                    if (pendingIntro && !showsPinnedCoverFrame) {
                        // The cover→inner transition happened while hidden —
                        // the intro was deferred to this moment so it plays
                        // from frame 0 on a screen the user can see.
                        startIntroPlay()
                    } else {
                        // Resume any interrupted catch-up sweep.
                        ensureAnimRunning()
                    }
                }
                // Never leave a blank surface: repaint the last-known frame.
                drawCurrentFrame(force = true)
            } else {
                stopPreviewLoop()
                updateSensorRegistration()
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surfaceReady = true
            hasDrawnContent = false
            onScreenIndex = -1
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceReady = true
            val wasCover = isCoverDisplay
            isCoverDisplay =
                width > 0 && height.toFloat() / width.toFloat() >= COVER_ASPECT_THRESHOLD
            if (isCoverDisplay != wasCover) {
                Log.i(
                    TAG,
                    "Surface ${width}x$height — cover-display heuristic " +
                        (if (isCoverDisplay) "ON (pinning frame $LAST_FRAME)" else "OFF")
                )
                if (isCoverDisplay) {
                    // Fold closed (possibly mid-intro): back to plain tracking.
                    mode = MODE_TRACKING
                    pendingIntro = false
                    // Make sure the pinned frame is decoded without moving the
                    // window — it may be pre-warming the next intro at frame 0.
                    postCacheUpdate(latestCacheCenter, latestCacheDirection)
                } else {
                    // Inner display just took over from the cover: play the
                    // full sequence deterministically (Ebb & Flow behavior) —
                    // but only once this surface is actually visible, so the
                    // user sees the intro from frame 0, not partway through.
                    if (isVisible) startIntroPlay() else pendingIntro = true
                }
            }
            // A surface that (re)appears mid-sweep must carry the sweep's vote.
            if (animRunning && !isCoverDisplay && !isPreview) requestFrameRate(SWEEP_FRAME_RATE)
            drawCurrentFrame(force = true)
        }

        /** Surface.setFrameRate on this engine's surface; no-op before the surface exists. */
        fun requestFrameRate(fps: Float) {
            if (!surfaceReady) return
            val surface = surfaceHolder?.surface ?: return
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    surface.setFrameRate(
                        fps, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                    )
                } else {
                    surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                }
            }.onFailure { Log.w(TAG, "setFrameRate($fps) failed", it) }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            super.onSurfaceDestroyed(holder)
        }

        // ------------------------------------------------------------------
        // Preview auto-scrub
        // ------------------------------------------------------------------

        private fun startPreviewLoop() {
            if (previewRunning) return
            previewRunning = true
            previewStartUptimeMs = SystemClock.uptimeMillis()
            previewLastUptimeMs = previewStartUptimeMs
            mainHandler.post(previewTicker)
        }

        private fun stopPreviewLoop() {
            previewRunning = false
            mainHandler.removeCallbacks(previewTicker)
        }

        // ------------------------------------------------------------------
        // Drawing (main thread; never decodes)
        // ------------------------------------------------------------------

        /** Has this engine painted real content yet (vs. nothing / placeholder)? */
        private var hasDrawnContent = false

        /** Frame index of the bitmap currently on this surface, or -1. */
        private var onScreenIndex = -1

        /**
         * Paints the frame this engine should show. Idempotent unless [force]:
         * if the wanted frame is already on screen nothing is drawn, so
         * decode-complete and other opportunistic repaints cost nothing.
         * Surface and visibility events pass [force] (the buffer may be new).
         */
        fun drawCurrentFrame(force: Boolean = false) {
            if (!surfaceReady) return
            val holder = surfaceHolder
            val drawStartNs = SystemClock.elapsedRealtimeNanos()
            val index = when {
                isCoverDisplay -> LAST_FRAME
                isPreview -> previewAnimator.displayedFrame
                else -> animator.displayedFrame
            }
            val exact = frameCache.get(index)
            val drawIndex: Int
            val bitmap = when {
                exact != null -> { drawIndex = index; exact }
                // The animated surface never shows a stand-in frame: the
                // gated sweep only steps onto decoded frames, so a miss here
                // means "keep what is on screen" — unless nothing real has
                // been painted yet (fresh surface), where the nearest frame
                // beats a blank.
                !isCoverDisplay && !isPreview && hasDrawnContent -> return
                else -> {
                    val near = frameCache.nearestIndex(index)
                    drawIndex = near ?: -1
                    near?.let { frameCache.get(it) }
                }
            }
            if (!force && hasDrawnContent && drawIndex == onScreenIndex) return
            runCatching {
                val canvas = holder.lockHardwareCanvas()
                try {
                    if (bitmap != null && !bitmap.isRecycled) {
                        drawCenterCrop(canvas, bitmap)
                        hasDrawnContent = true
                        onScreenIndex = drawIndex
                    } else {
                        // Nothing decoded yet — only ever visible briefly at
                        // the very first launch (the cache is service-scoped
                        // and survives engine churn).
                        canvas.drawColor(BACKGROUND_COLOR)
                    }
                } finally {
                    holder.unlockCanvasAndPost(canvas)
                }
            }.onFailure { error ->
                // A torn/destroyed surface or a bitmap recycled mid-draw is
                // recoverable: the next frame change or surface event redraws.
                Log.w(TAG, "Frame draw skipped", error)
            }
            val us = (SystemClock.elapsedRealtimeNanos() - drawStartNs) / 1000
            drawCount++; drawTotalUs += us; if (us > drawMaxUs) drawMaxUs = us
            if (isPreview) {
                val now = SystemClock.uptimeMillis()
                if (now - previewLastLogMs >= 1000) {
                    previewLastLogMs = now
                    val n = decodeCount
                    Log.d(TAG, "preview draw n=%d avg=%.1fms max=%.1fms | decode n=%d avg=%dms max=%dms".format(
                        Locale.US, drawCount, if (drawCount > 0) drawTotalUs / 1000f / drawCount else 0f, drawMaxUs / 1000f,
                        n, if (n > 0) decodeTotalMs / n else 0L, decodeMaxMs))
                    drawCount = 0; drawTotalUs = 0L; drawMaxUs = 0L
                    decodeCount = 0; decodeTotalMs = 0L; decodeMaxMs = 0L
                }
            }
        }

        /** Scales [bitmap] to fill the canvas, cropping overflow equally on both sides. */
        private fun drawCenterCrop(canvas: Canvas, bitmap: Bitmap) {
            val canvasW = canvas.width
            val canvasH = canvas.height
            val bitmapW = bitmap.width
            val bitmapH = bitmap.height
            if (canvasW <= 0 || canvasH <= 0 || bitmapW <= 0 || bitmapH <= 0) return

            val scale = max(canvasW.toFloat() / bitmapW, canvasH.toFloat() / bitmapH)
            val srcW = (canvasW / scale).toInt().coerceIn(1, bitmapW)
            val srcH = (canvasH / scale).toInt().coerceIn(1, bitmapH)
            val srcLeft = (bitmapW - srcW) / 2
            val srcTop = (bitmapH - srcH) / 2

            srcRect.set(srcLeft, srcTop, srcLeft + srcW, srcTop + srcH)
            dstRect.set(0, 0, canvasW, canvasH)
            canvas.drawBitmap(bitmap, srcRect, dstRect, bitmapPaint)
        }
    }
}
