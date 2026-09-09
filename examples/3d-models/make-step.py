#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Write a 1 m cube as a STEP AP214 manifold solid B-rep.

The fixture for the console viewer's CAD path: STEP is decoded by occt-import-js rather than by the
viewer's own parsers, so this is what exercises the vendored WASM decoder.

    python3 make-step.py [output-file]
"""
import sys

E = []


def e(text):
    E.append(text)
    return f"#{len(E)}"


# Corners of the unit cube, indexed by (x, y, z) bits.
C = [(x, y, z) for z in (0, 1) for y in (0, 1) for x in (0, 1)]


def idx(x, y, z):
    return z * 4 + y * 2 + x


pts = [e(f"CARTESIAN_POINT('',({float(x)},{float(y)},{float(z)}));") for x, y, z in C]
verts = [e(f"VERTEX_POINT('',{p});") for p in pts]

DIRS = {}
for d in [(1, 0, 0), (0, 1, 0), (0, 0, 1), (-1, 0, 0), (0, -1, 0), (0, 0, -1)]:
    DIRS[d] = e(f"DIRECTION('',({float(d[0])},{float(d[1])},{float(d[2])}));")

edges = {}


def edge(a, b):
    """An EDGE_CURVE from corner a to corner b, made once and reused by both faces."""
    key = (a, b)
    if key in edges:
        return edges[key]
    pa, pb = C[a], C[b]
    d = tuple(bb - aa for aa, bb in zip(pa, pb))
    n = max(abs(v) for v in d)
    unit = tuple(v / n for v in d)
    if unit not in DIRS:
        DIRS[unit] = e(f"DIRECTION('',({unit[0]},{unit[1]},{unit[2]}));")
    vec = e(f"VECTOR('',{DIRS[unit]},{float(n)});")
    line = e(f"LINE('',{pts[a]},{vec});")
    ec = e(f"EDGE_CURVE('',{verts[a]},{verts[b]},{line},.T.);")
    edges[key] = ec
    return ec


def face(loop_corners, normal, origin):
    """An ADVANCED_FACE whose outer bound walks loop_corners in order."""
    oriented = []
    for i in range(len(loop_corners)):
        a, b = loop_corners[i], loop_corners[(i + 1) % len(loop_corners)]
        if (a, b) in edges or (b, a) not in edges:
            oriented.append(e(f"ORIENTED_EDGE('',*,*,{edge(a, b)},.T.);"))
        else:
            oriented.append(e(f"ORIENTED_EDGE('',*,*,{edges[(b, a)]},.F.);"))
    loop = e(f"EDGE_LOOP('',({','.join(oriented)}));")
    bound = e(f"FACE_OUTER_BOUND('',{loop},.T.);")
    if normal not in DIRS:
        DIRS[normal] = e(f"DIRECTION('',({float(normal[0])},{float(normal[1])},{float(normal[2])}));")
    ref = (1.0, 0.0, 0.0) if normal[0] == 0 else (0.0, 0.0, 1.0)
    if ref not in DIRS:
        DIRS[ref] = e(f"DIRECTION('',({ref[0]},{ref[1]},{ref[2]}));")
    loc = e(f"CARTESIAN_POINT('',({float(origin[0])},{float(origin[1])},{float(origin[2])}));")
    ax = e(f"AXIS2_PLACEMENT_3D('',{loc},{DIRS[normal]},{DIRS[ref]});")
    plane = e(f"PLANE('',{ax});")
    return e(f"ADVANCED_FACE('',({bound}),{plane},.T.);")


# Six faces, each wound counter-clockwise seen from outside.
faces = [
    face([idx(0, 0, 0), idx(0, 1, 0), idx(1, 1, 0), idx(1, 0, 0)], (0, 0, -1), (0, 0, 0)),
    face([idx(0, 0, 1), idx(1, 0, 1), idx(1, 1, 1), idx(0, 1, 1)], (0, 0, 1), (0, 0, 1)),
    face([idx(0, 0, 0), idx(1, 0, 0), idx(1, 0, 1), idx(0, 0, 1)], (0, -1, 0), (0, 0, 0)),
    face([idx(0, 1, 0), idx(0, 1, 1), idx(1, 1, 1), idx(1, 1, 0)], (0, 1, 0), (0, 1, 0)),
    face([idx(0, 0, 0), idx(0, 0, 1), idx(0, 1, 1), idx(0, 1, 0)], (-1, 0, 0), (0, 0, 0)),
    face([idx(1, 0, 0), idx(1, 1, 0), idx(1, 1, 1), idx(1, 0, 1)], (1, 0, 0), (1, 0, 0)),
]

shell = e(f"CLOSED_SHELL('',({','.join(faces)}));")
brep = e(f"MANIFOLD_SOLID_BREP('cube',{shell});")

origin = e("CARTESIAN_POINT('',(0.,0.,0.));")
zdir = e("DIRECTION('',(0.,0.,1.));")
xdir = e("DIRECTION('',(1.,0.,0.));")
axis = e(f"AXIS2_PLACEMENT_3D('',{origin},{zdir},{xdir});")

unit_len = e("( LENGTH_UNIT() NAMED_UNIT(*) SI_UNIT(.MILLI.,.METRE.) );")
unit_ang = e("( NAMED_UNIT(*) PLANE_ANGLE_UNIT() SI_UNIT($,.RADIAN.) );")
unit_sol = e("( NAMED_UNIT(*) SI_UNIT($,.STERADIAN.) SOLID_ANGLE_UNIT() );")
unc = e(f"UNCERTAINTY_MEASURE_WITH_UNIT(LENGTH_MEASURE(1.E-07),{unit_len},'distance_accuracy_value','');")
ctx = e(f"(GEOMETRIC_REPRESENTATION_CONTEXT(3)"
        f"GLOBAL_UNCERTAINTY_ASSIGNED_CONTEXT(({unc}))"
        f"GLOBAL_UNIT_ASSIGNED_CONTEXT(({unit_len},{unit_ang},{unit_sol}))"
        f"REPRESENTATION_CONTEXT('',''));")
shape = e(f"ADVANCED_BREP_SHAPE_REPRESENTATION('',({axis},{brep}),{ctx});")

app_ctx = e("APPLICATION_CONTEXT('automotive design');")
e(f"APPLICATION_PROTOCOL_DEFINITION('','automotive_design',2000,{app_ctx});")
pctx = e("PRODUCT_CONTEXT('',%s,.mechanical.);" % app_ctx)
product = e("PRODUCT('cube','cube','',(%s));" % pctx)
pdf = e("PRODUCT_DEFINITION_FORMATION('','',%s);" % product)
pdctx = e("PRODUCT_DEFINITION_CONTEXT('',%s,.design.);" % app_ctx)
pd = e("PRODUCT_DEFINITION('design','',%s,%s);" % (pdf, pdctx))
pds = e(f"PRODUCT_DEFINITION_SHAPE('','',{pd});")
e(f"SHAPE_DEFINITION_REPRESENTATION({pds},{shape});")

body = "\n".join(f"#{i + 1}={t}" for i, t in enumerate(E))
out = f"""ISO-10303-21;
HEADER;
FILE_DESCRIPTION(('cube'),'2;1');
FILE_NAME('cut-cube.step','2026-01-01T00:00:00',(''),(''),'datahub make-examples.py','','');
FILE_SCHEMA(('AUTOMOTIVE_DESIGN {{ 1 0 10303 214 1 1 1 1 }}'));
ENDSEC;
DATA;
{body}
ENDSEC;
END-ISO-10303-21;
"""
path = sys.argv[1] if len(sys.argv) > 1 else "cube.step"
open(path, "w").write(out)
print(f"{path}: {len(out)} bytes, {len(E)} entities")
