package fi.ferry.messenger.model

import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * New Message (the iOS app's NewMessageTests): a search result's handle, and finding the DM we already have. Not ported:
 * the bridge's search and `create_dm` shapes and its ghost profiles (the engine sends `IGSearch` contacts and
 * `profiles` with each chat).
 */
class NewMessageTests {
    /** `IGSearch`'s contact shape: the handle sits in `identifiers`. */
    @Test fun searchResultsDecode() {
        val contact = json.decodeFromString<BridgeAPI.Contact>(
            """{"id":"17841400000000302","name":"Kivo Studio","avatar_url":"https://scontent.example.invalid/v/f002.jpg","identifiers":["instagram:kivostudio"]}""",
        )
        assertEquals(Fixture.kivoLogin, contact.id)
        assertEquals("Kivo Studio", contact.name)
        assertEquals("kivostudio", contact.handle)
        assertTrue(contact.avatarURL!!.startsWith("https://"))
        assertNull(json.decodeFromString<BridgeAPI.Contact>("""{"id":"1"}""").handle)
    }

    @Test fun handleComesOnlyFromInstagramIdentifiers() {
        assertEquals("kivostudio", BridgeAPI.Contact.handle(listOf("instagram:kivostudio")))
        assertEquals("a.b_c", BridgeAPI.Contact.handle(listOf("tel:+1555", "instagram:a.b_c")))
        assertNull(BridgeAPI.Contact.handle(listOf("tel:+1555")))
        assertNull(BridgeAPI.Contact.handle(listOf("instagram:")))
        assertNull(BridgeAPI.Contact.handle(null))
    }

    /** A result opens the DM we already have on *that* account, never the same person's DM on another account. */
    @Test fun existingDMIsFoundPerAccount() {
        val rooms = InboxTests.realRooms()
        assertEquals(Fixture.tamoRoom, Inbox.existingDM(Fixture.kivoLogin, Fixture.tamoLogin, rooms)?.id)
        assertEquals(Fixture.kivoRoom, Inbox.existingDM(Fixture.tamoLogin, Fixture.kivoLogin, rooms)?.id)
        assertNull(Inbox.existingDM(Fixture.kivoLogin, Fixture.kivoLogin, rooms))
        assertNull(Inbox.existingDM("17841400000000009", Fixture.tamoLogin, rooms))
    }

    /** A group with that person isn't "the DM". */
    @Test fun aGroupIsNotAnExistingDM() {
        val group = InboxTests.portal("!g", Fixture.tamoLogin, group = true)
        assertNull(Inbox.existingDM(group.bridge?.channelID!!, Fixture.tamoLogin, listOf(group)))
    }

    /** The contact is the joined member who isn't us; a group has none. */
    @Test fun contactUserIDIsTheOtherPerson() {
        assertEquals(Fixture.kivoGhost, Inbox.contactUserID(syncRoom(Fixture.tamoRoom), Fixture.me))
        assertNull(Inbox.contactUserID(InboxTests.portal("!g", Fixture.tamoLogin, group = true), Fixture.me))
    }
}
