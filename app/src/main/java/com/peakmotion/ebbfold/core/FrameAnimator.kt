package com.peakmotion.ebbfold.core

/**
 * Rate-limited frame chaser: keeps a [displayedFrame] that advances toward
 * [targetFrame] at no more than [maxFramesPerSecond].
 *
 * The hinge sets the target; the wallpaper draws the displayed frame. When the
 * hinge moves slower than the cap the displayed frame tracks it 1:1 (direct
 * scrub feel). When the hinge moves faster than the cap — a quick flick open —
 * the displayed frame sweeps through every intermediate frame at the cap, so
 * the full animation plays out instead of jumping to the final frame (matching
 * Google's Ebb & Flow behavior).
 *
 * Pure JVM state machine; the caller supplies elapsed wall time via [tick].
 * Not thread-safe — confine to one thread (the service uses the main thread).
 */
class FrameAnimator(
    val maxFramesPerSecond: Float = 90f,
    initialFrame: Int = 0,
    val frameCount: Int = AngleMapper.FRAME_COUNT,
    /**
     * Ease-out shaping: when > 0, the chase rate is
     * `clamp(easePerSecond * remainingFrames, minFramesPerSecond,
     * maxFramesPerSecond)` — fast while far from the target, decelerating as
     * it closes (classic smooth-damp, front-loaded and overshoot-free).
     * When 0 (default), the rate is constant at [maxFramesPerSecond].
     */
    val easePerSecond: Float = 0f,
    val minFramesPerSecond: Float = 0f,
) {
    var displayedFrame: Int = initialFrame.coerceIn(0, frameCount - 1)
        private set

    var targetFrame: Int = displayedFrame
        private set

    /** Fractional frames accumulated from ticks too small to cross a frame. */
    private var fractional: Float = 0f

    val isSettled: Boolean
        get() = displayedFrame == targetFrame

    /** Point the animator at a new target (clamped to the frame range). */
    fun retarget(target: Int) {
        targetFrame = target.coerceIn(0, frameCount - 1)
    }

    /**
     * Jumps the displayed frame straight to the target with no sweep — used
     * when nothing on screen shows the animated frame (e.g. the device is
     * closed and the cover display pins the final frame), so animating would
     * only burn decode work invisibly.
     */
    fun snap() {
        displayedFrame = targetFrame
        fractional = 0f
    }

    /**
     * Pull the displayed frame back to [frame] without touching the target —
     * used when playback is gated on decoding and the frames past [frame]
     * are not ready yet. Sub-frame progress is discarded so the sweep resumes
     * cleanly from there instead of double-stepping on the next tick.
     */
    fun holdAt(frame: Int) {
        displayedFrame = frame.coerceIn(0, frameCount - 1)
        fractional = 0f
    }

    /**
     * Advance toward the target given [elapsedMs] of wall time; returns the new
     * displayed frame. Zero/negative elapsed and settled states are no-ops.
     */
    fun tick(elapsedMs: Long): Int = advance(elapsedMs / 1000f)

    /**
     * Same as [tick] with nanosecond precision — for vsync timestamps (e.g.
     * Choreographer's frameTimeNanos), whose 8.33 ms intervals would lose 4%
     * of real time if truncated to whole milliseconds.
     */
    fun tickNanos(elapsedNanos: Long): Int = advance(elapsedNanos / 1_000_000_000f)

    private fun advance(elapsedSeconds: Float): Int {
        if (isSettled || elapsedSeconds <= 0f) {
            fractional = 0f
            return displayedFrame
        }
        val remaining = kotlin.math.abs(targetFrame - displayedFrame)
        val rate = if (easePerSecond > 0f) {
            (easePerSecond * remaining).coerceIn(minFramesPerSecond, maxFramesPerSecond)
        } else {
            maxFramesPerSecond
        }
        fractional += rate * elapsedSeconds
        val wholeSteps = fractional.toInt()
        if (wholeSteps > 0) {
            fractional -= wholeSteps
            val remaining = targetFrame - displayedFrame
            val step = wholeSteps.coerceAtMost(kotlin.math.abs(remaining))
            displayedFrame += if (remaining > 0) step else -step
            if (isSettled) fractional = 0f
        }
        return displayedFrame
    }
}
