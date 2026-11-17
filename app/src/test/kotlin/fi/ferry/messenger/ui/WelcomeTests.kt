package fi.ferry.messenger.ui

import fi.ferry.messenger.ui.Crossing.Motion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** O1's scene logic (iOS `WelcomeTests`, the parts Android has: the motion rules and the poses). */
class WelcomeTests {
    @Test fun animationsOffOrBatterySaverHoldTheSceneStill() {
        for (resumed in listOf(true, false)) {
            assertEquals(Motion.STILL, Crossing.motion(animationsOff = true, powerSave = false, resumed = resumed))
            assertEquals(Motion.STILL, Crossing.motion(animationsOff = false, powerSave = true, resumed = resumed))
            assertEquals(Motion.STILL, Crossing.motion(animationsOff = true, powerSave = true, resumed = resumed))
        }
    }

    @Test fun itRunsOnlyWhileTheAppIsResumed() {
        assertEquals(Motion.RUNNING, Crossing.motion(animationsOff = false, powerSave = false, resumed = true))
        assertEquals(Motion.PAUSED, Crossing.motion(animationsOff = false, powerSave = false, resumed = false))
    }

    @Test fun atRestTheFerryIsLevelAndAboard() {
        val rest = Crossing.pose(null)
        assertEquals(0f, rest.bob)
        assertEquals(0f, rest.roll)
        assertEquals(listOf(0f, 0f), rest.swell)
        assertEquals(0f, rest.edge)
        assertEquals(listOf(0.5f, 0.5f), rest.wake)
        assertEquals(Crossing.Boarding(1f, 1f, 1f), Crossing.boarding(null))
    }

    @Test fun theMotionStaysGentle() {
        var bob = 0f..0f
        var roll = 0f..0f
        for (step in 0..2000) {
            val pose = Crossing.pose(step * 0.01f)
            bob = minOf(bob.start, pose.bob)..maxOf(bob.endInclusive, pose.bob)
            roll = minOf(roll.start, pose.roll)..maxOf(roll.endInclusive, pose.roll)
            for ((drift, wavelength) in (pose.swell + pose.edge).zip(Crossing.SWELL_WAVELENGTHS + Crossing.EDGE_WAVELENGTH)) {
                assertTrue("drift $drift wraps every $wavelength", drift >= 0 && drift < wavelength)
            }
            for (wake in pose.wake) assertTrue("wake $wake", wake in 0.349f..0.701f)
        }
        assertEquals(-1.5f, bob.start, 0.01f)
        assertEquals(1.5f, bob.endInclusive, 0.01f)
        assertEquals(-0.8f, roll.start, 0.01f)
        assertEquals(0.8f, roll.endInclusive, 0.01f)
        assertEquals(8f, Crossing.pose(1f).swell[0], 0.001f) // 8 dp/s to the left
        assertEquals(8f, Crossing.pose(16f).swell[0], 0.001f) // and round again after 120 dp
    }

    @Test fun theFerryArrivesFirstThenTheFrontBubbleThenTheBack() {
        val start = Crossing.boarding(0f)
        assertEquals(Crossing.Boarding(0f, 0f, 0f), start)
        val early = Crossing.boarding(0.3f)
        assertTrue(early.ferry > 0.5f && early.front > 0f && early.back == 0f)
        val done = Crossing.boarding(3f)
        assertEquals(1f, done.ferry, 0.001f)
        assertEquals(1f, done.front, 0.001f)
        assertEquals(1f, done.back, 0.001f)
    }

    @Test fun theBubblesSpringPastTheirPlaceALittleTheFerryNever() {
        val bubble = (0..300).maxOf { Crossing.spring(it * 0.01f, 0.55f, 0.3f) }
        assertTrue("overshoot $bubble", bubble > 1f && bubble < 1.1f)
        val ferry = (0..300).maxOf { Crossing.spring(it * 0.01f, 0.7f, 0f) }
        assertTrue("ferry $ferry", ferry <= 1f)
    }
}
