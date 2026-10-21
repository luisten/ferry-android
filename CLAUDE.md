# Ferry Messenger for Android: notes for agents

The Android version of [Ferry Messenger](https://github.com/luisten/ferry-messenger) (iPhone, SwiftUI): Instagram DMs
straight from the phone through the same Go engine, `core/` of that repo (mautrix-meta, AGPL). The engine turns
Instagram's data into Matrix-style chats and keeps Instagram's rules (stages 1–2 there); this app shows them. The
plan is stage 3 of that repo's [docs/PLAN-android.md](https://github.com/luisten/ferry-messenger/blob/main/docs/PLAN-android.md)
(A0–A9, one step at a time, as the user directs). Its CLAUDE.md is the reference for Instagram's behaviour, the
chat model and the design; this file holds what's Android's own.

**Status:** A5 (2026-10-21): requests (`RequestBar`: Accept, Delete, "Reply instead"), Archive and Unarchive (this
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
JNI bridge, the login. Media come with A6. The engine is pinned at a2a0c44 (the iOS repo's main after PR #10: GIPHY stickers and GIFs, our watermark with an unread mailbox copy, removals of reactions loaded with the thread). The README is for users (plain words, no internals), and so is [CHANGELOG.md](CHANGELOG.md) ([Keep a Changelog](https://keepachangelog.com/en/1.1.0/)): a
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
  "RELEASED" (and background or stop the app, so its client disconnects). Answer theirs the same way.
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
  and JUnit 4 for tests only. The platform covers the web view and the Keystore: no library for those.
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
- **Archive is this phone's only** (as on iOS, DESIGN §4.5): a room tag (`Inbox.ARCHIVED_TAG`) from a map in
  SharedPreferences, put back on each update, so a newer message brings the chat back. Instagram isn't told.
- **New Message** searches as the chosen account (`IGSearch`, an Instagram request each: 300 ms after typing stops,
  2 letters or more) and opens with `IGOpenDM`: our DM, else Instagram's (read once), else a draft until the first
  message. Its answer's `seq` says when the room is in (`AppModel.seqWaiters`).
- **Colour means account** (DESIGN §0.1): the theme replaces Material's tonal palette with greys (`ui/Theme.kt`); the
  only colours are the accounts' (`AccountColor`: fill, ink, wash), each always with its mark or name. Glyphs are a few
  Material Icons path strings in `ui/Icons.kt`, not a library. Profile pictures load https only, in memory (`Pictures`).
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
- Kotlin can't alias a nested enum or sealed type with a `val` (`val p = Timeline.GroupPosition`): import its values.
- `--write-verification-metadata` prints "resolved during configuration time" warnings for every configuration:
  harmless, gone in a normal build.
