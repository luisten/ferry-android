package fi.ferry.messenger.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The account and contact shapes (the iOS app's BridgeAPI.swift, named after Ferry's bridge, whose shapes the app
 * kept): `IGAdapter.login` builds a `Login` from Instagram's viewer, so health, the account stack and banners read it.
 */
object BridgeAPI {
    data class Login(
        val id: String,
        val name: String? = null,
        val profile: Profile? = null,
        val state: State? = null,
        /** When this app first saw the current TRANSIENT_DISCONNECT (ms): how long it has lasted. */
        val transientSince: Long? = null,
    ) {
        data class Profile(val name: String? = null, val username: String? = null, val avatar: String? = null)

        data class State(
            val stateEvent: String? = null,
            val message: String? = null,
            /** Unix seconds of the last state change. */
            val timestamp: Double? = null,
            val info: Info? = null,
        ) {
            data class Info(val isProfessionalAccount: Boolean? = null)
        }

        /** Professional accounts have Instagram's Primary and General tabs. */
        val isProfessional: Boolean get() = state?.info?.isProfessionalAccount == true

        /** The account's state → what the screens show (DESIGN §7.1). */
        sealed interface Health {
            data object Connected : Health
            /** CONNECTING, no state yet, or a TRANSIENT_DISCONNECT under 2 minutes (most last under a second). */
            data object Connecting : Health
            data object Syncing : Health
            /** A TRANSIENT_DISCONNECT over 2 minutes: still automatic, now worth a banner. */
            data object Reconnecting : Health
            data object NeedsLogin : Health
            /** Anything else; its message, if any. */
            data class Error(val message: String?) : Health

            /** The user has to act. */
            val needsUser: Boolean get() = this == NeedsLogin || this is Error
            val raisesBanner: Boolean get() = needsUser || this == Reconnecting
            val isBusy: Boolean get() = this == Connecting || this == Syncing || this == Reconnecting

            val label: String
                get() = when (this) {
                    Connected -> "Connected"
                    Connecting -> "Connecting…"
                    Syncing -> "Syncing history…"
                    Reconnecting -> "Reconnecting…"
                    NeedsLogin -> "Signed out"
                    is Error -> "Can't connect"
                }
        }

        val health: Health get() = health(System.currentTimeMillis())

        fun health(now: Long): Health = when (state?.stateEvent) {
            "CONNECTED" -> Health.Connected
            "CONNECTING", null -> Health.Connecting
            "TRANSIENT_DISCONNECT" ->
                if (transientSince != null && now - transientSince > 120_000) Health.Reconnecting else Health.Connecting
            "BACKFILLING" -> Health.Syncing
            "BAD_CREDENTIALS", "LOGGED_OUT" -> Health.NeedsLogin
            else -> Health.Error(state.message)
        }

        /** The name Instagram shows for this account. */
        val displayName: String get() = profile?.name ?: name ?: profile?.username ?: id
    }

    /** One Instagram user from search (`IGSearch`). Only `id` is always there. */
    @Serializable
    data class Contact(
        /** The user's ID; a DM's channel ID is the other person's ID. */
        val id: String,
        val name: String? = null,
        @SerialName("avatar_url") val avatarURL: String? = null,
        /** `["instagram:<handle>"]`. */
        val identifiers: List<String>? = null,
    ) {
        val handle: String? get() = handle(identifiers)

        companion object {
            /** The Instagram username in an identifier list. */
            fun handle(identifiers: List<String>?): String? =
                identifiers?.filter { it.startsWith("instagram:") }?.map { it.removePrefix("instagram:") }?.firstOrNull { it.isNotEmpty() }
        }
    }
}

/** What the engine hands over (PLAN-android stage 1): the account, and chats as Matrix-style updates. */
object IG {
    /** `IGLogin`, `IGResume`: the account and its viewer (its first page's chats arrive as updates, up to `seq`). */
    @Serializable
    data class Login(val account: String, val viewer: Viewer, val seq: Long? = null)

    /** The account itself, in Instagram's own words. */
    @Serializable
    data class Viewer(
        val id: String? = null,
        val fbid: String? = null,
        val username: String? = null,
        @SerialName("full_name") val fullName: String? = null,
        @SerialName("profile_pic_url") val profilePicUrl: String? = null,
        @SerialName("is_professional_account") val isProfessionalAccount: Boolean? = null,
    )

    /**
     * What `IGNextEvent` hands over, in order: an account's event (`Connected`, `Disconnected`, `AuthError`, a retry's
     * `Login` or `LoginFailed`) or a chat's change (`Room`: an update with the people and reply targets that came with
     * it; `RoomRemoved`; New Message's `Draft`; `RoomMoved` from a draft `to` its chat).
     */
    @Serializable
    data class Update(
        val account: String? = null,
        val kind: String,
        /** Its place in the engine's order: a call that changes chats returns the last one's. */
        val seq: Long? = null,
        val error: String? = null,
        val code: String? = null,
        val reason: String? = null,
        val room: String? = null,
        val update: JoinedRoom? = null,
        val profiles: Map<String, UserProfile>? = null,
        val related: List<Event>? = null,
        val to: String? = null,
        /** `Login`: the viewer. */
        val viewer: Viewer? = null,
    )

    /** The small answers: a chat's `room`, a sent message's `id`, the `seq` of the call's last update. */
    @Serializable
    data class Result(val room: String? = null, val id: String? = null, val seq: Long? = null)
}

/** The engine's names the screens use, the account row, and applying an update. */
object IGAdapter {
    /** The one user the screens treat as "us" in every account's chats (core/chat `Me`). */
    const val ME = "@me"
    /** A chat's first page when Instagram has nothing older: the thread shows the start of the chat. */
    const val START_TOKEN = "ig-start"

    fun user(fbid: String): String = "@ig_$fbid"

    /** The account as the screens read it. `since`, `transientSince`: ms. */
    fun login(account: String, viewer: IG.Viewer?, state: String, message: String? = null, since: Long, transientSince: Long? = null) =
        BridgeAPI.Login(
            id = account,
            name = viewer?.username,
            profile = BridgeAPI.Login.Profile(viewer?.fullName, viewer?.username, viewer?.profilePicUrl),
            state = BridgeAPI.Login.State(state, message, since / 1000.0, BridgeAPI.Login.State.Info(viewer?.isProfessionalAccount)),
            transientSince = transientSince,
        )

    /** An engine update applied to its chat: the change, then the reply targets that came with it. */
    fun apply(update: IG.Update, room: Room) {
        update.update?.let(room::apply)
        update.related.orEmpty().forEach(room::addRelated)
    }
}
