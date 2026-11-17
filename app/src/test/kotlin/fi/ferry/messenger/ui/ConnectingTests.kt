package fi.ferry.messenger.ui

import fi.ferry.messenger.model.BridgeAPI.Login.Health
import fi.ferry.messenger.ui.Connecting.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** O4's checklist (iOS `ConnectingView`'s rows, worked out the same way). */
class ConnectingTests {
    @Test fun eachStateFillsTheChecklist() {
        assertEquals(listOf(Step.DONE, Step.RUNNING, Step.WAITING), Connecting.steps(Health.Connecting))
        assertEquals(listOf(Step.DONE, Step.RUNNING, Step.WAITING), Connecting.steps(Health.Reconnecting))
        assertEquals(listOf(Step.DONE, Step.DONE, Step.RUNNING), Connecting.steps(Health.Syncing))
        assertEquals(listOf(Step.DONE, Step.DONE, Step.DONE), Connecting.steps(Health.Connected))
        assertEquals(listOf(Step.DONE, Step.FAILED, Step.WAITING), Connecting.steps(Health.NeedsLogin))
        assertEquals(listOf(Step.DONE, Step.FAILED, Step.WAITING), Connecting.steps(Health.Error("nope")))
    }

    @Test fun continueUnlocksAtStepTwo() {
        assertTrue(Connecting.isConnected(Health.Syncing))
        assertTrue(Connecting.isConnected(Health.Connected))
        assertFalse(Connecting.isConnected(Health.Connecting))
        assertFalse(Connecting.isConnected(Health.NeedsLogin))
    }

    @Test fun theChatCountSaysSoFarUntilSynced() {
        assertNull(Connecting.chats(0, Health.Syncing))
        assertEquals("1 chat so far", Connecting.chats(1, Health.Syncing))
        assertEquals("12 chats so far", Connecting.chats(12, Health.Connecting))
        assertEquals("12 chats", Connecting.chats(12, Health.Connected))
    }
}
