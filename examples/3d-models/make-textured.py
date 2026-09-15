#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Write the textured examples: a UV test cube in three packagings, and a pipe with two valves.

uv-cube checks the three ways a texture reaches the viewer: embedded in a GLB, and as a sibling
file named by a .gltf or by an OBJ's material library. pipe-valves is what a textured plant model
looks like when it works. Both are Y-up, as glTF specifies and the viewer assumes.

    python3 make-textured.py [output-dir]
"""
import json
import math
import pathlib
import random
import struct
import sys
import zlib

TAU = math.pi * 2


def write_png(path, width, height, pixel, row_filter=0):
    """An 8-bit RGB PNG from pixel(x, y) -> (r, g, b)."""
    raw = bytearray()
    for y in range(height):
        row = [c for x in range(width) for c in pixel(x, y)]
        raw.append(row_filter)
        if row_filter == 1:
            # Sub filter: each byte minus the one a pixel to its left, suiting horizontal streaks.
            row = [(row[i] - (row[i - 3] if i >= 3 else 0)) & 0xFF for i in range(len(row))]
        raw += bytes(row)

    def chunk(kind, data):
        return (struct.pack(">I", len(data)) + kind + data
                + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF))

    path.write_bytes(b"\x89PNG\r\n\x1a\n"
                     + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
                     + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
                     + chunk(b"IEND", b""))


def uv_grid(x, y):
    """An 8x8 checker with red top-left, green top-right and blue bottom-left cells."""
    cx, cy = x // 32, y // 32
    if (cx, cy) == (0, 0):
        return 220, 40, 40
    if (cx, cy) == (7, 0):
        return 40, 180, 60
    if (cx, cy) == (0, 7):
        return 40, 80, 220
    return (235, 235, 235) if (cx + cy) % 2 == 0 else (60, 64, 72)


def brushed_steel():
    """Streaks along the image's x axis, tileable both ways, seeded so the output is stable."""
    rng = random.Random(7)
    size = 256
    rows = [rng.uniform(-1.0, 1.0) for _ in range(size)]
    waves = [(rng.randint(1, 3), rng.randint(2, 9), rng.uniform(0, TAU), rng.uniform(0.01, 0.03))
             for _ in range(5)]

    def pixel(x, y):
        streak = 0.5 * rows[y] + 0.3 * rows[(y + 1) % size] + 0.2 * rows[(y - 1) % size]
        cloud = sum(a * math.sin(TAU * (fx * x + fy * y) / size + ph) for fx, fy, ph, a in waves)
        v = 0.66 + 0.07 * streak + cloud
        return tuple(max(0, min(255, round(255 * v * tint))) for tint in (0.96, 0.98, 1.0))

    return size, pixel


class Part:
    """A named triangle mesh with normals and texture coordinates."""

    def __init__(self, name, material):
        self.name = name
        self.material = material
        self.pos, self.nrm, self.uv, self.idx = [], [], [], []

    def vertex(self, p, n, uv):
        self.pos.append(p)
        self.nrm.append(n)
        self.uv.append(uv)
        return len(self.pos) - 1

    def triangles(self):
        return len(self.idx) // 3


def normalize(v):
    d = math.sqrt(sum(c * c for c in v)) or 1.0
    return tuple(c / d for c in v)


# Axis frames for lathe(): (axis, e1, e2) with axis x e1 = e2, so the winding stays outward.
ALONG_X = ((1, 0, 0), (0, 1, 0), (0, 0, 1))
ALONG_Y = ((0, 1, 0), (0, 0, 1), (1, 0, 0))
ALONG_Z = ((0, 0, 1), (1, 0, 0), (0, 1, 0))


