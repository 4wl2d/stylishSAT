# Changelog

Versions follow the [version policy](docs/VERSIONING.md).

## Unreleased

- Refresh the README for the 0.4.0 study flow, distinguish it from the published 0.3.1 APK and label the older screenshots as an archive.
- Full sections: every question for one IELTS Reading passage or Listening recording on one page, with one clock and one saved playback position. Exam conditions add a clock (90 seconds a question for Reading) and play a recording once, then give two minutes to check. Short drills remain the practice mode. Answers given together in one sitting are not counted as repeats of each other.
- Content package 5 (schema 3): five full-length Academic passages of 864–891 words with 13–14 questions each, in formats the bank lacked — True/False/Not Given, Yes/No/Not Given, matching headings, matching information and features, summary completion from a word box, sentence and table completion, and diagram labelling. The three assessment passages (40 questions) are held back from single-item timed checks.
- Seven new Task 1 visuals: line graph, pie charts, tables, a process diagram and before/after maps, drawn in the app with a values table for accessibility.
- Writing revision loop: after submitting, check your own response against a list written for that exact task (both views, your position, the overview, the main comparisons and the required figures), mark each point Yes/Partly/No, note what to change, then write and save new versions. Compare any two versions word by word with your marks side by side. Open it from the result, from Progress history or from Today while a new version is in progress. Writing still saves as "needs review"; no band is given.
- Content package 6: all 31 Writing tasks carry a 4–6 point task checklist (new exercise versions; prompts and visuals unchanged).
- Exam tab: uncalibrated papers under exam timing, separate from Today. Digital SAT: two Reading and Writing modules (27 questions, 32 minutes each) and two Math modules (22 questions, 35 minutes each) with a 10-minute break, one question per screen, skipping, mark-for-review, a review page before submitting each module, and a harder or easier second module chosen from the first module's raw result by a stated 60% practice rule. IELTS: Academic Reading in 60 minutes (the three held-back full-length passages, 40 questions), Listening with four recordings played once and 10 minutes to transfer, and Writing Task 1 in 20 minutes then Task 2 in 40. Papers use unseen items and report raw counts and time only — no scaled SAT score or IELTS band. Answers given after the clock (when you choose to keep working) are recorded as overtime and still count as independent evidence.
- SAT working tools in drills and exam modules: a bundled scientific calculator on every Math question (degrees/radians, inverse trig, logs, powers, roots, factorials, answer recall, exact-fraction display), the Math reference sheet, striking out answer choices, tap-to-highlight sentences in passages, a scratch note per question and mark for review. Struck choices, highlights and notes are saved with the draft or paper and never reach marking; marked questions join the mistake notebook.
- Mistake notebook (Progress → Mistake notebook): every wrong or skipped answer reopens the exact question version you answered, with passage or audio, your answer, the key, the explanation and evidence. Record why it went wrong in your own words, tag a cause, mark it resolved, and queue a fresh unanswered question from the same family (or a similar unseen one in the skill); queued follow-ups appear on Today. Any history row now opens its question, key and explanation. Notes never change results, keys or skill levels.
- New listening voices (content package 7): all 24 Listening recordings and six Speaking samples are re-recorded from the unchanged scripts with Piper neural voices instead of eSpeak NG. Each speaker in a conversation has their own voice, and recordings mix British and American English. Transcripts name the speaker for every line. Answers and keys are unchanged; the 246 affected exercises get new versions, and earlier versions keep their original audio. The audio is still synthetic, and the player says that human listening review is pending.
- Practice listening controls: 0.75×/1×/1.25× speed, loop, and a transcript whose start times you can tap to replay a line (or loop it). They appear in practice drills, in the mistake notebook, in practice sections and after marking (timed checks and the diagnostic keep the plain player until then). Exam conditions still play each recording once with no controls.
- The Exam tab's IELTS Listening paper follows the IELTS order (everyday conversation first, lecture last). If you have already used held-back recordings, it fills the gap with conversations first so the paper keeps two of them.
- Encrypted export and restore (Settings → Your data): save answers, drafts, revisions, plans, the mistake notebook, exam sittings, older content versions and Speaking recordings to one passphrase-encrypted file (AES-256-GCM, PBKDF2-HMAC-SHA256 with 600,000 iterations) wherever you choose. There is no account or cloud sync. Restore checks the whole file before changing anything, adds what is missing, and keeps work already on the device. Models and app settings are not included.
- The course follows the exam date. Without a date it stays 28 days. With a date, it runs from today to the day before the exam (up to 120 days planned ahead; phases stay timed to the real date) and is re-planned when you open the app on a new day or finish a course day, keeping the days you have already done. Up to three weeks out: a sprint with new work on the three weakest SAT skills or two weakest IELTS skills, harder practice in the second half and one full sitting midway. Up to eight weeks, or more: a rules-first block, mixed practice, then harder exam practice with a full sitting every two weeks. A goal and known result on the exam's own scale (for example 1350, or 6.5) set the gap. A larger gap lengthens the rules block. If the known result already meets the goal, the rules block is dropped and a full sitting is suggested every week. The gap only shapes the route; nothing predicts a score. Today explains the current phase, focus, gap and next suggested sitting, and marks sitting days on the route grid.
- Motion system: sessions rise over Today and sink back, Settings and library pages push in from the side, tabs fade through under a stretching highlighter indicator, and Settings, sessions, library pages and onboarding steps follow the predictive back gesture.
- Study feedback: answer options cascade in, the key is marked by a drawn highlighter stroke, a wrong answer shakes, a correct one sparks, the timer ticks through its last ten seconds and a finished session counts up its score with a one-time confetti burst.
- Today, Library, Progress and Settings open with short staggered entrances; numbers count up, rings and bars fill, the activity map ripples in week by week and the route grid fills as a wave. Presses spring back, buttons lean toward where they lead and the segmented controls slide.
- Settings → Motion → **Calmer motion** replaces slides with fades and turns off confetti, shakes, pulses and staggered entrances. Android's *Remove animations* setting is followed as well.
- Short answers can be checked with the keyboard's Done key, onboarding choices confirm themselves before advancing, and the loading veil only appears when loading takes noticeably long.

## 0.4.0 — 2026-09-27

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
