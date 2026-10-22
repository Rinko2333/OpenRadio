"""生成 16x16 占位贴图（纯 Python，手写 PNG，无第三方依赖）。

想换美术直接改下面的 ROWS 像素图即可，不需要任何图像库。
每个字符对应 PAL 里的一个 RGBA 颜色，'.' 是透明。
"""

import struct
import zlib
import os

PAL = {
    '.': (0, 0, 0, 0),
    'a': (154, 154, 154, 255),   # 浅灰：金属高光
    'b': (51, 48, 46, 255),      # 描边
    'c': (74, 70, 66, 255),      # 中灰
    'd': (63, 168, 143, 255),    # 青绿：电台主色
    'e': (192, 192, 192, 255),   # 银：端子 / 天线帽
    'f': (31, 90, 78, 255),      # 深青
    'g': (120, 200, 120, 255),   # 绿：电量
    'h': (110, 105, 100, 255),   # 暗灰
    'i': (201, 162, 39, 255),    # 黄铜
}

# ---------------------------------------------------------------- 手持电台

RADIO = [
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

# ---------------------------------------------------------------- 电池

BATTERY = [
    "................",
    "......eeee......",
    "......eeee......",
    "...bbbbbbbbbb...",
    "...bccccccccb...",
    "...bdggggggdb...",
    "...bdggggggdb...",
    "...bdggggggdb...",
    "...bdggggggdb...",
    "...bdggggggdb...",
    "...bdggggggdb...",
    "...bccccccccb...",
    "...bbbbbbbbbb...",
    "................",
    "................",
    "................",
]

# ---------------------------------------------------------------- 天线
# 三支专用天线的区别是"杆有多高"，宽带那支多两片横条。
# 这不是最终美术，只是让四种天线在背包里能一眼分开。

ANTENNA_SHORT = [
    "................",
    "................",
    "................",
    "................",
    "................",
    ".......ee.......",
    ".......da.......",
    ".......da.......",
    ".......da.......",
    "......bbbb......",
    ".....bccccb.....",
    ".....bbbbbb.....",
    "................",
    "................",
    "................",
    "................",
]

ANTENNA_MEDIUM = [
    "................",
    ".......ee.......",
    ".......da.......",
    ".......da.......",
    ".......da.......",
    ".......da.......",
    ".......da.......",
    ".......da.......",
    ".......da.......",
    "......bbbb......",
    ".....bccccb.....",
    ".....bbbbbb.....",
    "................",
    "................",
    "................",
    "................",
]

ANTENNA_LONG = [
    ".......ee.......",
    "......d..d......",
    ".......dd.......",
    "......d..d......",
    ".......dd.......",
    ".......da.......",
    ".......da.......",
    ".......da.......",
    ".......da.......",
    "......bbbb......",
    ".....bccccb.....",
    ".....bbbbbb.....",
    "................",
    "................",
    "................",
    "................",
]

ANTENNA_WIDEBAND = [
    "................",
    "................",
    ".......ee.......",
    "....aaaaaaa.....",
    ".......da.......",
    ".......da.......",
    "....aaaaaaa.....",
    ".......da.......",
    ".......da.......",
    "......bbbb......",
    ".....bccccb.....",
    ".....bbbbbb.....",
    "................",
    "................",
    "................",
    "................",
]


def build_png(rows):
    assert len(rows) == 16, f"需要 16 行，实际 {len(rows)}"
    raw = bytearray()
    for row in rows:
        assert len(row) == 16, (row, len(row))
        raw.append(0)  # PNG 每行前置 filter 字节
        for ch in row:
            raw.extend(PAL[ch])

    def chunk(tag, data):
        return (struct.pack('>I', len(data)) + tag + data
                + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff))

    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', 16, 16, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(bytes(raw), 9))
    png += chunk(b'IEND', b'')
    return png


SPRITES = {
    'handheld_radio': RADIO,
    'battery': BATTERY,
    'antenna_short': ANTENNA_SHORT,
    'antenna_medium': ANTENNA_MEDIUM,
    'antenna_long': ANTENNA_LONG,
    'antenna_wideband': ANTENNA_WIDEBAND,
}

out_dir = 'src/main/resources/assets/openradio/textures/item'
os.makedirs(out_dir, exist_ok=True)

for name, rows in SPRITES.items():
    path = os.path.join(out_dir, f'{name}.png')
    data = build_png(rows)
    with open(path, 'wb') as fp:
        fp.write(data)
    print('wrote', path, len(data), 'bytes')


# ---------------------------------------------------------------- 方块贴图
# 方块贴图走独立的调色板扩展：太阳能板需要蓝色系，物品那套里没有。

PAL.update({
    'j': (28, 56, 110, 255),     # 深蓝：太阳能板底
    'k': (58, 108, 186, 255),    # 亮蓝：太阳能板
    'm': (16, 32, 64, 255),      # 网格线
})

# 正面 = 操作面板：显示屏 + 两个旋钮。FACING 就指向这一面。
FIXED_STATION_FRONT = [
    "bbbbbbbbbbbbbbbb",
    "baaaaaaaaaaaaaab",
    "baccccccccccccab",
    "bacbbbbbbbbbbcab",
    "bacbddddddddbcab",
    "bacbddeeddddbcab",
    "bacbddddddddbcab",
    "bacbddddddddbcab",
    "bacbbbbbbbbbbcab",
    "baccccccccccccab",
    "bacchhcccchhccab",
    "bacchhcccchhccab",
    "baccccccccccccab",
    "baaaaaaaaaaaaaab",
    "baaaaaaaaaaaaaab",
    "bbbbbbbbbbbbbbbb",
]