def lathe(part, runs, frame, centre, segs=32, uv_scale=(1.0, 1.0)):
    """Revolve (axial, radius) runs, outside on the left; smooth within a run, sharp between."""
    axis, e1, e2 = frame
    for run, closed in runs:
        seg_n = []
        for (x0, r0), (x1, r1) in zip(run, run[1:]):
            seg_n.append(normalize((-(r1 - r0), x1 - x0)))
        vert_n = []
        for i in range(len(run)):
            prev = seg_n[i - 1] if i > 0 else (seg_n[-1] if closed else None)
            nxt = seg_n[i] if i < len(seg_n) else (seg_n[0] if closed else None)
            pair = [n for n in (prev, nxt) if n]
            vert_n.append(normalize((sum(n[0] for n in pair), sum(n[1] for n in pair))))
        length = [0.0]
        for (x0, r0), (x1, r1) in zip(run, run[1:]):
            length.append(length[-1] + math.hypot(x1 - x0, r1 - r0))

        grid = []
        for i, (x, r) in enumerate(run):
            ring = []
            for k in range(segs + 1):
                a = TAU * k / segs
                radial = tuple(math.cos(a) * e1[c] + math.sin(a) * e2[c] for c in range(3))
                p = tuple(centre[c] + x * axis[c] + r * radial[c] for c in range(3))
                n = normalize(tuple(vert_n[i][0] * axis[c] + vert_n[i][1] * radial[c]
                                    for c in range(3)))
                uv = (length[i] / uv_scale[0], k / segs * uv_scale[1])
                ring.append(part.vertex(p, n, uv))
            grid.append((ring, r))

        for (ring0, r0), (ring1, r1) in zip(grid, grid[1:]):
            for k in range(segs):
                a, b, c, d = ring0[k], ring1[k], ring1[k + 1], ring0[k + 1]
                # A ring at radius zero is a point, so skip the triangle that would be flat.
                if r0 > 0:
                    part.idx += [a, d, c]
                if r1 > 0:
                    part.idx += [a, c, b]
    return part


def rod(x0, x1, r):
    """A solid cylinder with flat caps, as lathe runs."""
    return [([(x0, 0.0), (x0, r)], False), ([(x0, r), (x1, r)], False),
            ([(x1, r), (x1, 0.0)], False)]


def hollow(x0, x1, r_out, r_in):
    """A tube with annular ends, as lathe runs."""
    return [([(x0, r_out), (x1, r_out)], False), ([(x1, r_out), (x1, r_in)], False),
            ([(x1, r_in), (x0, r_in)], False), ([(x0, r_in), (x0, r_out)], False)]


def uv_cube(half=0.5):
    """Each face carries the whole grid, image top towards +Y (or -Z on top, +Z on the bottom)."""
    h = half
    faces = [
        ((0, 0, 1), [(-h, -h, h), (h, -h, h), (h, h, h), (-h, h, h)]),
        ((0, 0, -1), [(h, -h, -h), (-h, -h, -h), (-h, h, -h), (h, h, -h)]),
        ((1, 0, 0), [(h, -h, h), (h, -h, -h), (h, h, -h), (h, h, h)]),
        ((-1, 0, 0), [(-h, -h, -h), (-h, -h, h), (-h, h, h), (-h, h, -h)]),
        ((0, 1, 0), [(-h, h, h), (h, h, h), (h, h, -h), (-h, h, -h)]),
        ((0, -1, 0), [(-h, -h, -h), (h, -h, -h), (h, -h, h), (-h, -h, h)]),
    ]
    part = Part("uv-cube", "uv-grid")
    # Corners in order bottom-left, bottom-right, top-right, top-left, in glTF's top-left origin.
    corner_uv = [(0.0, 1.0), (1.0, 1.0), (1.0, 0.0), (0.0, 0.0)]
    for n, corners in faces:
        base = len(part.pos)
        for p, uv in zip(corners, corner_uv):
            part.vertex(p, n, uv)
        part.idx += [base, base + 1, base + 2, base, base + 2, base + 3]
    return part


