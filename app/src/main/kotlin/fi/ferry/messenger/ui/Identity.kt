package fi.ferry.messenger.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.ferry.messenger.AppModel
import fi.ferry.messenger.model.AccountIdentity
import fi.ferry.messenger.model.Inbox
import fi.ferry.messenger.model.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

// The account identity system (DESIGN §3): colour is never shown without the mark or the name, and the mark is
// never a separate element for TalkBack (its meaning goes into the parent's label).

/** A filled circle in the account's colour with its mark in white; `ring` is the cut-out over an avatar. */
@Composable
fun AccountMark(identity: AccountIdentity, size: Dp, ring: Dp = 0.dp, inverted: Boolean = false) {
    Box(
        Modifier
            .background(if (ring > 0.dp) MaterialTheme.colorScheme.background else Color.Transparent, CircleShape)
            .padding(ring)
            .size(size)
            .background(if (inverted) Color.White else identity.color.fillColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(identity.mark, color = if (inverted) identity.color.fillColor else Color.White, maxLines = 1,
            style = centered(with(LocalDensity.current) { (size * 0.55f).toSp() }), fontWeight = FontWeight.Bold)
    }
}

/** Capsule with the mark and the name (and "· @handle"): section headers, the thread's header (`compact`), banners. */
@Composable
fun AccountTag(identity: AccountIdentity, modifier: Modifier = Modifier, compact: Boolean = false, handle: String? = null) {
    val style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge
    Row(
        modifier.background(identity.color.wash, CircleShape)
            .padding(start = if (compact) 2.dp else 3.dp, end = if (compact) 7.dp else 9.dp, top = 2.dp, bottom = 2.dp)
            .clearAndSetSemantics { contentDescription = "${identity.name} account" + handle?.let { ", @$it" }.orEmpty() },
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 5.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        AccountMark(identity, if (compact) 14.dp else 16.dp)
        Text(identity.name, color = identity.color.ink, style = style, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        handle?.let { Text("· @$it", color = identity.color.ink, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** One of *your* accounts: its picture with a gap and a ring in the account's colour, drawn outside `size`. */
@Composable
fun AccountAvatar(identity: AccountIdentity?, url: String?, name: String, size: Dp) {
    val line = if (size <= 32.dp) 1.5.dp else 2.dp
    Box(
        Modifier.size(size + line * 4)
            .then(if (identity != null) Modifier.border(line, identity.color.fillColor, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) { Picture(url, name, size) }
}

/** A chat's picture: the contact's, else a group's two faces or its people glyph, else the name's initial. */
@Composable
fun ChatAvatar(room: Room, size: Dp) {
    val url = room.avatarURL
    if (url == null && room.bridge?.isDM == false) {
        val faces = Inbox.faces(room)
        if (faces.size == 2) {
            val face = size * 0.7f
            Box(Modifier.size(size)) {
                Box(Modifier.align(Alignment.TopStart)) { Picture(faces[0].avatarURL, faces[0].name, face) }
                // The front face cut out of the back one.
                Box(Modifier.align(Alignment.BottomEnd).offset(2.dp, 2.dp).background(MaterialTheme.colorScheme.background, CircleShape).padding(2.dp)) {
                    Picture(faces[1].avatarURL, faces[1].name, face)
                }
            }
        } else {
            Box(Modifier.size(size).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.people, null, Modifier.size(size * 0.55f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        Picture(url, room.name, size)
    }
}

/** A round picture, or the name's initial on grey until (or unless) it loads. */
@Composable
fun Picture(url: String?, name: String, size: Dp) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val image = Pictures.load(url, px)
    if (image != null) {
        Image(image, null, Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
    } else {
        Box(Modifier.size(size).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
            Text(name.firstOrNull { it.isLetterOrDigit() }?.uppercase().orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = centered(with(LocalDensity.current) { (size * 0.42f).toSp() }))
        }
    }
}

/** One line of text sized to a small circle, its glyph centred (no line height around it). */
private fun centered(size: TextUnit) = TextStyle(
    fontSize = size, lineHeight = size,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)

/** The account's mark at the avatar's bottom-right (84% of its size), with a cut-out ring. */
@Composable
fun MarkOnAvatar(identity: AccountIdentity?, avatar: Dp, mark: Dp = 20.dp) {
    if (identity == null) return
    Box(Modifier.offset(avatar * 0.84f - mark / 2 - 2.dp, avatar * 0.84f - mark / 2 - 2.dp)) { AccountMark(identity, mark, ring = 2.dp) }
}

/** The identity of the account a chat belongs to. */
fun identityOf(room: Room): AccountIdentity? = room.bridge?.receiver?.let { AppModel.identities[it] }

/**
 * Profile pictures from Instagram's CDN, https only, scaled down to the size shown and kept in memory for this launch.
 * They don't go through the engine, so they aren't in its request counts (as on iOS).
 */
object Pictures {
    // ponytail: memory only, one download per launch and size; a disk cache when pictures (A6) need one.
    private val cache = LruCache<String, ImageBitmap>(200)
    /** Our own pictures before Instagram has them (`local:<id>`, a local echo's preview). */
    private val seeded = HashMap<String, ImageBitmap>()

    fun seed(url: String, image: ImageBitmap) {
        seeded[url] = image
    }

    @Composable
    fun load(url: String?, px: Int): ImageBitmap? {
        url?.let { seeded[it] }?.let { return it }
        val key = url?.let { "$px:$it" }
        val image by produceState(key?.let(cache::get), key) {
            if (value == null && key != null && url.startsWith("https://")) {
                value = withContext(Dispatchers.IO) { fetch(url, px) }?.also { cache.put(key, it) }
            }
        }
        return image
    }

    private fun fetch(url: String, px: Int): ImageBitmap? = runCatching {
        fi.ferry.messenger.MediaFiles.cachedImage(url)?.let { return decode(it.readBytes(), px) }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        val bytes = try {
            if (connection.contentLengthLong > MAX_BYTES) return null
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
        if (bytes.size > MAX_BYTES) return null
        decode(bytes, px)
    }.getOrNull()

    private fun decode(bytes: ByteArray, px: Int): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }.getOrNull()

    private const val MAX_BYTES = 8_000_000
}
