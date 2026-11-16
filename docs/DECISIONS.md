# Decisions: Android, iOS 1.7 parity

This logs the Android-side decisions for bringing Ferry Messenger for Android to iOS 1.7 parity (newest at the bottom).
The iOS repo's `docs/DECISIONS.md` and `PLAN-android.md` hold the earlier ones.

| ID | Date | Decision | Why | Revisit when |
|---|---|---|---|---|
| D1 | 2026-11-16 | Engine pin `70aa388` (iOS main, v1.7); `go.mod` toolchain `go1.27.3`; `build-engine.sh` checks the Go version with `GOTOOLCHAIN=local` | Android's rule: pin what the user merged; no silent toolchain download | the next engine change |
| D2 | 2026-11-16 | Reaction log entries don't notify (`Timeline.notifiable`), but still make the chat unread and move it up | Android never notified reactions; iOS 1.6 said reactions don't notify; no withdraw logic needed | users ask for reaction notifications (then also withdraw on removal) |
| D3 | 2026-11-16 | Swipe to reply start→end (right in LTR), mirrored in RTL; iOS swipes left | Android messaging convention; ours (right side) are then away from the back-gesture edge | user feedback |
| D4 | 2026-11-16 | Camera: "Take Photo" and "Record Video" via the system camera (`TakePicture`/`CaptureVideo`, FileProvider `cache/camera`), no CAMERA permission, no library | Android has no single photo-or-video intent; no permission prompt | a camera library is approved |
| D5 | 2026-11-16 | Paste: the platform's own (field toolbar, keyboard insertion; `image/*`, `video/*`), no Paste chip; keyboard GIFs/stickers allowed | the Android way to paste; the keyboard's service, not Ferry's | users miss iOS's chip |
| D6 | 2026-11-16 | A per-chat sequential send order; video preparation one at a time; media first, then the text | Instagram's web send takes one attachment; MediaCodec limits | – |
| D7 | 2026-11-16 | `PickMultipleVisualMedia` with ordered selection (exists in activity 1.13.0, verified) | the platform picker, no permission | – |
| D8 | 2026-11-16 | TalkBack sender label on bubbles, with the time (iOS `spoken`/`SpokenSender`) | a pre-1.6 parity gap | – |
| D9 | 2026-11-16 | No new dependencies | the user approves each library | – |
| D10 | 2026-11-16 | Testing on the demo only; live items listed for the user | the user is asleep; no Instagram actions | – |
| D11 | 2026-11-16 | 1.7 release APK signed with a Mac-held key outside the repo, no minification, install guide | friends install by hand; JNI names | Play Store or CI signing |
| A12 | 2026-11-16 | **All** sends of a chat (texts, media, voice, retries) share one serial queue, so a text after a set waits for it; iOS sends the text at once | makes "then your text" true; a text alone with nothing queued goes as fast as today | texts feel slow behind big uploads; or iOS settles A§6.3 the other way |
| A13 | 2026-11-16 | Photo preparation one at a time (a single executor), not concurrent as on iOS | ten 2048 px decodes at once ≈ 250 MB on 2–4 GB phones | never needed faster |
| A14 | 2026-11-16 | Swipe: 60 dp threshold, 1/3 past it, 2:1 horizontal rule after the touch slop, claimed in the Main pass (children and the list first), consumed in the Initial pass after; arrow from the leading side at offset − 40 dp; `GestureThresholdActivate` haptic; no `systemGestureExclusion` (the edge's back gesture wins) | iOS's numbers; Compose's pass order gives the voice seek and the scroll priority and cancels the long press | real-phone feel |
| A15 | 2026-11-16 | Day chip: a surface capsule with a 2 dp shadow (no glass on Android), `labelMedium` semibold; never the incoming bubble grey | iOS's design review; the app's JumpButton/header convention | design pass |
| A16 | 2026-11-16 | The bubble clock follows the phone's 12/24-hour setting; day labels use the locale's best patterns ("EEEdMMM", wide weekday) | as the inbox does (`rememberTimeFormats`) | – |
| A17 | 2026-11-16 | Time inline via a transparent run + overlay (text); a dark capsule on cards; on the bubble's last part (a share's text) | WhatsApp's/iOS's layout | – |
| A18 | 2026-11-16 | One TalkBack description per bubble on `MessageMenu`'s node; links and Reply as custom actions; a media bubble reads "From Kivo, Photo, 9:41" (iOS: "From Kivo: Photo, 9:41") | Compose merges parent then child descriptions; avoids reading the time twice | a TalkBack user's feedback |
| A19 | 2026-11-16 | Link detector: http(s), `www.`, bare domains only with a short fixed TLD list | no TLD database without a library; NSDataDetector has one | missed or false links reported |
| A20 | 2026-11-16 | Mark as Unread swipe colour iOS's `swipeUnread` (#48484A / #636366, white content); icons `mark_email_unread` / `drafts` | neutral, set apart from Archive; colour means account | design pass |
| A21 | 2026-11-16 | `+` is a Material DropdownMenu: Take Photo, Record Video, Choose from Library (iOS order), camera items only with `FEATURE_CAMERA_ANY`; no camera app → "No camera app on this phone." | as iOS's menu; no `<queries>` needed for a launch | – |
| A22 | 2026-11-16 | One `PickMultipleVisualMedia(10)` launcher with the request's `maxItems` = slots left; the single picker for one slot; extras dropped with iOS's message | verified API: max = min(contract, request), must be ≥ 2 | – |
| A23 | 2026-11-16 | Pasted/inserted videos are copied into `cache/outgoing` before they queue | a clip's or IME's read grant may end before a queued transcode starts | – |
| A24 | 2026-11-16 | Demo switches named after iOS's: `--ei group_older`, `--ei group_extra`, `--ez reaction`, `--ez clipboard` | one vocabulary across both repos | – |
| A25 | 2026-11-16 | `versionCode` 17 for 1.7, +1 per release | sideloaded updates need a growing code | Play Store |
| A26 | 2026-11-16 | Reaction pills in a custom `Layout`: measured unconstrained, never sizing the bubble, placed by iOS's `PillPlacement`; drop 22 dp; group spacing 10/1 | the Box widened our short bubble; iOS's squeeze trap | FOLLOWUPS #59/#60/#62 on iOS |
| A27 | 2026-11-16 | The floating day shows only after a user drag, hides 1 s after the scroll stops, 200 ms fade | iOS; programmatic scrolls make no drag interaction | – |
| A28 | 2026-11-16 | Mark-unread swipe starts inside the row; no edge exclusion on the inbox | the left edge is back (leaves the app from the root) | real-phone feel |
| A29 | 2026-11-16 | `AppModel.apply` clears a chat's notification on Instagram's counts, not `isUnread` | a chat marked unread here would keep a stale notification | – |
