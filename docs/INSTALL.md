# Install StylishSAT

1. Open [the latest release](https://github.com/4wl2d/stylishSAT/releases/latest).
2. Download `stylishsat-VERSION.apk` to an Android 10+ ARM64 or x86_64 device.
3. Open the APK and allow installation from your browser/file manager when Android asks.
4. Choose SAT or IELTS Academic; set language, target, exam date and daily time in Settings.
5. Start the diagnostic or browse lessons. Bundled practice, explanations and audio work without AI models.

The universal APK contains both architectures. The source ZIP is for developers and cannot be installed as an Android app.

## Optional local AI

Review model sources/terms in Settings, then download Gemma 4 E2B IT (~2.59 GB) and Whisper base.en (~148 MB). Downloads need internet and extra temporary storage. After installation, inference/transcription runs locally. Eligibility requires ARM64, the 8 GB RAM class and enough free memory; speed varies. First GPU preparation can take substantially longer than later runs. Models are optional and not included in the APK.

Speaking also supports manual transcripts. Microphone permission is needed only for recording. Text feedback does not assess pronunciation.

## Updates and existing drafts

Install a newer official release over the previous official release to retain data. Official releases use the same signing certificate and increasing versionCode.

Older local debug/draft APKs use another key and cannot be updated in place with this release. Do not uninstall a draft containing data you need: uninstalling deletes progress, drafts, recordings and models, and there is no complete backup/export feature. Keep it and install the public release on another device or Android user profile.

## Verify a download

Download SHA256SUMS beside the APK. Run `shasum -a 256 -c SHA256SUMS` on macOS or `sha256sum -c SHA256SUMS` on Linux.

SDK users can run `apksigner verify --verbose --print-certs stylishsat-VERSION.apk` and compare the certificate digest with [release validation](RELEASE_VALIDATION.md).
