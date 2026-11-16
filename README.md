# Gem-Legacy

**Google's Gemini on old Android phones.** Gem-Legacy (short for *Gemini Legacy*) is a small, free Gemini chat app that runs on **Android 4.1 (Jelly Bean) and up**: text chat, photos from the camera or gallery, and hands-free voice calls where you can interrupt Gemini by talking over it.

Made by **DelaccTroix** at **FilenFolder Studio**.

> Not made by or affiliated with Google. "Gemini" is a trademark of Google LLC. Gem-Legacy talks to the public Gemini API using *your own* API key.

## Features

- Chat with Gemini, with the conversation saved on your phone
- Edit any message you sent: tap **Edit**, change it, and send it again
- Photos: take one with the camera or pick one from the gallery and ask about it (one picture at a time for now)
- Voice calls: talk to Gemini like a phone call, and start talking to interrupt it (still being improved)
- Dark mode (on by default) and Light mode
- Everything is remembered: your API key, theme, chat, speaker choice and unsent text
- Works on Android 4.1+: it includes modern TLS (Conscrypt), because the old built-in one can no longer connect to Google
- Automatic fallback: if a Gemini model is overloaded (HTTP 503) or retired, the next model in your list is tried
- Small app, no ads, no tracking, no accounts

## Tested on

| Android | Status | Notes |
| --- | --- | --- |
| 4.1.3 | Unstable | Voice chat is not working correctly |
| 5.1.1 | Stable | Everything works, voice chat has a delay |
| 7     | Stable | Everything works as intended (Tested by **Pecora_be01**)|
| 11    | Stable | Chat and photos work, the microphone permission couldn't go through (Fixed, Patch by **BlankRes**)|
| 14    | Stable | Same as 11, the microphone permission couldn't go through  (Fixed, Patch by **BlankRes**)|
| 16 | Stable | Everything works as intended |

*Will be updated as soon as it is tested in other devices.*

## Install

1. Download the APK from the [Releases page](https://github.com/FilenFolder/Gem-Legacy/releases).
2. On your phone, allow installing from unknown sources (Settings > Security).
3. Open the APK and install.

## Get a free API key

Gem-Legacy needs a Gemini API key (a free tier is available):

1. Open <https://aistudio.google.com/apikey> and sign in with a Google account.
2. Create an API key and copy it.
3. In Gem-Legacy, tap the gear icon, paste the key, tap **Test key**, then **Save**.

If your old phone's browser can't open that page, create the key on a computer or another phone and type it in.

## Voice calls

Tap the green phone button. The phone needs:

- Speech recognition: the Google app (voice search)
- Text-to-speech: for example Google Text-to-speech
- Microphone permission (asked on Android 6 and up)

By default Gemini's voice plays through the earpiece like a normal call. Tap **Speaker** for the loudspeaker. Headphones work best with the speaker on, because the microphone can hear Gemini's own voice.

Known limits: on some older recognizers the interruption only registers when you pause; Gemini can't hear you while it is thinking; voice chat is not yet reliable on Android 4.1; on Android 14 the microphone permission request has not gone through yet.

## Privacy

- Your API key is stored only in the app's private storage on your phone.
- Messages, and photos you send, go straight from your phone to Google's Gemini API. There is no other server.
- The chat history is saved on your phone as text only. Photos are never saved. The trash icon deletes the chat.
- Photos are shrunk to about 1024 px before they are sent.

## Build from source

You need Android Studio and Android SDK 34.

```
git clone https://github.com/FilenFolder/Gem-Legacy.git
cd Gem-Legacy
./gradlew assembleDebug
```

The APK is created in `app/build/outputs/apk/debug/`. No keys are needed to build: each user enters their own in the app.

Project layout (`app/src/main/java/com/filenfolder/gemlegacy/`):

| File | What it does |
| --- | --- |
| `MainActivity.kt` | Chat screen, bubbles, saved chat, edit message, camera and gallery, call screen |
| `VoiceCall.kt` | Voice call: speech recognition, text-to-speech, interrupting |
| `GeminiClient.kt` | Gemini REST client (plain `HttpURLConnection`) with model fallback |
| `LegacyTls.kt` | Modern TLS for old Android through Conscrypt |
| `SettingsActivity.kt`, `Prefs.kt` | API key, model list, theme and saved preferences |
| `ImageUtils.kt`, `PhotoProvider.kt` | Photo shrinking, rotation and camera file access |

## Contact

- Email: filenfolderofficial@gmail.com
- Discord: filenfolder
- Device reports (phone model, Android version, what worked) and suggestions are welcome through GitHub issues or the contacts above.

## License

The source code is free to read, use and modify for yourself, but it may not be published or redistributed as your own work, or under another name or package name, without written permission. See the [LICENSE](LICENSE) file for the full terms.

Uses [Conscrypt](https://github.com/google/conscrypt) and Google's Material Design icons, both under the Apache License 2.0.
