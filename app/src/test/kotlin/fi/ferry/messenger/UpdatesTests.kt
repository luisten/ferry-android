package fi.ferry.messenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The update check's version reading and comparison (A80, A82). */
class UpdatesTests {
    @Test
    fun newerComparesNumberByNumber() {
        assertTrue(Updates.newer("1.7.2", "1.7.1"))
        assertTrue(Updates.newer("1.8.0", "1.7.9"))
        assertTrue(Updates.newer("1.10.0", "1.9.0")) // numbers, not text
        assertTrue(Updates.newer("2.0", "1.99.99"))
        assertFalse(Updates.newer("1.7.1", "1.7.1"))
        assertFalse(Updates.newer("1.7.0", "1.7.1"))
    }

    @Test
    fun missingNumbersAreZero() {
        assertFalse(Updates.newer("1.7", "1.7.0"))
        assertFalse(Updates.newer("1.7.0", "1.7"))
        assertTrue(Updates.newer("1.7.1", "1.7"))
    }

    @Test
    fun androidOnlyFixesAddAFourthNumber() {
        assertTrue(Updates.newer("1.7.1.1", "1.7.1"))
        assertTrue(Updates.newer("1.7.1.2", "1.7.1.1"))
        assertTrue(Updates.newer("1.7.2", "1.7.1.1"))
        assertFalse(Updates.newer("1.7.1", "1.7.1.1"))
        assertFalse(Updates.newer("1.7.1.0", "1.7.1"))
    }

    @Test
    fun anythingButNumbersIsNeverNewer() {
        assertTrue(Updates.newer("v1.7.2", "1.7.1"))
        assertFalse(Updates.newer("1.8.0-beta", "1.7.1"))
        assertFalse(Updates.newer("", "1.7.1"))
        assertFalse(Updates.newer("1..8", "1.7.1"))
        assertFalse(Updates.newer("1.8", "1.7.1-debug"))
    }

    @Test
    fun tagFromTheLatestRedirect() {
        assertEquals("1.7.1", Updates.tag("https://github.com/luisten/ferry-android/releases/tag/v1.7.1"))
        assertEquals("1.7.1.1", Updates.tag("https://github.com/luisten/ferry-android/releases/tag/v1.7.1.1"))
        assertEquals("1.8", Updates.tag("/luisten/ferry-android/releases/tag/1.8"))
        // No release yet: GitHub sends "latest" to the releases page.
        assertNull(Updates.tag("https://github.com/luisten/ferry-android/releases"))
        assertNull(Updates.tag("https://github.com/luisten/ferry-android/releases/tag/v1.8.0-beta"))
        assertNull(Updates.tag("https://github.com/luisten/ferry-android/releases/tag/"))
        assertNull(Updates.tag(null))
    }

    @Test
    fun apkLink() {
        assertEquals("https://github.com/luisten/ferry-android/releases/download/v1.7.1/ferry-messenger-1.7.1.apk", Updates.apkURL("1.7.1"))
        assertEquals("https://github.com/luisten/ferry-android/releases/download/v1.7.1.1/ferry-messenger-1.7.1.1.apk", Updates.apkURL("1.7.1.1"))
    }
}
