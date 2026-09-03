package com.peakmotion.ebbfold.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec tests for [FrameRingCache] (PRD SC5):
 * exact window contents (middle and both edges), moving-window eviction/loading,
 * loader index bounds under out-of-range centers, exact and nearest lookup, and clear.
 *
 * Frames are plain Ints (the value is its own index) so the recording loader and
 * evict hook can assert exactly which indices were touched.
 */
class FrameRingCacheTest {

    private val loaded = mutableListOf<Int>()
    private val evicted = mutableListOf<Int>()

    private fun newCache(frameCount: Int = 120, radius: Int = 4): FrameRingCache<Int> =
        FrameRingCache(
            frameCount = frameCount,
            radius = radius,
            loader = { index ->
                loaded.add(index)
                index
            },
            onEvict = { value -> evicted.add(value) }
        )

    @Test
    fun defaultsMatchContract() {
        val cache = FrameRingCache(loader = { it })
        assertEquals(120, cache.frameCount)
        assertEquals(4, cache.radius)
    }

    @Test
    fun updateInMiddleCachesExactlyTheNineFrameWindow() {
        val cache = newCache()
        cache.update(60)

        assertEquals((56..64).toList(), loaded.sorted())
        assertEquals(9, loaded.size)
        for (index in 56..64) {
            assertEquals(index, cache.get(index))
        }
        assertNull(cache.get(55))
        assertNull(cache.get(65))
        assertTrue("nothing should be evicted on first fill", evicted.isEmpty())
    }

    @Test
    fun updateAtZeroClipsWindowToStart() {
        val cache = newCache()
        cache.update(0)

        assertEquals((0..4).toList(), loaded.sorted())
        for (index in 0..4) {
            assertEquals(index, cache.get(index))
        }
        assertNull(cache.get(5))
    }

    @Test
    fun updateAtLastFrameClipsWindowToEnd() {
        val cache = newCache()
        cache.update(119)

        assertEquals((115..119).toList(), loaded.sorted())
        for (index in 115..119) {
            assertEquals(index, cache.get(index))
        }
        assertNull(cache.get(114))
    }

    @Test
    fun movingWindowEvictsOldEntriesAndLoadsOnlyNewOnes() {
        val cache = newCache()
        cache.update(60) // caches 56..64
        loaded.clear()

        cache.update(70) // window becomes 66..74

        assertEquals((56..64).toList(), evicted.sorted())
        assertEquals((66..74).toList(), loaded.sorted())
        for (index in 66..74) {
            assertEquals(index, cache.get(index))
        }
        for (index in 56..64) {
            assertNull("index $index should have been evicted", cache.get(index))
        }
    }

    @Test
    fun overlappingUpdateDoesNotReloadCachedFrames() {
        val cache = newCache()
        cache.update(60) // caches 56..64
        loaded.clear()

        cache.update(62) // window becomes 58..66; 58..64 already cached

        assertEquals("only the missing frames load", listOf(65, 66), loaded.sorted())
        assertEquals("only the frames outside the new window evict", listOf(56, 57), evicted.sorted())
    }

    @Test
    fun repeatedUpdateWithSameCenterIsANoOp() {
        val cache = newCache()
        cache.update(60)
        loaded.clear()
        evicted.clear()

        cache.update(60)

        assertTrue("no reloads for an unchanged window", loaded.isEmpty())
        assertTrue("no evictions for an unchanged window", evicted.isEmpty())
    }

    @Test
    fun negativeCenterClampsToZeroWindow() {
        val cache = newCache()
        cache.update(-5)

        assertEquals((0..4).toList(), loaded.sorted())
        assertTrue(loaded.all { it in 0..119 })
    }

    @Test
    fun oversizedCenterClampsToLastWindow() {
        val cache = newCache()
        cache.update(500)

        assertEquals((115..119).toList(), loaded.sorted())
        assertTrue(loaded.all { it in 0..119 })
    }

