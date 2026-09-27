# Privacy

No account, advertising, analytics or automatic cloud sync. Responses, progress, drafts, recordings and models stay in app-private storage. System backup/device transfer are disabled for this data.

Internet permission supports optional model downloads from configured Hugging Face sources. Providers receive normal download-request metadata. The app validates pinned sizes and SHA-256 hashes. Local AI does not upload answers.

Microphone access is optional. You can enter a transcript manually. Deliberately copying/sharing a ChatGPT prompt sends its previewed contents to your chosen destination under that service's policies.

Uninstalling or clearing storage deletes learning data/models. There is no account, cloud sync or automatic backup.

## Encrypted export (unreleased; in builds after 0.4.0)

Settings → Your data → Export encrypted file writes one file to a location you choose with Android's file picker. It contains every private record (answers, drafts, writing revisions, plans and course progress, the mistake notebook, exam sittings, feedback), the older content versions your answers refer to, and your Speaking recordings. Downloaded models and app settings are not included.

The file is encrypted on the device with AES-256-GCM, using a key derived from your passphrase with PBKDF2-HMAC-SHA256 (600,000 iterations, random salt). The passphrase is not stored and cannot be recovered; without it nobody, including you, can open the file. Every 64 KB block is authenticated, so a wrong passphrase, an edited file or a cut-off file is refused before anything is restored.

The file leaves the device only if you save or move it somewhere else. If you pick a cloud folder in the file picker, that provider stores the encrypted file under its own terms. An exported file is not deleted when you uninstall the app.

Restore (Settings → Your data → Restore) checks the whole file first, then adds what is missing. Work already on the device is never overwritten; the only exception is an exam with no answers yet on this device, whose profile, plan and course progress are taken from the file. Read [installation and updates](INSTALL.md) before switching from a draft build: 0.4.0 and earlier have no export.
