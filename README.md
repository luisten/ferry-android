# Ferry Messenger for Android

Your Instagram messages in a calm app of their own: the chats, without the feed. This is the Android version of
[Ferry Messenger](https://github.com/luisten/ferry-messenger) for iPhone.

**Not ready yet.** Today it logs in, shows your inbox (your chats from every account, requests and folders) and lets
you read and answer your chats, message requests included, start new ones, and send and see photos, videos and voice
messages. There's no download to install; this page will say when there is.

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