    @Test
    fun loaderNeverReceivesOutOfRangeIndices() {
        val cache = newCache()
        cache.update(-5)
        cache.update(500)
        cache.update(0)
        cache.update(119)
        cache.update(-1000)
        cache.update(Int.MAX_VALUE)
        cache.update(Int.MIN_VALUE)

        assertTrue("loader saw out-of-range index in $loaded", loaded.all { it in 0..119 })
    }

    @Test
    fun customFrameCountAndRadiusAreHonored() {
        val cache = newCache(frameCount = 10, radius = 2)
        cache.update(9)

        assertEquals((7..9).toList(), loaded.sorted())
        assertTrue(loaded.all { it in 0..9 })
    }

    @Test
    fun getReturnsNullBeforeAnyUpdate() {
        val cache = newCache()
        assertNull(cache.get(0))
        assertNull(cache.get(60))
        assertNull(cache.get(119))
    }

    @Test
    fun nearestReturnsNullWhenEmpty() {
        val cache = newCache()
        assertNull(cache.nearest(60))
    }

    @Test
    fun nearestReturnsClosestCachedEntry() {
        val cache = newCache()
        cache.update(60) // caches 56..64

        assertEquals(56, cache.nearest(0))
        assertEquals(56, cache.nearest(55))
        assertEquals(56, cache.nearest(56))
        assertEquals(60, cache.nearest(60))
        assertEquals(64, cache.nearest(70))
        assertEquals(64, cache.nearest(119))
    }

    @Test
    fun nearestIndexReturnsClosestCachedKey() {
        val cache = newCache()
        assertNull(cache.nearestIndex(60))
        cache.update(60) // caches 56..64
        assertEquals(56, cache.nearestIndex(0))
        assertEquals(60, cache.nearestIndex(60))
        assertEquals(64, cache.nearestIndex(119))
    }

    @Test
    fun clearEvictsEverything() {
        val cache = newCache()
        cache.update(60) // caches 56..64

        cache.clear()

        assertEquals((56..64).toList(), evicted.sorted())
        for (index in 56..64) {
            assertNull(cache.get(index))
        }
        assertNull(cache.nearest(60))
    }

    @Test
    fun clearOnEmptyCacheIsANoOp() {
        val cache = newCache()
        cache.clear()
        assertTrue(evicted.isEmpty())
    }

    @Test
    fun cacheIsUsableAgainAfterClear() {
        val cache = newCache()
        cache.update(60)
        cache.clear()
        loaded.clear()
        evicted.clear()

        cache.update(10)

        assertEquals((6..14).toList(), loaded.sorted())
        assertEquals(10, cache.get(10))
    }
}

class FrameRingCacheDirectionalTest {
    private fun recordingCache(loads: MutableList<Int>) = FrameRingCache<Int>(
        frameCount = 120, radius = 4,
        loader = { loads.add(it); it },
    )

    @org.junit.Test
    fun upwardSweepBiasesWindowAhead() {
        val loads = mutableListOf<Int>()
        val c = recordingCache(loads)
        c.update(60, direction = 1)
        // biased window: [60-2, 60+6] = 58..66
        org.junit.Assert.assertEquals((58..66).toList(), loads.sorted())
        for (i in 58..66) org.junit.Assert.assertNotNull(c.get(i))
        org.junit.Assert.assertNull(c.get(57))
        org.junit.Assert.assertNull(c.get(67))
    }

    @org.junit.Test
    fun downwardSweepBiasesWindowBehind() {
        val loads = mutableListOf<Int>()
        val c = recordingCache(loads)
        c.update(60, direction = -1)
        // biased window: [60-6, 60+2] = 54..62
        org.junit.Assert.assertEquals((54..62).toList(), loads.sorted())
    }

    @org.junit.Test
    fun centerIsAlwaysLoadedFirst() {
        val loads = mutableListOf<Int>()
        recordingCache(loads).update(60, direction = 1)
        org.junit.Assert.assertEquals(60, loads.first())
        loads.clear()
        recordingCache(loads).update(60, direction = -1)
        org.junit.Assert.assertEquals(60, loads.first())
        loads.clear()
        recordingCache(loads).update(60)
        org.junit.Assert.assertEquals(60, loads.first())
    }

