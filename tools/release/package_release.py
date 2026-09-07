#!/usr/bin/env python3
"""Build an immutable Git export and sign an APK without publishing or exposing secrets."""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import tarfile
import tempfile
from check_repository import ROOT, git, version


def run(*args, cwd=ROOT, capture=False):
    result = subprocess.run(list(map(str, args)), cwd=cwd, check=True,
                            stdout=subprocess.PIPE if capture else None, text=True)
    return result.stdout if capture else None


def main():
    if git('status', '--porcelain'):
        raise RuntimeError('Commit the intended public tree before packaging')
    run('python3', ROOT / 'tools/release/check_repository.py', '--release')
    name, code = version((ROOT / 'version.properties').read_text())
    if git('tag', '--list', 'v' + name):
        raise RuntimeError('This version is already tagged; never rebuild a published release')
    commit = git('rev-parse', 'HEAD')
    sdk = Path(os.environ['ANDROID_HOME'])
    signing_key = Path(os.environ['STYLISHSAT_KEYSTORE']).resolve()
    password_file = Path(os.environ['STYLISHSAT_STORE_PASSWORD_FILE']).resolve()
    alias = os.environ.get('STYLISHSAT_KEY_ALIAS', 'stylishsat')
    for p in (signing_key, password_file):
        if not p.is_file() or p.is_relative_to(ROOT):
            raise RuntimeError('Signing files must exist outside the repository')
        if os.name != 'nt' and p.stat().st_mode & 0o077:
            raise RuntimeError('Signing files must be readable only by their owner')
    build_tools = sdk / 'build-tools/37.0.0'
    out = ROOT / 'build/release' / name
    if out.exists():
        raise RuntimeError('Output already exists; preserve it and choose a new clean checkout')
    out.mkdir(parents=True)
    apk = out / f'stylishsat-{name}.apk'
    with tempfile.TemporaryDirectory(prefix='stylishsat-release-') as tmp:
        tmp = Path(tmp)
        archive = tmp / 'source.tar'
        run('git', 'archive', '--format=tar', f'--output={archive}', commit)
        source = tmp / 'source'
        source.mkdir()
        with tarfile.open(archive) as tar:
            tar.extractall(source, filter='data')
        (source / 'build').mkdir()
        run('python3', 'tools/content/validate_bank.py', '--report', 'build/content-validation.json', cwd=source)
        run('./gradlew', ':app:testReleaseUnitTest', ':app:lintRelease', ':app:assembleRelease',
            ':app:assembleReleaseAndroidTest', '-PtestBuildType=release', '--max-workers=2', '--console=plain', cwd=source)
        aligned = tmp / 'aligned.apk'
        unsigned = source / 'app/build/outputs/apk/release/app-release-unsigned.apk'
        run(build_tools / 'zipalign', '-P', '16', '4', unsigned, aligned)
        def sign(input_apk, output_apk):
            run(build_tools / 'apksigner', 'sign', '--ks', signing_key, '--ks-key-alias', alias,
                '--ks-pass', 'file:' + str(password_file), '--v4-signing-enabled', 'false',
                '--out', output_apk, input_apk)
        sign(aligned, apk)
        # Test APK is kept locally for release instrumentation; never publish it.
        sign(source / 'app/build/outputs/apk/androidTest/release/app-release-androidTest.apk',
             out / 'instrumentation.apk')
        validation = out / 'validation'
        validation.mkdir()
        import shutil
        shutil.copytree(source / 'app/build/test-results/testReleaseUnitTest', validation / 'unit-tests')
        shutil.copy(source / 'app/build/reports/lint-results-release.xml', validation / 'lint.xml')
        shutil.copy(source / 'build/content-validation.json', validation / 'content.json')
    signature = run(build_tools / 'apksigner', 'verify', '--verbose', '--print-certs', apk, capture=True)
    run(build_tools / 'zipalign', '-c', '-P', '16', '4', apk)
    metadata = run(build_tools / 'aapt2', 'dump', 'badging', apk, capture=True)
    if "application-debuggable" in metadata or f"versionCode='{code}'" not in metadata or f"versionName='{name}'" not in metadata:
        raise RuntimeError('APK version or debuggability mismatch')
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    (out / 'SHA256SUMS').write_text(f'{digest}  {apk.name}\n')
    (out / 'provenance.json').write_text(json.dumps(dict(versionName=name, versionCode=code,
        commit=commit, apk=apk.name, bytes=apk.stat().st_size, sha256=digest,
        signature=signature, source='git archive HEAD; no ignored or untracked build inputs'), indent=2)+'\n')
    print(f'Verified signed APK: {apk}\nSHA-256: {digest}\nSource: {commit}')


if __name__ == '__main__':
    main()
