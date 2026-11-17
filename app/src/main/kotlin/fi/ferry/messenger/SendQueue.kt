package fi.ferry.messenger

import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Every send of a chat, one after another in the order they were asked for (decisions D6, A12): texts, photos, videos,
 * voice notes and retries share their chat's line, so Instagram gets them in the order the thread shows them, a set's
 * text after its photos. Chats don't wait for each other. A job reports its own failure; one that fails or throws
 * doesn't hold up the next. A chat's thread goes after 30 s with nothing to send.
 */
class SendQueue {
    private val lines = mutableMapOf<String, ExecutorService>()

    @Synchronized
    fun submit(chat: String, job: () -> Unit) {
        lines.getOrPut(chat) {
            ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue()).apply { allowCoreThreadTimeOut(true) }
        }.execute {
            // Uncaught, it would end the process (Android's default handler); the job has said what went wrong.
            try { job() } catch (_: Exception) {}
        }
    }
}
