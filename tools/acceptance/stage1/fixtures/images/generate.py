#!/usr/bin/env python3
"""Deterministic generator for the canonical T1.9 inline-image probe.

Produces a small, genuinely valid RGB PNG with fixed known content:
a white canvas, a blue border frame and the black text "LLMGW" rendered
from a 5x7 bitmap font at 4x scale. No randomness, no timestamps, no
personal information -- re-running this script reproduces byte-identical
output. The committed PNG bytes are the source of truth; the manifest
sha256/length are computed from the bytes actually written.

Usage:  python3 generate.py
"""
import hashlib
import json
import struct
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent

SCALE = 4
MARGIN = 2  # font-pixel gap between the border frame and the glyphs
TEXT = "LLMGW"
WHITE = (255, 255, 255)
BLACK = (0, 0, 0)
BLUE = (37, 99, 235)

# 5x7 bitmap glyphs ('1' = ink). Fixed, hand-authored shapes.
FONT = {
    "L": ["10000", "10000", "10000", "10000", "10000", "10000", "11111"],
    "M": ["10001", "11011", "10101", "10101", "10001", "10001", "10001"],
    "G": ["01110", "10001", "10000", "10111", "10001", "10001", "01111"],
    "W": ["10001", "10001", "10001", "10101", "10101", "11011", "10001"],
}
GLYPH_W, GLYPH_H, GAP = 5, 7, 1


def build_pixels():
    text_w = len(TEXT) * GLYPH_W + (len(TEXT) - 1) * GAP
    font_w = text_w + 2 * MARGIN
    font_h = GLYPH_H + 2 * MARGIN
    width, height = font_w * SCALE, font_h * SCALE

    # Pre-render the text into a font-pixel ink map.
    ink = set()
    cursor = MARGIN
    for ch in TEXT:
        glyph = FONT[ch]
        for gy in range(GLYPH_H):
            for gx in range(GLYPH_W):
                if glyph[gy][gx] == "1":
                    ink.add((cursor + gx, MARGIN + gy))
        cursor += GLYPH_W + GAP

    rows = []
    for y in range(height):
        row = bytearray()
        fy = y // SCALE
        for x in range(width):
            fx = x // SCALE
            border = fx == 0 or fy == 0 or fx == font_w - 1 or fy == font_h - 1
            if border:
                color = BLUE
            elif (fx, fy) in ink:
                color = BLACK
            else:
                color = WHITE
            row.extend(color)
        rows.append(bytes(row))
    return width, height, rows


def chunk(tag, data):
    return (struct.pack(">I", len(data)) + tag + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))


def encode_png(width, height, rows):
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)  # 8-bit RGB
    raw = b"".join(b"\x00" + row for row in rows)  # filter type 0 per scanline
    idat = zlib.compress(raw, 9)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", idat) + chunk(b"IEND", b""))


def main():
    width, height, rows = build_pixels()
    png = encode_png(width, height, rows)
    out = HERE / "llmgw-probe.png"
    out.write_bytes(png)

    # Pin the manifest from the bytes actually written (committed source of truth).
    committed = out.read_bytes()
    digest = hashlib.sha256(committed).hexdigest()
    import base64
    manifest = {
        "description": "Canonical T1.9 inline-image probe fixtures (own small image, sha256 pinned).",
        "images": [
            {
                "file": "llmgw-probe.png",
                "mediaType": "image/png",
                "bytes": len(committed),
                "sha256": digest,
                "width": width,
                "height": height,
                "content": "White canvas, blue border frame, black 'LLMGW' text (5x7 bitmap font, 4x scale).",
                "base64": base64.b64encode(committed).decode("ascii"),
            }
        ],
    }
    (HERE / "manifest.json").write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {out.name}: {len(committed)} bytes, {width}x{height}, sha256={digest}")


if __name__ == "__main__":
    main()