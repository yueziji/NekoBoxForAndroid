#!/usr/bin/env python3
"""Select official stable cores; never execute release text or move backwards."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
REPO = 'yueziji/NekoBoxForAndroid'


def gh_json(endpoint, paginate=False):
    command = ['gh', 'api', endpoint]
    if paginate:
        command += ['--paginate', '--slurp']
    return json.loads(subprocess.check_output(command, text=True, encoding='utf-8'))


def version(value):
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', value):
        raise ValueError('Expected a stable numeric version')
    return tuple(map(int, value.split('.')))


def select(latest, releases, baseline, verify_current=False):
    tag = latest.get('tag_name', '')
    if latest.get('draft') or latest.get('prerelease') or not tag.startswith('v'):
        raise ValueError('Official latest release is not stable')
    current = tag[1:]
    candidate = version(current)
    published = [version(baseline)]
    for release in releases:
        match = re.fullmatch(r'dynamic-core-([0-9]+\.[0-9]+\.[0-9]+)(?:-[A-Za-z0-9._-]+)?', release.get('tag_name', ''))
        if match and not release.get('draft') and any(a.get('name') == 'nekobox-core-arm64-v8a.zip' for a in release.get('assets', [])):
            published.append(version(match[1]))
    newer = candidate > max(published)
    return dict(version=current, tag=tag, build=newer or verify_current, publish=newer and not verify_current)


def official_commit(tag):
    version(tag.removeprefix('v'))
    obj = gh_json(f'repos/SagerNet/sing-box/git/ref/tags/{tag}')['object']
    for _ in range(4):
        if obj['type'] == 'commit':
            if not re.fullmatch(r'[a-f0-9]{40}', obj['sha']):
                break
            return obj['sha']
        if obj['type'] != 'tag':
            break
        obj = gh_json(f"repos/SagerNet/sing-box/git/tags/{obj['sha']}")['object']
    raise ValueError('Official tag does not resolve to a commit')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--verify-current', action='store_true')
    args = parser.parse_args()
    contract = json.loads((ROOT / 'buildScript/dynamic/compatibility.json').read_text())
    latest = gh_json('repos/SagerNet/sing-box/releases/latest')
    releases = [r for page in gh_json(f'repos/{REPO}/releases?per_page=100', True) for r in page]
    decision = select(latest, releases, contract['baseline_version'], args.verify_current)
    decision['commit'] = official_commit(decision['tag']) if decision['build'] else ''
    print(json.dumps(decision))
    if os.getenv('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            for key, value in decision.items():
                output.write(f'{key}={str(value).lower() if isinstance(value, bool) else value}\n')
    if os.getenv('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
            summary.write(f"Official stable: **{decision['tag']}**. Build: **{decision['build']}**. Publish: **{decision['publish']}**.\n")


if __name__ == '__main__':
    main()
