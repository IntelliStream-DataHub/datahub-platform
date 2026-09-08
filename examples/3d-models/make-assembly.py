#!/usr/bin/env python3
"""Write a small industrial assembly: a flanged pipe run and a pair of meshing spur gears.

Where cut-cube exercises the format layer, this exercises what a real model brings with it: named
parts, several materials, curved surfaces with smooth normals, and enough triangles that a viewer
has to actually shade something.

    python3 make-assembly.py [output-dir]
"""
import base64
import json
import math
import pathlib
import struct
import sys

TAU = math.pi * 2


class Part:
    """A named triangle mesh with per-vertex normals, in assembly coordinates."""

    def __init__(self, name, material):
        self.name = name
        self.material = material
        self.pos = []
        self.nrm = []
        self.idx = []

    def quad(self, a, b, c, d, na, nb, nc, nd):
        base = len(self.pos)
        self.pos += [a, b, c, d]
        self.nrm += [na, nb, nc, nd]
        self.idx += [base, base + 1, base + 2, base, base + 2, base + 3]

    def tri(self, a, b, c, na, nb, nc):
        base = len(self.pos)
        self.pos += [a, b, c]
        self.nrm += [na, nb, nc]
        self.idx += [base, base + 1, base + 2]

    def triangles(self):
        return len(self.idx) // 3


def place(fn):
    """Apply a rotation matrix and offset to every point a builder emits."""
    def apply(part, rot, off):
        n = len(part.pos)
        for i in range(n):
            part.pos[i] = xf(rot, part.pos[i], off)
            part.nrm[i] = xf(rot, part.nrm[i], (0, 0, 0))
        return part
    return apply


def xf(rot, v, off):
    return tuple(sum(rot[r][c] * v[c] for c in range(3)) + off[r] for r in range(3))


ROT_X90 = [(1, 0, 0), (0, 0, -1), (0, 1, 0)]
ROT_ID = [(1, 0, 0), (0, 1, 0), (0, 0, 1)]


def ring(radius, z, segs):
    return [(radius * math.cos(TAU * i / segs), radius * math.sin(TAU * i / segs), z)
            for i in range(segs)]


def normals_of(points):
    out = []
    for x, y, _ in points:
        d = math.hypot(x, y) or 1.0
        out.append((x / d, y / d, 0.0))
    return out


def tube(part, r_out, r_in, z0, z1, segs=28):
    """A hollow cylinder: smooth outer and inner walls, flat annular caps."""
    outer0, outer1 = ring(r_out, z0, segs), ring(r_out, z1, segs)
    inner0, inner1 = ring(r_in, z0, segs), ring(r_in, z1, segs)
    no, ni = normals_of(outer0), [(-n[0], -n[1], 0.0) for n in normals_of(inner0)]
    for i in range(segs):
        j = (i + 1) % segs
        part.quad(outer0[i], outer0[j], outer1[j], outer1[i], no[i], no[j], no[j], no[i])
        part.quad(inner1[i], inner1[j], inner0[j], inner0[i], ni[i], ni[j], ni[j], ni[i])
        up, dn = (0.0, 0.0, 1.0), (0.0, 0.0, -1.0)
        part.quad(inner1[i], inner1[j], outer1[j], outer1[i], up, up, up, up)
        part.quad(outer0[i], outer0[j], inner0[j], inner0[i], dn, dn, dn, dn)
    return part


