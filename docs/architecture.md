# Architecture proposal

The following is a design proposal made before implementation. Confirmed user requirements are documented in the README.

## Standalone Android configuration

Built as a native Kotlin app, separating the home screen, a foreground service handling the microphone, conversation state management, voice communication, tool execution, and settings storage.

Voice conversation uses a WebRTC connection to the OpenAI Realtime API as the primary candidate. The API model ID is configurable; availability and Japanese-language quality will be verified with a real account before deciding on the default. The GPT-Live introduction is used as a reference for the experience, but the overall experience described in that article should not be equated with the features provided by any individual API.

Instead of the usual backend-mediated authentication setup, the app accepts the user's own API key in its settings and connects to the API directly from the device. The key is not embedded in the APK. Credentials are encrypted with an Android Keystore key and excluded from backups and logs. This approach does not guarantee key protection if the device itself is compromised. This is a design decision driven by the standalone operation requirement, and differs from OpenAI's recommended backend-based configuration.

## Voice and state management

The base states are `Stopped / Waiting / Connecting / Listening / Responding / ExecutingTool / AwaitingApproval / Recovering`. Because voice I/O and tools can overlap, speech, playback, response, and running tools are tracked separately from the state display.

1. Once the user grants permission and starts the app, a microphone foreground service monitors for the wake word.
2. Upon detecting the selected wake phrase, the connection process begins. The microphone is handed off carefully so the wake word detector and WebRTC recording do not contend for it.
3. Connection completion is indicated visually and with a short sound, and the conversation proceeds in Japanese. Any speech dropped during connection and the buffering approach for leading audio will be validated on the device.
4. The end-of-conversation timer is cleared when speech begins. The conversation is not ended midway through an AI response or tool processing.
5. The silence timer starts only after response audio playback finishes. The generation-finished event and playback-finished event are treated separately.
6. On timeout, an end button press, or an end-of-conversation utterance, the connection is closed, audio/history/tool results are discarded, and the app returns to wake word monitoring.
7. On disconnection, retries are limited. Audio or tool results from a stale session must not leak into a new session.

On user interruption, playback stops and the server-side response cancellation and conversation context are synchronized. Whether the app reacts to its own speaker output, the effectiveness of AEC, and the audio input source will all be verified on the actual device.

Separate from the conversation-end timer, there are upper time limits on connection and tool execution. An approval-pending state has a cancel option and an expiry, and execution never proceeds without approval.

On Android 11, a foreground service started from the background has microphone-usage restrictions. After reboot, the app is a candidate to launch as the default HOME app, and the service is started from a visible Activity. BOOT_COMPLETED alone does not guarantee continuous recording, and automatic recovery from a force-stop is not guaranteed either.

## Wake word

Picovoice was rejected because its registration requirements do not fit. sherpa-onnx 1.12.14 and the public GigaSpeech 3.3M KWS model are bundled into the APK. The wake phrase is currently fixed to `Hey Butler`; the selection UI has been removed for now (`Settings.wakePhrase` returns it unconditionally), but the `WakePhrase` enum and the other phrase asset files (`Hello Computer`, `Hello Butler`, `Hello World`) are kept because the on-device instrumentation still exercises all four. No account, AccessKey, or additional model creation/download is required. The model runs on the device CPU. There is no fallback to cloud detection or an authentication server. The AAR and onnx model are not tracked in Git; Gradle automatically runs `scripts/fetch-deps.sh` to fetch and verify them (see [third_party/sherpa-onnx](../third_party/sherpa-onnx/README.md)).

`WakeDecoder` performs only keyword detection from audio samples and has no dependency on the network or credentials. `LocalWakeWordEngine` performs 16kHz microphone capture and inference on a dedicated thread, and on stop it releases the microphone and audio stream before invoking the callback. Only the model weights are kept and reused within the service, and released when the service ends. The service starts WebRTC only after that release completes. Audio used for detection is neither retained nor sent.

The detection threshold is configurable. The English keyword and Japanese conversation are handled separately, and detection quality of "Hello World" by Japanese speakers will be measured on the device. The old Picovoice key and custom model are removed when settings are reset.

## Tools and MCP

`ToolRegistry` registers the schema, execution logic, approval policy, and cancel/expiry for each tool.

