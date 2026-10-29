#!/usr/bin/env python3
"""Create deterministic encoded EXIF fixtures; never modify an existing differing fixture."""
import hashlib
import io
import json
from pathlib import Path
from PIL import Image, ImageOps, ImageDraw, __version__

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT/'fixtures/image-orientation'
COLORS = [(255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 0, 255), (255, 255, 0), (0, 255, 255)]


def write_identical(path, data):
    if path.exists():
        if path.read_bytes() != data:
            raise ValueError(f'Refusing to overwrite differing fixture: {path}')
    else:
        path.write_bytes(data)


def samples(image):
    with image.convert('RGBA') as rgba:
        return [dict(x=x, y=y, rgba=list(rgba.getpixel((x, y))))
                for y in range(20, image.height, 40) for x in range(20, image.width, 40)]


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    cases = []
    for kind in ('jpeg', 'png'):
        with Image.new('RGB' if kind == 'jpeg' else 'RGBA', (80, 120)) as source:
            draw = ImageDraw.Draw(source)
            for i, color in enumerate(COLORS):
                value = color if kind == 'jpeg' else (*color, [0, 64, 128, 192, 255, 255][i])
                x, y = (i % 2)*40, (i // 2)*40
                draw.rectangle((x, y, x+39, y+39), fill=value)
            for orientation in range(1, 9):
                exif = Image.Exif(); exif[274] = orientation
                buffer = io.BytesIO()
                options = dict(quality=100, subsampling=0) if kind == 'jpeg' else {}
                source.save(buffer, format='JPEG' if kind == 'jpeg' else 'PNG', exif=exif, **options)
                encoded = buffer.getvalue(); path = OUT/f'{kind}-{orientation}.{kind}'
                write_identical(path, encoded)
                with Image.open(io.BytesIO(encoded)) as raw:
                    assert raw.getexif()[274] == orientation
                    with ImageOps.exif_transpose(raw) as displayed:
                        cases.append(dict(file=path.name, format=kind, orientation=orientation,
                                          sha256=hashlib.sha256(encoded).hexdigest(), raw_size=list(raw.size),
                                          display_size=list(displayed.size), raw_samples=samples(raw), display_samples=samples(displayed)))
    manifest = dict(schema=1, origin='synthetic-encoded-colour-grid', pillow_version=__version__, cases=cases,
                    scope='RGB JPEG and RGBA PNG EXIF direction/alpha fixtures; no ICC/high-depth/production acceptance')
    write_identical(OUT/'manifest.json', (json.dumps(manifest, ensure_ascii=False, indent=2)+'\n').encode('utf-8'))
    print(json.dumps(dict(status='passed', cases=len(cases), source_files=[c['file'] for c in cases])))


if __name__ == '__main__':
    main()