FIXED_STATION_SIDE = [
    "bbbbbbbbbbbbbbbb",
    "baaaaaaaaaaaaaab",
    "baaaaaaaaaaaaaab",
    "baaaaaaaaaaaaaab",
    "baahhaahhaahhaab",
    "baahhaahhaahhaab",
    "baaaaaaaaaaaaaab",
    "baaaaaaaaaaaaaab",
    "baaaaaaaaaaaaaab",
    "baaaaaaaaaaaaaab",
    "baahhaahhaahhaab",
    "baahhaahhaahhaab",
    "baaaaaaaaaaaaaab",
    "baaaaaaaaaaaaaab",
    "baaaaaaaaaaaaaab",
    "bbbbbbbbbbbbbbbb",
]

FIXED_STATION_TOP = [
    "bbbbbbbbbbbbbbbb",
    "beeaaaaaaaaaaaeb",
    "baaaaaaaaaaaaaab",
    "baaaaaaaaaaaaaab",
    "baaaaahhaaaaaaab",
    "baaaaaaaaaaaaaab",
    "baaaaaaaaaaaaaab",
    "baaahhhhhhhhaaab",
    "baaahhhhhhhhaaab",
    "baaaaaaaaaaaaaab",
    "baaaaaaaaaaaaaab",
    "baaaaahhaaaaaaab",
    "baaaaaaaaaaaaaab",
    "baaaaaaaaaaaaaab",
    "beeaaaaaaaaaaaeb",
    "bbbbbbbbbbbbbbbb",
]

SOLAR_TOP = [
    "bbbbbbbbbbbbbbbb",
    "bkkkkkkkkkkkkkkb",
    "bkkkkkkkkkkkkkkb",
    "bkkkkkkkkkkkkkkb",
    "bkkkkkkkkkkkkkkb",
    "bkmmmmmmmmmmmmkb",
    "bkkkkkkkkkkkkkkb",
    "bkkkkkkkkkkkkkkb",
    "bkkkkkkkkkkkkkkb",
    "bkkkkkkkkkkkkkkb",
    "bkmmmmmmmmmmmmkb",
    "bkkkkkkkkkkkkkkb",
    "bkkkkkkkkkkkkkkb",
    "bkkkkkkkkkkkkkkb",
    "bkkkkkkkkkkkkkkb",
    "bbbbbbbbbbbbbbbb",
]

SOLAR_SIDE = [
    "bbbbbbbbbbbbbbbb",
    "baaaaaaaaaaaaaab",
    "baaaaaaaaaaaaaab",
    "baakkkkkkkkkkaab",
    "baakkkkkkkkkkaab",
    "baakkkkkkkkkkaab",
    "baakkkkkkkkkkaab",
    "baakkkkkkkkkkaab",
    "baakkkkkkkkkkaab",
    "baakkkkkkkkkkaab",
    "baakkkkkkkkkkaab",
    "baaaaaaaaaaaaaab",
    "baaaaaaaaaaaaaab",
    "baaaaaaaaaaaaaab",
    "baaaaaaaaaaaaaab",
    "bbbbbbbbbbbbbbbb",
]

SOLAR_BOTTOM = [
    "bbbbbbbbbbbbbbbb",
    "baaaaaaaaaaaaaab",
    "baaaaaaaaaaaaaab",
    "baaaaaaaaaaaaaab",
    "baaahhhhhhhhaaab",
    "baaahhhhhhhhaaab",
    "baaahhhhhhhhaaab",
    "baaahhhhhhhhaaab",
    "baaahhhhhhhhaaab",
    "baaahhhhhhhhaaab",
    "baaahhhhhhhhaaab",
    "baaahhhhhhhhaaab",
    "baaahhhhhhhhaaab",
    "baaaaaaaaaaaaaab",
    "baaaaaaaaaaaaaab",
    "bbbbbbbbbbbbbbbb",
]

BLOCK_SPRITES = {
    'fixed_station_front': FIXED_STATION_FRONT,
    'fixed_station_side': FIXED_STATION_SIDE,
    'fixed_station_top': FIXED_STATION_TOP,
    'solar_power_top': SOLAR_TOP,
    'solar_power_side': SOLAR_SIDE,
    'solar_power_bottom': SOLAR_BOTTOM,
}

block_dir = 'src/main/resources/assets/openradio/textures/block'
os.makedirs(block_dir, exist_ok=True)

for name, rows in BLOCK_SPRITES.items():
    # 尺寸写错会让贴图整体错位，而且是运行期才看出来，所以在生成时就拦下。
    assert len(rows) == 16, f'{name}: 需要 16 行，实际 {len(rows)}'
    for index, row in enumerate(rows):
        assert len(row) == 16, f'{name}: 第 {index} 行长度 {len(row)}，需要 16'
        assert all(ch in PAL for ch in row), f'{name}: 第 {index} 行有未定义的颜色字符'
    path = os.path.join(block_dir, f'{name}.png')
    data = build_png(rows)
    with open(path, 'wb') as fp:
        fp.write(data)
    print('wrote', path, len(data), 'bytes')
