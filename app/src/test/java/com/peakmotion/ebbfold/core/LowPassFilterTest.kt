package com.peakmotion.ebbfold.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec tests for [LowPassFilter] (PRD SC5):
 * default alpha, convergence toward a step input, no overshoot in either direction,
 * raw-input clamping, and NaN handling.
 */
class LowPassFilterTest {

    @Test
    fun defaultAlphaIsPointFifteen() {
        assertEquals(0.15f, LowPassFilter().alpha, 0f)
    }

    @Test
    fun defaultInitialValueIsFullyOpen() {
        assertEquals(180f, LowPassFilter().value, 0f)
    }

    @Test
    fun submitAppliesFilterFormulaAndReturnsNewValue() {
        val filter = LowPassFilter() // value = 180, alpha = 0.15
        val returned = filter.submit(0f)
        // 180 + 0.15 * (0 - 180) = 153
        assertEquals(153f, returned, 1e-4f)
        assertEquals(filter.value, returned, 0f)
    }

    @Test
    fun customAlphaIsRespected() {
        val filter = LowPassFilter(alpha = 0.5f, initial = 100f)
        assertEquals(50f, filter.submit(0f), 1e-4f)
    }

    @Test
    fun convergesToStepInputStrictlyAndWithinHundredIterations() {
        val filter = LowPassFilter() // starts at 180
        var previous = filter.value
        var converged = false
        for (iteration in 1..100) {
            val next = filter.submit(0f)
            assertTrue(
                "iteration $iteration: value $next did not strictly decrease from $previous",
                next < previous
            )
            previous = next
            if (next <= 0.5f) {
                converged = true
                break
            }
        }
        assertTrue(
            "did not get within 0.5 of 0 within 100 iterations (value=${filter.value})",
            converged
        )
    }

    @Test
    fun neverOvershootsBelowTargetWhenConverging() {
        val filter = LowPassFilter() // starts at 180
        repeat(1000) { iteration ->
            val value = filter.submit(0f)
            assertTrue("iteration $iteration: overshot below 0 (value=$value)", value >= 0f)
        }
    }

    @Test
    fun neverOvershootsAboveTargetWhenConverging() {
        val filter = LowPassFilter(initial = 5f)
        repeat(1000) { iteration ->
            val value = filter.submit(180f)
            assertTrue("iteration $iteration: overshot above 180 (value=$value)", value <= 180f)
        }
        // And it does actually converge upward toward 180.
        assertTrue("did not converge toward 180 (value=${filter.value})", filter.value > 179.5f)
    }

    @Test
    fun rawAboveRangeBehavesLikeMaxAngle() {
        val clampedFilter = LowPassFilter(initial = 90f)
        val referenceFilter = LowPassFilter(initial = 90f)
        assertEquals(referenceFilter.submit(180f), clampedFilter.submit(400f), 0f)
        // Stays identical over repeated submissions too.
        repeat(10) {
            assertEquals(referenceFilter.submit(180f), clampedFilter.submit(400f), 0f)
        }
    }

    @Test
    fun rawBelowRangeBehavesLikeZero() {
        val clampedFilter = LowPassFilter(initial = 90f)
        val referenceFilter = LowPassFilter(initial = 90f)
        assertEquals(referenceFilter.submit(0f), clampedFilter.submit(-100f), 0f)
        repeat(10) {
            assertEquals(referenceFilter.submit(0f), clampedFilter.submit(-100f), 0f)
        }
    }

    @Test
    fun nanRawIsIgnoredAndReturnsCurrentValue() {
        val filter = LowPassFilter(initial = 90f)
        filter.submit(100f)
        val before = filter.value
        assertEquals(before, filter.submit(Float.NaN), 0f)
        assertEquals(before, filter.value, 0f)
    }
}
