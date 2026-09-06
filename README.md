# Butler

An always-listening Japanese voice AI assistant for a 32-bit ARM Android 11 (LineageOS 18.1) tablet.

A native Android app under development. It bundles a wake word model and only requires an OpenAI API key to be configured — a self-contained setup. On-device verification of the voice/API flow is not yet complete.

## Confirmed requirements

- Runs standalone as an Android app with no dedicated backend required; it connects to external services such as OpenAI, weather, and MCP.
- The wake word is currently fixed to `Hey Butler` (selection UI removed for now). English pronunciation is assumed. The app name is Butler, and the conversation language is Japanese. The other phrase files (`Hello Computer`, `Hello Butler`, `Hello World`) remain bundled and are still exercised by the on-device instrumentation tests.
- Wake word detection is on-device only. sherpa-onnx, which requires no registration, processes the standby audio locally on the device with no fallback to cloud detection. Audio is sent to OpenAI only after detection or after a manual conversation start.
- Supports natural two-way voice conversation, including interruption by the user's speech while a response is playing.
- The wake state is maintained while a conversation continues. The silence timeout before ending is configurable.
- No long-term memory by default; only the conversation context within the same session is retained.
- Supports web-search-based answers and can be configured for MCP integration.
- Can control home appliances (lights, switches, air conditioners, etc.) and query their state through a Home Assistant server on the home LAN.
- The home screen is a clock over an image background, with weather shown alongside it; the target region is configurable.
- A physical device is available and reachable via ADB.

## Design and validation

- [Architecture proposal](docs/architecture.md)
- [Device validation procedure](docs/device-validation.md)

ADB is set up and device connectivity has been confirmed. The device is Android 11 / API 30, 32-bit ARM (armeabi-v7a), 960x480.

The build uses JDK 17 or 21, and Android SDK 35 / Build Tools 35.0.0. In this working environment, the SDK and Gradle were installed under `.tools/` (not tracked in Git).

## Setup

1. Set the Android SDK location in `sdk.dir` in `local.properties`.
2. Build with `./gradlew assembleDebug testDebugUnitTest`. The sherpa-onnx AAR and the wake word model are not tracked in Git; on the first build, `./gradlew` automatically runs `scripts/fetch-deps.sh`, which fetches and verifies them from GitHub Releases (network access is required only for the first run). You can also run `scripts/fetch-deps.sh` manually.
3. Install onto the device with `adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk`.
4. Enter the OpenAI API key in the dedicated Settings screen. Do not write the key into source code or this README.
5. Turn on "呼びかけを待つ" (listen for the wake phrase) and grant microphone permission.
6. Say "Hey Butler" and, after "Please speak" is shown, talk in Japanese.

Wake word detection is entirely self-contained via the bundled sherpa-onnx model — no account, AccessKey, or runtime download is required. No Realtime connection is created while on standby, and the recording used for detection is never reused for or sent to the conversation session. WebRTC starts only after the on-device detector has released the microphone. Wake phrase detection itself works even without an OpenAI API key configured; a setup prompt is shown after detection in that case.

### Home Assistant setup

1. In Home Assistant, expose the entities you want voice control over to Assist (Settings → Voice assistants → Expose), and create a Long-Lived Access Token from your Home Assistant user profile page.
2. In the app's 設定 → 連携 screen, enter the Home Assistant URL (e.g. `http://192.168.1.10:8123`; a fixed IP is more reliable than `homeassistant.local` on this device) and the access token, then tap "Home Assistant 接続を確認" to verify connectivity.
3. Once both are set, the assistant gains a tool to operate and query devices through Home Assistant's Assist conversation API. The Home Assistant server must be reachable over the home LAN; HTTPS with a self-signed certificate is not supported — use plain HTTP on the LAN, or a certificate trusted by the system (e.g. Let's Encrypt via Nabu Casa or a reverse proxy).

## Current features and limitations

