#!/usr/bin/env python3
"""Independent Pillow EXIF transpose oracle; synthetic pixels only, no product decoder acceptance."""
import hashlib
import json
from pathlib import Path
from PIL import Image, ImageOps, __version__

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = [
    [0, 1, 2, 3, 4, 5], [1, 0, 3, 2, 5, 4],
    [5, 4, 3, 2, 1, 0], [4, 5, 2, 3, 0, 1],
    [0, 2, 4, 1, 3, 5], [4, 2, 0, 5, 3, 1],
    [5, 3, 1, 4, 2, 0], [1, 3, 5, 0, 2, 4],
]


def main():
    observations = []
    for orientation, expected in enumerate(EXPECTED, 1):
        with Image.new('RGB', (2, 3)) as source:
            source.putdata([(i, 0, 0) for i in range(6)])
            source.getexif()[274] = orientation
            before = source.tobytes()
            with ImageOps.exif_transpose(source) as displayed:
                actual = [displayed.getpixel((x, y))[0] for y in range(displayed.height) for x in range(displayed.width)]
                assert actual == expected
                assert displayed.size == ((3, 2) if orientation >= 5 else (2, 3))
                assert source.tobytes() == before and source.getexif()[274] == orientation
                observations.append(dict(orientation=orientation, size=list(displayed.size), pixels=actual))
    report = dict(status='passed', pillow_version=__version__, observations=observations,
                  source_preserved=True, sample_origin='synthetic-2x3-pixels',
                  script_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                  scope='Independent host Pillow EXIF transpose permutations; no PNG/JPEG decode, platform or App acceptance')
    output = ROOT/'reports/image-orientation-oracle.json'
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    main()
