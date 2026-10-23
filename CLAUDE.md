# Ferry Messenger for Android: notes for agents

The Android version of [Ferry Messenger](https://github.com/luisten/ferry-messenger) (iPhone, SwiftUI): Instagram DMs
straight from the phone through the same Go engine, `core/` of that repo (mautrix-meta, AGPL). The engine turns
Instagram's data into Matrix-style chats and keeps Instagram's rules (stages 1–2 there); this app shows them. The
plan is stage 3 of that repo's [docs/PLAN-android.md](https://github.com/luisten/ferry-messenger/blob/main/docs/PLAN-android.md)
(A0–A9, one step at a time, as the user directs). Its CLAUDE.md is the reference for Instagram's behaviour, the
chat model and the design; this file holds what's Android's own.

**Status:** iOS 1.5.1 (2026-10-23): the 0.3 s hold, holding any card (an opened set's too), and the link preview's
picture and tap, the last a gap from before; checked on the demo. iOS 1.1–1.2's last gaps (2026-10-22): Chat Info (`ui/ChatInfo.kt`) and the person card, Share in the
photo viewer and Share All, swipe to archive, pull to refresh, drag to reorder accounts; checked on the demo. With it
Android has all of iOS 1.0–1.5. iOS 1.5 parity (2026-10-22): the iOS logo and launch screen; a chat that opens unread holds its "New
messages" line; the jump-to-newest button; listening back to a voice note; the long press's reaction sheet with recent
and any emoji; iOS's thread header, composer and inbox bottom bar. A8 (2026-10-22), first part: notifications by E8's option 1 (the user's choice: a foreground service,
`EngineService`, keeps the engine connected while Ferry is closed; `Notifier`); checked on the demo. Still A8: days on a
real phone for battery and phone makers' battery savers, and whether Instagram minds the connection. A7: accounts (`ui/Accounts.kt`: Welcome, "Name this account" for each new account and "Add
another account?" after the first, the identity editor, each account's page with Remove Account, the signed-out
prompt and bar, the inbox's banner; `AppModel.login(relogin)` refuses a different account). A6 and A7 checked live
2026-10-22 (re-login, a photo and a voice note to kivostudio, the viewer, Load story on an expired story). A6: media (`ui/Media.kt`: photo, album, GIF and sticker, video, reel and story with "Load
video", post and voice cards, the full-screen viewer; `MediaFiles` the download cache; `Outgoing` getting a pick ready;
`VoiceRecorder`, `VoicePlayer`); checked on the demo only. A5 (2026-10-21): requests (`RequestBar`: Accept, Delete, "Reply instead"), Archive and Unarchive (this
phone only), New Message (`ui/NewMessageScreen.kt`: From account, Instagram's people search, the DM); checked live,
requests through the engine's forced request (`--es request_room`). A4: the thread, write side (`ui/Composer.kt`: the composer, the message menu; `AppModel`'s
send, reply, react, edit, unsend, typing, with a local echo and "Not sent. Tap to retry"; checked live between
the dummies). A3: the thread, read side
(`ui/ThreadScreen.kt`: bubbles, group runs, replies, reactions, "Seen" and "Seen by", Instagram's own lines, the start
block, older pages; media as labelled cards until A6). A2: the
inbox, read-only (`ui/`: account stack, scope chips, Folders menu, Requests card,
rows, search, the folder lists, a minimal Accounts sheet) on `AppModel`, which connects the stored accounts and
applies the engine's updates. A1: the chat model (`model/`, the iOS app's `Room`, `Timeline`, `Inbox`, in Kotlin) with
its unit tests on the engine's golden files and the Matrix-style fixtures. A0: the Gradle project, the engine with its
JNI bridge, the login. A signed APK and the friends' install guide come with A9. The engine is pinned at a2a0c44 (the iOS repo's main after PR #10: GIPHY stickers and GIFs, our watermark with an unread mailbox copy, removals of reactions loaded with the thread). The README is for users (plain words, no internals), and so is [CHANGELOG.md](CHANGELOG.md) ([Keep a Changelog](https://keepachangelog.com/en/1.1.0/)): a
user-visible change adds a line under Unreleased.

## Commands

```sh
export JAVA_HOME=~/Library/Android/jdk/jdk-21.0.12.1+1/Contents/Home ANDROID_HOME=~/Library/Android/sdk PATH=~/sdk/go1.27.1/bin:$PATH
./gradlew assembleDebug                         # runs build-engine.sh (libigcore.so, arm64-v8a), then the APK
./gradlew testDebugUnitTest                     # the model's tests, on the JVM, ~5 s (results: app/build/test-results)
adb install -r app/build/outputs/apk/debug/app-debug.apk
~/Library/Android/sdk/emulator/emulator -avd igcore_ps16k -no-snapshot -gpu host   # with a window: logins need one
adb logcat -s ferry                             # C6 counts: logged each time the app leaves the screen (HOME)
adb shell am start -n fi.ferry.messenger/.MainActivity --ez demo true   # debug: made-up data, nothing connects
./gradlew --write-verification-metadata sha256 help assembleDebug   # after a dependency change; review the diff
git ls-files | xargs grep -nE '"sessionid"\s*:\s*"[^"]{12,}|/Users/[a-z]'   # secret and home-path scan: expect nothing
```

Every launch with a stored account is an Instagram action (IGResume: index, mailbox, folder pages, socket). Read
the counts with HOME (`adb shell input keyevent KEYCODE_HOME`), never by relaunching.

## The engine

- **Pinned in `go.mod`** as `github.com/luisten/ferry-messenger/core` at a commit (pseudo-version; no tags until
  the API settles, tags are the user's), its `cmd/igcore` named by a `tool` line so `go mod tidy` keeps it. Bump:
  `go get -tool github.com/luisten/ferry-messenger/core/cmd/igcore@<commit>` and `go mod tidy`, then check that
  `go.mod`'s `replace` still matches core's own (Go doesn't carry a dependency's `replace` over: without it the build
  silently uses upstream `imroc/req`, not Beeper's fork) and that `go.sum` is core's `go.sum` plus the engine's own
  two lines (`sort go.sum | comm -23 - <(git -C <iOS repo> show <commit>:core/go.sum | sort)`).
- **The JNI bridge is `core/cmd/igcore/jni_android.c` in the iOS repo** (decision E6), beside the C API it wraps;
  cgo compiles it only for Android. Its symbols name `fi.ferry.core.IGCore` ([IGCore.kt](app/src/main/kotlin/fi/ferry/core/IGCore.kt)):
  moving that class or package breaks the bridge. Strings cross as UTF-8 `ByteArray`s, never `jstring` (emoji).
- **Changing the bridge or the engine:** a branch in a git worktree of the iOS repo (never its main checkout), and a
  gitignored `go.work` here that uses it (`go work init . <path to that worktree>/core`). `build-engine.sh` then
  builds the worktree's engine. Once the user merges it there, pin that commit and delete `go.work`.
- The library is ~17.8 MB, stored uncompressed and 16 KB-aligned in the APK (Android 15+ phones need the alignment;
  NDK r30 does it). arm64-v8a only (`abiFilters`).

## Invariants

**Safety** (as on iOS; its CLAUDE.md says why)

- **Never type into instagram.com, never store or log Instagram passwords.** The user does every login by hand, in
  the emulator's window; tell them before each one, with its expected cost.
- **The session leaves the engine only through `IGSession`**, straight into [Sessions](app/src/main/kotlin/fi/ferry/messenger/Sessions.kt):
  one file per account in `noBackupFilesDir`, AES-GCM with an Android Keystore key. `allowBackup="false"` and
  [data_extraction_rules.xml](app/src/main/res/xml/data_extraction_rules.xml) keep everything out of cloud backup and
  device transfer. Never pass cookies through logs, intents, `SharedPreferences`, files, test fixtures or env vars.
- **The login web view's store is app-wide and on disk** (no private store per view, unlike iOS's
  `nonPersistent()`): `LoginScreen` clears cookies and web storage before the page loads and when the view goes, then
  flushes. A process killed mid-login leaves that attempt's cookies until the next login clears them.
- **Nothing secret or message text goes to Logcat:** the page's console is swallowed (`onConsoleMessage`), `ferry`
  logs counts and account IDs only, the engine's own log goes to stderr (`/dev/null` in an app).
- **One live user of the dummies at a time.** Other sessions (the iOS app's) test with the same accounts: before a
  live check, message them "LOCK: <accounts>, <what>, <expected requests>" and wait for "OK"; afterwards send
  "RELEASED" (and force-stop the app, `adb shell am force-stop fi.ferry.messenger`: since A8 its service stays
  connected in the background). Answer theirs the same way.
- **Check screens on the demo first** (`--ez demo true`, `Demo.kt`, debug builds only): the iOS app's made-up accounts,
  chats, requests and group; the engine isn't even loaded, so it costs no Instagram action.
- **Live checks use the two dummies only** (stand-ins `tamoveska` personal, `kivostudio` professional; real names only
  in the iOS repo's gitignored `docs/local/test-accounts.md`). Every login, launch, send and "Seen" is an Instagram
  action: count them (C6) and report them.
- **The repo is private until the first clean version 1**, then public: no cookies, tokens, real account names or
  home-directory paths in tracked files. Commit freely; **push only when the user asks**, after the scans in
  Commands. Licence AGPL-3.0, like the iOS app.

**Code**

- **Dependencies (E7): Google's own only** (Kotlin, Compose/Material 3, AndroidX, Media3 when needed), exact versions,
  each checksummed in `gradle/verification-metadata.xml`; Gradle itself pinned by `distributionSha256Sum`. Name the
  library and version to the user and wait for a yes before adding one; never a dynamic version. Approved beyond
  that: `kotlinx-serialization-json` (Kotlin's own, the engine's JSON, as Swift's `Codable`) with its compiler plugin,
  and JUnit 4 for tests only; Media3 1.11.1 (exoplayer, ui-compose, transformer: playback and shrinking videos, the
  user's choice for A6), which brings Guava 33.3.1-android and annotation jars. The platform covers the web view, the
  Keystore, the photo picker and voice recording (`MediaRecorder`): no library for those.
- **The chat model (`model/`) is a port of the iOS app's, kept close to it** (same names, same order, the Swift
  doc comments) so a fix on one side ports to the other: change both, and port the tests with it. It models what
  the engine sends (core/chat) and nothing of Ferry's bridge: no send statuses ("delivered"), no bridge bot, no
  `xma_fetch_status` failures, no personal spaces, room summary, server-side `m.tag` or implicit DM names. Pure: no
  `android.*`, so the tests run on the JVM. Glyphs are `Glyph` values; the screens pick the Material Symbol.
- **Fixtures** (`app/src/test/resources/fixtures`) are copied unchanged from the iOS repo at the pinned commit, and
  `engine_*.json` are the engine's own output: when the pin moves and core/chat's output changed, copy them again
  (their README). Tests say where a bridge-era fixture's extras (its bot, its spaces) changed what they assert.
- `compileSdk` 37 because Compose 1.12 requires it; `targetSdk` 36 decides behaviour (the emulator is API 36).
- Engine calls are blocking and can wait on the network: never on the main thread. `IGNextEvent` is polled on its own
  thread; everything the screens read changes on the main thread only (`AppModel`). Rooms are plain mutable objects:
  reading `AppModel.rooms` subscribes a screen, and the map is re-sent after each batch of updates.
- **Opening a chat** calls `IGOpenChat` (a read, at most every 30 s, the engine decides) and `IGMarkRead` on open and
  on each new message while it shows: the engine sends "Seen" only when the chat is unread and isn't a request. The
  thread's list is laid out from the bottom (`reverseLayout`), so it opens on the newest message and older pages land
  above it without moving it; its top item pages back (`IGOlder`, a read) only while it's on screen.
- **Sending** shows the message at once (a `PendingMessage` whose transaction ID is the offline threading ID from
  `IGNewOTID`, so Instagram's echo replaces it); a failure leaves "Not sent. Tap to retry" (the same ID again) or
  Delete. React, edit and unsend wait for the echo instead; a failure shows as a notice. The menu (long press) offers
  only what the engine allows: no reactions, edits or unsends in a request, Edit only within `Timeline.canEdit`.
  Typing goes to the engine as it starts and stops; the engine throttles it and never sends it in a request.
- **A request** shows `RequestBar` instead of the composer (`Inbox.threadBar`). Accept and Delete are optimistic
  (`Room.requestAction`: the chat moves or hides at once) and undone with a notice if the engine refuses; a Delete's
  room then goes with the engine's `RoomRemoved`. "Reply instead" sends through the engine, which accepts first.
- **Accounts** (A7, the iOS app's rules and copy): an identity (name, colour, mark) can't share any of the three with
  another account (`AccountIdentity.problems`, tested); a new login is named first (`AppModel.naming`). Re-login
  goes through the same web view with the account to fix (`login(cookies, relogin)`): another account that isn't
  ours is stopped, its updates dropped, and the user told who to log in as. Remove Account stops its client
  (`IGStop`), deletes its session and identity and drops its chats; its archive and Requests markers stay, hidden.
  Health: `Inbox.banner` (offline after 2 failed connects with none connected, else the account in trouble),
  "Later" hides it until it changes; a signed-out account's chats get `RecoveryBar` instead of the composer.
  Demo switches: `--ez signed_out true` (Kivo signed out), `--ez naming true` (O5 for Tamo); live, `--es
  signed_out_account <id>` shows that account signed out (locally, nothing sent) until it logs in again (A3's check).
- **Notifications** (E8 = option 1, 2026-10-22; option 3, a relay and FCM shared with iOS, is the later decision):
  `EngineService` (type `remoteMessaging`, started with the first account, at boot, stopped with the last one; never
  in the demo) holds the process, and so the engine's connections, with a silent "Connected" notification. A live
  update's new messages from others notify (`Notifier.messages`, DESIGN §3.3: title the contact, "to Studio", the
  account's colour); not a history page, the first sync, messages from before the process, requests or General
  (DESIGN §4.5), or the chat on screen. Text is marked private (Android's sensitive-notification setting decides the
  lock screen). Opening or reading the chat clears it. O7 asks once (Android 13+); Accounts offers the battery
  exemption. A signed-out account notifies when Ferry isn't on screen. Demo: `--ez incoming true` (Noor, 10 s in).
- **iOS parity is UI too** (the user, 2026-10-22): the same logo, layout and copy as the iOS app, in Material.
  The icon is the iOS repo's `docs/design/ferry-icon-color.svg` with its paths copied verbatim into the adaptive
  icon (`res/drawable/ic_launcher_*`, the 1024 artboard centred in the 108 dp layer), the themed icon and the
  launch mark (dark: `ferry-icon-color-dark.svg`'s colours). Redo them when the SVGs change.
- **Opening a thread** (iOS 1.5): unread, it holds the "New messages" line under the last message read unless what's
  new reaches less than a quarter screen past it; hidden until placed (1 s at most). The list is reversed, so the
  placement measures offsets after `scrollToItem` (a chat's start clamps it). A touch, the jump button or a send
  lets go. Only a send from this phone follows the end (`Message.isLocalEcho`). Demo: `--ez busy true`.
- **The long press opens a sheet** (iOS 1.5; 0.3 s since iOS 1.5.1, `quickHold` in the thread's list, Android's own
  0.4 s elsewhere; a longer delay chosen in accessibility settings stays): the message named in one line, `RecentReactions` (tested; stored in
  SharedPreferences, recorded when adding) and "+" (a field for the keyboard's emoji, `isEmoji`), then Reply, Copy,
  Edit, Share All (photos and videos sent together), Open in Instagram, Unsend/Delete, each run once the sheet is gone.
- **Archive is this phone's only** (as on iOS, DESIGN §4.5): a room tag (`Inbox.ARCHIVED_TAG`) from a map in
  SharedPreferences, put back on each update, so a newer message brings the chat back. Instagram isn't told. A row
  swiped left (`SwipeToDismissBox`, its grey only as wide as the swipe) or long-pressed archives or unarchives it.
- **Chat Info** (iOS 1.2, DESIGN T3) is drawn over the thread, which stays composed underneath (its draft, its place,
  its "Seen"), opened from the header capsule or ⋯. The person card (`PersonCard`) shows only what the thread brought:
  nothing is asked of Instagram. Pull to refresh (inbox) and opening Accounts call `AppModel.restartSync`: an account
  whose connect failed connects again (an IGResume each); connected and signed-out ones cost nothing.
- **Share** goes through `FileProvider` (`${applicationId}.files`), which lends only `cache/media` and
  `cache/outgoing` (`res/xml/shared_files.xml`), read-only, to the app the user picks.
- **New Message** searches as the chosen account (`IGSearch`, an Instagram request each: 300 ms after typing stops,
  2 letters or more) and opens with `IGOpenDM`: our DM, else Instagram's (read once), else a draft until the first
  message. Its answer's `seq` says when the room is in (`AppModel.seqWaiters`).
- **Media** (A6, the iOS app's rules): cards download their file on a tap, never in the row's scope (a row scrolled
  away would cancel it), into `MediaFiles` (cache folder, Instagram's file names, https only, trimmed past 500 MB); a
  failed download reads the thread again (`IGReadChat`), since links expire. A card registers its tap with the
  message's own gesture handler (`LocalTap`), so a long press on a photo still opens the menu; a card with its own tap
  (each one of an opened set) takes the message's long press too (`TapTarget.hold`). GIFs play three times
  once per launch, stickers loop, neither in a request or with animations off (Android 9+, `AnimatedImageDrawable`).
- **Sending media** (`Outgoing`): photos as JPEG (at most 2048 px, quality 85/70/50 until under 8,000,000 bytes; no
  metadata is copied); animated GIFs and WebPs as they are with their metadata cut out (`MediaClean`: no comments,
  XMP or EXIF, only frames, timing and loop), at most 8,000,000; videos through Transformer with only the
  orientation written (no location, XMP or dates), as they are up to 25,000,000 bytes, else 720p, 540p, 480p, else
  "Trim it first". Media goes first, the text after as its own message (Instagram drops captions). Voice notes: AAC
  .m4a, 44.1 kHz mono 64 kbit/s, sent as audio/mp4 with the voice flag; a tap records hands-free, a hold sends when
  let go after a second, five minutes sends itself, leaving the thread or the app throws it away.
- **Colour means account** (DESIGN §0.1): the theme replaces Material's tonal palette with greys (`ui/Theme.kt`); the
  only colours are the accounts' (`AccountColor`: fill, ink, wash), each always with its mark or name. Glyphs are a few
  Material Icons path strings in `ui/Icons.kt`, not a library. iOS draws SF Symbols, which Apple licenses for its
  platforms only: pick the closest Material icon (`tray.2` → `all_inbox`, outlined) and match the button around it. Profile pictures load https only, in memory (`Pictures`).
- An account gets its default name, colour and mark (`AccountIdentity.withDefaults`) only once Instagram has said who
  it is (its viewer, kept in SharedPreferences with identities, Requests markers and the scope); else a stored
  account from before would be named by its ID for good.

## Traps (each one bit us)

- **A `WebView` in Compose's `AndroidView` needs `MATCH_PARENT` layout params.** With the default `WRAP_CONTENT` the
  page's viewport is 0 px tall to CSS (`html { height: 100% }`, `100dvh`): instagram.com's login (its "Bloks" page)
  loaded, laid out and stayed invisible, white on white. Plain pages rendered, so it looked like Instagram refusing
  WebViews; it wasn't (removing `; wv` from the user agent changed nothing).
- **Debug builds expose the WebView to DevTools over adb** (`webview_devtools_remote_<pid>`): `adb forward
  tcp:9222 localabstract:webview_devtools_remote_<pid>`, then the CDP endpoints at `localhost:9222`. That's how the
  trap above was found. Only on the login page before anyone logs in, and `adb forward --remove-all` after: once a
  session exists the page holds its cookies.
- **Hand-installed SDK packages** (zips from dl.google.com, checksummed; there are no cmdline-tools here): AGP writes a
  `package.xml` for a plain folder, but not for Platform 37.0 (its minor version), and then asks to install it and
  fails on licences. Write the `package.xml` from `platforms/android-36`'s with the new path, API level and name.
  Never create `licenses/`: accepting the SDK's terms is the user's.
- The emulator image's WebView is Chrome 133 (Feb 2025) and can't update (no Play Store); a phone's is current.
- `shouldOverrideUrlLoading` sees neither POSTs nor in-page (history API) navigations, so the logout block is
  defence in depth, as on iOS.
- **`org.json` can't be used in the model:** it's a stub in JVM unit tests ("not mocked"), which is why the model
  decodes with kotlinx-serialization.
- **kotlinx's `JsonPrimitive` answers `doubleOrNull`/`booleanOrNull` for strings too** ("12" is 12.0): the model's
  accessors (`string`, `number`, `long`, `bool` in Events.kt) check `isString` first, as Swift's `JSON` enum does.
- **Don't read the composer's text in `ThreadScreen`'s body.** Each keystroke then recomposes the whole thread (the
  timeline's reduce and layout, every bubble): ~200 ms a key on the emulator. Only the composer slot reads it; the
  typing state is a `derivedStateOf`.
- **"Isn't responding" on the emulator can be the Mac, not the app.** With the host loaded (load average 20–46, the
  iOS simulator and builds alongside) the emulator swaps and the app waits for a CPU: an injected long press then
  misses Android's 5 s input timeout. Each `uiautomator dump` also costs the app's main thread 0.4–7 s. Before
  blaming the code: `uptime`, the ANR's `schedstat` (time waiting to run), and the main thread's CPU while idle
  (`/proc/<pid>/task/<pid>/stat`, fields 14–15) should be ~0. Tap by coordinates from a screenshot, not by a dump.
- **Instagram echoes "❤" for "❤️"** (it drops the emoji variation selector U+FE0F): compare reaction keys with
  `Message.Reaction.same`, never `==`, or our own heart isn't ours and choosing it again re-reacts (iOS fixed the
  same in luisten/ferry-messenger#10). A removal must name Instagram's key: `IGReact` with "❤️" left its "❤" in
  place (a fresh load still had it, 2026-10-21), so `AppModel.react` sends the stored key: checked live, the heart
  went and a fresh load agreed.
- **A removal echo for a reaction loaded with the thread isn't matched** (engine): Instagram names it by its log ID,
  the engine's history reactions are `<msg>~react<fbid>`, so the screen keeps it until a fresh load. Fixed in
  core/chat by luisten/ferry-messenger#10 (one such reaction on the message: redacted; several: a re-read), pinned.
- **The emulator dies when its audio can't reach the Mac's output** (`coreaudio(output): Could not resume the device`,
  then hanging QEMU threads, exit 139), here with the output on an external monitor: opening the microphone was
  enough. Boot with `-no-audio` (the guest still records, silence) or switch the Mac's output. A crash right after
  an install can leave a corrupt APK: the package vanishes at the next boot, its data stays; reinstall (same uid),
  and `adb shell sync` after installs. After a crash the emulator shows a dialog asking to send Google the report
  and waits: boot with `-crash-report-mode never -no-metrics`.
- **Android sends BOOT_COMPLETED when a force-stopped app next starts**, not only at boot: the receiver started the
  service in the demo (with the emulator's stored session). The service stops itself when the app is the demo.
- **The emulator's boot connects its stored live session** unless the app was force-stopped before the emulator shut
  down (a stopped app gets no BOOT_COMPLETED): E8's service starts as on a phone. A boot after a demo session connected
  a dummy for 70 s without a lock (4 requests, 2026-10-22). Force-stop after every session, the demo's too, and right
  after a boot check `adb shell pidof fi.ferry.messenger`.
- **A failed Instagram request is retried five times** (mautrix-meta's `MaxHTTPRetries`): Load story on an expired
  story cost 6 `reels_media` requests (2026-10-22). Count a failing call as six.
- **The emulator's 2 GB can't hold it all** (system services, the app with Media3, the engine): it swaps and Android's
  own system process stops responding ("Process system isn't responding", an AlarmManager lock in the trace, the
  time spent waiting to run ten times the running). Boot with `-memory 3072` (a boot option; the AVD stays at 2 GB).
- **A composable that handles a press must stay in composition until the release**: swapping the composer for the
  recording strip on press removed the mic button and its gesture, so a hold never saw its release.
- Kotlin can't alias a nested enum or sealed type with a `val` (`val p = Timeline.GroupPosition`): import its values.
- `--write-verification-metadata` prints "resolved during configuration time" warnings for every configuration:
  harmless, gone in a normal build.