def valve(side, cx):
    """A flanged, bolted gate valve on the pipe axis, handwheel up."""
    c = (cx, 0.0, 0.0)
    parts = []
    body = [([(-0.165, 0.085), (-0.165, 0.11)], False),
            ([(-0.165 + 0.33 * t / 12, 0.11 + 0.03 * math.sin(math.pi * t / 12))
              for t in range(13)], False),
            ([(0.165, 0.11), (0.165, 0.085)], False),
            ([(0.165, 0.085), (-0.165, 0.085)], False)]
    parts.append(lathe(Part(f"valve-{side}-body", "valve-yellow"), body, ALONG_X, c, segs=40))
    parts.append(lathe(Part(f"valve-{side}-flange-a", "valve-yellow"),
                       hollow(-0.20, -0.165, 0.20, 0.085), ALONG_X, c, segs=40))
    parts.append(lathe(Part(f"valve-{side}-flange-b", "valve-yellow"),
                       hollow(0.165, 0.20, 0.20, 0.085), ALONG_X, c, segs=40))
    studs = Part(f"valve-{side}-studs", "bolt")
    for i in range(8):
        a = TAU * (i + 0.5) / 8
        lathe(studs, rod(-0.225, 0.225, 0.011), ALONG_X,
              (cx, 0.168 * math.cos(a), 0.168 * math.sin(a)), segs=10)
    parts.append(studs)

    bonnet = Part(f"valve-{side}-bonnet", "valve-yellow")
    lathe(bonnet, rod(0.10, 0.30, 0.055), ALONG_Y, c, segs=28)
    lathe(bonnet, rod(0.30, 0.325, 0.08), ALONG_Y, c, segs=28)
    parts.append(bonnet)
    parts.append(lathe(Part(f"valve-{side}-stem", "brushed-steel"),
                       rod(0.325, 0.47, 0.014), ALONG_Y, c, segs=16, uv_scale=(0.5, 1.0)))

    wheel = Part(f"valve-{side}-handwheel", "valve-yellow")
    hub = (cx, 0.45, 0.0)
    rim = [(0.016 * math.cos(TAU * t / 16), 0.13 - 0.016 * math.sin(TAU * t / 16))
           for t in range(17)]
    lathe(wheel, [(rim, True)], ALONG_Y, hub, segs=40)
    lathe(wheel, rod(-0.02, 0.02, 0.03), ALONG_Y, hub, segs=20)
    for frame in (ALONG_X, ALONG_Z):
        lathe(wheel, rod(-0.125, 0.125, 0.009), frame, hub, segs=10)
    parts.append(wheel)
    return parts


def pipe_valves():
    """A 4 m horizontal pipe along X, broken by a valve 0.8 m in from each end."""
    steel = (0.5, 1.0)
    parts = [
        lathe(Part("pipe-left", "brushed-steel"), hollow(-2.0, -1.4, 0.10, 0.085), ALONG_X,
              (0, 0, 0), segs=40, uv_scale=steel),
        lathe(Part("pipe-middle", "brushed-steel"), hollow(-1.0, 1.0, 0.10, 0.085), ALONG_X,
              (0, 0, 0), segs=40, uv_scale=steel),
        lathe(Part("pipe-right", "brushed-steel"), hollow(1.4, 2.0, 0.10, 0.085), ALONG_X,
              (0, 0, 0), segs=40, uv_scale=steel),
    ]
    return parts + valve("left", -1.2) + valve("right", 1.2)


def gltf_document(scene, parts, materials, buf, image):
    """Append geometry to buf and return the glTF JSON; image is PNG bytes to embed, or a uri."""
    names = list(materials)
    accessors, views, meshes, nodes = [], [], [], []

    def view(blob, target=None):
        while len(buf) % 4:
            buf.append(0)
        entry = {"buffer": 0, "byteOffset": len(buf), "byteLength": len(blob)}
        if target:
            entry["target"] = target
        buf.extend(blob)
        views.append(entry)
        return len(views) - 1

    for part in parts:
        attrs = {
            "POSITION": (part.pos, "VEC3", "<3f"),
            "NORMAL": (part.nrm, "VEC3", "<3f"),
        }
        if "baseColorTexture" in materials[part.material]["pbrMetallicRoughness"]:
            attrs["TEXCOORD_0"] = (part.uv, "VEC2", "<2f")
        prim = {"attributes": {}, "material": names.index(part.material)}
        accessors.append({"bufferView": view(struct.pack(f"<{len(part.idx)}I", *part.idx), 34963),
                          "componentType": 5125, "count": len(part.idx), "type": "SCALAR"})
        prim["indices"] = len(accessors) - 1
        for key, (data, kind, fmt) in attrs.items():
            acc = {"bufferView": view(b"".join(struct.pack(fmt, *d) for d in data), 34962),
                   "componentType": 5126, "count": len(data), "type": kind}
            if key == "POSITION":
                acc["min"] = [min(p[i] for p in data) for i in range(3)]
                acc["max"] = [max(p[i] for p in data) for i in range(3)]
            accessors.append(acc)
            prim["attributes"][key] = len(accessors) - 1
        meshes.append({"name": part.name, "primitives": [prim]})
        nodes.append({"name": part.name, "mesh": len(meshes) - 1})

    doc = {
        "asset": {"version": "2.0", "generator": "datahub make-textured.py"},
        "scene": 0,
        "scenes": [{"name": scene, "nodes": list(range(len(nodes)))}],
        "nodes": nodes,
        "meshes": meshes,
        "materials": [dict(materials[m], name=m) for m in names],
        "accessors": accessors,
        "bufferViews": views,
        "samplers": [{"magFilter": 9729, "minFilter": 9987, "wrapS": 10497, "wrapT": 10497}],
        "textures": [{"sampler": 0, "source": 0}],
        "images": [{"bufferView": view(image), "mimeType": "image/png"}
                   if isinstance(image, bytes) else {"uri": image}],
    }
    return doc


