# Bundled wake-word inference

- Library: sherpa-onnx **1.12.14**, Apache-2.0 (see LICENSE).
- Library source: https://github.com/k2-fsa/sherpa-onnx/tree/v1.12.14
- AAR: https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.14/sherpa-onnx-1.12.14.aar
- Model: **sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01**, Apache-2.0 (upstream model card in MODEL.md).
- Archive: https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2
- Original model repository: https://www.modelscope.cn/models/pkufool/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01
- Documentation: https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html

We use the int8 encoder and joiner, fp32 decoder, and original tokens.txt. Asset filenames are shortened to encoder.onnx, decoder.onnx and joiner.onnx. keywords.txt selects upstream HELLO WORLD. hello-computer.txt, hey-butler.txt and hello-butler.txt use the bundled model’s SentencePiece tokenizer to encode HELLO COMPUTER, HEY BUTLER and HELLO BUTLER. All files add result labels. Only the selected phrase file is loaded. No runtime download or license-server connection is used. Gradle packages only armeabi-v7a.

Note: the `-mobile` variant of this archive was evaluated as a way to shrink the model further, but its encoder is exported for a fixed batch size of 1 (per its notes.md) and fails at inference time on sherpa-onnx 1.12.14's `KeywordSpotter` (`onnxruntime` Reshape error on `/downsample/Reshape_1`, shape `{17,1,128}` vs requested `{8,2,1,128}`). We use the regular (non-mobile) archive.

The AAR and the three `.onnx` model files are **not** checked into git (the AAR is ~40MB, the models ~6MB). `scripts/fetch-deps.sh` downloads them from the upstream releases above into `.tools/sherpa-onnx/` (gitignored), verifies their sha256, and installs them to `app/libs/sherpa-onnx-1.12.14.aar` and `app/src/main/assets/wake/*.onnx`. Gradle runs this script automatically (see the `fetchDeps` task in `app/build.gradle.kts`) whenever any of those files is missing; it can also be run manually. `tokens.txt` and the keyword/phrase `.txt` files are small and stay tracked in git, though the script also extracts and re-verifies `tokens.txt`.

`third_party/sherpa-onnx/SHA256SUMS` is the source of truth for verification. Run `shasum -a 256 -c third_party/sherpa-onnx/SHA256SUMS` from the repository root to check the full distribution (the fetch script does this too). Upgrade the AAR and model together only after rerunning on-device inference tests.

The AAR also includes ONNX Runtime (MIT). Its license is included as ONNXRUNTIME-LICENSE. Upstream: https://github.com/microsoft/onnxruntime
