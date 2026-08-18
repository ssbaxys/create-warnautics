"""Generate small-bomb cassette models with black binding rods + blockstate/items."""
from __future__ import annotations

import json
import os
from copy import deepcopy

from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources"))
BLOCK_MODELS = os.path.join(ROOT, "assets", "cbc_more_content", "models", "block")
ITEM_MODELS = os.path.join(ROOT, "assets", "cbc_more_content", "models", "item")
BLOCKSTATES = os.path.join(ROOT, "assets", "cbc_more_content", "blockstates")
TEXTURES_DIR = os.path.join(ROOT, "assets", "cbc_more_content", "textures", "block")

TEXTURES = {
    "side": "cbc_more_content:block/small_bomb_side",
    "top": "cbc_more_content:block/small_bomb_top",
    "bottom": "cbc_more_content:block/small_bomb_bottom",
    "fin": "cbc_more_content:block/small_bomb_fin",
    "rod": "cbc_more_content:block/cassette_rod",
    "particle": "cbc_more_content:block/small_bomb_side",
}

BASE_PARTS = [
    {"from": [5, 2, 5], "to": [11, 11, 11], "faces": {
        "north": {"uv": [5, 5, 11, 14], "texture": "#side"},
        "east": {"uv": [5, 5, 11, 14], "texture": "#side"},
        "south": {"uv": [5, 5, 11, 14], "texture": "#side"},
        "west": {"uv": [5, 5, 11, 14], "texture": "#side"},
        "up": {"uv": [5, 5, 11, 11], "texture": "#top"},
        "down": {"uv": [5, 5, 11, 11], "texture": "#bottom"},
    }},
    {"from": [6, 11, 6], "to": [10, 13, 10], "faces": {
        "north": {"uv": [6, 3, 10, 5], "texture": "#top"},
        "east": {"uv": [6, 3, 10, 5], "texture": "#top"},
        "south": {"uv": [6, 3, 10, 5], "texture": "#top"},
        "west": {"uv": [6, 3, 10, 5], "texture": "#top"},
        "up": {"uv": [6, 6, 10, 10], "texture": "#top"},
        "down": {"uv": [6, 6, 10, 10], "texture": "#top"},
    }},
    {"from": [7, 13, 7], "to": [9, 15, 9], "faces": {
        "north": {"uv": [7, 1, 9, 3], "texture": "#fin"},
        "east": {"uv": [7, 1, 9, 3], "texture": "#fin"},
        "south": {"uv": [7, 1, 9, 3], "texture": "#fin"},
        "west": {"uv": [7, 1, 9, 3], "texture": "#fin"},
        "up": {"uv": [7, 7, 9, 9], "texture": "#fin"},
        "down": {"uv": [7, 7, 9, 9], "texture": "#fin"},
    }},
    {"from": [4, 0, 7], "to": [12, 3, 9], "faces": {
        "north": {"uv": [4, 13, 12, 16], "texture": "#fin"},
        "east": {"uv": [7, 13, 9, 16], "texture": "#fin"},
        "south": {"uv": [4, 13, 12, 16], "texture": "#fin"},
        "west": {"uv": [7, 13, 9, 16], "texture": "#fin"},
        "up": {"uv": [4, 7, 12, 9], "texture": "#fin"},
        "down": {"uv": [4, 7, 12, 9], "texture": "#fin"},
    }},
    {"from": [7, 0, 4], "to": [9, 3, 12], "faces": {
        "north": {"uv": [7, 13, 9, 16], "texture": "#fin"},
        "east": {"uv": [4, 13, 12, 16], "texture": "#fin"},
        "south": {"uv": [7, 13, 9, 16], "texture": "#fin"},
        "west": {"uv": [4, 13, 12, 16], "texture": "#fin"},
        "up": {"uv": [7, 4, 9, 12], "texture": "#fin"},
        "down": {"uv": [7, 4, 9, 12], "texture": "#fin"},
    }},
]