def elbow(part, bend, r_out, r_in, sweep, segs=20, ring_segs=20):
    """A quarter torus, the pipe bend. Sweeps in the XZ plane about the Y axis."""
    def point(t, a, r):
        ang = sweep * t
        cx, cz = bend * math.cos(ang), bend * math.sin(ang)
        rad = (math.cos(ang), 0.0, math.sin(ang))
        nrm = (rad[0] * math.cos(a), math.sin(a), rad[2] * math.cos(a))
        return ((cx + r * nrm[0], r * nrm[1], cz + r * nrm[2]), nrm)

    for i in range(segs):
        t0, t1 = i / segs, (i + 1) / segs
        for k in range(ring_segs):
            a0, a1 = TAU * k / ring_segs, TAU * (k + 1) / ring_segs
            (p00, n00), (p01, n01) = point(t0, a0, r_out), point(t0, a1, r_out)
            (p10, n10), (p11, n11) = point(t1, a0, r_out), point(t1, a1, r_out)
            part.quad(p00, p01, p11, p10, n00, n01, n11, n10)
            (q00, m00), (q01, m01) = point(t0, a0, r_in), point(t0, a1, r_in)
            (q10, m10), (q11, m11) = point(t1, a0, r_in), point(t1, a1, r_in)
            part.quad(q10, q11, q01, q00,
                      neg(m10), neg(m11), neg(m01), neg(m00))
    return part


def neg(v):
    return (-v[0], -v[1], -v[2])


def gear(part, teeth, r_root, r_tip, bore, z0, z1):
    """A spur gear: an extruded toothed profile with a central bore."""
    profile = []
    for i in range(teeth):
        base = TAU * i / teeth
        step = TAU / teeth
        for frac, r in ((0.00, r_root), (0.16, r_tip), (0.42, r_tip),
                        (0.58, r_root), (0.80, r_root)):
            ang = base + step * frac
            profile.append((r * math.cos(ang), r * math.sin(ang)))
    n = len(profile)
    bore_pts = [(bore * math.cos(TAU * i / n), bore * math.sin(TAU * i / n)) for i in range(n)]

    for i in range(n):
        j = (i + 1) % n
        (ax, ay), (bx, by) = profile[i], profile[j]
        ex, ey = bx - ax, by - ay
        d = math.hypot(ex, ey) or 1.0
        nr = (ey / d, -ex / d, 0.0)
        part.quad((ax, ay, z0), (bx, by, z0), (bx, by, z1), (ax, ay, z1), nr, nr, nr, nr)
        up, dn = (0.0, 0.0, 1.0), (0.0, 0.0, -1.0)
        (cx, cy), (dx, dy) = bore_pts[i], bore_pts[j]
        part.quad((cx, cy, z1), (dx, dy, z1), (bx, by, z1), (ax, ay, z1), up, up, up, up)
        part.quad((ax, ay, z0), (bx, by, z0), (dx, dy, z0), (cx, cy, z0), dn, dn, dn, dn)
        bn = (-cx / bore, -cy / bore, 0.0)
        bm = (-dx / bore, -dy / bore, 0.0)
        part.quad((cx, cy, z1), (dx, dy, z1), (dx, dy, z0), (cx, cy, z0), bn, bm, bm, bn)
    return part


def box(part, lo, hi):
    x0, y0, z0 = lo
    x1, y1, z1 = hi
    faces = [
        ([(x0, y0, z0), (x0, y1, z0), (x1, y1, z0), (x1, y0, z0)], (0, 0, -1)),
        ([(x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)], (0, 0, 1)),
        ([(x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1)], (0, -1, 0)),
        ([(x1, y1, z0), (x0, y1, z0), (x0, y1, z1), (x1, y1, z1)], (0, 1, 0)),
        ([(x0, y1, z0), (x0, y0, z0), (x0, y0, z1), (x0, y1, z1)], (-1, 0, 0)),
        ([(x1, y0, z0), (x1, y1, z0), (x1, y1, z1), (x1, y0, z1)], (1, 0, 0)),
    ]
    for pts, nrm in faces:
        part.quad(pts[0], pts[1], pts[2], pts[3], nrm, nrm, nrm, nrm)
    return part


MATERIALS = {
    "steel": (0.62, 0.66, 0.72),
    "flange": (0.44, 0.48, 0.55),
    "bronze": (0.72, 0.53, 0.26),
    "frame": (0.26, 0.28, 0.33),
}

move = place(None)


