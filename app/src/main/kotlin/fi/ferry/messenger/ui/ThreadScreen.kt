package fi.ferry.messenger.ui

import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.ferry.messenger.AppModel
import fi.ferry.messenger.model.AccountIdentity
import fi.ferry.messenger.model.Inbox
import fi.ferry.messenger.model.LinkPreview
import fi.ferry.messenger.model.Message
import fi.ferry.messenger.model.MessageKind
import fi.ferry.messenger.model.Room
import fi.ferry.messenger.model.ShareCaption
import fi.ferry.messenger.model.Timeline
import fi.ferry.messenger.model.Timeline.GroupPosition
import fi.ferry.messenger.model.UserProfile
import fi.ferry.messenger.model.parseURL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** DESIGN §6.1 groups: the sender's 28 dp avatar beside the last bubble of a run, and the indent it leaves. */
private val GROUP_AVATAR = 28.dp
private val GROUP_INDENT = GROUP_AVATAR + 6.dp

/**
 * T1, one chat (DESIGN §5 T1, §6), locked to the account it belongs to: `Timeline.reduce(room)`, laid out from the
 * bottom so it opens on the newest message and older pages land above without moving it. Read-only until A4.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(roomID: String, back: () -> Unit) {
    BackHandler(onBack = back)
    val room = AppModel.rooms[roomID]
    if (room == null) {
        Empty(Icons.chats, "Chat not found", "It's no longer in this account's chats.")
        return
    }
    val me = AppModel.ME
    val identity = identityOf(room)
    val profiles = AppModel.profiles
    val messages = Timeline.reduce(room, me)
    val isGroup = room.bridge?.isDM == false
    // How far we'd read when the chat opened unread, taken once, before our "Seen" moves it: the "New messages" line.
    val unreadAfter = remember(roomID) { if (room.isUnread) room.receipts[me]?.ts else null }
    val rows = Timeline.layout(messages, isGroup, unreadAfter)
    val lastOwn = messages.lastOrNull { it.isOwn && !it.kind.isChatEvent }?.id
    val typing = room.typing.any { it != me }
    val people = People(room, profiles)
    val seen = if (isGroup) messages.firstOrNull { it.seenBy.isNotEmpty() } else null
    // Everyone but us and its sender: "Seen by everyone".
    val seenLine = seen?.let { Timeline.seenByLine(it.seenBy.map(people::firstName), it.seenBy.size >= Inbox.memberCount(room) - if (it.isOwn) 1 else 2) }
    val times = rememberThreadTimes()
    val list = rememberLazyListState()
    var reactionsOf by remember { mutableStateOf<Message?>(null) }

    LaunchedEffect(roomID) { AppModel.openThread(room) } // the whole thread: reactions, edits, reply targets
    // On open and on each new message while it shows; the engine decides whether a "Seen" goes (unread, not a request).
    LaunchedEffect(room.events.lastOrNull()?.eventID) { AppModel.markRead(room) }
    // A new message while at the end: follow it. ponytail: no hold on the "New messages" line yet; opens at the end.
    LaunchedEffect(messages.lastOrNull()?.id, typing) { if (list.firstVisibleItemIndex <= 1) list.animateScrollToItem(0) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(Modifier.fillMaxSize()) {
            if (identity != null) {
                Box(Modifier.fillMaxWidth().height(170.dp).background(Brush.verticalGradient(listOf(identity.color.wash, Color.Transparent))))
            }
            Column(Modifier.padding(padding)) {
                ThreadHeader(room, identity, back)
                LazyColumn(Modifier.fillMaxSize(), state = list, reverseLayout = true, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                    if (typing) item("typing") { TypingBubble(Modifier.padding(top = 10.dp, start = if (isGroup) GROUP_INDENT else 0.dp)) }
                    items(rows.asReversed(), key = { it.id }) { row ->
                        MessageRow(row, identity, people, isGroup, showsStatus = row.id == lastOwn,
                            seenLine = if (row.id == seen?.id) seenLine else null, times = times, showReactions = { reactionsOf = it })
                    }
                    item("top") { Top(room, identity, messages.isEmpty()) }
                }
            }
        }
    }
    reactionsOf?.let { message ->
        ModalBottomSheet({ reactionsOf = null }, containerColor = MaterialTheme.colorScheme.background) { ReactionList(message, people) }
    }
}

/** Who someone is in this chat: the thread's names (`Room.displayName`), first names for captions, pictures. */
private class People(val room: Room, val profiles: Map<String, UserProfile>) {
    fun name(user: String) = room.displayName(user, profiles[user])
    fun avatar(user: String) = room.avatarURL(user, profiles[user])
    /** "You", or the first word of their name: "You replied to Kivo", "Kivo unsent a message". */
    fun firstName(user: String) = if (user == AppModel.ME) "You" else name(user).split(' ').firstOrNull { it.isNotEmpty() } ?: name(user)
}

