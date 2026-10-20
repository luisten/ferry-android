package fi.ferry.messenger.model

import fi.ferry.messenger.model.MessageKind.ShareState.READY
import fi.ferry.messenger.model.MessageKind.ShareState.UNRESOLVED
import fi.ferry.messenger.model.MessageKind.ShareType.OTHER
import fi.ferry.messenger.model.MessageKind.ShareType.POST
import fi.ferry.messenger.model.MessageKind.ShareType.REEL
import fi.ferry.messenger.model.MessageKind.ShareType.STORY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What media and shares show as (the iOS app's MediaKindTests, DESIGN §6.3–6.8): real events where they exist, the
 * rest hand-made from the shapes the engine copies from the bridge (`xma.go`, `from-instagram.go`). Not ported yet:
 * the media cache and playback (A6), and share failures (`xma_fetch_status`, which only the bridge sent).
 */
class MediaKindTests {
    private fun kind(content: kotlinx.serialization.json.JsonElement) = MessageKind.of("m.room.message", content)

    // Real events

    @Test fun realUnresolvedReelAndItsResolveEdit() {
        val unresolved = Fixture.event("event_reel_unresolved")
        assertEquals(MessageKind.Share(REEL, UNRESOLVED), MessageKind.of(unresolved.type, unresolved.content))
        val preview = MediaInfo(unresolved.content)
        assertEquals("mxc://igm.home.arpa/rjDVCqAboxCeGbrIeHzoHEgy", preview.previewURL) // an image previews itself
        assertFalse(preview.isVideo)

        val edit = Fixture.event("event_reel_resolve_edit").content["m.new_content"]!!
        assertEquals(MessageKind.Share(REEL, READY), kind(edit))
        val video = MediaInfo(edit)
        assertTrue(video.isVideo)
        assertEquals("mxc://igm.home.arpa/qfzfnZlXNGlgiHYGVUpxmyYw", video.url)
        assertEquals("video/mp4", video.mimetype)
        assertEquals(29_429_419L, video.size)
        assertEquals(720.0, video.width)
        assertEquals(1280.0, video.height)
        assertEquals("mxc://igm.home.arpa/rjDVCqAboxCeGbrIeHzoHEgy", video.previewURL) // the old preview, kept as thumbnail
        assertNull(video.duration) // Instagram doesn't say how long a reel is
        assertEquals("www.instagram.com", parseURL(video.externalURL!!)?.host)
    }

    /** "Load video" on our own reel: the edit is ours too, so it passes the same-sender rule and swaps the card. */
    @Test fun realOwnReelResolved() {
        val events = Fixture.events("events_m5_kivo_own_resolve")
        assertEquals(MessageKind.Share(REEL, UNRESOLVED), Timeline.reduce(listOf(events[0]), Fixture.me).first().kind)
        val messages = Timeline.reduce(events, Fixture.me)
        assertEquals(1, messages.size)
        val reel = messages.first()
        assertEquals(MessageKind.Share(REEL, READY), reel.kind)
        assertTrue(reel.isOwn)
        assertFalse(reel.isEdited)
        assertEquals("mxc://igm.home.arpa/KCQEYOLketrXHGoZFDTWGEeT", MediaInfo(reel.content).url)
    }

    /** An older page's reel has only the link in its caption: no author, no caption text. */
    @Test fun realBackfilledCaptionIsJustTheLink() {
        assertEquals(ShareCaption(), ShareCaption.fromHTML(Fixture.event("event_reel_unresolved").content["formatted_body"].string))
    }

    @Test fun realPhoto() {
        val event = Fixture.event("event_photo_incoming")
        assertEquals(MessageKind.Image, MessageKind.of(event.type, event.content))
        val info = MediaInfo(event.content)
        assertEquals(info.url, info.previewURL)
        assertEquals(1200.0, info.width)
        assertEquals(900.0, info.height)
        assertEquals("image/jpeg", info.mimetype)
    }

    /** A voice note as Instagram returns it: Ogg/Opus, its length in the audio part. */
    @Test fun realVoiceNotes() {
        val event = Fixture.event("event_voice_incoming")
        assertEquals(MessageKind.Voice, MessageKind.of(event.type, event.content))
        val info = MediaInfo(event.content)
        assertEquals("audio/ogg", info.mimetype)
        assertEquals(7291L, info.duration)
        assertEquals(emptyList<Any>(), event.content["org.matrix.msc1767.audio"]["waveform"].array)

        val app = Fixture.event("event_voice_igapp") // recorded in the Instagram app
        assertEquals(MessageKind.Voice, MessageKind.of(app.type, app.content))
        assertEquals("audio/ogg", MediaInfo(app.content).mimetype)
        assertEquals(6997L, MediaInfo(app.content).duration)
        assertEquals(35_539L, MediaInfo(app.content).size)
    }

    /** Our own sends keep what we sent (audio/mp4 voice, the JPEG), so own cards get the same kinds. */
    @Test fun realOwnMediaKinds() {
        val messages = Timeline.reduce(Fixture.events("events_m5_tamo_own_media"), Fixture.me)
        assertEquals(listOf(MessageKind.Image, MessageKind.Voice), messages.map { it.kind })
        assertEquals("audio/mp4", MediaInfo(messages[1].content).mimetype)
    }

    // Live shares from the Instagram app

    /** Live shares arrive as the media itself, with the preview image as its thumbnail. */
    @Test fun realLiveSharesArriveReady() {
        val reel = Fixture.event("event_reel_live")
        assertEquals(MessageKind.Share(REEL, READY), kind(reel.content))
        val reelInfo = MediaInfo(reel.content)
        assertTrue(reelInfo.isVideo)
        assertEquals(9_919_668L, reelInfo.size)
        assertEquals("mxc://igm.home.arpa/EMxiFthAXmieYXCpJdYWMcRP", reelInfo.previewURL)

        // A landscape reel: the video is 1276×718 but its preview is portrait; the card keeps the reel's frame.
        val landscape = Fixture.event("event_reel_live_landscape")
        assertEquals(MessageKind.Share(REEL, READY), kind(landscape.content))
        assertEquals(718.0 / 1276, MediaInfo(landscape.content).aspect!!, 0.001)

        // A story is a video too.
        val story = Fixture.event("event_story_live")
        assertEquals(MessageKind.Share(STORY, READY), kind(story.content))
        assertTrue(MediaInfo(story.content).isVideo)

        // A post is the image itself.
        val post = Fixture.event("event_post_live")
        assertEquals(MessageKind.Share(POST, READY), kind(post.content))
        val postInfo = MediaInfo(post.content)
        assertFalse(postInfo.isVideo)
        assertEquals(postInfo.url, postInfo.previewURL)
        assertEquals(4096.0 / 3075, postInfo.aspect!!, 0.001)
    }

    /** A reel has no author or caption; a post's title is "<handle> <caption…>"; a story's author is in its URL. */
    @Test fun realLiveCaptions() {
        val reel = ShareCaption.of(Fixture.event("event_reel_live").content)
        assertEquals(ShareCaption(), reel)
        assertNull(reel.author)

        val story = ShareCaption.of(Fixture.event("event_story_live").content)
        assertEquals("sample.stories", story.author)
        assertNull(story.caption)

        val post = ShareCaption.of(Fixture.event("event_post_live").content)
        assertEquals("sample.photos", post.author)
        assertNull(post.title)
        val caption = post.caption!!
        assertTrue(caption, caption.startsWith("Morning light over the harbour"))
        assertTrue(caption, caption.endsWith("Walking the old qu…"))

        // Regression: the HTML alone can't tell the handle from the caption.
        val htmlOnly = ShareCaption.fromHTML(Fixture.event("event_post_live").content["formatted_body"].string)
        assertNull(htmlOnly.author)
        assertTrue(htmlOnly.title!!.startsWith("sample.photos Morning"))
    }

    // Shares (hand-made from the bridge's shapes)

    /** `wrapXMAItem` + `wrapXMACaption` with every text field filled. */
    private val captionedReel = obj(
        "msgtype" to "m.video",
        "body" to "**tidepool.films**\nGolden hour on the reef.\n\n[https://www.instagram.com/reel/DAbc123/](https://www.instagram.com/reel/DAbc123/?id=1_2)",
        "format" to "org.matrix.custom.html",
        "formatted_body" to "<strong>tidepool.films</strong><p>Golden hour on the reef.<br/>No lights &amp; no &#34;filters&#34;.</p><p><a href=\"https://www.instagram.com/reel/DAbc123/?id=1_2\">https://www.instagram.com/reel/DAbc123/</a></p>",
        "external_url" to "https://www.instagram.com/reel/DAbc123/?id=1_2",
        "url" to "mxc://igm.home.arpa/handmadeLiveReel",
        "info" to mapOf("mimetype" to "video/mp4", "size" to 12_000_000, "w" to 720, "h" to 1280, "thumbnail_url" to "mxc://igm.home.arpa/handmadeLivePreview"),
    )

    @Test fun shareTypes() {
        assertEquals(MessageKind.Share(REEL, READY), kind(captionedReel))
        val story = obj("msgtype" to "m.image", "body" to "story", "url" to "mxc://igm.home.arpa/handmadeStory",
            "external_url" to "https://www.instagram.com/stories/priya.mixes/3456789012345678901?reel_id=4242")
        assertEquals(MessageKind.Share(STORY, READY), kind(story))
        // A post (`/p/`): an image post, and a video post that plays like a reel.
        val post = captionedReel.setting(listOf("msgtype"), "m.image").setting(listOf("external_url"), "https://www.instagram.com/p/DPost42/?id=5_6")
        assertEquals(MessageKind.Share(POST, READY), kind(post))
        assertEquals(MessageKind.Share(POST, READY), kind(post.setting(listOf("msgtype"), "m.video")))
        // Anything else shared (a profile, music…): shown as what it is, the preview and the link.
        assertEquals(MessageKind.Share(OTHER, READY), kind(post.setting(listOf("external_url"), "https://www.instagram.com/tidepool.films/")))
    }

    /** View-once media arrives as a notice ("This %s can only be %s once. Use the Instagram app to view."). */
    @Test fun viewOnceNotices() {
        fun notice(body: String) = kind(obj("msgtype" to "m.notice", "body" to body))
        assertEquals(MessageKind.ViewOnce, notice("This image can only be viewed once. Use the Instagram app to view."))
        assertEquals(MessageKind.ViewOnce, notice("This video can only be replayed once. Use the Instagram app to view."))
        assertEquals(MessageKind.Notice, notice("Failed to transfer attachment"))
        assertEquals(MessageKind.Notice, notice("Unsupported message. Use the Instagram app to view."))
    }

    /** A text with a `com.beeper.linkpreviews` entry. */
    @Test fun linkPreview() {
        val content = obj(
            "msgtype" to "m.text", "body" to "https://lowtide.band/annex",
            "com.beeper.linkpreviews" to listOf(mapOf(
                "matched_url" to "https://lowtide.band/annex", "og:url" to "https://lowtide.band/annex",
                "og:title" to "Lowtide live at The Annex, Nov 14", "og:description" to "Tickets",
                "og:image" to "mxc://igm.home.arpa/handmadeLinkImage", "og:image:width" to 1200, "og:image:height" to "630",
            )),
        )
        assertEquals(MessageKind.Link, kind(content))
        val preview = LinkPreview.of(content)!!
        assertEquals("lowtide.band", parseURL(preview.url)?.host)
        assertEquals("Lowtide live at The Annex, Nov 14", preview.title)
        assertEquals("mxc://igm.home.arpa/handmadeLinkImage", preview.imageURL)
        assertEquals(630.0 / 1200, preview.aspect!!, 0.001) // a number or a string
        // Counter-case: an empty list is a plain text.
        val empty = content.setting(listOf("com.beeper.linkpreviews"), emptyList<Any>())
        assertEquals(MessageKind.Text, kind(empty))
        assertNull(LinkPreview.of(empty))
    }

    // Caption (`wrapXMACaption`, every part optional)

    @Test fun captionPartsAndUnescaping() {
        val full = "<blockquote>Reel</blockquote><strong>tidepool.films</strong><br>Original audio<p>Line one<br/>A &amp; B &lt;3 &#39;x&#39;</p><p><a href=\"https://www.instagram.com/reel/X/?id=1\">https://www.instagram.com/reel/X/</a></p>"
        val caption = ShareCaption.fromHTML(full)
        assertEquals("Reel", caption.eyebrow)
        assertEquals("tidepool.films", caption.title)
        assertEquals("Original audio", caption.subtitle)
        assertEquals("Line one\nA & B <3 'x'", caption.caption)
        assertEquals("tidepool.films", caption.author)

        val captioned = ShareCaption.of(captionedReel)
        assertEquals("tidepool.films", captioned.author)
        assertEquals("Golden hour on the reef.\nNo lights & no \"filters\".", captioned.caption)
        assertNull(captioned.subtitle)
    }

    /** Outside posts and stories, only a title that is a handle counts as the author; any other title is text. */
    @Test fun titleThatIsNotAHandleIsNoAuthor() {
        val caption = ShareCaption.fromHTML("<strong>Sunset at Playa Grande</strong><p><a href=\"https://www.instagram.com/reel/Y/\">https://www.instagram.com/reel/Y/</a></p>")
        assertEquals("Sunset at Playa Grande", caption.title)
        assertNull(caption.author)
        assertNull(caption.caption)
        assertEquals(ShareCaption(), ShareCaption.fromHTML(null))
        // A post title that doesn't start with a handle-shaped word stays a title.
        val post = obj("formatted_body" to "<strong>¡Hola! from the reef</strong>", "external_url" to "https://www.instagram.com/p/Z/")
        assertNull(ShareCaption.of(post).author)
        assertEquals("¡Hola! from the reef", ShareCaption.of(post).title)
        // A story URL without a handle segment gives no author.
        assertNull(ShareCaption.of(obj("external_url" to "https://www.instagram.com/stories/")).author)
    }

    /** The inbox names who made a share when it's known ("Sent a post by @sample.photos"). */
    @Test fun previewSaysWhoMadeTheShare() {
        val room = InboxTests.portal("!p", Fixture.kivoLogin)
        fun last(event: Event, sender: String, ts: Long): String? {
            room.apply(timeline(event.copy(sender = sender, originServerTS = ts)))
            return Inbox.preview(room, Fixture.me)?.text
        }
        assertEquals("Sent a reel", last(Fixture.event("event_reel_live"), Fixture.tamoGhost, 5)) // a reel has no author
        assertEquals("You sent a story by @sample.stories", last(Fixture.event("event_story_live"), Fixture.me, 6))
        assertEquals("Sent a post by @sample.photos", last(Fixture.event("event_post_live"), Fixture.tamoGhost, 7))
    }
}
