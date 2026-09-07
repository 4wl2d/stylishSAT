# Release validation

The 0.3.0 release uses a non-debuggable Android release variant and a dedicated RSA-4096 signing certificate. The package script builds an isolated export of the exact Git commit, verifies the APK and emits SHA256SUMS plus provenance.json. Downloaded model weights and private local files are absent from that export.

## Signing identity

Official release certificate SHA-256:

```text
c51f1787d42609ce87844064db0dfd2fd4f7de21911b57510e841c8295e1f625
```

Verify with Android SDK `apksigner verify --verbose --print-certs APK`. The private key is never committed, included in artifacts or uploaded to CI.

## Automated checks

- 92 release-variant JVM tests passed, with zero failures/errors/skips during preparation.
- Android Lint: zero errors, 36 warnings and one informational hint. Dependency/style recommendations remain; the release does not claim a warning-free codebase.
- Content validation: 810 exercises, 48 lessons and 30 audio files passed structure, checksum, split and historical-version checks.
- Gitleaks scanned the public export. The only exclusions are the `keyPrefixSha256` field in two immutable prompt regression fixtures; these are content digests rather than credentials.
- GitHub CI independently builds from the public checkout. Its status and reports are available in [Actions](https://github.com/4wl2d/stylishSAT/actions/workflows/ci.yml).

Exact final APK SHA-256, source commit and installation/device results are recorded with [the release assets and notes](https://github.com/4wl2d/stylishSAT/releases/tag/v0.3.0). Intermediate local APKs are not release artifacts.

## Evidence boundaries

Bundled material is machine-validated draft content. Independent editorial review, expert AI-quality acceptance and the student pilot remain pending. Native AI timing depends on model/device state and has not been rebenchmarked for this publication. Older development benchmarks are not represented as measurements of the new release APK. 32-bit-only Android systems are unsupported.
