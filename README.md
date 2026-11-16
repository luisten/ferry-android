# Ferry Messenger for Android

Your Instagram messages in a calm app of their own: the chats, without the feed. This is the Android version of
[Ferry Messenger](https://github.com/luisten/ferry-messenger) for iPhone.

**Version 1.7, a test version for friends.** It matches the iPhone app's 1.7: your chats from every account in one inbox,
requests and folders; reading and answering with replies, reactions, edits, photos, videos, voice messages and links;
swiping a message to reply; marking a chat as unread; sending several photos and videos at once or taking one with
the camera; new messages as notifications. [CHANGELOG.md](CHANGELOG.md) lists it all.

**Getting it:** there's no app store. The person who invited you sends you the `.apk` file, and
[docs/INSTALL.md](docs/INSTALL.md) shows how to install it. You need an Android 8.0 or newer phone with a 64-bit ARM
processor (nearly every phone of the last years).

## How it works

- You log in on Instagram's own web page, inside the app. Your password goes only to Instagram; the app never sees
  it.
- The app then talks to Instagram straight from your phone, the way instagram.com does. No server sits in between,
  so your messages go nowhere else.
- Your login stays on your phone, locked with a key that can't leave it. It isn't part of your phone's backups.
- Ferry Messenger isn't made by Instagram or Meta. Instagram could change how it works at any time.

## Licence

[AGPL-3.0](LICENSE), like the iPhone app, because it's built on [mautrix](https://github.com/mautrix/meta)'s
Instagram library, which uses the same licence.
