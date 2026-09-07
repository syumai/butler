# Fixture provenance

These `.pcm` fixtures (16 kHz mono PCM16 little-endian raw audio) are synthetic speech generated with the OpenAI TTS API (`POST /v1/audio/speech`) via `scripts/generate-fixtures.py`.

- Generator: `scripts/generate-fixtures.py`
- Model: `gpt-4o-mini-tts`
- Generated: 2026-09-08

| Fixture | Text | Voice | Instructions |
| --- | --- | --- | --- |
| `hello-butler.pcm` | Hello Butler | alloy |  |
| `hello-computer.pcm` | Hello Computer | alloy |  |
| `hello-world.pcm` | Hello World | alloy |  |
| `hey-butler.pcm` | Hey Butler | alloy |  |
| `ordinary-speech.pcm` | Clouds are rolling in, so bring an umbrella this afternoon. | alloy |  |
| `hello-butler-ja.pcm` | ハロー、バトラー | alloy |  |
| `hello-butler-ja-2.pcm` | ハロー、バトラー | nova |  |
| `ordinary-speech-ja.pcm` | 今日は夕方から雨らしいから、洗濯物は早めに取り込んでおいてね。 | alloy |  |
| `near-miss-ja.pcm` | バター取って | alloy |  |
