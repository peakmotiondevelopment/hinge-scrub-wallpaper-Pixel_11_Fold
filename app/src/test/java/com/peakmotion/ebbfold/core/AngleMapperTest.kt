package com.peakmotion.ebbfold.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec tests for [AngleMapper]: the animation is compressed into the visible
 * hinge range MIN_ANGLE..MAX_ANGLE (the inner display is off below MIN_ANGLE),
 * plus monotonicity, clamping, and total safety (no input crashes, every
 * output is a valid frame index).
 */
class AngleMapperTest {

    @Test
    fun constantsMatchContract() {
        assertEquals(120, AngleMapper.FRAME_COUNT)
        assertEquals(180f, AngleMapper.MAX_ANGLE, 0f)
        assertEquals(45f, AngleMapper.MIN_ANGLE, 0f)
    }

    @Test
    fun minAngleAndBelowMapToFrameZero() {
        assertEquals(0, AngleMapper.frameIndexFor(AngleMapper.MIN_ANGLE))
        assertEquals(0, AngleMapper.frameIndexFor(30f))
        assertEquals(0, AngleMapper.frameIndexFor(0f))
    }

    @Test
    fun fullyOpenMapsToLastFrame() {
        assertEquals(119, AngleMapper.frameIndexFor(180f))
    }

    @Test
    fun visibleRangeMidpointMapsToMidFrame() {
        // Midpoint of the visible range 45..180 is 112.5 degrees:
        // round((112.5 - 45) / 135 * 119) = round(59.5) = 60 (ties round up)...
        val index = AngleMapper.frameIndexFor(112.5f)
        assertEquals(60, index)
        // ...but the spec allows either side of the exact midpoint 59.5.
        assertTrue("midpoint index $index outside 59..60", index in 59..60)
    }

    @Test
    fun ninetyDegreesLandsProportionallyInVisibleRange() {
        // (90 - 45) / 135 * 119 = 39.67 → 40: a third of the way through.
        assertEquals(40, AngleMapper.frameIndexFor(90f))
    }

    @Test
    fun monotonicNonDecreasingAcrossFullSweep() {
        var previousIndex = -1
        var angle = 0f
        while (angle <= 180f) {
            val index = AngleMapper.frameIndexFor(angle)
            assertTrue(
                "index $index at angle $angle dropped below previous index $previousIndex",
                index >= previousIndex
            )
            previousIndex = index
            angle += 0.5f // 0.5 is exact in binary floating point, so this sweep hits 180 exactly
        }
        assertEquals("sweep should end at the last frame", 119, previousIndex)
    }

    @Test
    fun everyFrameIsReachableAcrossVisibleSweep() {
        // With 135 visible degrees for 120 frames, a fine sweep must hit every index.
        val seen = sortedSetOf<Int>()
        var angle = AngleMapper.MIN_ANGLE
        while (angle <= 180f) {
            seen.add(AngleMapper.frameIndexFor(angle))
            angle += 0.125f
        }
        assertEquals((0..119).toList(), seen.toList())
    }

    @Test
    fun negativeAngleClampsToFrameZero() {
        assertEquals(0, AngleMapper.frameIndexFor(-50f))
    }

    @Test
    fun overRotatedAngleClampsToLastFrame() {
        assertEquals(119, AngleMapper.frameIndexFor(500f))
    }

    @Test
    fun nanMapsToCalmFrameWithoutThrowing() {
        // NaN is documented to behave like 180 degrees (the calm, fully-open frame).
        assertEquals(119, AngleMapper.frameIndexFor(Float.NaN))
    }

    @Test
    fun everyOutputIsValidFrameIndexForBroadInputSweep() {
        val inputs = mutableListOf(
            Float.NaN,
            Float.NEGATIVE_INFINITY,
            Float.POSITIVE_INFINITY,
            -Float.MAX_VALUE,
            Float.MAX_VALUE,
            Float.MIN_VALUE
        )
        var angle = -400f
        while (angle <= 600f) {
            inputs.add(angle)
            angle += 0.25f
        }
        for (input in inputs) {
            val index = AngleMapper.frameIndexFor(input) // must not throw for any input
            assertTrue("index $index for input $input outside 0..119", index in 0..119)
        }
    }
}
