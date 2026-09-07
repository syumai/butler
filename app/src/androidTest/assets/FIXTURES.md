# Fixture provenance

These `.pcm` fixtures (16 kHz mono PCM16 little-endian raw audio) are synthetic speech generated with the OpenAI TTS API (`POST /v1/audio/speech`) via `scripts/generate-fixtures.py`.

- Generator: `scripts/generate-fixtures.py`
- Model: `gpt-4o-mini-tts`
- Voice: `alloy`
- Generated: 2026-09-07

| Fixture | Text |
| --- | --- |
| `hello-butler.pcm` | Hello Butler |
| `hello-computer.pcm` | Hello Computer |
| `hello-world.pcm` | Hello World |
| `hey-butler.pcm` | Hey Butler |
| `ordinary-speech.pcm` | Clouds are rolling in, so bring an umbrella this afternoon. |
