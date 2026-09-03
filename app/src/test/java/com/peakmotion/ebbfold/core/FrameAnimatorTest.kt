package com.peakmotion.ebbfold.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameAnimatorTest {

    @Test
    fun `starts settled at initial frame`() {
        val a = FrameAnimator(maxFramesPerSecond = 90f, initialFrame = 119)
        assertEquals(119, a.displayedFrame)
        assertEquals(119, a.targetFrame)
        assertTrue(a.isSettled)
    }

    @Test
    fun `retarget makes it unsettled until reached`() {
        val a = FrameAnimator(maxFramesPerSecond = 90f, initialFrame = 119)
        a.retarget(0)
        assertFalse(a.isSettled)
        assertEquals(0, a.targetFrame)
        assertEquals(119, a.displayedFrame) // no movement until tick
    }

    @Test
    fun `tick advances toward target at capped rate`() {
        val a = FrameAnimator(maxFramesPerSecond = 90f, initialFrame = 0)
        a.retarget(119)
        // 1000 ms at 90 f/s => exactly 90 frames forward.
        a.tick(1000)
        assertEquals(90, a.displayedFrame)
        assertFalse(a.isSettled)
    }

    @Test
    fun `never overshoots target in either direction`() {
        val up = FrameAnimator(maxFramesPerSecond = 90f, initialFrame = 100)
        up.retarget(110)
        up.tick(10_000)
        assertEquals(110, up.displayedFrame)
        assertTrue(up.isSettled)

        val down = FrameAnimator(maxFramesPerSecond = 90f, initialFrame = 20)
        down.retarget(5)
        down.tick(10_000)
        assertEquals(5, down.displayedFrame)
        assertTrue(down.isSettled)
    }

    @Test
    fun `fast open plays through intermediate frames, not a jump`() {
        val a = FrameAnimator(maxFramesPerSecond = 90f, initialFrame = 0)
        a.retarget(119) // hinge slammed open
        val seen = mutableListOf(a.displayedFrame)
        var guard = 0
        while (!a.isSettled && guard++ < 300) {
            a.tick(16) // ~60 Hz animation ticks
            if (seen.last() != a.displayedFrame) seen.add(a.displayedFrame)
        }
        assertEquals(119, a.displayedFrame)
        // Sweep must pass through many intermediates (not teleport):
        assertTrue("saw ${seen.size} frames", seen.size >= 60)
        // Strictly monotonic toward the target:
        assertEquals(seen, seen.sorted())
        // Per-tick step is bounded: 16 ms at 90 f/s = at most 2 frames per tick.
        seen.zipWithNext().forEach { (prev, next) ->
            assertTrue("step ${next - prev}", next - prev in 1..2)
        }
        // Full sweep duration ≈ 119 / 90 ≈ 1.32 s → ~83 ticks of 16 ms.
        assertTrue("took $guard ticks", guard in 70..100)
    }

    @Test
    fun `fractional progress accumulates across small ticks`() {
        val a = FrameAnimator(maxFramesPerSecond = 90f, initialFrame = 0)
        a.retarget(119)
        // 90 f/s = 0.09 frames per ms; a 5 ms tick is 0.45 frames.
        a.tick(5)
        assertEquals(0, a.displayedFrame)
        a.tick(5)
        assertEquals(0, a.displayedFrame) // 0.9 accumulated
        a.tick(5)
        assertEquals(1, a.displayedFrame) // 1.35 -> crosses 1
    }

    @Test
    fun `retarget mid-flight reverses cleanly`() {
        val a = FrameAnimator(maxFramesPerSecond = 90f, initialFrame = 0)
        a.retarget(119)
        a.tick(500) // at 45
        assertEquals(45, a.displayedFrame)
        a.retarget(10) // hinge closed again mid-sweep
        a.tick(500)
        assertEquals(10, a.displayedFrame)
        assertTrue(a.isSettled)
    }

    @Test
    fun `tracking small target changes is immediate at tick granularity`() {
        val a = FrameAnimator(maxFramesPerSecond = 90f, initialFrame = 60)
        a.retarget(61)
        a.tick(16)
        assertEquals(61, a.displayedFrame) // slow scrub stays 1:1 with the hinge
        assertTrue(a.isSettled)
    }

    @Test
    fun `targets are clamped to frame range`() {
        val a = FrameAnimator(maxFramesPerSecond = 90f, initialFrame = 0, frameCount = 120)
        a.retarget(500)
        assertEquals(119, a.targetFrame)
        a.retarget(-5)
        assertEquals(0, a.targetFrame)
    }

    @Test
    fun `zero and negative elapsed are no-ops`() {
        val a = FrameAnimator(maxFramesPerSecond = 90f, initialFrame = 0)
        a.retarget(119)
        a.tick(0)
        assertEquals(0, a.displayedFrame)
        a.tick(-50)
        assertEquals(0, a.displayedFrame)
    }

    @Test
    fun `settled ticks do not drift`() {
        val a = FrameAnimator(maxFramesPerSecond = 90f, initialFrame = 42)
        repeat(100) { a.tick(16) }
        assertEquals(42, a.displayedFrame)
        assertTrue(a.isSettled)
    }
}

class FrameAnimatorSnapTest {
    @org.junit.Test
    fun snapJumpsStraightToTarget() {
        val a = FrameAnimator(maxFramesPerSecond = 80f, initialFrame = 119)
        a.retarget(3)
        a.snap()
        org.junit.Assert.assertEquals(3, a.displayedFrame)
        org.junit.Assert.assertTrue(a.isSettled)
    }

