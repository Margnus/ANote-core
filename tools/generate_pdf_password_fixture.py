"""Create one immutable synthetic AES-256 revision-6 variant of the original PDF fixture.

Encryption is intentionally randomized. Existing outputs are verified, never regenerated in place.
Run with the bundled PDF Python runtime (pypdf plus cryptography).
"""
import hashlib
import json
from pathlib import Path
from pypdf import PdfReader, PdfWriter

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'android-app/src/androidTest/assets/pdf'
PASSWORD = 'notecore-fixture'  # Public synthetic credential only.


def main():
    source = ASSETS / 'crop-rotations.pdf'
    target = ASSETS / 'password-aes256.pdf'
    manifest = ASSETS / 'password-manifest.json'
    sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    if not target.exists():
        writer = PdfWriter(clone_from=source)
        writer.encrypt(PASSWORD, owner_password='notecore-test-owner', algorithm='AES-256')
        with target.open('xb') as stream:
            writer.write(stream)
    locked = PdfReader(target)
    encryption = locked.trailer['/Encrypt']
    assert (encryption['/V'], encryption['/R'], encryption['/Length']) == (5, 6, 256)
    assert encryption['/CF']['/StdCF']['/CFM'] == '/AESV3'
    assert locked.decrypt('wrong-fixture-password') == 0 and locked.decrypt(PASSWORD) != 0
    plain = PdfReader(source)
    assert len(locked.pages) == len(plain.pages) == 4
    for a, b in zip(plain.pages, locked.pages):
        assert a.get_contents().get_data() == b.get_contents().get_data()
        assert list(a.cropbox) == list(b.cropbox) and a.rotation == b.rotation
    result = {'schema': 1, 'source_sha256': sha(source), 'aes256_sha256': sha(target),
              'encryption': {'V': 5, 'R': 6, 'Length': 256, 'CFM': 'AESV3'},
              'pages': 4, 'content_streams_crop_rotation_preserved': True,
              'password': PASSWORD, 'scope': 'Public synthetic password; no user PDF or production credential.'}
    if manifest.exists():
        assert json.loads(manifest.read_text(encoding='utf-8')) == result
    else:
        manifest.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
