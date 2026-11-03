#!/usr/bin/env python3
"""Build-only AAR repackaging and signed update bundles. Never writes private keys."""
import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import zipfile
from datetime import datetime, timezone


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--aar', required=True)
    parser.add_argument('--seq', required=True)
    parser.add_argument('--android-jar', required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.aar) as aar:
        entries = {name: aar.read(name) for name in aar.namelist() if not name.endswith('/')}
    original = entries['classes.jar']
    with zipfile.ZipFile(io.BytesIO(original)) as jar:
        classes = {name: jar.read(name) for name in jar.namelist() if not name.endswith('/')}
    # Include names as well as bytes; zip timestamps are not part of the binding contract.
    binding = hashlib.sha256()
    for name, data in sorted(classes.items()):
        if name.endswith('.class'):
            binding.update(name.encode() + b'\0' + data)
    binding_hash = binding.hexdigest()
    contract = json.loads((root / 'buildScript/dynamic/compatibility.json').read_text())
    if binding_hash != contract['binding_sha256']:
        raise RuntimeError('Java binding changed: stop automatic publication; an App upgrade is required')
    staging = output / 'java'
    staging.mkdir(exist_ok=True)
    (staging / 'original.jar').write_bytes(original)
    source = Path(args.seq).read_text()
    old = 'System.loadLibrary("gojni");'
    if source.count(old) != 1:
        raise RuntimeError('Unexpected gomobile loader; refusing to patch')
    source = source.replace(old, '''try {
            Class.forName("moe.matsuri.nb4a.core.CoreLoader").getMethod("load").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }''')
    (staging / 'Seq.java').write_text(source)
    subprocess.run(['javac', '-source', '8', '-target', '8', '-classpath',
                    os.pathsep.join([str(staging / 'original.jar'), args.android_jar]),
                    '-d', str(staging / 'classes'), str(staging / 'Seq.java')], check=True)
    for path in (staging / 'classes').rglob('*.class'):
        classes[path.relative_to(staging / 'classes').as_posix()] = path.read_bytes()
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as jar:
        for name, data in classes.items():
            jar.writestr(name, data)
    entries['classes.jar'] = buffer.getvalue()
    assets = root / 'app/src/main/assets/dynamic-core'
    assets.mkdir(parents=True, exist_ok=True)
    (assets / 'binding.txt').write_text(binding_hash)
    (assets / 'builtin-version.txt').write_text(args.version)
    # Key is injected by CI as an environment variable and kept only in process memory.
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding
    key = serialization.load_der_private_key(base64.b64decode(os.environ['DYNAMIC_CORE_SIGNING_KEY']), None)
    public = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    expected = base64.b64decode((assets / 'public-key.txt').read_text())
    if public != expected:
        raise RuntimeError('Signing key does not match the App trust anchor')
    from cryptography import x509
    from cryptography.x509.oid import NameOID
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'NekoBox Dynamic')])
    certificate = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
                   .public_key(key.public_key()).serial_number(1)
                   .not_valid_before(datetime(2026, 1, 1, tzinfo=timezone.utc))
                   .not_valid_after(datetime(2056, 1, 1, tzinfo=timezone.utc))
                   .sign(key, hashes.SHA256()))
    (output / 'signer.der').write_bytes(certificate.public_bytes(serialization.Encoding.DER))
    for abi in ('arm64-v8a', 'x86_64'):
        library = entries[f'jni/{abi}/libgojni.so']
        manifest = json.dumps(dict(schema=1, binding_sha256=binding_hash, abi=abi, min_sdk=23,
                                   version=args.version, sha256=digest(library)),
                              sort_keys=True, separators=(',', ':')).encode()
        signature = key.sign(manifest, padding.PKCS1v15(), hashes.SHA256())
        with zipfile.ZipFile(output / f'nekobox-core-{abi}.zip', 'w', zipfile.ZIP_DEFLATED) as bundle:
            bundle.writestr('core.json', manifest)
            bundle.writestr('core.sig', signature)
            bundle.writestr('libgojni.so', library)
    destination = root / 'app/libs/libcore.aar'
    destination.parent.mkdir(exist_ok=True)
    with zipfile.ZipFile(destination, 'w', zipfile.ZIP_DEFLATED) as aar:
        for name, data in entries.items():
            aar.writestr(name, data)
    print('Prepared dynamic loader, binding contract and signed bundles:', binding_hash)


if __name__ == '__main__':
    main()