    @org.junit.Test
    fun snapClearsFractionalProgress() {
        val a = FrameAnimator(maxFramesPerSecond = 80f, initialFrame = 0)
        a.retarget(119)
        a.tick(5) // partial fractional accumulation
        a.snap()
        org.junit.Assert.assertEquals(119, a.displayedFrame)
        // Post-snap, a new chase starts clean: 12.5ms at 80f/s = exactly 1 frame.
        a.retarget(0)
        a.tick(25)
        org.junit.Assert.assertEquals(117, a.displayedFrame)
    }

    @org.junit.Test
    fun snapWhenSettledIsNoOp() {
        val a = FrameAnimator(maxFramesPerSecond = 80f, initialFrame = 42)
        a.snap()
        org.junit.Assert.assertEquals(42, a.displayedFrame)
    }
}

class FrameAnimatorEasedTest {
    private fun eased(initial: Int) = FrameAnimator(
        maxFramesPerSecond = 160f, initialFrame = initial,
        easePerSecond = 5f, minFramesPerSecond = 40f,
    )

    @org.junit.Test
    fun easedSweepIsFrontLoaded() {
        val a = eased(0)
        a.retarget(119)
        a.tick(250) // first quarter second
        val early = a.displayedFrame
        // At peak 160 f/s the first 250 ms covers ~40 frames — over a third.
        org.junit.Assert.assertTrue("early=$early", early >= 35)
        // The last stretch is slow: from remaining ~8, rate is at the 40 floor.
        while (!a.isSettled) a.tick(8)
        org.junit.Assert.assertEquals(119, a.displayedFrame)
    }

    @org.junit.Test
    fun easedSweepDeceleratesMonotonically() {
        val a = eased(0)
        a.retarget(119)
        val steps = mutableListOf<Int>()
        var prev = 0
        while (!a.isSettled) {
            a.tick(50)
            steps.add(a.displayedFrame - prev)
            prev = a.displayedFrame
        }
        // Per-50ms steps must never grow (deceleration, allowing rounding jitter of 1).
        steps.zipWithNext().forEach { (s1, s2) ->
            org.junit.Assert.assertTrue("step grew $s1 -> $s2", s2 <= s1 + 1)
        }
        // And the first step must be far larger than the last (front-loading).
        org.junit.Assert.assertTrue(steps.first() >= steps.last() * 3)
    }

    @org.junit.Test
    fun easedNeverOvershoots() {
        val a = eased(10)
        a.retarget(100)
        a.tick(60_000)
        org.junit.Assert.assertEquals(100, a.displayedFrame)
        a.retarget(3)
        a.tick(60_000)
        org.junit.Assert.assertEquals(3, a.displayedFrame)
    }

    @org.junit.Test
    fun easedFullSweepFinishesAboutOneSecond() {
        val a = eased(0)
        a.retarget(119)
        var ms = 0
        while (!a.isSettled && ms < 5000) { a.tick(8); ms += 8 }
        org.junit.Assert.assertTrue("took ${ms}ms", ms in 600..1400)
    }

    @org.junit.Test
    fun easedFloorKeepsSmallMovesSnappy() {
        val a = eased(60)
        a.retarget(64) // 4-frame glide, rate floored at 40 f/s -> ~100 ms
        var ms = 0
        while (!a.isSettled && ms < 1000) { a.tick(8); ms += 8 }
        org.junit.Assert.assertTrue("took ${ms}ms", ms <= 160)
    }

    @org.junit.Test
    fun defaultRemainsLinear() {
        val a = FrameAnimator(maxFramesPerSecond = 90f, initialFrame = 0)
        a.retarget(119)
        a.tick(1000)
        org.junit.Assert.assertEquals(90, a.displayedFrame) // unchanged legacy behavior
    }
    @Test
    fun `tickNanos keeps sub-millisecond vsync remainders`() {
        // 120 vsyncs of a 120 Hz display = 8,333,333 ns each = 1.000 s total.
        // At 120 f/s that is 120 frames; truncating each interval to whole
        // milliseconds (8 ms) would lose 4% and land short of the target.
        val a = FrameAnimator(maxFramesPerSecond = 120f, initialFrame = 0)
        a.retarget(119)
        repeat(120) { a.tickNanos(8_333_333L) }
        assertEquals(119, a.displayedFrame)
        assertTrue(a.isSettled)
    }
    @Test
    fun `holdAt pulls the displayed frame back and keeps the target`() {
        val a = FrameAnimator(maxFramesPerSecond = 120f, initialFrame = 0)
        a.retarget(119)
        a.tick(200) // 24 frames
        assertEquals(24, a.displayedFrame)
        a.holdAt(20) // e.g. frames 21..24 were not decoded yet
        assertEquals(20, a.displayedFrame)
        assertEquals(119, a.targetFrame)
        assertFalse(a.isSettled)
        a.tick(100) // resumes from 20
        assertEquals(32, a.displayedFrame)
    }

    @Test
    fun `holdAt discards sub-frame progress`() {
        val a = FrameAnimator(maxFramesPerSecond = 120f, initialFrame = 0)
        a.retarget(119)
        a.tick(7) // 0.84 of a frame accumulated
        assertEquals(0, a.displayedFrame)
        a.holdAt(0)
        a.tick(7) // 0.84 again, not 1.68
        assertEquals(0, a.displayedFrame)
    }
}
