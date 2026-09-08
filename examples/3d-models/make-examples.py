#!/usr/bin/env python3
"""Write one small example model per format the console's 3D viewer accepts.

Every file is the same shape, a 1 m cube with one corner cut off, so a format that loads wrong is
obvious at a glance rather than looking plausible. They are generated rather than collected so the
repository carries no third-party model licensing.

    python3 make-examples.py [output-dir]
"""
import base64
import json
import pathlib
import struct
import sys
import zipfile

# A unit cube with the +X+Y+Z corner truncated, giving one triangular face the others do not have.
V = [
    (0.0, 0.0, 0.0), (1.0, 0.0, 0.0), (1.0, 1.0, 0.0), (0.0, 1.0, 0.0),
    (0.0, 0.0, 1.0), (1.0, 0.0, 1.0), (0.0, 1.0, 1.0),
    (1.0, 0.6, 1.0), (1.0, 1.0, 0.6), (0.6, 1.0, 1.0),
]
F = [
    (0, 3, 2), (0, 2, 1),               # bottom
    (4, 5, 7), (4, 7, 9), (4, 9, 6),    # top, around the cut
    (0, 1, 5), (0, 5, 4),               # -Y
    (1, 2, 8), (1, 8, 7), (1, 7, 5),    # +X
    (2, 3, 6), (2, 6, 9), (2, 9, 8),    # +Y
    (3, 0, 4), (3, 4, 6),               # -X
    (7, 8, 9),                          # the cut corner
]


def write_stl(path):
    out = b"\0" * 80 + struct.pack("<I", len(F))
    for f in F:
        out += struct.pack("<3f", 0.0, 0.0, 0.0)
        for i in f:
            out += struct.pack("<3f", *V[i])
        out += struct.pack("<H", 0)
    path.write_bytes(out)


def write_obj(path):
    mtl = path.with_suffix(".mtl")
    lines = [f"mtllib {mtl.name}", "usemtl steel"]
    lines += [f"v {x} {y} {z}" for x, y, z in V]
    lines += ["f " + " ".join(str(i + 1) for i in f) for f in F]
    path.write_text("\n".join(lines) + "\n")
    mtl.write_text("newmtl steel\nKa 0.2 0.2 0.2\nKd 0.55 0.60 0.68\nKs 0.3 0.3 0.3\nNs 40\n")


def write_ply(path):
    head = ["ply", "format ascii 1.0", f"element vertex {len(V)}",
            "property float x", "property float y", "property float z",
            f"element face {len(F)}", "property list uchar int vertex_index", "end_header"]
    body = [f"{x} {y} {z}" for x, y, z in V] + ["3 " + " ".join(map(str, f)) for f in F]
    path.write_text("\n".join(head + body) + "\n")


def write_off(path):
    body = [f"{x} {y} {z}" for x, y, z in V] + ["3 " + " ".join(map(str, f)) for f in F]
    path.write_text("OFF\n" + f"{len(V)} {len(F)} 0\n" + "\n".join(body) + "\n")


def write_wrl(path):
    coords = ", ".join(f"{x} {y} {z}" for x, y, z in V)
    idx = ", ".join(" ".join(map(str, f)) + ", -1" for f in F)
    path.write_text(
        "#VRML V2.0 utf8\n"
        "Shape {\n"
        "  appearance Appearance { material Material { diffuseColor 0.55 0.60 0.68 } }\n"
        "  geometry IndexedFaceSet {\n"
        f"    coord Coordinate {{ point [ {coords} ] }}\n"
        f"    coordIndex [ {idx} ]\n"
        "  }\n"
        "}\n")


def write_amf(path):
    verts = "".join(
        f'<vertex><coordinates><x>{x}</x><y>{y}</y><z>{z}</z></coordinates></vertex>'
        for x, y, z in V)
    tris = "".join(f"<triangle><v1>{a}</v1><v2>{b}</v2><v3>{c}</v3></triangle>" for a, b, c in F)
    path.write_text(
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<amf unit="meter">\n'
        f'<object id="1"><mesh><vertices>{verts}</vertices>'
        f'<volume>{tris}</volume></mesh></object>\n'
        '</amf>\n')


def write_3mf(path):
    verts = "".join(f'<vertex x="{x}" y="{y}" z="{z}" />' for x, y, z in V)
    tris = "".join(f'<triangle v1="{a}" v2="{b}" v3="{c}" />' for a, b, c in F)
    model = (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<model unit="meter" xml:lang="en-US" '
        'xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">\n'
        f'<resources><object id="1" type="model"><mesh>'
        f'<vertices>{verts}</vertices><triangles>{tris}</triangles>'
        '</mesh></object></resources>\n'
        '<build><item objectid="1" /></build>\n'
        '</model>\n')
    content_types = (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml" />'
        '<Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml" />'
        '</Types>\n')
    rels = (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Target="/3D/3dmodel.model" Id="rel0" '
        'Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel" /></Relationships>\n')
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", content_types)
        z.writestr("_rels/.rels", rels)
        z.writestr("3D/3dmodel.model", model)


