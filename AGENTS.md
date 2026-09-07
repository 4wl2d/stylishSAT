# Working on StylishSAT

Read README.md and docs/DEVELOPMENT.md first. Inspect current source, keep changes scoped and preserve user data and regression tests.

## Product contracts

- Room and deterministic domain logic own answers, progress and scheduling. AI feedback must never rewrite keys, grades or mastery.
- Keep SAT and IELTS histories isolated. Preserve exact exercise versions, drafts, audio references and assessment-family boundaries across updates.
- Compose input owns live editing. Do not let delayed persistence overwrite newer input.
- Never invent expert review, student results, benchmark measurements or device support. A build pass is not educational acceptance.
- Keep optional models out of Git/APKs. Preserve immutable model revisions, sizes, checksums and upstream terms.

## Version and release rules

- Follow docs/VERSIONING.md. `version.properties` is the only app-version source; never hardcode a second version in Gradle.
- Every distributed APK needs a strictly increasing versionCode, including prereleases. Never reuse a published version, replace an asset or move a tag.
- Add user-visible changes under Unreleased in CHANGELOG.md. Bump app versions only for a release or when explicitly requested.
- Content package, exercise and Room schema versions are independent. Changed published content requires new versions; database changes need preserving migrations and tests.
- A release requires passing CI, a signed non-debuggable release APK, signature/checksum verification and release installation, UI and persistence smoke testing on a supported Android target (identify emulator versus physical hardware). Publish only when authorized by the task.
- Never commit signing credentials, private device identifiers, learner data, model weights, APKs, raw logs or generated authoring workspaces. Inspect the staged diff and run tools/release/check_repository.py before pushing.
- Preserve ignored local work. Do not delete files merely to make the public tree smaller.

## Required checks

Create build/, run `python3 tools/release/check_repository.py`, `python3 tools/content/validate_bank.py --report build/content-validation.json`, and `./gradlew :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease` for release work. Keep existing assertions and immutable fixtures. Runtime instrumentation requires model setup; run deterministic data tests separately. Report exact variant/device and evidence limits.
