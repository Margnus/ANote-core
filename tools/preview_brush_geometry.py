#!/usr/bin/env python3
"""Inspect actual JVM numeric geometry as SVG; not a native raster conformance test."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NS = 'http://www.w3.org/2000/svg'
ET.register_namespace('', NS)


def element(parent, tag, attributes=None, text=None):
    node = ET.SubElement(parent, f'{{{NS}}}{tag}', attributes or {})
    node.text = text
    return node


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, default=ROOT/'build/brush-golden-actual.json')
    parser.add_argument('--output', type=Path, default=ROOT/'build/brush-golden-preview.svg')
    args = parser.parse_args()
    cases = json.loads(args.input.read_text(encoding='utf-8'))
    if not cases:
        raise SystemExit('No actual numeric geometry. Run verifyPrototype first.')
    rows = (len(cases) + 1) // 2
    svg = ET.Element(f'{{{NS}}}svg', {'width': '1040', 'height': str(110 + rows * 230),
                                   'viewBox': f'0 0 1040 {110 + rows * 230}'})
    element(svg, 'rect', {'width': '100%', 'height': '100%', 'fill': '#f3f5f8'})
    element(svg, 'text', {'x': '32', 'y': '38', 'font-size': '23', 'font-family': 'sans-serif', 'fill': '#253247'},
            'Shared brush geometry - candidate 2')
    element(svg, 'text', {'x': '32', 'y': '65', 'font-size': '13', 'font-family': 'sans-serif', 'fill': '#536174'},
            'Actual JVM vertices; one nonzero fill per stroke. Native rendering and performance are unverified.')
    for index, case in enumerate(cases):
        x = 24 + index % 2 * 504
        y = 88 + index // 2 * 230
        element(svg, 'rect', {'x': str(x), 'y': str(y), 'width': '488', 'height': '212', 'rx': '12', 'fill': '#ffffff'})
        element(svg, 'text', {'x': str(x + 20), 'y': str(y + 30), 'font-size': '17', 'font-family': 'sans-serif', 'fill': '#253247'}, case['id'])
        vertices = case['vertices']; offsets = case['contour_offsets']
        assert case['fill_rule'] == 'NON_ZERO' and case['blend_mode'] == 'SOURCE_OVER'
        left, top, right, bottom = case['bounds']
        scale = min(420 / max(right - left, 1e-12), 132 / max(bottom - top, 1e-12))
        tx = x + 244 - (left + right) * scale / 2
        ty = y + 117 - (top + bottom) * scale / 2
        commands = []
        for start, end in zip(offsets, offsets[1:]):
            assert 0 <= start < end <= len(vertices)
            commands.extend(f'{"M" if i == start else "L"}{vertices[i][0]:.17g},{vertices[i][1]:.17g}' for i in range(start, end))
            commands.append('Z')
        color = case['argb'] & 0xffffffff
        # All contours belong to ONE path. Opacity must not accumulate within a stroke.
        element(svg, 'path', {'d': ' '.join(commands), 'transform': f'translate({tx:.17g} {ty:.17g}) scale({scale:.17g})',
                              'fill': f'#{color & 0xffffff:06x}', 'fill-opacity': f'{(color >> 24) / 255:.17g}',
                              'fill-rule': 'nonzero'})
        element(svg, 'text', {'x': str(x + 20), 'y': str(y + 195), 'font-size': '12', 'font-family': 'sans-serif', 'fill': '#536174'},
                f'{case["source_points"]} samples / {len(vertices)} vertices / {len(offsets) - 1} contours / alpha {color >> 24}')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(svg).write(args.output, encoding='utf-8', xml_declaration=True)
    print(f'Wrote {len(cases)} actual-geometry previews: {args.output}')


if __name__ == '__main__':
    main()
