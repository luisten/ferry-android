package fi.ferry.messenger

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A chat's sends go in the order asked for (decision A12), whatever each one takes or however it ends. */
class SendQueueTests {
    private val sent = Collections.synchronizedList(mutableListOf<String>())

    private fun SendQueue.send(chat: String, name: String, ms: Long = 0, fails: Boolean = false, done: CountDownLatch? = null) =
        submit(chat) {
            Thread.sleep(ms)
            sent += name
            done?.countDown()
            if (fails) throw IllegalStateException("upload refused")
        }

    private fun CountDownLatch.waits() = assertTrue("timed out", await(5, TimeUnit.SECONDS))

    @Test fun mediaThenTextInTheOrderPicked() {
        val queue = SendQueue()
        val done = CountDownLatch(5)
        // A big video first, quick photos after it, then the text: none overtakes another.
        queue.send("!a", "video 1", ms = 120, done = done)
        queue.send("!a", "photo 2", ms = 10, done = done)
        queue.send("!a", "photo 3", done = done)
        queue.send("!a", "voice", ms = 30, done = done)
        queue.send("!a", "text", done = done)
        done.waits()
        assertEquals(listOf("video 1", "photo 2", "photo 3", "voice", "text"), sent)
    }

    @Test fun aFailureInTheMiddleHoldsNothingUp() {
        val queue = SendQueue()
        val done = CountDownLatch(4)
        queue.send("!a", "photo 1", done = done)
        queue.send("!a", "photo 2", ms = 20, fails = true, done = done)
        queue.send("!a", "photo 3", done = done)
        queue.send("!a", "text", done = done)
        done.waits()
        assertEquals(listOf("photo 1", "photo 2", "photo 3", "text"), sent)
    }

    @Test fun aRetryGoesAfterWhatsAlreadyQueued() {
        val queue = SendQueue()
        val done = CountDownLatch(4)
        queue.send("!a", "photo 1", ms = 20, fails = true, done = done)
        queue.send("!a", "photo 2", ms = 20, done = done)
        queue.send("!a", "text", done = done)
        queue.send("!a", "photo 1 again", done = done) // "Not sent. Tap to retry." while the rest is on its way
        done.waits()
        assertEquals(listOf("photo 1", "photo 2", "text", "photo 1 again"), sent)
    }

    @Test fun chatsDontWaitForEachOther() {
        val queue = SendQueue()
        val release = CountDownLatch(1)
        val other = CountDownLatch(1)
        queue.submit("!a") { release.await(5, TimeUnit.SECONDS); sent += "a: slow upload" }
        queue.send("!b", "b: text", done = other)
        other.waits() // while !a's upload is still going
        release.countDown()
        val after = CountDownLatch(1)
        queue.send("!a", "a: text", done = after)
        after.waits()
        assertEquals(listOf("b: text", "a: slow upload", "a: text"), sent)
    }
}
