#!/usr/bin/env python3
"""Validate the public Git tree and app/release version contract (stdlib only)."""
from pathlib import Path, PurePosixPath
import os
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]


def git(*args):
    return subprocess.check_output(['git', '-C', str(ROOT), *args], text=True).strip()


def version(text):
    values = dict(line.split('=', 1) for line in text.splitlines() if line and not line.startswith('#'))
    name, code = values['versionName'], int(values['versionCode'])
    if not re.fullmatch(r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-(alpha|beta|rc)\.[1-9]\d*)?', name):
        raise ValueError('versionName must be MAJOR.MINOR.PATCH or an alpha/beta/rc.N')
    if not 1 <= code <= 2_100_000_000:
        raise ValueError('versionCode is outside Android limits')
    return name, code


def main():
    name, code = version((ROOT / 'version.properties').read_text())
    errors = []
    paths = git('ls-files', '-z').split('\0')
    paths = [p for p in paths if p]
    if not paths:
        raise ValueError('No tracked files; stage the intended public tree first')
    forbidden_parts = {'.idea', '.gradle', '.kotlin', '.local', '.local-models', 'build', '.cxx',
                       '.externalNativeBuild', '__pycache__', '.venv', 'outputs', 'artifacts'}
    forbidden_suffixes = {'.apk', '.aab', '.jks', '.keystore', '.p12', '.pem', '.log', '.csv', '.litertlm'}
    total = 0
    for relative in paths:
        p = ROOT / relative
        parts = PurePosixPath(relative).parts
        if forbidden_parts.intersection(parts) or p.suffix in forbidden_suffixes or p.name in {'local.properties', 'keystore.properties'} or p.name.startswith('.env'):
            errors.append('Private/generated file tracked: ' + relative)
        if p.is_symlink() or not p.is_file():
            errors.append('Tracked path must be a regular file: ' + relative)
            continue
        total += p.stat().st_size
        if p.stat().st_size > 10 * 1024 * 1024:
            errors.append('File exceeds 10 MiB public-source budget: ' + relative)
        if p.suffix in {'.md', '.kt', '.kts', '.py', '.json', '.yml', '.yaml', '.xml', '.properties'}:
            text = p.read_text()
            if re.search(r'/(?:Users|home)/[A-Za-z0-9_.-]+/', text):
                errors.append('Machine-specific home path: ' + relative)
            if p.suffix == '.md':
                for target in re.findall(r'\]\(([^)]+)\)', text) + re.findall(r'(?:src|href)="([^"]+)"', text):
                    target = target.split('#', 1)[0]
                    if not target or '://' in target or target.startswith('mailto:'):
                        continue
                    resolved = (p.parent / target).resolve()
                    try:
                        linked = resolved.relative_to(ROOT).as_posix()
                    except ValueError:
                        errors.append('Link escapes repository: ' + relative)
                        continue
                    if linked not in paths and not resolved.is_dir():
                        errors.append(f'Link to untracked/missing file: {relative} -> {target}')
    if not re.search(r'^## ' + re.escape(name) + r' — \d{4}-\d{2}-\d{2}$', (ROOT / 'CHANGELOG.md').read_text(), re.M):
        errors.append('Changelog lacks the exact release version/date heading')
    ref = os.environ.get('GITHUB_REF', '')
    if ref.startswith('refs/tags/') and ref != 'refs/tags/v' + name:
        errors.append('Tag does not match version.properties')
    if ref.startswith('refs/tags/') and git('cat-file', '-t', ref) != 'tag':
        errors.append('Release tags must be annotated')
    for tag in git('tag', '--list', 'v*').splitlines():
        if git('rev-list', '-n', '1', tag) == git('rev-parse', 'HEAD'):
            if tag != 'v' + name and ref.startswith('refs/tags/'):
                errors.append('HEAD has a different release tag: ' + tag)
            continue
        try:
            previous_name, previous_code = version(git('show', tag + ':version.properties'))
        except (subprocess.CalledProcessError, ValueError, KeyError):
            errors.append('Cannot validate prior release version: ' + tag)
            continue
        strict = ref.startswith('refs/tags/') or '--release' in sys.argv
        if previous_code > code or (strict and (previous_name == name or previous_code == code)):
            errors.append('Version/name must advance beyond ' + tag)
    if errors:
        print('\n'.join(errors), file=sys.stderr)
        return 1
    print(f'PASS: {len(paths)} public files, {total / 1024**2:.1f} MiB; version {name}, code {code}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