def build():
    """A 90 degree flanged pipe run over a base plate, driving two meshing gears."""
    parts = []

    # Pipe run: inlet along +X into a bend, then up in +Z. Radii in metres.
    r_out, r_in = 0.10, 0.082
    inlet = tube(Part("pipe-inlet", "steel"), r_out, r_in, 0.0, 0.55, segs=28)
    parts.append(move(inlet, [(0, 0, 1), (0, 1, 0), (-1, 0, 0)], (-0.75, 0.0, 0.62)))

    bend = elbow(Part("pipe-elbow", "steel"), 0.22, r_out, r_in, math.pi / 2)
    parts.append(move(bend, ROT_ID, (-0.20, 0.0, 0.62)))

    riser = tube(Part("pipe-riser", "steel"), r_out, r_in, 0.0, 0.42, segs=28)
    parts.append(move(riser, ROT_ID, (-0.20, 0.0, 0.84)))

    fl_a = tube(Part("flange-inlet", "flange"), 0.17, r_in, 0.0, 0.035, segs=28)
    parts.append(move(fl_a, [(0, 0, 1), (0, 1, 0), (-1, 0, 0)], (-0.75, 0.0, 0.62)))

    fl_b = tube(Part("flange-outlet", "flange"), 0.17, r_in, 0.0, 0.035, segs=28)
    parts.append(move(fl_b, ROT_ID, (-0.20, 0.0, 1.23)))

    # Two meshing spur gears on parallel axes, lying in the XZ plane.
    drive = gear(Part("gear-drive", "bronze"), 18, 0.30, 0.35, 0.06, -0.05, 0.05)
    parts.append(move(drive, ROT_X90, (0.45, 0.10, 0.72)))

    driven = gear(Part("gear-driven", "bronze"), 12, 0.20, 0.245, 0.05, -0.04, 0.04)
    parts.append(move(driven, ROT_X90, (1.04, 0.10, 0.72)))

    shaft_a = tube(Part("shaft-drive", "frame"), 0.055, 0.0, 0.0, 0.26, segs=24)
    parts.append(move(shaft_a, ROT_X90, (0.45, -0.16, 0.72)))

    shaft_b = tube(Part("shaft-driven", "frame"), 0.045, 0.0, 0.0, 0.24, segs=24)
    parts.append(move(shaft_b, ROT_X90, (1.04, -0.14, 0.72)))

    parts.append(box(Part("base-plate", "frame"), (-1.0, -0.30, 0.50), (1.35, 0.30, 0.56)))
    return parts


