package com.peakmotion.ebbfold.core

import java.util.TreeMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import kotlin.math.abs

/**
 * A sliding-window cache of animation frames centered on the current frame index.
 *
 * The cache keeps loaded a contiguous window of frames `[center - radius, center + radius]`,
 * intersected with the valid range `[0, frameCount - 1]`. As the center moves (the hinge
 * angle changes), [update] loads the newly needed frames and evicts everything that fell
 * outside the window, keeping memory bounded to at most `2 * radius + 1` entries.
 *
 * Behavior:
 * - [update] clamps the requested center into `0..frameCount - 1`, evicts every cached
 *   entry outside the target window (calling [onEvict] with each evicted value before
 *   removing it), then calls [loader] only for window indices not already cached.
 *   [loader] is never invoked with an index outside `0..frameCount - 1`.
 * - [get] is an exact lookup: returns the cached value for that index, or null.
 * - [nearest] returns the cached value whose index is closest to the requested index
 *   (null if the cache is empty). Ties are broken toward the lower index.
 * - [clear] evicts every entry (calling [onEvict] for each) and empties the cache.
 *
 * Threading: [loader] and [onEvict] are ALWAYS invoked outside the cache's
 * monitor, so a slow load (a JPEG decode) never blocks a concurrent [get] or
 * [nearest] — the render thread can always read the cache immediately, even
 * mid-window-fill, and see every frame as soon as it lands. Concurrent
 * [update] calls are serialized against each other. Pure JVM — no Android
 * dependencies.
 *
 * @param frameCount total number of frames in the animation.
 * @param radius how many frames to keep loaded on each side of the center.
 * @param loader loads the frame for a given index; only called for indices in `0 until frameCount`.
 * @param onEvict called with each value as it is evicted (e.g. to recycle a bitmap).
 */
