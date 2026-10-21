import struct, zlib, os

PAL = {
    '.': (0, 0, 0, 0),
    'a': (154, 154, 154, 255),
    'b': (51, 48, 46, 255),
    'c': (74, 70, 66, 255),
    'd': (63, 168, 143, 255),
    'e': (192, 192, 192, 255),
    'f': (31, 90, 78, 255),
}

ROWS = [
    ".........a......",
    ".........a......",
    ".........a......",
    "...bbbbbbbbbb...",
    "...bffffffffb...",
    "...bcddddddcb...",
    "...bcddddddcb...",
    "...bcddddddcb...",
    "...bffffffffb...",
    "...bbbbbbbbbb...",
    "...bcbcbcbcbb...",
    "...bbbbbbbbbb...",
    "...bceebbbbbb...",
    "...bbbbbbbbbb...",
    "...bbbbbbbbbb...",
    "...bbbbbbbbbb...",
]

out = 'src/main/resources/assets/openradio/textures/item/handheld_radio.png'
os.makedirs(os.path.dirname(out), exist_ok=True)

raw = bytearray()
for row in ROWS:
    assert len(row) == 16, (row, len(row))
    raw.append(0)
    for ch in row:
        raw.extend(PAL[ch])

def chunk(tag, data):
    return (struct.pack('>I', len(data)) + tag + data
            + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff))

png = b'\x89PNG\r\n\x1a\n'
png += chunk(b'IHDR', struct.pack('>IIBBBBB', 16, 16, 8, 6, 0, 0, 0))
png += chunk(b'IDAT', zlib.compress(bytes(raw), 9))
png += chunk(b'IEND', b'')

with open(out, 'wb') as fp:
    fp.write(png)
print('wrote', out, len(png), 'bytes')
