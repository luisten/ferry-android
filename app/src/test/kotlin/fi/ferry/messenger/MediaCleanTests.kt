package fi.ferry.messenger

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nothing but frames, timing and the loop count leaves the phone in a GIF or WebP we send as it is. */
class MediaCleanTests {
    private fun bytes(vararg parts: Any): ByteArray = parts.flatMap { p ->
        when (p) {
            is String -> p.toByteArray(Charsets.US_ASCII).toList()
            is Int -> listOf(p.toByte())
            is ByteArray -> p.toList()
            else -> error(p)
        }
    }.toByteArray()

    private fun ByteArray.has(text: String) = String(this, Charsets.ISO_8859_1).contains(text)

    // A 1×1 GIF89a with a 2-colour table, the NETSCAPE loop, a comment, an XMP block, a frame's control and one frame.
    private val gif = bytes(
        "GIF89a", 1, 0, 1, 0, 0x80, 0, 0, 0, 0, 0, 0xFF, 0xFF, 0xFF,
        0x21, 0xFF, 11, "NETSCAPE2.0", 3, 1, 0, 0, 0,
        0x21, 0xFE, 9, "Helsinki!", 0,
        0x21, 0xFF, 11, "XMP DataXMP", 12, "<x:gps 60.1>", 0,
        0x21, 0xF9, 4, 0, 10, 0, 0, 0,
        0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x4C, 0x01, 0,
        0x3B,
    )

    @Test fun gifKeepsFramesAndLoopButNoComments() {
        val clean = MediaClean.gif(gif)!!
        assertTrue(clean.has("NETSCAPE2.0"))
        assertFalse(clean.has("Helsinki"))
        assertFalse(clean.has("XMP"))
        assertFalse(clean.has("gps"))
        val expected = bytes(
            "GIF89a", 1, 0, 1, 0, 0x80, 0, 0, 0, 0, 0, 0xFF, 0xFF, 0xFF,
            0x21, 0xFF, 11, "NETSCAPE2.0", 3, 1, 0, 0, 0,
            0x21, 0xF9, 4, 0, 10, 0, 0, 0,
            0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x4C, 0x01, 0,
            0x3B,
        )
        assertArrayEquals(expected, clean)
    }

    @Test fun notAGIF() {
        assertNull(MediaClean.gif("PNG not a gif".toByteArray()))
        assertNull(MediaClean.gif(gif.copyOf(40))) // cut short
    }

    private fun chunk(fourcc: String, data: ByteArray) =
        bytes(fourcc, data.size and 0xFF, 0, 0, 0, data, *(if (data.size % 2 == 1) arrayOf<Any>(0) else emptyArray()))

    @Test fun webpLosesExifAndXmpAndTheirFlags() {
        val vp8x = chunk("VP8X", bytes(0x2E, 0, 0, 0, 0, 0, 0, 0, 0, 0)) // ICC, ANIM, EXIF, XMP flags set
        val anim = chunk("ANIM", bytes(0, 0, 0, 0, 0, 0))
        val exif = chunk("EXIF", "GPS 60.1N".toByteArray())
        val xmp = chunk("XMP ", "<x:gps/>".toByteArray())
        val frame = chunk("ANMF", bytes(1, 2, 3))
        val body = vp8x + anim + exif + xmp + frame
        val webp = bytes("RIFF", (4 + body.size) and 0xFF, 0, 0, 0, "WEBP", body)
        val clean = MediaClean.webp(webp)!!
        assertFalse(clean.has("EXIF"))
        assertFalse(clean.has("GPS"))
        assertFalse(clean.has("XMP"))
        assertTrue(clean.has("ANIM"))
        assertTrue(clean.has("ANMF"))
        assertEquals(0x22, clean[20].toInt()) // VP8X flags: ICC and ANIM left
        assertEquals(clean.size - 8, (clean[4].toInt() and 0xFF) or ((clean[5].toInt() and 0xFF) shl 8))
    }
}