def write_glb(path, doc, buf):
    doc["buffers"] = [{"byteLength": len(buf)}]
    js = json.dumps(doc, separators=(",", ":")).encode("utf-8")
    js += b" " * ((4 - len(js) % 4) % 4)
    body = bytes(buf) + b"\0" * ((4 - len(buf) % 4) % 4)
    chunks = (struct.pack("<II", len(js), 0x4E4F534A) + js
              + struct.pack("<II", len(body), 0x004E4942) + body)
    path.write_bytes(struct.pack("<III", 0x46546C67, 2, 12 + len(chunks)) + chunks)


def textured(texture, metallic, roughness):
    return {"pbrMetallicRoughness": {"baseColorTexture": {"index": texture},
                                     "metallicFactor": metallic, "roughnessFactor": roughness}}


def plain(rgb, metallic, roughness):
    return {"pbrMetallicRoughness": {"baseColorFactor": list(rgb) + [1.0],
                                     "metallicFactor": metallic, "roughnessFactor": roughness}}


def write_uv_cube(out):
    write_png(out / "uv-grid.png", 256, 256, uv_grid)
    png = (out / "uv-grid.png").read_bytes()
    cube = uv_cube()
    materials = {"uv-grid": textured(0, 0.0, 0.8)}

    buf = bytearray()
    write_glb(out / "uv-cube.glb", gltf_document("uv-cube", [cube], materials, buf, png), buf)

    buf = bytearray()
    doc = gltf_document("uv-cube", [cube], materials, buf, "uv-grid.png")
    doc["buffers"] = [{"byteLength": len(buf), "uri": "uv-cube.bin"}]
    (out / "uv-cube.bin").write_bytes(bytes(buf))
    (out / "uv-cube.gltf").write_text(json.dumps(doc, indent=1) + "\n")

    lines = ["mtllib uv-cube.mtl", "o uv-cube", "usemtl uv-grid"]
    lines += [f"v {x} {y} {z}" for x, y, z in cube.pos]
    # OBJ puts the texture origin bottom-left, so v flips relative to glTF.
    lines += [f"vt {u} {1.0 - v}" for u, v in cube.uv]
    lines += [f"vn {x} {y} {z}" for x, y, z in cube.nrm]
    for t in range(0, len(cube.idx), 3):
        lines.append("f " + " ".join(f"{i + 1}/{i + 1}/{i + 1}" for i in cube.idx[t:t + 3]))
    (out / "uv-cube.obj").write_text("\n".join(lines) + "\n")
    (out / "uv-cube.mtl").write_text(
        "newmtl uv-grid\nKa 0 0 0\nKd 1 1 1\nKs 0 0 0\nmap_Kd uv-grid.png\n")
    return [f"uv-cube.{ext}" for ext in ("glb", "gltf", "bin", "obj", "mtl")] + ["uv-grid.png"]


def write_pipe_valves(out, scratch):
    size, pixel = brushed_steel()
    write_png(scratch, size, size, pixel, row_filter=1)
    png = scratch.read_bytes()
    scratch.unlink()
    parts = pipe_valves()
    materials = {
        "brushed-steel": textured(0, 0.85, 0.38),
        # Safety yellow, as linear RGB.
        "valve-yellow": plain((1.0, 0.62, 0.02), 0.1, 0.45),
        "bolt": plain((0.08, 0.08, 0.09), 0.6, 0.5),
    }
    buf = bytearray()
    doc = gltf_document("pipe-valves", parts, materials, buf, png)
    write_glb(out / "pipe-valves.glb", doc, buf)
    for p in parts:
        print(f"  {p.name:<24} {p.material:<14} {p.triangles():>6} triangles")
    print(f"{len(parts)} parts, {sum(p.triangles() for p in parts)} triangles, "
          f"{len(materials)} materials, {len(png)} byte texture")
    return ["pipe-valves.glb"]


if __name__ == "__main__":
    out = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    out.mkdir(parents=True, exist_ok=True)
    written = write_uv_cube(out) + write_pipe_valves(out, out / ".brushed-steel.png")
    for name in written:
        print(f"  {name}: {(out / name).stat().st_size} bytes")
