#!/usr/bin/env python3
"""生成多方块天线的模型、blockstate、物品模型、战利品表与配方。

为什么用脚本而不是手写 JSON：这些模型的 elements 坐标必须和 Java 里的
VoxelShape 一一对应，手抄一遍 16 组数字迟早会错位一格；错位在游戏里表现为
"能看见的方块和能站上去的方块不是同一个东西"，而且极难定位。

跑一次就把所有文件写出来，改形状只改这里的常量。
"""

import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, 'src', 'main', 'resources')
BLOCK_MODELS = os.path.join(RES, 'assets', 'openradio', 'models', 'block')
ITEM_MODELS = os.path.join(RES, 'assets', 'openradio', 'models', 'item')
BLOCKSTATES = os.path.join(RES, 'assets', 'openradio', 'blockstates')
LOOT = os.path.join(RES, 'data', 'openradio', 'loot_table', 'blocks')
RECIPES = os.path.join(RES, 'data', 'openradio', 'recipe')

METAL = 'openradio:block/antenna_metal'
CABLE = 'openradio:block/feeder_cable'


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as handle:
        json.dump(data, handle, indent=2, ensure_ascii=False)
        handle.write('\n')
    print('wrote', os.path.relpath(path, ROOT))


def all_faces(texture):
    return {side: {'texture': texture} for side in
            ('down', 'up', 'north', 'south', 'west', 'east')}


def box(bottom, top, texture):
    """from/to 用像素坐标（0–16），与 Block.box 的语义一致。"""
    return {'from': bottom, 'to': top, 'faces': all_faces(texture)}


def elements_model(textures, boxes):
    return {'textures': textures, 'elements': boxes}


# ---------------------------------------------------------------- 天线部件

# 基座：16×4 的底板 + 从 y=4 伸到顶的细柱。和 Java 里的 SHAPE 完全一致。
write(os.path.join(BLOCK_MODELS, 'antenna_base.json'), elements_model(
    {'particle': METAL, 'm': METAL},
    [box([0, 0, 0], [16, 4, 16], '#m'),
     box([6, 4, 6], [10, 16, 10], '#m')]))

write(os.path.join(BLOCK_MODELS, 'antenna_mast.json'), elements_model(
    {'particle': METAL, 'm': METAL},
    [box([6, 0, 6], [10, 16, 10], '#m')]))

# 振子：柱 + 顶部十字。十字是"这是振子不是杆"的唯一视觉标识。
for suffix in ('short', 'medium', 'long', 'wideband'):
    texture = f'openradio:block/antenna_element_{suffix}'
    write(os.path.join(BLOCK_MODELS, f'antenna_element_{suffix}.json'), elements_model(
        {'particle': texture, 'e': texture},
        [box([6, 0, 6], [10, 16, 10], '#e'),
         box([2, 12, 6], [14, 15, 10], '#e'),
         box([6, 12, 2], [10, 15, 14], '#e')]))

# ---------------------------------------------------------------- 馈线

# 核心与三条臂。为什么 up/down 单独建模而不是用 "x": 90 旋转：
# 旋转方向的符号非常容易搞反，而搞反了不报错、只是模型上下颠倒，
# 排查成本远高于多写两个 20 行的 JSON。形状必须和 Java 里的 VoxelShape 对上。
write(os.path.join(BLOCK_MODELS, 'feeder_core.json'), elements_model(
    {'particle': CABLE, 'c': CABLE},
    [box([6, 6, 6], [10, 10, 10], '#c')]))

write(os.path.join(BLOCK_MODELS, 'feeder_arm.json'), elements_model(
    {'particle': CABLE, 'c': CABLE},
    [box([6, 6, 0], [10, 10, 6], '#c')]))

write(os.path.join(BLOCK_MODELS, 'feeder_arm_up.json'), elements_model(
    {'particle': CABLE, 'c': CABLE},
    [box([6, 10, 6], [10, 16, 10], '#c')]))

write(os.path.join(BLOCK_MODELS, 'feeder_arm_down.json'), elements_model(
    {'particle': CABLE, 'c': CABLE},
    [box([6, 0, 6], [10, 6, 10], '#c')]))

# ---------------------------------------------------------------- blockstate