# Bomb positions as (dx, dy, dz). Pair: 1 rod. Triangle: 3 rods. Quad: rods between bombs (like 2–3).
LAYOUTS = {
    2: {
        "scale": 0.70,
        "bombs": [(-3.4, 0.0, 0.0), (3.4, 0.0, 0.0)],
        "edges": [(0, 1)],
    },
    3: {
        "scale": 0.58,
        "bombs": [(-3.6, 0.0, 2.2), (3.6, 0.0, 2.2), (0.0, 0.0, -3.6)],
        "edges": [(0, 1), (1, 2), (2, 0)],
    },
    4: {
        "scale": 0.52,
        "bombs": [
            (-3.5, 0.0, -3.5),
            (3.5, 0.0, -3.5),
            (-3.5, 0.0, 3.5),
            (3.5, 0.0, 3.5),
        ],
        # Same style as 2–3: rods only between bomb centers (no separate cube frame).
        "edges": [(0, 1), (1, 3), (3, 2), (2, 0)],
    },
}

ROD_FACES = {
    face: {"uv": [0, 0, 16, 16], "texture": "#rod"}
    for face in ("north", "east", "south", "west", "up", "down")
}


def clamp(v: float) -> float:
    return max(0.0, min(16.0, round(v, 3)))


def write_rod_texture() -> None:
    os.makedirs(TEXTURES_DIR, exist_ok=True)
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y in range(16):
        for x in range(16):
            # Near-black metal with slight edge highlight
            if x in (0, 15) or y in (0, 15):
                img.putpixel((x, y), (48, 48, 52, 255))
            elif x in (1, 14) or y in (1, 14):
                img.putpixel((x, y), (28, 28, 30, 255))
            else:
                img.putpixel((x, y), (12, 12, 14, 255))
    img.save(os.path.join(TEXTURES_DIR, "cassette_rod.png"))


def transform_part(part: dict, scale: float, dx: float, dy: float, dz: float) -> dict:
    out = deepcopy(part)
    fx, fy, fz = part["from"]
    tx, ty, tz = part["to"]
    nf = [
        clamp(8 + (fx - 8) * scale + dx),
        clamp(fy * scale + dy),
        clamp(8 + (fz - 8) * scale + dz),
    ]
    nt = [
        clamp(8 + (tx - 8) * scale + dx),
        clamp(ty * scale + dy),
        clamp(8 + (tz - 8) * scale + dz),
    ]
    out["from"] = [min(nf[0], nt[0]), min(nf[1], nt[1]), min(nf[2], nt[2])]
    out["to"] = [max(nf[0], nt[0]), max(nf[1], nt[1]), max(nf[2], nt[2])]
    return out


def bomb_center(dx: float, dy: float, dz: float, scale: float) -> tuple[float, float, float]:
    # Approx body center of scaled bomb
    return (8 + dx, 6.5 * scale + dy, 8 + dz)


def axis_aligned_rod(x0, y0, z0, x1, y1, z1, thickness: float = 0.55) -> dict | None:
    """Rod as AABB between two points (works best when roughly axis-aligned)."""
    t = thickness * 0.5
    dx, dy, dz = abs(x1 - x0), abs(y1 - y0), abs(z1 - z0)
    # Dominant axis gets the length; others get thickness
    if dx >= dy and dx >= dz:
        y = (y0 + y1) * 0.5
        z = (z0 + z1) * 0.5
        return {
            "from": [clamp(min(x0, x1)), clamp(y - t), clamp(z - t)],
            "to": [clamp(max(x0, x1)), clamp(y + t), clamp(z + t)],
            "faces": deepcopy(ROD_FACES),
        }
    if dz >= dx and dz >= dy:
        x = (x0 + x1) * 0.5
        y = (y0 + y1) * 0.5
        return {
            "from": [clamp(x - t), clamp(y - t), clamp(min(z0, z1))],
            "to": [clamp(x + t), clamp(y + t), clamp(max(z0, z1))],
            "faces": deepcopy(ROD_FACES),
        }
    # vertical
    x = (x0 + x1) * 0.5
    z = (z0 + z1) * 0.5
    return {
        "from": [clamp(x - t), clamp(min(y0, y1)), clamp(z - t)],
        "to": [clamp(x + t), clamp(max(y0, y1)), clamp(z + t)],
        "faces": deepcopy(ROD_FACES),
    }


