# ThoughtDrop

A voice notebook for Android 8.0 and later. Record without an account or an internet connection. Each completed recording stays in the app’s private storage as an AAC file. You can play it back, give it a title, edit its transcript and search your notebook.

## Try it on your phone

1. Build the APK using the instructions below or open your existing ThoughtDrop installer on the phone. If Android asks, allow the app you used to open the download to install this file. You can switch that permission off again afterwards.
2. Open ThoughtDrop, tap the microphone and grant microphone access. Speak, then tap stop. Tap **Play** on the new note to listen.
3. For automatic transcription, open the settings cog, enter your own Google Gemini API key and tap **Save settings**. This queues saved notes awaiting transcription, including earlier recordings. When online, the app sends the recording to Google for a short title and transcript. A blank key keeps new recordings local. The key is stored in private app preferences.
4. Tap a pencil to edit. Saving an edit makes your text final for that note, including when an automatic transcript was pending. The original audio is kept. Search matches words and word prefixes in both titles and transcripts.

Uninstalling ThoughtDrop or clearing its app data removes the notebook. This test APK uses a debug signature. Android requires the same signing key for future in-place updates.

## Quick capture

Use **Record immediately on launch** in Settings to start recording when opening the app from its launcher icon. Add **Record a thought** from your launcher’s widget picker or from the Quick Settings tile editor to start a recording directly. Opening one of these shortcuts during recording brings you to the active recorder.

You can lock the screen while recording. Stop and save in the app or from the recording notification. On Android 13 or later, enable the recording notification in Settings if you want the notification action. A manual app force-stop or shutting down the phone can interrupt an unfinished clip.

## Transcription

ThoughtDrop uses the Gemini Developer API model `gemini-3.5-flash-lite`. It sends AAC audio as inline M4A data and requests JSON containing a title and transcript. WorkManager waits for connectivity and retries temporary service failures with backoff. Invalid project credentials, an exhausted quota or an unclear clip can prevent transcription. The note shows the problem and offers retry. Audio larger than 14 MB is retained for playback and manual editing.

[Google AI Studio API keys](https://aistudio.google.com/apikey) · [Gemini pricing and data-use terms](https://ai.google.dev/gemini-api/docs/pricing)

## Build

Open this folder in Android Studio with JDK 17, Android SDK Platform 35 and Android SDK Build Tools 34.0.0. On macOS or Linux, run:

```sh
./gradlew assembleDebug
```

On Windows, use `gradlew.bat assembleDebug`. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Run `./gradlew connectedDebugAndroidTest` with a connected device or running emulator. Test requests use a local interceptor and require no Google credentials.

Built with Kotlin, Jetpack Compose, Room with FTS4, WorkManager and Glance. Legacy M4A/JSON note pairs in the thoughts directory are imported into Room during notebook startup. Application source is licensed under MIT. Dependencies retain their own licenses.
