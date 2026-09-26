# Changelog

Versions follow the [version policy](docs/VERSIONING.md).

## Unreleased

- Redesigned interface: paper-and-ink look with one highlighter accent, a system-following dark theme and a drawn icon set.
- First-run setup chooses language, exam and daily minutes, then goes straight to the diagnostic.
- Today leads with a single next step (continue, diagnostic, route day or intensive block), plus a week strip, streak, answer time against the daily goal and the course route as a tappable grid.
- Sessions show one progress segment per step coloured by its saved result, mark the key and your choice in place, keep actions in a bottom bar above the keyboard, add haptic feedback and offer another round from the summary.
- Library opens skills and lessons as pages; Progress adds a 17-week activity map; Settings adds an exam-date picker and daily-minute presets and checks model files off the main thread.
- Content status, privacy and grading notes are consolidated in Settings → About instead of repeating on every screen.

## 0.3.1 — 2026-09-07

First installable GitHub Release, including all functionality in the 0.3.0 source snapshot.

- Provision pinned Android SDK tools on clean GitHub runners.
- Restore and verify the remote annotated tag after Actions checkout, which can flatten the triggering tag into a local commit reference.
- Publish a signed APK, SHA256SUMS and source/signature provenance from the verified release commit.

## 0.3.0 — 2026-09-07

First public source snapshot, following local 0.2.5-draft builds. The published tag is preserved; its APK release was superseded by 0.3.1 during CI verification.

- Offline SAT and IELTS Academic practice: 810 exercises, 48 bilingual lessons and 30 bundled audio files.
- Diagnostics, adaptive practice, 28-day courses, spaced review and 2/4/6-hour intensive plans.
- Saved drafts, course continuation, active-time limits, counted hints and separate exam histories.
- Writing charts and prepared feedback; Speaking recording, playback and editable transcripts.
- Optional verified Gemma 4 E2B IT and Whisper base.en downloads for local feedback and transcription on eligible devices.
- Manual ChatGPT prompt preview/copy/share, with returned feedback separate from grading.
- Signed release APK, checksums, MIT license, public documentation, CI and agent release rules.

The authored bank remains machine-validated draft content. Independent editorial review, expert AI-quality acceptance and the student pilot are pending. No calibrated SAT scores, IELTS bands or pronunciation assessment are provided.
