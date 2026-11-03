#!/usr/bin/env python3
"""Publish only tested packages; keep incomplete uploads hidden in a draft."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile
from upstream import ROOT, REPO, gh_json, official_commit, select, version


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--version', required=True)
    parser.add_argument('--commit', required=True)
    parser.add_argument('--source', required=True)
    parser.add_argument('--run-url', required=True)
    parser.add_argument('--directory', required=True)
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()
    version(args.version)
    if not all(re.fullmatch(r'[a-f0-9]{40}', v) for v in [args.commit, args.source]):
        raise ValueError('Expected immutable source commits')
    contract = json.loads((ROOT / 'buildScript/dynamic/compatibility.json').read_text())
    output = Path(args.directory)
    assets = [output / f'nekobox-core-{abi}.zip' for abi in ['arm64-v8a', 'x86_64']]
    for path, abi in zip(assets, ['arm64-v8a', 'x86_64']):
        with zipfile.ZipFile(path) as bundle:
            info = json.loads(bundle.read('core.json'))
            if (info['version'] != args.version + '-dynamic.1' or info['abi'] != abi
                    or info['binding_sha256'] != contract['binding_sha256']
                    or hashlib.sha256(bundle.read('libgojni.so')).hexdigest() != info['sha256']):
                raise ValueError('Publication package does not match the tested candidate')
    metadata = output / 'upstream.json'
    metadata.write_text(json.dumps(dict(version=args.version, upstream_commit=args.commit,
                                       app_source_commit=args.source, binding_sha256=contract['binding_sha256'],
                                       tests=args.run_url), indent=2) + '\n')
    assets.append(metadata)
    checksums = output / 'SHA256SUMS.txt'
    checksums.write_text(''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in assets))
    assets.append(checksums)
    notes = output / 'release-notes.md'
    notes.write_text(f'''自动跟进官方 sing-box **{args.version}**，包含 NekoBox 兼容补丁。

在已安装的 NekoBox Dynamic 中打开“关于 → 内核更新与回退”，断开代理后下载更新并重启。
arm64 手机使用 `nekobox-core-arm64-v8a.zip`；此发布只更新内核，无需重装 App。

发布前已通过：固定 Java 绑定指纹校验、Go / Android 单元测试、旧 APK 导入和加载候选内核、
CI 模拟器中的配置生成器兼容检查、SOCKS 数据转发、切换及回退测试。
这不代表所有真实节点或 arm64 真机 VPN 已验收；当前 App 仍为预览版。

[验证记录]({args.run_url}) · [构建源码](https://github.com/{REPO}/tree/{args.source})

官方源码：[`v{args.version}`](https://github.com/SagerNet/sing-box/tree/{args.commit})。
源码提交、绑定指纹和文件校验值随附 `upstream.json` / `SHA256SUMS.txt`。
''', encoding='utf-8')
    if args.dry_run:
        print('Publication dry run passed; metadata and checksums prepared, no release created')
        return
    latest = gh_json('repos/SagerNet/sing-box/releases/latest')
    releases = [r for page in gh_json(f'repos/{REPO}/releases?per_page=100', True) for r in page]
    decision = select(latest, releases, contract['baseline_version'])
    if not decision['publish'] or decision['version'] != args.version or official_commit(decision['tag']) != args.commit:
        raise ValueError('Candidate is no longer the newest unpublished official stable release')
    tag = f'dynamic-core-{args.version}-auto.1'
    if any(r.get('tag_name') == tag for r in releases):
        raise ValueError('Release already exists, possibly as an incomplete draft; inspect it without overwriting')
    subprocess.run(['gh', 'release', 'create', tag, *map(str, assets), '-R', REPO,
                    '--target', args.source, '--title', f'sing-box {args.version} · 自动内核更新',
                    '--notes-file', str(notes), '--draft', '--prerelease'], check=True)
    subprocess.run(['gh', 'release', 'edit', tag, '-R', REPO, '--draft=false'], check=True)


if __name__ == '__main__':
    main()