/** One glass-free capsule's worth: back, the contact, and under the name the account this chat belongs to. */
@Composable
private fun ThreadHeader(room: Room, identity: AccountIdentity?, back: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(back) { Icon(Icons.back, "Back") }
        Row(Modifier.semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically) {
            ChatAvatar(room, 32.dp)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(room.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (identity != null) AccountTag(identity, compact = true)
            }
        }
    }
}

/** The top of the history: the start block (who it's with, the account) once it's all here, else a spinner that pages back. */
@Composable
private fun Top(room: Room, identity: AccountIdentity?, isEmpty: Boolean) {
    if (room.reachedStart) {
        val block = Inbox.startBlock(room.bridge?.isDM == false, Inbox.memberCount(room), AppModel.contactHandle(room), identity?.name, isEmpty)
        Column(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 20.dp).semantics(mergeDescendants = true) {},
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ChatAvatar(room, 64.dp)
            Spacer(Modifier.height(2.dp))
            Text(room.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            block.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (identity != null) AccountTag(identity, Modifier.padding(top = 6.dp))
            block.messagingFrom?.let { Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    } else {
        // Composed only while on screen: it pages back until the screen is full or the history ends.
        Box(Modifier.fillMaxWidth().heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LaunchedEffect(room.prevBatch) { AppModel.loadOlder(room) }
    }
}

@Composable
private fun MessageRow(
    row: Timeline.Row,
    identity: AccountIdentity?,
    people: People,
    isGroup: Boolean,
    showsStatus: Boolean,
    seenLine: String?,
    times: ThreadTimes,
    showReactions: (Message) -> Unit,
) {
    val message = row.message
    val own = message.isOwn
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(top = row.spacing.dp)) {
        if (row.startsUnread) UnreadLine()
        if (row.showsTime) {
            Text(times.label(message.ts), Modifier.fillMaxWidth().padding(bottom = 6.dp), style = MaterialTheme.typography.labelMedium,
                color = secondary, textAlign = TextAlign.Center)
        }
        if (message.kind.isChatEvent) {
            // Instagram's own lines and what the app can't show: small centred text across the thread.
            val text = (message.kind as? MessageKind.Unsupported)?.let { "${people.firstName(message.sender)} sent ${it.what}. Open Instagram to see it." } ?: message.body
            Text(text, Modifier.fillMaxWidth().padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall, color = secondary, textAlign = TextAlign.Center)
            return@Column
        }
        val indent = if (row.groupSender == null) 0.dp else GROUP_INDENT
        Column(Modifier.fillMaxWidth(), horizontalAlignment = if (own) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (row.groupSender?.showsName == true && message.reply == null) {
                // Neutral, never coloured: colour means account.
                Text(people.name(message.sender), Modifier.padding(start = indent + 12.dp), style = MaterialTheme.typography.labelMedium, color = secondary, maxLines = 1)
            }
            message.reply?.let { Quote(it, message, row, people, Modifier.padding(start = indent)) }
            ShareCaption.of(message.content).eyebrow?.takeIf { message.kind != MessageKind.Unsent }?.let {
                Text(it, Modifier.padding(start = indent + 4.dp, end = 4.dp), style = MaterialTheme.typography.labelMedium, color = secondary)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                row.groupSender?.let { sender ->
                    Box(Modifier.size(GROUP_AVATAR).offset(y = if (message.reactions.isEmpty()) 0.dp else (-14).dp)) {
                        if (sender.showsAvatar) Picture(people.avatar(message.sender), people.name(message.sender), GROUP_AVATAR)
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Box(Modifier.fillMaxWidth(0.75f), contentAlignment = if (own) Alignment.BottomEnd else Alignment.BottomStart) {
                    Box(Modifier.padding(bottom = if (message.reactions.isEmpty()) 0.dp else 14.dp)) {
                        Bubble(message, row.position, identity, people)
                        if (message.reactions.isNotEmpty()) {
                            ReactionPills(message, people, isGroup, Modifier.align(if (own) Alignment.BottomStart else Alignment.BottomEnd)
                                .offset(x = if (own) (-8).dp else 8.dp, y = 14.dp)) { showReactions(message) }
                        }
                    }
                }
            }
            if (message.isEdited && message.kind != MessageKind.Unsent) {
                Text("Edited", Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.labelSmall, color = secondary)
            }
            when {
                message.status is Message.Status.Failed -> Text("Not sent.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                showsStatus && message.kind != MessageKind.Unsent -> Text(
                    seenLine ?: when (message.status) {
                        Message.Status.Sending -> "Sending…"
                        else -> if (message.isSeen) "Seen" else "Sent"
                    },
                    Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.labelSmall, color = secondary,
                )
                seenLine != null -> Text(seenLine, Modifier.fillMaxWidth().padding(horizontal = 6.dp), style = MaterialTheme.typography.labelSmall,
                    color = secondary, textAlign = TextAlign.End)
            }
        }
    }
}

/** Where what you hadn't read starts, when the chat opened unread. */
@Composable
private fun UnreadLine() {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text("New messages", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** A story reply or reaction is the story with what was sent under it, as Instagram shows them. */
@Composable
private fun Bubble(message: Message, position: GroupPosition, identity: AccountIdentity?, people: People) {
    val own = message.isOwn
    val shape = bubbleShape(position, own)
    val fill = if (own) identity?.color?.fillColor ?: MaterialTheme.colorScheme.onBackground else Bubbles.incoming
    val text = if (own) Color.White else MaterialTheme.colorScheme.onBackground
    Column(horizontalAlignment = if (own) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (val kind = message.kind) {
            MessageKind.Unsent -> {
                val stroke = MaterialTheme.colorScheme.onSurfaceVariant
                Text(
                    if (own) "You unsent a message" else "${people.firstName(message.sender)} unsent a message",
                    Modifier.drawBehind {
                        drawRoundRect(stroke, cornerRadius = CornerRadius(size.height / 2),
                            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
                    }.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MessageKind.Text, MessageKind.Notice -> TextBubble(message.body, shape, fill, text)
            MessageKind.Link -> {
                val preview = LinkPreview.of(message.content)
                Column(Modifier.background(fill, shape).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(message.body, style = MaterialTheme.typography.bodyLarge, color = text)
                    if (preview != null) {
                        Spacer(Modifier.height(6.dp))
                        preview.title?.let { Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = text, maxLines = 2) }
                        Text(parseURL(preview.url)?.host.orEmpty(), style = MaterialTheme.typography.labelMedium, color = text.copy(alpha = 0.75f))
                    }
                }
            }
            // ponytail: a labelled card until A6 shows the media itself (photos, videos, voice, GIFs, shares).
            else -> MediaPlaceholder(kind, message, shape, fill, text)
        }
        message.shareText?.let { TextBubble(it, bubbleShape(GroupPosition.SINGLE, own), fill, text) }
    }
}

@Composable
private fun TextBubble(body: String, shape: RoundedCornerShape, fill: Color, text: Color) {
    if (isEmojiOnly(body)) {
        Text(body, fontSize = 48.sp) // DESIGN §6.1: no bubble
    } else {
        Text(body, Modifier.background(fill, shape).padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyLarge, color = text)
    }
}

@Composable
private fun MediaPlaceholder(kind: MessageKind, message: Message, shape: RoundedCornerShape, fill: Color, text: Color) {
    val media = kind.media ?: return
    val author = (kind as? MessageKind.Share)?.let { ShareCaption.of(message.content).author }
    Row(Modifier.background(fill, shape).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.of(media.glyph), null, Modifier.size(20.dp), tint = text)
        Text(media.label + (author?.let { " · @$it" } ?: ""), style = MaterialTheme.typography.bodyMedium, color = text)
    }
}

/** DESIGN §6.1: radius 20; the corners on the sender's side shrink to 6 where the bubble joins its neighbour. */
private fun bubbleShape(position: GroupPosition, own: Boolean): RoundedCornerShape {
    val top = if (position == GroupPosition.MIDDLE || position == GroupPosition.LAST) 6.dp else 20.dp
    val bottom = if (position == GroupPosition.FIRST || position == GroupPosition.MIDDLE) 6.dp else 20.dp
    return if (own) RoundedCornerShape(topStart = 20.dp, topEnd = top, bottomEnd = bottom, bottomStart = 20.dp)
    else RoundedCornerShape(topStart = top, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = bottom)
}

/** 1–3 emoji and nothing else. */
private fun isEmojiOnly(text: String): Boolean {
    val graphemes = java.text.BreakIterator.getCharacterInstance().run {
        setText(text)
        generateSequence(first()) { next().takeIf { it != java.text.BreakIterator.DONE } }.zipWithNext { a, b -> text.substring(a, b) }.toList()
    }
    return graphemes.size in 1..3 && graphemes.all { g ->
        val first = g.codePointAt(0)
        UCharacter.hasBinaryProperty(first, UProperty.EMOJI_PRESENTATION) ||
            g.codePointCount(0, g.length) > 1 && g.codePoints().anyMatch { UCharacter.hasBinaryProperty(it, UProperty.EMOJI) && it > 0xFF }
    }
}

/** "You replied to Kivo" and a faded outline of the original (DESIGN §6.2). */
@Composable
private fun Quote(reply: Message.Reply, message: Message, row: Timeline.Row, people: People, modifier: Modifier) {
    val replied = when (reply.sender) {
        null -> "replied"
        message.sender -> if (message.isOwn) "replied to yourself" else "replied to themselves"
        AppModel.ME -> "replied to you"
        else -> "replied to ${people.firstName(reply.sender)}"
    }
    // Where a group's run starts this is its sender label too, so the full name: "Sam Lee replied to Theo".
    val replier = if (message.isOwn) "You" else if (row.groupSender?.showsName == true) people.name(message.sender) else people.firstName(message.sender)
    val snippet = when {
        !reply.isLoaded -> "Loading…"
        reply.kind == MessageKind.Unsent -> "Unsent message"
        else -> reply.kind?.media?.label ?: reply.body
    }
    Column(modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = if (message.isOwn) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("$replier $replied", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(snippet, Modifier.widthIn(max = 280.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Emoji + count in a DM; in a group each emoji with up to three faces (DESIGN §6.2). Opens who reacted. */
@Composable
private fun ReactionPills(message: Message, people: People, isGroup: Boolean, modifier: Modifier, show: () -> Unit) {
    val spoken = "Reactions: " + message.reactions.joinToString(", ") { r ->
        if (isGroup) "${r.key} from ${r.senders.joinToString(" and ") { if (it == AppModel.ME) "you" else people.name(it) }}"
        else if (r.count > 1) "${r.key} ${r.count}" else r.key
    }
    Row(
        modifier.shadow(2.dp, CircleShape).border(2.dp, MaterialTheme.colorScheme.background, CircleShape).background(Bubbles.reaction, CircleShape)
            .clickable(onClick = show).heightIn(min = 24.dp).padding(horizontal = 6.dp, vertical = 2.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(if (isGroup) 6.dp else 2.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        for (r in message.reactions) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.key, fontSize = 14.sp)
                if (isGroup) {
                    Row(horizontalArrangement = Arrangement.spacedBy((-5).dp)) {
                        for (user in r.senders.take(3)) Picture(people.avatar(user), people.name(user), 16.dp)
                    }
                    if (r.count > 3) Count("+${r.count - 3}")
                } else if (r.count > 1) Count("${r.count}")
            }
        }
    }
}

@Composable
private fun Count(text: String) = Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Who reacted with what, as Instagram lists them. */
@Composable
private fun ReactionList(message: Message, people: People) {
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Text("Reactions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        for (r in message.reactions) for (user in r.senders) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                Picture(people.avatar(user), people.name(user), 36.dp)
                Spacer(Modifier.width(12.dp))
                Text(if (user == AppModel.ME) "You" else people.name(user), Modifier.weight(1f), maxLines = 1)
                Text(r.key, fontSize = 24.sp)
            }
        }
    }
}

/** DESIGN T1: an incoming bubble with three dots. */
@Composable
private fun TypingBubble(modifier: Modifier) {
    Box(modifier.fillMaxWidth()) {
        Text("•••", Modifier.background(Bubbles.incoming, CircleShape).padding(horizontal = 14.dp, vertical = 8.dp)
            .clearAndSetSemantics { contentDescription = "Typing" },
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = FontStyle.Normal)
    }
}

/** The thread's own colours (DESIGN §8.1): the incoming bubble and the reaction pills. */
private object Bubbles {
    val incoming: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF262629) else Color(0xFFE9E9EB)
    val reaction: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF2C2C2E) else Color.White
}

/** A time label above a block: the time today, else the date and time, in the phone's format. */
private class ThreadTimes(private val today: DateTimeFormatter, private val other: DateTimeFormatter) {
    fun label(ts: Long): String {
        val date = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
        val now = Instant.now().atZone(ZoneId.systemDefault())
        return date.format(if (date.toLocalDate() == now.toLocalDate()) today else other)
    }
}

@Composable
private fun rememberThreadTimes(): ThreadTimes {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val h24 = DateFormat.is24HourFormat(context)
    return remember(locale, h24) {
        fun best(skeleton: String) = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
        ThreadTimes(best(if (h24) "Hm" else "hm"), best(if (h24) "dMMMHm" else "dMMMhm"))
    }
}