class FrameRingCache<T>(
    val frameCount: Int = 120,
    val radius: Int = 4,
    private val loader: (Int) -> T,
    private val onEvict: (T) -> Unit = {},
    /**
     * Optional always-resident frame index (clamped to range). It is loaded on
     * the first [update] and NEVER evicted by window moves — only [clear]
     * releases it. Used for the wallpaper's resting/cover frame, which must
     * stay drawable while the sliding window is anywhere else in the sequence.
     */
    pinnedIndex: Int? = null,
    /**
     * When set, the frames missing from an [update] window are loaded
     * concurrently on this executor (one task per frame, dispatched in load
     * order); [update] still returns only after every load has landed. When
     * null, loads run sequentially on the caller's thread.
     */
    private val loadExecutor: Executor? = null,
) {
    private val pinned: Int? = pinnedIndex?.coerceIn(0, frameCount - 1)

    /** Guards [cache]. Held only for map reads/writes — never across [loader]/[onEvict]. */
    private val lock = Any()

    /** Serializes [update] callers so two window fills never interleave. */
    private val updateLock = Any()

    /** Sorted by frame index, so ascending iteration is trivial (used by [nearest]). */
    private val cache = TreeMap<Int, T>()

    /**
     * Bumped by [clear]. A load that started before a clear must not resurface
     * afterwards, so [update] only publishes a loaded value if the generation
     * it observed when deciding to load is still current.
     */
    private var generation = 0L

    /**
     * Slides the window to be centered on [centerIndex] (clamped to the valid range):
     * evicts everything outside the new window, then loads only the missing frames.
     *
     * [direction] biases the window during a sweep (see [windowFor]) so the
     * decoder works AHEAD of playback. The window size never exceeds
     * `2 * radius + 1` and [loader] is never called out of range.
     *
     * Load order is pinned frame first, then center, then outward with the
     * direction side prioritized — the frame being drawn right now is always
     * decoded first. Each loaded frame is published to readers individually,
     * the moment its load completes.
     *
     * [isNeeded] is consulted right before each load (the pinned frame is
     * exempt); returning false skips that frame — lets a caller veto loads
     * that a newer, not-yet-processed window no longer wants.
     */
    fun update(
        centerIndex: Int,
        direction: Int = 0,
        isNeeded: (Int) -> Boolean = { true },
    ) = synchronized(updateLock) {
        val center = centerIndex.coerceIn(0, frameCount - 1)
        val window = windowFor(center, direction)
        val lowest = window.first
        val highest = window.last

        // Desired load order: pinned, center, then alternating outward with the
        // sweep direction's side first.
        val wanted = ArrayList<Int>(highest - lowest + 2)
        pinned?.let { wanted.add(it) }
        if (center in lowest..highest) wanted.add(center)
        for (step in 1..(highest - lowest)) {
            val first = center + step * (if (direction < 0) -1 else 1)
            val second = center - step * (if (direction < 0) -1 else 1)
            if (first in lowest..highest) wanted.add(first)
            if (second in lowest..highest) wanted.add(second)
        }

        // Under the lock: evict first (so memory stays bounded while the new
        // frames load) and decide what is missing. Loading happens unlocked.
        val evictedValues = ArrayList<T>()
        val missing = ArrayList<Int>()
        val observedGeneration: Long
        synchronized(lock) {
            observedGeneration = generation
            val iterator = cache.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if ((entry.key < lowest || entry.key > highest) && entry.key != pinned) {
                    evictedValues.add(entry.value)
                    iterator.remove()
                }
            }
            for (index in wanted) {
                if (!cache.containsKey(index) && index !in missing) missing.add(index)
            }
        }
        for (value in evictedValues) onEvict(value)

        val executor = loadExecutor
        if (executor == null) {
            for (index in missing) loadOne(index, observedGeneration, isNeeded)
        } else {
            val done = CountDownLatch(missing.size)
            for (index in missing) {
                val task = Runnable {
                    try {
                        loadOne(index, observedGeneration, isNeeded)
                    } finally {
                        done.countDown()
                    }
                }
                try {
                    executor.execute(task)
                } catch (rejected: RuntimeException) {
                    task.run() // executor shut down: fall back to inline
                }
            }
            done.await()
        }
    }

    /**
     * The window [update] uses for [centerIndex]/[direction]: symmetric
     * `[center - radius, center + radius]` for direction 0, shifted by
     * `radius / 2` toward the sweep direction otherwise — all clamped to the
     * frame range.
     */
    fun windowFor(centerIndex: Int, direction: Int = 0): IntRange {
        val center = centerIndex.coerceIn(0, frameCount - 1)
        val bias = if (direction == 0) 0 else (radius / 2) * (if (direction > 0) 1 else -1)
        return maxOf(0, center - radius + bias)..minOf(frameCount - 1, center + radius + bias)
    }

    private fun loadOne(index: Int, observedGeneration: Long, isNeeded: (Int) -> Boolean) {
        if (index != pinned && !isNeeded(index)) return
        val value = loader(index)
        val accepted = synchronized(lock) {
            if (generation != observedGeneration || cache.containsKey(index)) {
                false
            } else {
                cache[index] = value
                true
            }
        }
        if (!accepted) onEvict(value)
    }

    /** Exact lookup: the cached value for [index], or null if that frame is not cached. */
    fun get(index: Int): T? = synchronized(lock) { cache[index] }

    /**
     * Returns the cached value whose frame index is closest to [index], or null if the
     * cache is empty. Ties are broken toward the lower frame index.
     */
    fun nearest(index: Int): T? = synchronized(lock) { nearestKeyLocked(index)?.let { cache[it] } }

    /** The frame index of the entry [nearest] would return, or null if the cache is empty. */
    fun nearestIndex(index: Int): Int? = synchronized(lock) { nearestKeyLocked(index) }

    private fun nearestKeyLocked(index: Int): Int? {
        var best: Int? = null
        var bestDistance = Long.MAX_VALUE
        // Ascending iteration + strict '<' means the lower index wins a distance tie.
        for (key in cache.keys) {
            val distance = abs(key.toLong() - index.toLong())
            if (distance < bestDistance) {
                bestDistance = distance
                best = key
            }
        }
        return best
    }

    /** Evicts every cached entry (calling [onEvict] for each) and empties the cache. */
    fun clear() {
        val values = synchronized(lock) {
            generation++
            val copy = ArrayList(cache.values)
            cache.clear()
            copy
        }
        for (value in values) onEvict(value)
    }
}
