package fi.ferry.messenger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mic's press (as on iOS): a tap goes hands-free; held, letting go sends a note of a second or more. */
class VoiceRecorderTests {
    @Test fun aTapGoesHandsFree() {
        assertFalse(VoiceRecorder.sendsOnRelease(heldMs = 120, recordedMs = 120))
        assertFalse(VoiceRecorder.sendsOnRelease(heldMs = 299, recordedMs = 1_500)) // still a tap
    }

    @Test fun heldSendsOnlyASecondOrMore() {
        assertTrue(VoiceRecorder.sendsOnRelease(heldMs = 1_000, recordedMs = 1_000))
        assertFalse(VoiceRecorder.sendsOnRelease(heldMs = 700, recordedMs = 700)) // too short: goes on hands-free
        assertFalse(VoiceRecorder.isLongEnough(999))
    }
}
