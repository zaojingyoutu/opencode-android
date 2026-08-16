#!/usr/bin/env python3
"""Generate placeholder launcher icons for all mipmap densities."""
import struct, zlib

def make_png(width, height, color=(48,132,255,255)):
    """Create a minimal valid PNG with a colored background."""
    def chunk(t, data):
        return t + struct.pack('>I', len(data)) + data + struct.pack('>I', zlib.crc32(t + data) & 0xffffffff)
    ihdr = struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0)
    raw = b''
    for _ in range(height):
        raw += b'\x00' + bytes(color) * width
    data = zlib.compress(raw)
    return b''.join([
        b'\x89PNG\r\n\x1a\n',
        chunk(b'IHDR', ihdr),
        chunk(b'IDAT', data),
        chunk(b'IEND', b'')
    ])

import os
base = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                    '..', 'android-app', 'app', 'src', 'main', 'res')
sizes = {
    'mipmap-hdpi': 72, 'mipmap-mdpi': 48,
    'mipmap-xhdpi': 96, 'mipmap-xxhdpi': 144,
    'mipmap-xxxhdpi': 192
}
for folder, size in sizes.items():
    path = os.path.join(base, folder, 'ic_launcher.png')
    with open(path, 'wb') as f:
        f.write(make_png(size, size, (48, 132, 255, 255)))
    print(f'wrote {path} ({size}x{size})')