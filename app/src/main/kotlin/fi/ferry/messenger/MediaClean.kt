package fi.ferry.messenger

import java.io.ByteArrayOutputStream

/**
 * Animated GIFs and WebPs go to Instagram as they are (Android has no GIF encoder to rewrite them, as iOS does), so
 * their metadata is cut out first: nothing but the frames, their timing and the loop count leaves the phone. Still
 * images never come here: they're re-encoded as JPEG, which copies no metadata. Null for a file that doesn't parse.
 */
object MediaClean {
    /** GIF: keeps the frames and their graphic control, the NETSCAPE/ANIMEXTS loop; drops comments, XMP, ICC, the rest. */
    fun gif(data: ByteArray): ByteArray? = runCatching {
        val sig = String(data, 0, 6, Charsets.US_ASCII)
        if (sig != "GIF89a" && sig != "GIF87a") return null
        val out = ByteArrayOutputStream(data.size)
        var i = 13
        val screen = data[10].toInt() and 0xFF
        if (screen and 0x80 != 0) i += 3 * (1 shl ((screen and 7) + 1))
        out.write(data, 0, i)
        while (i < data.size) {
            when (data[i].toInt() and 0xFF) {
                0x3B -> { out.write(0x3B); return out.toByteArray() }
                0x2C -> { // an image: its descriptor, colour table, LZW size and data
                    val packed = data[i + 9].toInt() and 0xFF
                    var end = i + 10
                    if (packed and 0x80 != 0) end += 3 * (1 shl ((packed and 7) + 1))
                    end = subBlocks(data, end + 1)
                    out.write(data, i, end - i)
                    i = end
                }
                0x21 -> {
                    val label = data[i + 1].toInt() and 0xFF
                    val end = subBlocks(data, i + 2)
                    val app = if (label == 0xFF && data[i + 2].toInt() == 11) String(data, i + 3, 11, Charsets.US_ASCII) else null
                    val keep = label == 0xF9 || label == 0x01 || app == "NETSCAPE2.0" || app == "ANIMEXTS1.0"
                    if (keep) out.write(data, i, end - i)
                    i = end
                }
                else -> return null
            }
        }
        null // no trailer
    }.getOrNull()

    /** The index after a run of GIF sub-blocks (each a length byte and that many bytes, ending with a zero). */
    private fun subBlocks(data: ByteArray, from: Int): Int {
        var i = from
        while (true) {
            val n = data[i].toInt() and 0xFF
            i += 1 + n
            if (n == 0) return i
        }
    }

    /** WebP: drops the EXIF and XMP chunks and clears their flags in VP8X; keeps everything else (ICC, ANIM, frames). */
    fun webp(data: ByteArray): ByteArray? = runCatching {
        if (String(data, 0, 4, Charsets.US_ASCII) != "RIFF" || String(data, 8, 4, Charsets.US_ASCII) != "WEBP") return null
        val body = ByteArrayOutputStream(data.size)
        var i = 12
        while (i + 8 <= data.size) {
            val fourcc = String(data, i, 4, Charsets.US_ASCII)
            val size = le32(data, i + 4)
            val end = i + 8 + size + (size and 1)
            if (end > data.size) return null
            when (fourcc) {
                "EXIF", "XMP " -> {}
                "VP8X" -> {
                    val chunk = data.copyOfRange(i, end)
                    chunk[8] = (chunk[8].toInt() and 0x08.inv() and 0x04.inv()).toByte()
                    body.write(chunk)
                }
                else -> body.write(data, i, end - i)
            }
            i = end
        }
        val chunks = body.toByteArray()
        ByteArrayOutputStream(12 + chunks.size).apply {
            write("RIFF".toByteArray(Charsets.US_ASCII))
            val size = 4 + chunks.size
            write(byteArrayOf(size.toByte(), (size shr 8).toByte(), (size shr 16).toByte(), (size shr 24).toByte()))
            write("WEBP".toByteArray(Charsets.US_ASCII))
            write(chunks)
        }.toByteArray()
    }.getOrNull()

    private fun le32(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8) or
            ((data[at + 2].toInt() and 0xFF) shl 16) or ((data[at + 3].toInt() and 0xFF) shl 24)
}
