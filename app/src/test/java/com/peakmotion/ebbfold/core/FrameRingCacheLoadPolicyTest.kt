package com.peakmotion.ebbfold.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors

class FrameRingCacheLoadPolicyTest {

    @Test
    fun windowForMatchesTheUpdateWindow() {
        val c = FrameRingCache<Int>(frameCount = 120, radius = 4, loader = { it })
        assertEquals(56..64, c.windowFor(60, 0))
        assertEquals(58..66, c.windowFor(60, 1))
        assertEquals(54..62, c.windowFor(60, -1))
        assertEquals(0..4, c.windowFor(0, 0))
        assertEquals(115..119, c.windowFor(500, 0))
    }

    @Test
    fun biasScalesWithRadius() {
        val c = FrameRingCache<Int>(frameCount = 120, radius = 12, loader = { it })
        assertEquals(54..78, c.windowFor(60, 1))  // [60-6, 60+18]: prefetch runs 18 ahead
        assertEquals(42..66, c.windowFor(60, -1))
    }

    @Test
    fun isNeededVetoesIndividualLoads() {
        val loads = mutableListOf<Int>()
        val c = FrameRingCache<Int>(frameCount = 120, radius = 4, loader = { loads.add(it); it })
        c.update(60, 0) { it != 62 }
        assertEquals((56..64).filter { it != 62 }, loads.sorted())
        assertNull(c.get(62))
        c.update(60) // no veto: the gap fills
        assertEquals(62, c.get(62))
    }

    @Test
    fun pinnedFrameIgnoresTheVeto() {
        val loads = mutableListOf<Int>()
        val c = FrameRingCache<Int>(frameCount = 120, radius = 4, loader = { loads.add(it); it }, pinnedIndex = 119)
        c.update(0) { false }
        assertEquals(listOf(119), loads)
    }

    @Test
    fun parallelLoaderFillsTheWholeWindowExactlyOnce() {
        val pool = Executors.newFixedThreadPool(3)
        try {
            val loads = Collections.synchronizedList(mutableListOf<Int>())
            val c = FrameRingCache<Int>(
                frameCount = 120, radius = 4,
                loader = { Thread.sleep(2); loads.add(it); it },
                loadExecutor = pool,
            )
            c.update(60, 1)
            assertEquals((58..66).toList(), loads.sorted())
            for (i in 58..66) assertEquals(i, c.get(i))
            c.update(70, 1)
            for (i in 68..76) assertEquals(i, c.get(i))
            assertEquals("every index loaded at most once", loads.size, loads.toSet().size)
        } finally {
            pool.shutdownNow()
        }
    }
}