def diagonal_rod_xz(x0, y, z0, x1, z1, thickness: float = 0.55) -> list[dict]:
    """Approximate diagonal in XZ with a rotated element (Minecraft allows ±22.5/±45)."""
    import math

    mx, mz = (x0 + x1) * 0.5, (z0 + z1) * 0.5
    dx, dz = x1 - x0, z1 - z0
    length = math.hypot(dx, dz)
    if length < 0.2:
        return []
    angle = math.degrees(math.atan2(dx, dz))  # rotation around Y
    # Snap to allowed model angles
    allowed = [-45, -22.5, 0, 22.5, 45]
    snap = min(allowed, key=lambda a: abs(a - ((angle + 180) % 360 - 180)))
    # Also try angle+180 equivalents within [-45,45] by flipping
    candidates = []
    for a in allowed:
        for base in (angle, angle + 180, angle - 180):
            diff = abs(((base - a + 180) % 360) - 180)
            candidates.append((diff, a))
    snap = min(candidates, key=lambda t: t[0])[1]

    t = thickness * 0.5
    half = length * 0.5
    return [{
        "from": [clamp(mx - t), clamp(y - t), clamp(mz - half)],
        "to": [clamp(mx + t), clamp(y + t), clamp(mz + half)],
        "rotation": {"origin": [mx, y, mz], "axis": "y", "angle": snap},
        "faces": deepcopy(ROD_FACES),
    }]


def make_edge_rod(c0, c1) -> list[dict]:
    x0, y0, z0 = c0
    x1, y1, z1 = c1
    dx, dy, dz = abs(x1 - x0), abs(y1 - y0), abs(z1 - z0)
    # Mostly horizontal diagonal in XZ
    if dy < 1.0 and dx > 1.0 and dz > 1.0:
        return diagonal_rod_xz(x0, (y0 + y1) * 0.5, z0, x1, z1)
    rod = axis_aligned_rod(x0, y0, z0, x1, y1, z1)
    return [rod] if rod else []


def make_cassette_model(count: int) -> dict:
    layout = LAYOUTS[count]
    scale = layout["scale"]
    elements: list[dict] = []
    centers = []
    for dx, dy, dz in layout["bombs"]:
        for part in BASE_PARTS:
            elements.append(transform_part(part, scale, dx, dy, dz))
        centers.append(bomb_center(dx, dy, dz, scale))

    for i, j in layout["edges"]:
        elements.extend(make_edge_rod(centers[i], centers[j]))

    return {
        "parent": "minecraft:block/block",
        "textures": TEXTURES,
        "elements": elements,
    }


def write_json(path: str, data: dict) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def write_blockstate() -> None:
    facings = {
        "up": {},
        "down": {"x": 180},
        "north": {"x": 90},
        "south": {"x": 90, "y": 180},
        "west": {"x": 90, "y": 270},
        "east": {"x": 90, "y": 90},
    }
    models = {
        1: "cbc_more_content:block/small_bomb",
        2: "cbc_more_content:block/small_bomb_cassette_2",
        3: "cbc_more_content:block/small_bomb_cassette_3",
        4: "cbc_more_content:block/small_bomb_cassette_4",
    }
    variants = {}
    for cassette, model in models.items():
        for powered in ("false", "true"):
            for facing, rot in facings.items():
                key = f"facing={facing},powered={powered},cassette={cassette}"
                entry = {"model": model}
                entry.update(rot)
                variants[key] = entry
    write_json(os.path.join(BLOCKSTATES, "small_bomb.json"), {"variants": variants})


def write_item_models() -> None:
    write_json(
        os.path.join(ITEM_MODELS, "small_bomb.json"),
        {
            "parent": "cbc_more_content:block/small_bomb",
            "overrides": [
                {"predicate": {"cbc_more_content:cassette": 0.5}, "model": "cbc_more_content:item/small_bomb_cassette_2"},
                {"predicate": {"cbc_more_content:cassette": 0.75}, "model": "cbc_more_content:item/small_bomb_cassette_3"},
                {"predicate": {"cbc_more_content:cassette": 1.0}, "model": "cbc_more_content:item/small_bomb_cassette_4"},
            ],
        },
    )
    for n in (2, 3, 4):
        write_json(
            os.path.join(ITEM_MODELS, f"small_bomb_cassette_{n}.json"),
            {"parent": f"cbc_more_content:block/small_bomb_cassette_{n}"},
        )
        write_json(
            os.path.join(ITEM_MODELS, f"small_bomb_{n}.json"),
            {"parent": f"cbc_more_content:block/small_bomb_cassette_{n}"},
        )


def main() -> None:
    write_rod_texture()
    for n in (2, 3, 4):
        write_json(os.path.join(BLOCK_MODELS, f"small_bomb_cassette_{n}.json"), make_cassette_model(n))
    write_blockstate()
    write_item_models()
    print("Wrote cassette models with black rods + cassette_rod.png")


if __name__ == "__main__":
    main()
