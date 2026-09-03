package com.peakmotion.ebbfold.core

/**
 * Exponential low-pass filter for smoothing noisy hinge-angle sensor readings.
 *
 * Each call to [submit] moves [value] a fraction ([alpha]) of the way toward the raw
 * reading: `value += alpha * (raw - value)`. With `0 < alpha < 1` the filtered value
 * converges monotonically toward a constant input and never overshoots it.
 *
 * Behavior:
 * - Raw readings are clamped to `0..180` degrees before filtering.
 * - A NaN raw reading is ignored: [submit] returns the current [value] unchanged.
 * - The initial value is likewise sanitized: it is clamped to `0..180`, and a NaN
 *   initial value falls back to 180 (fully open). The public constructor signature is
 *   unchanged; this is purely defensive.
 *
 * Not thread-safe on its own; callers on multiple threads must synchronize externally.
 * Pure JVM — no Android dependencies.
 *
 * @param alpha smoothing factor in `(0, 1]`; higher = snappier, lower = smoother.
 * @param initial the starting filtered value, in degrees.
 */
class LowPassFilter(val alpha: Float = 0.15f, initial: Float = 180f) {

    /** The current filtered value in degrees. Updated by [submit]. */
    var value: Float = if (initial.isNaN()) AngleMapper.MAX_ANGLE
    else initial.coerceIn(0f, AngleMapper.MAX_ANGLE)
        private set

    /**
     * Feeds one raw sensor reading into the filter and returns the new filtered [value].
     * The raw reading is clamped to `0..180` first; a NaN reading is ignored and the
     * current [value] is returned unchanged.
     */
    fun submit(raw: Float): Float {
        if (raw.isNaN()) return value
        val clampedRaw = raw.coerceIn(0f, AngleMapper.MAX_ANGLE)
        value += alpha * (clampedRaw - value)
        return value
    }
}
