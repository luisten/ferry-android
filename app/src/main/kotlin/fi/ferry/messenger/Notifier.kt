package fi.ferry.messenger

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import fi.ferry.messenger.model.Event
import fi.ferry.messenger.model.Inbox
import fi.ferry.messenger.model.MessageKind
import fi.ferry.messenger.model.Room
import fi.ferry.messenger.model.Timeline

/**
 * Notifications (E8, A8; DESIGN §3.3, §4.5): a new message's title is the contact and its subtitle the account ("to
 * Studio"), in the account's colour; requests and General stay quiet. The message text is marked private: Android's
 * "sensitive notifications" setting decides whether a locked screen shows it ("New message · to Studio" otherwise).
 */
object Notifier {
    private const val CONNECTION = "connection"
    private const val MESSAGES = "messages"
    private const val ACCOUNTS = "accounts"
    const val CONNECTION_ID = 1

    /** Per chat, what its notification shows: the latest few messages from others, oldest first. */
    private val lines = HashMap<String, MutableList<Line>>()
    private val notified = HashSet<String>()

    private class Line(val sender: String, val text: String, val ts: Long)

    fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(listOf(
            NotificationChannel(MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            },
            NotificationChannel(ACCOUNTS, "Accounts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When an account needs you to log in again"
            },
            NotificationChannel(CONNECTION, "Connection", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps your accounts connected while Ferry is closed, so messages arrive"
                setShowBadge(false)
            },
        ))
    }

    fun allowed(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** The foreground service's own, silent and collapsed. */
    fun connection(context: Context) = NotificationCompat.Builder(context, CONNECTION)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Connected")
        .setContentText("New messages arrive while Ferry is closed.")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setContentIntent(open(context, null))
        .build()

    /**
     * New messages from others in `room` (`Timeline.notifiable`). Not for requests, General, or the chat on screen.
     */
    fun messages(context: Context, room: Room, events: List<Event>, account: String?, color: Int?) {
        if (!allowed(context) || Inbox.isRequest(room) || Inbox.folder(room) == Inbox.Folder.GENERAL) return
        val fresh = events.filter { e -> notified.add(e.eventID!!) } // once each (a re-read brings them again)
        if (fresh.isEmpty()) return
        val list = lines.getOrPut(room.id) { mutableListOf() }
        for (e in fresh) {
            val kind = MessageKind.of(e.type, e.content)
            val text = kind.media?.label ?: Timeline.body(e.content)
            list += Line(e.sender?.let { room.displayName(it, AppModel.profiles[it]) } ?: room.name, text, e.originServerTS ?: System.currentTimeMillis())
        }
        while (list.size > 6) list.removeAt(0)
        val me = Person.Builder().setName("You").build()
        val style = NotificationCompat.MessagingStyle(me).setGroupConversation(room.bridge?.isDM == false)
        if (room.bridge?.isDM == false) style.conversationTitle = room.name
        for (line in list) style.addMessage(line.text, line.ts, Person.Builder().setName(line.sender).build())
        val subtitle = account?.let { "to $it" }
        val notification = NotificationCompat.Builder(context, MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setContentTitle(room.name)
            .setContentText(list.last().text)
            .setSubText(subtitle)
            .apply { color?.let { setColor(it) } }
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(context, MESSAGES).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("New message").setSubText(subtitle).apply { color?.let { setColor(it) } }.build())
            .setContentIntent(open(context, room.id))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(room.id, 0, notification)
    }

    /** The chat was opened or read (here or elsewhere). */
    fun clear(context: Context, roomID: String) {
        if (lines.remove(roomID) != null) NotificationManagerCompat.from(context).cancel(roomID, 0)
    }

    /** DESIGN §7.2 item 5: an account signed out while Ferry wasn't on screen. */
    fun signedOut(context: Context, accountID: String, name: String) {
        if (!allowed(context)) return
        val notification = NotificationCompat.Builder(context, ACCOUNTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$name is signed out")
            .setContentText("Log in again to keep getting its messages.")
            .setContentIntent(open(context, null))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(accountID, 1, notification)
    }

    private fun open(context: Context, roomID: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { roomID?.let { putExtra(MainActivity.OPEN_ROOM, it) } }
        return PendingIntent.getActivity(context, roomID.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
