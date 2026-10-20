# Ferry Messenger for Android: notes for agents

The Android version of [Ferry Messenger](https://github.com/luisten/ferry-messenger) (iPhone, SwiftUI): Instagram DMs
straight from the phone through the same Go engine, `core/` of that repo (mautrix-meta, AGPL). The engine turns
Instagram's data into Matrix-style chats and keeps Instagram's rules (stages 1–2 there); this app shows them. The
plan is stage 3 of that repo's [docs/PLAN-android.md](https://github.com/luisten/ferry-messenger/blob/main/docs/PLAN-android.md)
(A0–A9, one step at a time, as the user directs). Its CLAUDE.md is the reference for Instagram's behaviour, the
chat model and the design; this file holds what's Android's own.

**Status:** A0 (2026-10-20): the Gradle project, the engine with its JNI bridge, one login on the emulator showing
the chat count. No chat screens yet. **The bridge isn't merged in the iOS repo yet** (branch `claude/android-jni`):
the pinned 443b80e has none, so builds need a `go.work` pointing at that branch's worktree (below) until the user
merges it and this repo pins the merge. The README is for users (plain words, no
internals), and so is [CHANGELOG.md](CHANGELOG.md) ([Keep a Changelog](https://keepachangelog.com/en/1.1.0/)): a
user-visible change adds a line under Unreleased.

## Commands

```sh
export JAVA_HOME=~/Library/Android/jdk/jdk-21.0.12.1+1/Contents/Home ANDROID_HOME=~/Library/Android/sdk PATH=~/sdk/go1.27.1/bin:$PATH
./gradlew assembleDebug                         # runs build-engine.sh (libigcore.so, arm64-v8a), then the APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
~/Library/Android/sdk/emulator/emulator -avd igcore_ps16k -no-snapshot -gpu host   # with a window: logins need one
adb logcat -s ferry                             # C6 counts: logged each time the app leaves the screen (HOME)
./gradlew --write-verification-metadata sha256 help assembleDebug   # after a dependency change; review the diff
git ls-files | xargs grep -nE '"sessionid"\s*:\s*"[^"]{12,}|/Users/[a-z]'   # secret and home-path scan: expect nothing
```

Every launch with a stored account is an Instagram action (IGResume: index, mailbox, folder pages, socket). Read
the counts with HOME (`adb shell input keyevent KEYCODE_HOME`), never by relaunching.

## The engine

- **Pinned in `go.mod`** as `github.com/luisten/ferry-messenger/core` at a commit (pseudo-version; no tags until
  the API settles, tags are the user's), its `cmd/igcore` named by a `tool` line so `go mod tidy` keeps it. Bump:
  `go get -tool github.com/luisten/ferry-messenger/core/cmd/igcore@<commit>`, then check that `go.mod`'s `replace`
  still matches core's own (Go doesn't carry a dependency's `replace` over: without it the build silently uses
  upstream `imroc/req`, not Beeper's fork).
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
- **Live checks use the two dummies only** (stand-ins `tamoveska` personal, `kivostudio` professional; real names only
  in the iOS repo's gitignored `docs/local/test-accounts.md`). Every login, launch, send and "Seen" is an Instagram
  action: count them (C6) and report them.
- **The repo is private until the first clean version 1**, then public: no cookies, tokens, real account names or
  home-directory paths in tracked files. Commit freely; **push only when the user asks**, after the scans in
  Commands. Licence AGPL-3.0, like the iOS app.

**Code**

- **Dependencies (E7): Google's own only** (Kotlin, Compose/Material 3, AndroidX, Media3 when needed), exact versions,
  each checksummed in `gradle/verification-metadata.xml`; Gradle itself pinned by `distributionSha256Sum`. Name the
  library and version to the user and wait for a yes before adding one; never a dynamic version. The platform covers
  the web view, the Keystore and JSON (`org.json`): no library for those.
- `compileSdk` 37 because Compose 1.12 requires it; `targetSdk` 36 decides behaviour (the emulator is API 36).
- Engine calls are blocking and can wait on the network: never on the main thread. `IGNextEvent` is polled on its own
  thread; state the screen reads changes on the main thread only (`Engine`).

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
- `--write-verification-metadata` prints "resolved during configuration time" warnings for every configuration:
  harmless, gone in a normal build.