- Web search: a proposed approach calls Responses API web search from a Realtime function tool. Only the necessary query is passed, and the results and citations are returned into the voice conversation. Links are shown on screen. The exact request schema will be validated during implementation.
- Weather: the home display and conversation use the same weather-fetching layer. Region, coordinates, time zone, and fetch timestamp are managed, and the last fetch time is shown when offline. The provider is not yet selected.
- Remote MCP: a public HTTPS endpoint can be connected via Realtime MCP tools. The app handles authentication, allowed tools, approval events, and generating follow-up responses.
- LAN MCP: it is not assumed that OpenAI can reach the LAN directly. An extension path is planned where an Android-side MCP client connects and relays it as a function tool.
- stdio MCP: launching external commands on Android is out of scope initially. Only HTTP-based MCP connections are targeted.

Read operations are distinguished from operations that make external changes, and the content of change operations is shown on screen for confirmation. Tool results are treated as external data, and instructions embedded within a result must not override settings or permissions. Limits are placed on call count, response size, and execution time.

## Home screen and settings

The home screen shows an image background suited to a landscape display, a dark overlay to preserve readability, a large clock, the date, and the weather. During a voice conversation, state, captions, and an end button are overlaid. The background image can be chosen from the device, and a nighttime brightness adjustment is also a candidate setting.

Background rendering has three levels of precedence: the weather scene (when the "weather-linked background" setting is on and the current scene is known) takes priority over the imported photo, which in turn takes priority over the original code-drawn illustration. `WeatherScene` maps the same Open-Meteo weather code and day/night flag already used for the weather text into a coarse scene (clear/partly cloudy/cloudy/fog/rain/snow/thunder), and it is recomputed on each periodic weather refresh. All scenes are code-drawn and static — no animation, and geometry is only recomputed when the scene or view size changes — reusing the illustration's ridge silhouettes with scene-specific palettes and simple deterministic elements (stars, clouds, rain streaks, snow, lightning).

Configurable items:

- OpenAI API key, voice model, and voice.
- Number of seconds of silence before ending the conversation. 30 seconds is a provisional default, not a confirmed user value.
- Weather region search/selection, with direct latitude/longitude entry as an option.
- Background image, 12/24-hour clock display, and the weather-linked background toggle.
- Wake word detection threshold (the model itself is bundled).
- MCP server URL, authentication, tools to use, and approval policy.
- Starting/stopping microphone standby.

Settings and the weather cache may be persisted, but recordings and conversation history are not saved. Not saving in-app history is a separate matter from the API provider's own data retention.

Settings live in a dedicated `SettingsActivity` (started from the home screen's 設定 button) rather than a dialog, laid out as a two-pane, Android-Settings-style screen sized for the landscape 480px-tall device: a fixed-width category list on the left and a scrollable row list on the right. Categories: 会話 (OpenAI API key, voice/search model, silence timeout), 呼びかけ (standby toggle, the fixed wake phrase display, detection threshold), 天気と背景 (region, latitude/longitude, weather-linked background toggle, background image picker/reset), 連携 (MCP server URL and bearer token), and 情報 (app name/version, third-party notices). Every row change is saved immediately (no global save button); text/number rows open a compact single-EditText dialog, switches toggle inline. Entering the screen stops the assistant service, and a change in a category that affects it (会話/呼びかけ/連携) restarts or stops it right away — the same start-if-enabled/stop-otherwise behavior the old dialog applied on save. Background/weather changes set an in-memory `Settings.dirty` flag that `MainActivity` checks in `onResume` to rebuild the home screen.

## Implementation order

1. Verify device ABI, audio path, permissions, and continuous recording on the device.
2. Implement the Android project, settings, clock screen, and manually triggered voice conversation.
3. Implement wake word detection, microphone handoff, interruption, and the end timer.
4. Implement background image, weather, web search, and citation display.
5. Implement MCP connection, approval, cancellation, and failure recovery.
6. Validate reboot, long-duration operation, false detection, and disconnection on the device.

## References

- https://developers.openai.com/api/docs/guides/realtime
- https://developers.openai.com/api/docs/guides/realtime-webrtc
- https://developers.openai.com/api/docs/guides/realtime-mcp
- https://developer.android.com/about/versions/11/privacy/foreground-services
- https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html
