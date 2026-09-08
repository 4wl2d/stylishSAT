# Releasing

The first installable GitHub Release is 0.3.1 (Android code 10). The earlier public v0.3.0 source tag is preserved. Read [VERSIONING.md](VERSIONING.md). Publishing a release is a maintainer operation; a normal CI run cannot access the signing key.

1. Finalize CHANGELOG.md, increment both fields in version.properties, update user documentation and run the required checks.
2. Review the exact staged tree and run `python3 tools/release/check_repository.py`. Scan a clean export with Gitleaks. Commit on a release-preparation branch, push it and open a pull request to main. Merge only after the required checks pass, then wait for the CI workflow on the resulting main commit to pass.
3. From a clean checkout of that passing main commit, fetch the remote tags before package validation using the commands below, then build and sign. Keep keystore and password files outside the repository, readable only by the maintainer. Never use a debug key or generate a replacement for an existing release key.
4. Before publishing, run `apksigner verify --verbose --print-certs APK` and manually compare the signed APK certificate SHA-256 with the official fingerprint in [RELEASE_VALIDATION.md](RELEASE_VALIDATION.md). Stop if they differ; the package script verifies the signature but does not enforce this identity comparison. Install the signed APK on a dedicated Android test device or emulator and identify the exact target. Test launch, lessons, practice, audio, saved drafts and restart. For native/AI changes, rerun the relevant optional-model suite. Record exact APK hashes and evidence boundaries in release notes; do not attribute old benchmarks to a new APK.
5. Create an annotated `v<versionName>` tag on that commit and push it. Wait for the tag CI to pass. Use `gh release create TAG APK SHA256SUMS provenance.json --verify-tag --draft --title 'StylishSAT VERSION' --notes-file NOTES` to prepare the exact assets and notes, inspect them, then publish with `gh release edit TAG --draft=false --latest`. For `-alpha.N`, `-beta.N` and `-rc.N`, add `--prerelease --latest=false` when creating the draft and publish with `gh release edit TAG --draft=false --prerelease --latest=false` instead.
6. Read back the release and tag, download the published APK, SHA256SUMS and provenance.json and verify their consistency with the tested APK and tagged source commit. Never replace published assets. Fixes use a new version/code/tag.

```sh
git fetch origin --tags
export ANDROID_HOME=/path/to/android-sdk
export STYLISHSAT_KEYSTORE=/private/path/release.p12
export STYLISHSAT_STORE_PASSWORD_FILE=/private/path/password.txt
export STYLISHSAT_KEY_ALIAS=stylishsat
python3 tools/release/package_release.py
```

The script requires a clean tracked tree, validates it, runs content checks, release tests/lint/assembly, aligns/signs the APK, verifies its signature and emits APK, SHA256SUMS and provenance.json under ignored build/release/VERSION/. It never uploads credentials or publishes anything.

Back up the original keystore and its password securely. Losing the signing key prevents compatible updates to existing installations. The first public certificate fingerprint is recorded in [RELEASE_VALIDATION.md](RELEASE_VALIDATION.md). Old debug builds have another certificate; see [INSTALL.md](INSTALL.md) before changing installations.

CI builds without signing credentials, uses pinned actions with read-only permissions, and validates tags against version.properties. The first release was produced locally with the same public source checks and uploaded by the maintainer workflow above.