def write_glb(path, parts):
    """glTF with one node per part, each carrying its name and material."""
    mats = list(MATERIALS)
    buf = bytearray()
    accessors, views, meshes, nodes = [], [], [], []

    for part in parts:
        idx = struct.pack(f"<{len(part.idx)}I", *part.idx)
        pos = b"".join(struct.pack("<3f", *p) for p in part.pos)
        nrm = b"".join(struct.pack("<3f", *n) for n in part.nrm)
        offs = []
        for blob in (idx, pos, nrm):
            while len(buf) % 4:
                buf.append(0)
            offs.append(len(buf))
            buf += blob
        mins = [min(p[i] for p in part.pos) for i in range(3)]
        maxs = [max(p[i] for p in part.pos) for i in range(3)]
        base = len(accessors)
        views += [
            {"buffer": 0, "byteOffset": offs[0], "byteLength": len(idx), "target": 34963},
            {"buffer": 0, "byteOffset": offs[1], "byteLength": len(pos), "target": 34962},
            {"buffer": 0, "byteOffset": offs[2], "byteLength": len(nrm), "target": 34962},
        ]
        accessors += [
            {"bufferView": base, "componentType": 5125, "count": len(part.idx), "type": "SCALAR"},
            {"bufferView": base + 1, "componentType": 5126, "count": len(part.pos),
             "type": "VEC3", "min": mins, "max": maxs},
            {"bufferView": base + 2, "componentType": 5126, "count": len(part.nrm), "type": "VEC3"},
        ]
        meshes.append({"name": part.name, "primitives": [{
            "attributes": {"POSITION": base + 1, "NORMAL": base + 2},
            "indices": base, "material": mats.index(part.material)}]})
        nodes.append({"name": part.name, "mesh": len(meshes) - 1})

    gltf = {
        "asset": {"version": "2.0", "generator": "datahub make-assembly.py"},
        "scene": 0,
        "scenes": [{"name": "gear-pump", "nodes": list(range(len(nodes)))}],
        "nodes": nodes,
        "meshes": meshes,
        "materials": [{"name": m, "pbrMetallicRoughness": {
            "baseColorFactor": list(MATERIALS[m]) + [1.0],
            # Kept low deliberately: the viewer lights the scene without an environment map, and
            # a metallic surface with nothing to reflect renders black.
            "metallicFactor": 0.05,
            "roughnessFactor": 0.55}} for m in mats],
        "accessors": accessors,
        "bufferViews": views,
        "buffers": [{"byteLength": len(buf)}],
    }
    js = json.dumps(gltf, separators=(",", ":")).encode("utf-8")
    js += b" " * ((4 - len(js) % 4) % 4)
    body = bytes(buf) + b"\0" * ((4 - len(buf) % 4) % 4)
    chunks = (struct.pack("<II", len(js), 0x4E4F534A) + js
              + struct.pack("<II", len(body), 0x004E4942) + body)
    path.write_bytes(struct.pack("<III", 0x46546C67, 2, 12 + len(chunks)) + chunks)


def write_obj(path, parts):
    mtl = path.with_suffix(".mtl")
    lines = [f"mtllib {mtl.name}"]
    offset = 1
    for part in parts:
        lines.append(f"g {part.name}")
        lines.append(f"usemtl {part.material}")
        lines += [f"v {x:.4f} {y:.4f} {z:.4f}" for x, y, z in part.pos]
        lines += [f"vn {x:.3f} {y:.3f} {z:.3f}" for x, y, z in part.nrm]
        for t in range(0, len(part.idx), 3):
            a, b, c = (i + offset for i in part.idx[t:t + 3])
            lines.append(f"f {a}//{a} {b}//{b} {c}//{c}")
        offset += len(part.pos)
    path.write_text("\n".join(lines) + "\n")
    mtl.write_text("\n".join(
        f"newmtl {name}\nKd {r:.3f} {g:.3f} {b:.3f}\nKa 0.1 0.1 0.1\nKs 0.4 0.4 0.4\nNs 60\n"
        for name, (r, g, b) in MATERIALS.items()))


def write_stl(path, parts):
    tris = []
    for part in parts:
        for t in range(0, len(part.idx), 3):
            a, b, c = (part.pos[i] for i in part.idx[t:t + 3])
            n = part.nrm[part.idx[t]]
            tris.append((n, a, b, c))
    out = b"\0" * 80 + struct.pack("<I", len(tris))
    for n, a, b, c in tris:
        out += struct.pack("<3f", *n) + struct.pack("<3f", *a) + struct.pack("<3f", *b) \
            + struct.pack("<3f", *c) + struct.pack("<H", 0)
    path.write_bytes(out)


if __name__ == "__main__":
    out = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    out.mkdir(parents=True, exist_ok=True)
    parts = build()
    total = sum(p.triangles() for p in parts)
    write_glb(out / "gear-pump.glb", parts)
    write_obj(out / "gear-pump.obj", parts)
    write_stl(out / "gear-pump.stl", parts)
    for p in parts:
        print(f"  {p.name:<16} {p.material:<8} {p.triangles():>6} triangles")
    print(f"{len(parts)} parts, {total} triangles, {len(MATERIALS)} materials")
    for name in ("gear-pump.glb", "gear-pump.obj", "gear-pump.mtl", "gear-pump.stl"):
        print(f"  {name}: {(out / name).stat().st_size} bytes")