    @org.junit.Test
    fun directionSideLoadsBeforeOppositeSide() {
        val loads = mutableListOf<Int>()
        recordingCache(loads).update(60, direction = 1)
        // ahead frame 61 must decode before behind frame 59
        org.junit.Assert.assertTrue(loads.indexOf(61) < loads.indexOf(59))
        loads.clear()
        recordingCache(loads).update(60, direction = -1)
        org.junit.Assert.assertTrue(loads.indexOf(59) < loads.indexOf(61))
    }

    @org.junit.Test
    fun directionalEdgesNeverLoadOutOfRange() {
        for (dir in listOf(-1, 0, 1)) for (center in listOf(-10, 0, 1, 118, 119, 500)) {
            val loads = mutableListOf<Int>()
            recordingCache(loads).update(center, direction = dir)
            org.junit.Assert.assertTrue(
                "dir=$dir center=$center loads=$loads",
                loads.all { it in 0..119 }
            )
            org.junit.Assert.assertTrue(loads.isNotEmpty())
        }
    }

    @org.junit.Test
    fun windowSizeNeverExceedsSymmetricBound() {
        for (dir in listOf(-1, 0, 1)) {
            val loads = mutableListOf<Int>()
            recordingCache(loads).update(60, direction = dir)
            org.junit.Assert.assertTrue(loads.size <= 9)
        }
    }
}

class FrameRingCachePinnedTest {
    private fun cache(loads: MutableList<Int>, evicts: MutableList<Int>) = FrameRingCache(
        frameCount = 120, radius = 4,
        loader = { loads.add(it); it },
        onEvict = { evicts.add(it) },
        pinnedIndex = 119,
    )

    @org.junit.Test
    fun pinnedFrameLoadsOnFirstUpdateAndSurvivesWindowMoves() {
        val loads = mutableListOf<Int>(); val evicts = mutableListOf<Int>()
        val c = cache(loads, evicts)
        c.update(119)            // resting state: window includes 119
        c.update(0)              // device closed: window snaps to the far end
        org.junit.Assert.assertNotNull("pinned frame evicted by window move", c.get(119))
        org.junit.Assert.assertFalse(evicts.contains(119))
        c.update(60); c.update(30); c.update(0)
        org.junit.Assert.assertNotNull(c.get(119))
        org.junit.Assert.assertFalse(evicts.contains(119))
        // Loaded exactly once despite all the moves.
        org.junit.Assert.assertEquals(1, loads.count { it == 119 })
    }

    @org.junit.Test
    fun pinnedFrameLoadsEvenWhenWindowNeverTouchesIt() {
        val loads = mutableListOf<Int>(); val evicts = mutableListOf<Int>()
        val c = cache(loads, evicts)
        c.update(0) // window 0..4 only
        org.junit.Assert.assertNotNull("pinned frame absent", c.get(119))
        org.junit.Assert.assertEquals(119, loads.first()) // pinned loads first
    }

    @org.junit.Test
    fun clearReleasesPinnedToo() {
        val loads = mutableListOf<Int>(); val evicts = mutableListOf<Int>()
        val c = cache(loads, evicts)
        c.update(0)
        c.clear()
        org.junit.Assert.assertNull(c.get(119))
        org.junit.Assert.assertTrue(evicts.contains(119))
    }

    @org.junit.Test
    fun outOfRangePinnedIsClamped() {
        val loads = mutableListOf<Int>()
        val c = FrameRingCache(frameCount = 120, radius = 4, loader = { loads.add(it); it }, pinnedIndex = 500)
        c.update(0)
        org.junit.Assert.assertNotNull(c.get(119))
        org.junit.Assert.assertTrue(loads.all { it in 0..119 })
    }
}
