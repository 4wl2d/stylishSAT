# Third-party notices

StylishSAT code, documentation and project-authored materials are offered under [MIT](LICENSE), to the extent rights are held by the contributors. Third-party components retain their own licenses; the MIT grant does not override model terms or trademarks.

| Component | License / source |
| --- | --- |
| whisper.cpp / ggml, pinned commit 371b5a7561823ab2bb32142d2751e35e7534727b | [MIT notice](app/src/main/assets/licenses/whisper-LICENSE.txt), [source](https://github.com/ggml-org/whisper.cpp/tree/371b5a7561823ab2bb32142d2751e35e7534727b) |
| AndroidX, Jetpack Compose, Room, DataStore | [Apache-2.0](app/src/main/assets/licenses/Apache-2.0.txt), [AndroidX](https://android.googlesource.com/platform/frameworks/support/) |
| Kotlin, kotlinx.coroutines, kotlinx.serialization | Apache-2.0, [JetBrains](https://github.com/JetBrains/kotlin) |
| LiteRT-LM and LiteRT | Apache-2.0, [Google AI Edge](https://github.com/google-ai-edge/LiteRT-LM) |
| Gradle wrapper | Apache-2.0, [Gradle](https://github.com/gradle/gradle) |
| eSpeak NG 1.52.0 (authoring tool; executable not bundled) | [GPL-3.0-or-later](https://github.com/espeak-ng/espeak-ng/tree/1.52.0); synthetic output uses original project scripts |
| Gemma 4 E2B IT (optional separate download) | [Gemma terms](https://ai.google.dev/gemma/terms), [model source](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) |
| Whisper base.en (optional separate download) | [MIT](https://github.com/openai/whisper/blob/main/LICENSE), [converted model source](https://huggingface.co/ggerganov/whisper.cpp) |

No model weights are distributed in Git or the APK. The model catalog pins source revisions, sizes and checksums. Native/runtime dependencies can include additional transitive notices retained in their upstream distributions. The license texts above are also packaged in assets/licenses. See [content provenance](app/src/main/assets/licenses/content-notices.txt) and [content limitations](docs/CONTENT.md).