def write_dae(path):
    pos = " ".join(f"{c}" for v in V for c in v)
    idx = " ".join(str(i) for f in F for i in f)
    path.write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<COLLADA xmlns="http://www.collada.org/2005/11/COLLADASchema" version="1.4.1">\n'
        '<asset><contributor><authoring_tool>datahub make-examples.py</authoring_tool></contributor>'
        '<created>2026-01-01T00:00:00Z</created><modified>2026-01-01T00:00:00Z</modified>'
        '<unit meter="1" name="meter"/><up_axis>Z_UP</up_axis></asset>\n'
        '<library_geometries><geometry id="g1" name="cut_cube"><mesh>\n'
        f'<source id="g1-pos"><float_array id="g1-pos-a" count="{len(V) * 3}">{pos}</float_array>\n'
        f'<technique_common><accessor source="#g1-pos-a" count="{len(V)}" stride="3">'
        '<param name="X" type="float"/><param name="Y" type="float"/><param name="Z" type="float"/>'
        '</accessor></technique_common></source>\n'
        '<vertices id="g1-vtx"><input semantic="POSITION" source="#g1-pos"/></vertices>\n'
        f'<triangles count="{len(F)}"><input semantic="VERTEX" source="#g1-vtx" offset="0"/>'
        f'<p>{idx}</p></triangles>\n'
        '</mesh></geometry></library_geometries>\n'
        '<library_visual_scenes><visual_scene id="s1" name="scene">'
        '<node id="n1" name="cut_cube"><instance_geometry url="#g1"/></node>'
        '</visual_scene></library_visual_scenes>\n'
        '<scene><instance_visual_scene url="#s1"/></scene>\n'
        '</COLLADA>\n')


def _gltf_parts():
    """Shared buffer and JSON for the glTF and GLB variants."""
    idx = b"".join(struct.pack("<H", i) for f in F for i in f)
    idx += b"\0" * ((4 - len(idx) % 4) % 4)
    pos = b"".join(struct.pack("<3f", *v) for v in V)
    buf = idx + pos
    mins = [min(v[i] for v in V) for i in range(3)]
    maxs = [max(v[i] for v in V) for i in range(3)]
    gltf = {
        "asset": {"version": "2.0", "generator": "datahub make-examples.py"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"mesh": 0, "name": "cut_cube"}],
        "meshes": [{"primitives": [{"attributes": {"POSITION": 1}, "indices": 0}]}],
        "accessors": [
            {"bufferView": 0, "componentType": 5123, "count": len(F) * 3, "type": "SCALAR"},
            {"bufferView": 1, "componentType": 5126, "count": len(V), "type": "VEC3",
             "min": mins, "max": maxs},
        ],
        "bufferViews": [
            {"buffer": 0, "byteOffset": 0, "byteLength": len(idx), "target": 34963},
            {"buffer": 0, "byteOffset": len(idx), "byteLength": len(pos), "target": 34962},
        ],
        "buffers": [{"byteLength": len(buf)}],
    }
    return gltf, buf


def write_gltf(path):
    gltf, buf = _gltf_parts()
    gltf["buffers"][0]["uri"] = "data:application/octet-stream;base64," + \
        base64.b64encode(buf).decode("ascii")
    path.write_text(json.dumps(gltf, indent=1) + "\n")


def write_glb(path):
    gltf, buf = _gltf_parts()
    js = json.dumps(gltf, separators=(",", ":")).encode("utf-8")
    js += b" " * ((4 - len(js) % 4) % 4)
    body = (struct.pack("<II", len(js), 0x4E4F534A) + js
            + struct.pack("<II", len(buf), 0x004E4942) + buf)
    path.write_bytes(struct.pack("<III", 0x46546C67, 2, 12 + len(body)) + body)


WRITERS = {
    "cut-cube.stl": write_stl,
    "cut-cube.obj": write_obj,
    "cut-cube.ply": write_ply,
    "cut-cube.off": write_off,
    "cut-cube.wrl": write_wrl,
    "cut-cube.amf": write_amf,
    "cut-cube.3mf": write_3mf,
    "cut-cube.dae": write_dae,
    "cut-cube.gltf": write_gltf,
    "cut-cube.glb": write_glb,
}


if __name__ == "__main__":
    out = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    out.mkdir(parents=True, exist_ok=True)
    for name, writer in WRITERS.items():
        target = out / name
        writer(target)
        print(f"{target}: {target.stat().st_size} bytes")
