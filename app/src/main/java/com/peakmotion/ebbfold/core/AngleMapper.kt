package com.peakmotion.ebbfold.core

import kotlin.math.roundToInt

/**
 * Maps a foldable hinge angle (in degrees) to a frame index of a 120-frame animation.
 *
 * The animation is compressed into the VISIBLE hinge range: on a Fold the
 * inner display only switches on at roughly [MIN_ANGLE] degrees, so mapping
 * frames across the full 0–180° would waste a third of the sequence on angles
 * nobody can see. Instead:
 *
 * - Angles at or below [MIN_ANGLE] map to frame 0.
 * - Angles from [MIN_ANGLE] to [MAX_ANGLE] map linearly onto frames 0–119,
 *   so 180 degrees (fully open) is frame 119.
 *
 * This also reproduces Google Ebb & Flow's "first open plays the whole
 * animation" behavior for free: while the device is closed the model settles
 * at frame 0, so every unfold starts the sequence from the beginning and the
 * rate-capped animator plays it through.
 *
 * Safety:
 * - The result is clamped to `0..119` as a final net, so every possible input
 *   (including infinities) yields a valid frame index.
 * - A NaN input never crashes: NaN is treated as 180 degrees → frame 119.
 *   Rationale: NaN means the hinge sensor gave us garbage, and the fully-open
 *   "calm" frame is the safest thing to show on a phone lying flat/open.
 *
 * Pure JVM — no Android dependencies.
 */
object AngleMapper {
    const val FRAME_COUNT = 120
    const val MAX_ANGLE = 180f

    /**
     * Hinge angle below which the inner display is off (the device is closed
     * enough that only the cover screen shows). Frames are compressed into
     * [MIN_ANGLE]..[MAX_ANGLE] so the whole sequence is visible.
     */
    const val MIN_ANGLE = 45f

    /**
     * Returns the frame index (`0..FRAME_COUNT - 1`) for the given hinge angle in degrees.
     * Never throws; see class KDoc for clamping and NaN behavior.
     */
    fun frameIndexFor(angleDegrees: Float): Int {
        // NaN comparisons are always false, so coerceIn would pass NaN straight through.
        // Handle it explicitly first: treat NaN as fully open (the calm frame).
        val safeAngle = if (angleDegrees.isNaN()) MAX_ANGLE else angleDegrees
        val clampedAngle = safeAngle.coerceIn(MIN_ANGLE, MAX_ANGLE)
        val fraction = (clampedAngle - MIN_ANGLE) / (MAX_ANGLE - MIN_ANGLE)
        val index = (fraction * (FRAME_COUNT - 1)).roundToInt()
        return index.coerceIn(0, FRAME_COUNT - 1)
    }
}
