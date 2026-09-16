# Third-party notices

Butler bundles or links against the following third-party components.

| Component | License | Source |
| --- | --- | --- |
| vosk-android 0.3.75 | Apache-2.0 | https://github.com/alphacep/vosk-api (see [third_party/vosk](vosk/)) |
| vosk-model-small-ja-0.22 | Apache-2.0 | https://alphacephei.com/vosk/models |
| JNA 5.18.1 | Apache-2.0 / LGPL-2.1 (used under Apache-2.0) | https://github.com/java-native-access/jna |
| Julius 4.6 (`libjulius-bin.so`, default wake engine) | BSD-3-Clause | https://github.com/julius-speech/julius (see [third_party/julius](julius/)) |
| Julius Dictation Kit acoustic model | see [third_party/julius](julius/) | https://github.com/julius-speech/dictation-kit |
| WebRTC (`io.github.webrtc-sdk:android:150.7871.01`) | BSD-3-Clause | https://webrtc.org |
| OkHttp 4.12.0 | Apache-2.0 | https://github.com/square/okhttp |
| Material Icons (`ic_arrow_back.xml`, `ic_settings.xml`) | Apache-2.0 | https://github.com/google/material-design-icons |
| Open-Meteo weather data | CC BY 4.0 | https://open-meteo.com (attribution shown in the UI) |

See [third_party/vosk](vosk/) for vosk-android's, JNA's, and the Vosk Japanese model's detailed provenance, hashes, and license files, and [third_party/julius](julius/) for the same for Julius and the Julius Dictation Kit acoustic model (the default wake engine — see Settings -> Wake). `app/src/main/res/values/strings.xml`'s `settings_licenses_body` (and the `values-ja` equivalent), shown in-app under "Third-party notices" / "配布物と出典", summarizes this same list for end users.
