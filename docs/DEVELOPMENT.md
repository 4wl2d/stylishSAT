# Development

## Toolchain

Use the checked-in Gradle 9.5.0 wrapper (checksum pinned), JDK 25, Android SDK platform 37.0, Build Tools 37.0.0, NDK 28.2.13676358 and CMake 3.22.1. AGP 9.3.2, Kotlin 2.2.10 and LiteRT-LM 0.16.1 are pinned. The daemon configuration can download JDK 25; CI installs it explicitly.

Set ANDROID_HOME or an ignored local.properties with the SDK path. First builds download dependencies and the pinned, checksum-verified whisper.cpp source. No model or credential is needed for core checks.

```sh
git clone git@github.com:4wl2d/stylishSAT.git
cd stylishSAT
mkdir -p build
python3 tools/release/check_repository.py
python3 tools/content/validate_bank.py --report build/content-validation.json
./gradlew :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

Release output is unsigned until the maintainer signs it. Debug APKs use another certificate; do not install them over official releases.

On a dedicated test device, install matching app/test APKs and run:

```sh
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL shell am instrument -w -e package com.tomilov.stylishsat.data com.tomilov.stylishsat.test/androidx.test.runner.AndroidJUnitRunner
```

Runtime tests require explicit setup/models and are separate from deterministic data tests. For release instrumentation build `:app:assembleReleaseAndroidTest -PtestBuildType=release`, sign both APKs with the same release key, and install on a dedicated device. Never clear/uninstall a user's app for test setup.

## Source map

| Path under app/src/main | Responsibility |
| --- | --- |
| java/.../domain | Content contracts, exact marking, adaptive/course planning |
| java/.../data | Room history, imports, FTS retrieval, preferences |
| java/.../StudyViewModel.kt | Ordered persistence, sessions, drafts |
| java/.../ui | Compose screens and bilingual presentation |
| java/.../ai | Verified downloads, bounded prompts, local tutor |
| java/.../speech and cpp | Recording, playback, whisper.cpp JNI |
| assets/content and assets/audio | Versioned bank and media |

Room owns persistent truth; AI supplies feedback. Historical test fixtures intentionally stay in Git. Local authoring masters, experimental reports, models and outputs are ignored. A public checkout contains all inputs required by the commands above.
