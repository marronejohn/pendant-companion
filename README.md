# Pendant Companion

A privacy-first Android app for the Limitless/Omi pendant (LS-PT-01).

The app syncs the pendant over Bluetooth, decrypts and decodes the recordings,
transcribes them on the phone, scores them against an enrolled voiceprint so you
know how much of the day was you, mirrors the transcripts to your own Google
Drive folder, and writes daily, weekly, monthly, quarterly and yearly
reflection cards from those transcripts.

Everything except one optional step happens on the phone. The audio never
leaves it.

## What it does

- **Sync**: read-only download of every recording on the pendant (optional
  acknowledge mode makes the pendant erase each page once the phone has saved
  it; the phone then holds the only copy, and a backup zip is rebuilt after
  each acknowledged sync).
- **Transcribe**: on-device Whisper transcription (import the model once). No
  cloud, no account.
- **Your voice**: enrol a voiceprint from any session where you talk, and every
  session is scored for how much of it was you.
- **Transcripts to Drive**: all transcripts are copied into a Drive folder you
  pick once, named `YYYY-MM-DD_session-N.txt`; copies older than 14 days are
  deleted automatically. The app itself never uses the internet for this: the
  copy goes through Android's own folder access.
- **Reflection cards**: plain-English daily, weekly, monthly, quarterly and
  yearly cards in the same folder (`reflection_YYYY-MM-DD.json`,
  `review_<layer>_<period>.json`), readable in the app's Daily reflection card.
- **Delete what you don't want**: per-session delete, delete of all short or
  empty recordings, and a factory reset that clears everything.

## Reliable background sync

Syncing and transcription run in a foreground service, so Android shows a
persistent notification while they work. That notification is what keeps the
work alive; do not force-stop the app from it. For overnight and scheduled
syncs to be reliable on phones that aggressively save battery:

- **Settings → Apps → Pendant Companion → Battery → Unrestricted** (on some
  phones: "Don't optimize" / "Allow background activity").
- Turn off **Remove permissions if app is unused** on the same screen.
- Don't swipe the app away from Recents; on some phones that kills background
  services.
- Keep Bluetooth on. A sync that is interrupted picks up where it left off.

## Export

Everything the app records can leave it only when you send it. Any session can
be shared from the session list: a single session or several at once, as
transcript text or audio, through Android's share sheet (Save to Files, email,
anything installed). Transcripts also mirror to your Drive folder
automatically, as described above.

## Roadmap

Planned, in the same privacy-first shape as everything else:

- Multilingual Whisper models (transcription today is English-only).
- An optional self-hosted transcription endpoint for people who prefer to run
  their own Whisper server.

## Daily insights (AI), bring-your-own key

The reflection cards are written by an AI service you choose, called from your
phone at a time you set. Your key is stored in the app's private storage, is
never logged or exported, and only the transcript text for the card goes to
that service.

Setup, once:

1. Pick the transcripts folder in the **Sync & automation** card. The cards are
   written to the same folder.
2. Get an API key.
   - **Google Gemini (default, free tier available)**: open
     [aistudio.google.com](https://aistudio.google.com) in a browser, sign in
     with your Google account, tap **Get API key**, create a key, copy it.
   - **Any OpenAI-compatible service** (OpenAI, Groq, OpenRouter, a local
     server): take that service's key and its API base URL (for example
     `https://api.openai.com/v1`).
3. In the **Daily insights (AI)** card, leave **Gemini** selected (or pick
   **OpenAI-compatible** and fill in the base URL), leave the model as
   `gemini-2.5-flash` (or type your service's model name), and paste your key.
4. Turn on **Write cards automatically** and set the card time (9 pm is a good
   default).
5. Tap **Write today's card now** to test. The result line tells you what
   happened. The card then appears in the **Daily reflection** card.

The daily card is written once a day. Weekly cards are written on Sundays,
monthly on the 1st, quarterly on Jan/Apr/Jul/Oct 1st, half-yearly on
1 Jan / 1 Jul, and yearly on 31 December. A card is only written when its
transcripts are on the phone, and writing the same card again replaces it.

## Privacy

- Audio stays on the pendant and the phone; transcription is on-device.
- The only network call the app makes is the reflection card request, to the
  AI service you configured, with your key.
- If you never turn Daily insights on, the app never uses the internet.
- Acknowledge mode makes the phone the only copy of the recordings; keep a
  backup of its zip if that matters to you.

## Install

Download the APK from the Releases page and install it (allow installing from
your browser or files app when Android asks). Requires Android 8.0+ and an
arm64-v8a phone.

## Build from source

```
./gradlew assembleRelease
```

The release build is signed with the debug keystore so it installs like any
sideloaded app. Native libraries (ONNX Runtime for the voiceprint, Opus) are
compressed to keep the APK small.

## Device

Built for the Limitless/Omi pendant (LS-PT-01). The protocol, decryption and
flash layout follow the open projects around this device; no Limitless account
or cloud is used anywhere in this app.