- Kotlin / native Views. Original landscape illustration background, clock, and support for importing an arbitrary image.
- A dedicated, category-based settings screen (`SettingsActivity`), styled like the Android Settings app, replacing the previous settings dialog. Categories: 会話 (conversation: API key, models, silence timeout), 呼びかけ (wake: standby toggle, wake phrase display, detection threshold), 天気と背景 (weather and background), 連携 (MCP integration), and 情報 (app info and third-party notices). Every change is saved immediately, as in Android's own Settings app.
- Wake phrase detection via sherpa-onnx 1.12.14 + the GigaSpeech 3.3M KWS model. The phrase is currently fixed to "Hey Butler" (selection UI removed for now); the detection threshold remains configurable. A short synthesized chime plays immediately on wake detection, before the conversation connects.
- WebRTC voice conversation, Japanese instructions, semantic VAD, interruption support, and a configurable silence timeout.
- Web search via the Responses API with clickable citations. In-app history is discarded when a conversation ends, and searches specify `store: false`.
- Weather display via Open-Meteo. The region name and latitude/longitude are configurable. A place-name search UI and a persistent weather cache are not yet implemented.
- Optional weather-linked home screen background ("天気に合わせて背景を変える" in Settings): when enabled, the background is replaced by a code-drawn scene matching the current outdoor weather code and day/night state (clear, partly cloudy, cloudy, fog, rain, snow, thunder), redrawn on each 15-minute weather refresh. Off by default; when off, or while the scene is unknown, the imported photo or the original illustration is shown as before.
- The weather background animates: rain and snow fall (rain in three depth layers, nearly vertical with a slight, consistent wind lean), clouds and fog drift slowly, night stars twinkle, and thunder scenes flash periodically. Animation is capped at ~25 fps and only runs while the app is visible; the static clear-day scene and the imported photo/illustration paths are unaffected. See [Architecture](docs/architecture.md) for how the render cost is kept low on the target device.
- One public HTTPS MCP server can be configured, with on-screen approval for each call. LAN-based MCP clients, OAuth flows, and managing multiple servers are not yet implemented.
- Home Assistant integration is a client-side function tool that calls a LAN Home Assistant server's `/api/conversation/process` REST endpoint directly (a Long-Lived Access Token, not the MCP server integration, so nothing needs to be exposed publicly). Cleartext HTTP is allowed for this LAN traffic; HTTPS with a self-signed certificate is not supported. Unlike the MCP tool, there is no per-call on-screen approval — the model is instructed to confirm risky actions (unlocking, high-cost or dangerous operations) verbally before calling the tool.
- The API key is encrypted with Android Keystore, and backups are disabled. This does not guarantee full key protection if the device itself is compromised.
- Targets a specific Android 11 device (targetSdk 35, armeabi-v7a). Not configured for Play Store publication.
- Registers as a HOME app candidate but does not automatically change the default HOME app. Recovery after reboot, long-duration standby, and real voice quality all still require on-device validation.

Loading the model takes about 10 seconds the first time detection starts. While the service is running, the weights are reused, and only the audio stream is discarded and recreated for each conversation.

The wake word can be verified without an API key. Voice conversation with OpenAI is verified after registering an API key. The default model ID is configurable in Settings and depends on availability per account.

## Wake word on-device testing

After `./gradlew assembleDebug assembleDebugAndroidTest`, install both APKs onto the same device.

```sh
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL shell am instrument -w dev.syumai.butler.test/dev.syumai.butler.WakeInstrumentation
```

`PASS` indicates success. `-e mode record -e seconds N` records N seconds of real microphone audio to the app's external files directory for offline analysis of how the model hears a speaker (used to tune the phrase files for Japanese pronunciation). This runner is not a normal JUnit runner; it runs via `am instrument`. It feeds the actual bundled model 16kHz PCM audio for the wake phrase, other sentences, and silence. The fixtures are synthetic TTS audio, and this does not substitute for validating pronunciation accuracy with a microphone, at a distance, or with Japanese speakers.

The provenance, license, and hashes of the distributed artifacts are documented in [third_party/sherpa-onnx](third_party/sherpa-onnx/README.md). The AAR and onnx model are not included in Git; `scripts/fetch-deps.sh` fetches and verifies them.
