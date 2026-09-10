# Third-party notices

Butler bundles or links against the following third-party components.

| Component | License | Source |
| --- | --- | --- |
| sherpa-onnx 1.12.14 | Apache-2.0 | https://github.com/k2-fsa/sherpa-onnx (see [third_party/sherpa-onnx](sherpa-onnx/)) |
| GigaSpeech KWS model (sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01) | Apache-2.0 | https://www.modelscope.cn/models/pkufool/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01 |
| ONNX Runtime | MIT | https://github.com/microsoft/onnxruntime |
| vosk-android 0.3.75 | Apache-2.0 | https://github.com/alphacep/vosk-api (see [third_party/vosk](vosk/)) |
| vosk-model-small-ja-0.22 | Apache-2.0 | https://alphacephei.com/vosk/models |
| JNA 5.18.1 | Apache-2.0 / LGPL-2.1 (used under Apache-2.0) | https://github.com/java-native-access/jna |
| WebRTC (`io.github.webrtc-sdk:android:150.7871.01`) | BSD-3-Clause | https://webrtc.org |
| OkHttp 4.12.0 | Apache-2.0 | https://github.com/square/okhttp |
| Material Icons (`ic_arrow_back.xml`, `ic_settings.xml`) | Apache-2.0 | https://github.com/google/material-design-icons |
| Open-Meteo weather data | CC BY 4.0 | https://open-meteo.com (attribution shown in the UI) |

See [third_party/sherpa-onnx](sherpa-onnx/) for sherpa-onnx's and the KWS model's detailed provenance, hashes, and license files (`sherpa-onnx`'s own LICENSE and the bundled ONNXRUNTIME-LICENSE). See [third_party/vosk](vosk/) for vosk-android's, JNA's, and the Vosk Japanese model's detailed provenance, hashes, and license files. `app/src/main/res/values/strings.xml`'s `settings_licenses_body` (and the `values-ja` equivalent), shown in-app under "Third-party notices" / "配布物と出典", summarizes this same list for end users.
