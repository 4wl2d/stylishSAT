# Versioning

We use [Semantic Versioning 2.0.0](https://semver.org/) adapted to an Android application. Compatibility includes saved data, content imports and documented behavior. `version.properties` is the only app-version source.

| Change | Rule |
| --- | --- |
| Compatible bug fix or content correction | PATCH: 0.3.0 → 0.3.1 |
| New functionality | MINOR: 0.3.0 → 0.4.0 |
| Breaking change before 1.0 | MINOR, with migration notes |
| Breaking change after 1.0 | MAJOR, with migration notes |
| Release candidate | e.g. 0.4.0-rc.1; GitHub prerelease |
| Docs, CI or refactoring only | No automatic bump; record relevant changes under Unreleased |

Every distributed build, including an RC, gets a strictly larger integer versionCode (maximum 2,100,000,000). Codes are sequential and independent of SemVer. Never reuse a code, even for a rebuilt or withdrawn release. Published APKs are immutable; repairs require a new version and code.

Tags are annotated `v<versionName>` tags on the exact checked commit. Tag, Android metadata, changelog, release title and APK filename must agree. Never move/recreate published tags or overwrite release assets.

0.x releases are installable early versions with documented limitations. Version 1.0 requires explicit product acceptance including editorial review, expert AI-quality assessment and student testing. A normal 0.x release does not imply calibrated exam scoring.

Content schema/package, exercise and Room versions remain independent. Increment changed published items and packages, update checksums, preserve historical snapshots/assessment splits and provide data-preserving migrations. No major version authorizes silent data deletion.

Agents follow this policy and [AGENTS.md](../AGENTS.md). Maintainers finalize Unreleased, bump both app fields, validate and follow [RELEASING.md](RELEASING.md).
