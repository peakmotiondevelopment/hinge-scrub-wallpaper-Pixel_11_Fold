package com.peakmotion.ebbfold.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The wallpaper draws on the main thread via [FrameRingCache.get] while a
 * background thread decodes frames via [FrameRingCache.update]. A decode must
 * never hold up a draw — otherwise the draw thread stalls for the length of a
 * JPEG decode (or a whole window fill) and the animation visibly hitches.
 */
class FrameRingCacheConcurrencyTest {

    @Test(timeout = 5000)
    fun getDoesNotWaitForAnInFlightLoad() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cache = FrameRingCache<Int>(
            frameCount = 120, radius = 4,
            loader = { index ->
                entered.countDown()
                release.await(3, TimeUnit.SECONDS) // a slow "decode"
                index
            },
        )
        val updater = Thread { cache.update(60, direction = 1) }
        updater.start()
        assertTrue("loader never entered", entered.await(2, TimeUnit.SECONDS))

        val reader = Executors.newSingleThreadExecutor()
        try {
            val future = reader.submit<Int?> { cache.get(60) }
            val outcome = try {
                future.get(200, TimeUnit.MILLISECONDS)
                "returned"
            } catch (e: TimeoutException) {
                "blocked"
            }
            assertEquals("get() must return while a load is in flight", "returned", outcome)
        } finally {
            release.countDown()
            updater.join()
            reader.shutdownNow()
        }
    }
    @Test(timeout = 5000)
    fun clearDuringAnInFlightLoadDiscardsTheLateValue() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val evicted = Collections.synchronizedList(mutableListOf<Int>())
        val cache = FrameRingCache<Int>(
            frameCount = 120, radius = 4,
            loader = { index ->
                if (index == 60) {
                    entered.countDown()
                    release.await(3, TimeUnit.SECONDS)
                }
                index
            },
            onEvict = { evicted.add(it) },
        )
        val updater = Thread { cache.update(60) }
        updater.start()
        assertTrue("loader never entered", entered.await(2, TimeUnit.SECONDS))

        cache.clear() // e.g. service onDestroy while a decode is mid-flight
        release.countDown()
        updater.join()

        for (index in 56..64) {
            assertNull("frame $index resurfaced after clear()", cache.get(index))
        }
        assertTrue("late value must be handed to onEvict, not leaked", evicted.contains(60))
    }
}