SIMPLE_STATES = {
    'antenna_base': 'antenna_base',
    'antenna_mast': 'antenna_mast',
    'antenna_element_short': 'antenna_element_short',
    'antenna_element_medium': 'antenna_element_medium',
    'antenna_element_long': 'antenna_element_long',
    'antenna_element_wideband': 'antenna_element_wideband',
}

for state_name, model_name in SIMPLE_STATES.items():
    write(os.path.join(BLOCKSTATES, f'{state_name}.json'),
          {'variants': {'': {'model': f'openradio:block/{model_name}'}}})

write(os.path.join(BLOCKSTATES, 'feeder_cable.json'), {'multipart': [
    {'apply': {'model': 'openradio:block/feeder_core'}},
    {'when': {'north': 'true'}, 'apply': {'model': 'openradio:block/feeder_arm'}},
    {'when': {'east': 'true'}, 'apply': {'model': 'openradio:block/feeder_arm', 'y': 90}},
    {'when': {'south': 'true'}, 'apply': {'model': 'openradio:block/feeder_arm', 'y': 180}},
    {'when': {'west': 'true'}, 'apply': {'model': 'openradio:block/feeder_arm', 'y': 270}},
    {'when': {'up': 'true'}, 'apply': {'model': 'openradio:block/feeder_arm_up'}},
    {'when': {'down': 'true'}, 'apply': {'model': 'openradio:block/feeder_arm_down'}},
]})

# ---------------------------------------------------------------- 物品模型

# 物品模型不能统一指向 block/<name>：馈线是 multipart，根本没有
# block/feeder_cable.json 这个文件。物品栏里退回显示核心那一小段。
ITEM_STATE_PARENTS = dict(SIMPLE_STATES)
ITEM_STATE_PARENTS['feeder_cable'] = 'feeder_core'

for state_name, parent in ITEM_STATE_PARENTS.items():
    write(os.path.join(ITEM_MODELS, f'{state_name}.json'),
          {'parent': f'openradio:block/{parent}'})

# ---------------------------------------------------------------- 战利品表

for block_name in list(SIMPLE_STATES) + ['feeder_cable']:
    write(os.path.join(LOOT, f'{block_name}.json'), {
        'type': 'minecraft:block',
        'random_sequence': f'openradio:blocks/{block_name}',
        'pools': [{
            'rolls': 1,
            'bonus_rolls': 0,
            'entries': [{'type': 'minecraft:item', 'name': f'openradio:{block_name}'}],
            'conditions': [{'condition': 'minecraft:survives_explosion'}],
        }],
    })

# ---------------------------------------------------------------- 配方


def shaped(pattern, key, result, count=1):
    return {
        'type': 'minecraft:crafting_shaped',
        'category': 'misc',
        'pattern': pattern,
        'key': key,
        'result': {'id': result, 'count': count},
    }


write(os.path.join(RECIPES, 'antenna_base.json'), shaped(
    ['I I', 'ICI', 'III'],
    {'I': {'item': 'minecraft:iron_ingot'}, 'C': {'item': 'minecraft:copper_ingot'}},
    'openradio:antenna_base'))

# 两根杆用两块铁：杆是纯高度投入，应该便宜到"想加就加"。
write(os.path.join(RECIPES, 'antenna_mast.json'), shaped(
    ['I', 'I'],
    {'I': {'item': 'minecraft:iron_ingot'}},
    'openradio:antenna_mast', 2))

ELEMENT_CORES = {
    'short': 'minecraft:copper_ingot',
    'medium': 'minecraft:gold_ingot',
    'long': 'minecraft:redstone',
    'wideband': 'minecraft:quartz',
}
for suffix, core in ELEMENT_CORES.items():
    write(os.path.join(RECIPES, f'antenna_element_{suffix}.json'), shaped(
        ['III', 'ICI', 'III'],
        {'I': {'item': 'minecraft:iron_ingot'}, 'C': {'item': core}},
        f'openradio:antenna_element_{suffix}'))

write(os.path.join(RECIPES, 'feeder_cable.json'), shaped(
    ['CCC'],
    {'C': {'item': 'minecraft:copper_ingot'}},
    'openradio:feeder_cable', 8))

print('\ndone')
