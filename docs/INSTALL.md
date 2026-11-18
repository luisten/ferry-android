# Installing Ferry Messenger on Android

Ferry Messenger isn't in an app store: you install a file by hand. It takes about five minutes.

**You need:** an Android 8.0 or newer phone with a 64-bit ARM processor (nearly every phone of the last years), an
Instagram account, and the `.apk` file from the
[latest release](https://github.com/luisten/ferry-android/releases/latest). Only install it from there, or from
someone you trust who got it there.

## Install

1. Save the `.apk` file on your phone (from a message, a download link or a cloud folder) and open it from the app you
   got it in (your files app, Chrome, a messenger).
2. Android says that app isn't allowed to install apps. Tap **Settings**, switch on **Allow from this source** (also
   called "Install unknown apps"), go back, and tap the file again. That allows the one app you opened the file from,
   nothing else; you can switch it off afterwards.
3. Tap **Install** (**Update** when an older Ferry is already on the phone). Google Play Protect may say it doesn't know the app or its developer, or offer to scan it. That's
   expected: the app isn't from Google Play. Choose **Install anyway** (or **Scan**, which is fine too).
4. Open Ferry Messenger.

## First start

- Tap **Connect Instagram account** on the welcome screen and log in. **You log in on Instagram's own page inside the app.** Your password goes only to
  Instagram; Ferry never sees it. Then give the account a name, a colour and a mark, so its chats are easy to tell
  apart. You can add more accounts later.
- **Notifications:** Ferry stays connected to Instagram in the background to tell you about new messages, and shows a
  quiet "Connected" notification for it. Allow notifications when Android asks; without them you won't hear about
  messages.
- **Battery:** some phone makers' battery savers stop apps in the background. If messages arrive late or only when you
  open Ferry, open **Accounts** in the app and use the battery exemption it offers. Your phone's own settings may also
  have a "battery" or "background" setting for Ferry that should be "unrestricted".

## Updating

Ferry looks for a new version once a day while it's open. When there is one, the inbox shows **Ferry Messenger X.Y.Z
is available**: tap **Update**. The first time, Android asks whether Ferry may install apps: tap **Open Settings**,
switch on **Allow from this source** (also called "Install unknown apps"), and go back; the update starts by itself.
Ferry downloads the new version from the same releases page and installs it. On Android 12 and newer that usually
happens without another question, and Ferry closes and comes back as the new version (open it again if it doesn't);
on older phones Android shows its own **Update** screen first. **Later** hides it for now.
Don't want it to look? Switch off **Check for updates** in Accounts. It sends nothing about you or your accounts.

You can also update by hand: install the new `.apk` the same way as the first time, over the old one. Either way your
accounts and chats stay. This works because every version is signed with the developer's own key. A build signed with
a different key (a test build, say) won't install over it: uninstall Ferry first, which signs you out, then install
and log in again.

**Obtainium instead:** if you use [Obtainium](https://github.com/ImranR98/Obtainium), an app that keeps apps up to date
from their release pages, add `https://github.com/luisten/ferry-android` to it, and switch off Check for updates in
Ferry so you aren't told twice.

To check that your file is the developer's, its signing certificate's SHA-256 fingerprint is

`75:D2:47:FA:A0:92:7E:AF:BD:35:8B:A6:B1:F2:5B:AD:6E:F5:E6:6A:40:6C:7A:14:73:E4:9B:A2:E0:79:ED:C4`

(`apksigner verify --print-certs ferry-messenger-1.7.1.apk`, from the Android SDK's build-tools, shows it.)

## Good to know

- Google is rolling out a developer verification requirement for apps installed outside Google Play, in some countries.
  Where it applies, Android may refuse to install an app like this one. It's a possibility, not something known for your
  phone; if the install is blocked and says why, please report it (see below).
- Ferry isn't made by Instagram or Meta, and Instagram can change how it works at any time. Using it is at your own
  risk.
- Notifications depend on your phone letting Ferry run in the background (see Battery above).
- Messages from message requests and General don't notify.
- Marking a chat as unread, and archiving, are on this phone only; Instagram isn't told.
- Turning the phone keeps you in the chat. Changing the phone's language, or Android closing Ferry to free memory,
  takes you back to the inbox.
- It runs on 64-bit ARM phones only, and the screen reader (TalkBack) support hasn't had a check on a real phone yet.

## Something wrong?

Open an [issue](https://github.com/luisten/ferry-android/issues), or tell the person who gave you the app. Please
include: your phone's make and model, its Android version, what you did, what
you expected and what happened, and roughly when. A screenshot of a problem is welcome, but only of something you're
happy to share, and an issue is public: **never send a password, and don't send screenshots with other people's messages unless they're fine
with it.**

## Removing it

Open Accounts in Ferry and remove each account (this deletes its login from the phone), then uninstall Ferry like any
other app (long-press its icon, then App info, then Uninstall). Nothing else is left behind, and nothing of it is in
your phone's backups.
