package fi.ferry.core

/**
 * The engine's C API (`core/cmd/igcore/main.go` in github.com/luisten/ferry-messenger), through its JNI bridge
 * (`jni_android.c` beside it, which names this class and package: move either and the bridge breaks). One function
 * per export, in the same order, with the same meaning; main.go's comments are the reference.
 *
 * JSON crosses as UTF-8 bytes both ways (`encodeToByteArray`, `decodeToString`), never Java strings: emoji
 * (docs/spikes/2026-10-19-android-core.md in that repo). A failure is `{"error", "code", "reason"}`; a null result is
 * [nextEvent]'s "nothing happened". Every call but [newOTID], [counts], [stats], [setDebug] and [setCapture] can wait
 * on the network: never call one on the main thread. Nothing here may log what crosses it (cookies, message text).
 */
object IGCore {
    init {
        System.loadLibrary("igcore")
    }

    @JvmStatic external fun setCapture(dir: ByteArray)
    @JvmStatic external fun login(cookies: ByteArray): ByteArray?
    @JvmStatic external fun resume(account: ByteArray, cookies: ByteArray): ByteArray?
    @JvmStatic external fun setDebug(blockSends: Boolean, requestRoom: ByteArray)
    @JvmStatic external fun nextEvent(timeoutMS: Int): ByteArray?
    @JvmStatic external fun openChat(room: ByteArray): ByteArray?
    @JvmStatic external fun readChat(room: ByteArray): ByteArray?
    @JvmStatic external fun older(room: ByteArray): ByteArray?
    @JvmStatic external fun send(room: ByteArray, text: ByteArray, replyTo: ByteArray, otid: ByteArray): ByteArray?
    @JvmStatic external fun newOTID(): ByteArray?
    @JvmStatic external fun accept(room: ByteArray): ByteArray?
    @JvmStatic external fun deleteThread(room: ByteArray): ByteArray?
    @JvmStatic external fun search(account: ByteArray, query: ByteArray): ByteArray?
    @JvmStatic external fun openDM(account: ByteArray, contact: ByteArray): ByteArray?
    @JvmStatic external fun sendMedia(
        room: ByteArray, path: ByteArray, mime: ByteArray, voice: Boolean, replyTo: ByteArray, otid: ByteArray,
    ): ByteArray?
    @JvmStatic external fun resolveMedia(room: ByteArray, message: ByteArray): ByteArray?
    @JvmStatic external fun react(room: ByteArray, message: ByteArray, emoji: ByteArray, remove: Boolean): ByteArray?
    @JvmStatic external fun edit(room: ByteArray, message: ByteArray, text: ByteArray): ByteArray?
    @JvmStatic external fun unsend(room: ByteArray, message: ByteArray): ByteArray?
    @JvmStatic external fun markRead(room: ByteArray): ByteArray?
    @JvmStatic external fun typing(room: ByteArray, on: Boolean): ByteArray?

    /**
     * Not called on Android: E8 keeps the sockets open while Ferry is closed (iOS calls them when it leaves the
     * screen and comes back).
     */
    @JvmStatic external fun pause()
    @JvmStatic external fun unpause()

    @JvmStatic external fun counts(): ByteArray?
    @JvmStatic external fun session(account: ByteArray): ByteArray?
    @JvmStatic external fun stop(account: ByteArray)
    @JvmStatic external fun stats(): ByteArray?
}
