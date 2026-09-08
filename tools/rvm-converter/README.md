# RVM converter

Turns an AVEVA PDMS/E3D **RVM** model into glTF, so the console's 3D viewer can open it. One
statically linked binary, meant to be invoked as a child process and to hand its output back on
stdout.

```bash
./build.sh                 # -> build/rvm-converter, ~2.9 MB, no runtime dependencies
```

Upstream is [rvmparser](https://github.com/cdyk/rvmparser) (MIT), pinned in `build.sh`, plus
`0001-glb-to-stdout.patch`. It reads RVM geometry and the `.att` / `.txt` attribute sidecars,
tessellates the PDMS primitives, and writes glTF or GLB.

## Why a patch

Upstream picks its output container from the **path suffix** and has no way to say "write a GLB to
stdout": `--output-gltf=/dev/stdout` is rejected before anything is written, because `/dev/stdout`
has no `.glb` suffix. The patch adds `-` as an output path meaning exactly that, in 21 lines.

Nothing else was needed. Upstream already writes the GLB container as a single forward pass of
`fwrite` with no seeking, so a pipe is a valid destination, and **all of its logging already goes
to stderr**, so the stdout stream carries nothing but the container.

## The contract for the calling process

```
rvm-converter --output-gltf=- model.rvm [model.att]
  stdout : the GLB, and nothing else
  stderr : progress and statistics, one line each
  exit   : 0 on success
```

Two things a caller has to get right:

- **Read stdout and stderr concurrently.** The converter is chatty on stderr, roughly 30 lines for
  a trivial model and far more for a real one. A caller that drains stdout to completion first will
  deadlock once the stderr pipe buffer fills.
- **Do not merge the streams.** `redirectErrorStream(true)` corrupts the GLB with log text.

Useful flags for plant-scale input, all documented by `--help`: `--output-gltf-center` for models
far from the origin, `--keep-regex` and `--discard-groups` to prune the hierarchy, and the
tessellation tolerance for trading triangles against fidelity.

## Test fixture

`testdata/make_rvm.py` writes a minimal RVM holding one box, built from the chunk layout the parser
reads. Enough to exercise the pipeline end to end without a real export:

```bash
python3 testdata/make_rvm.py box.rvm
build/rvm-converter --output-gltf=- box.rvm > box.glb    # 1 mesh, 24 vertices, 12 triangles
```

It validates the plumbing, not compatibility with AVEVA's writer, since it is derived from the same
parser it feeds. For that, see below.

## Verified against a real model

Equinor publishes part of the **Huldra** platform model under an open data sharing licence, in
[equinor/rvmsharp](https://github.com/equinor/rvmsharp) under `TestSamples/Huldra`, with the full
dataset at [data.equinor.com](https://data.equinor.com). It is a genuine as-built North Sea
platform model, written by **AVEVA Everything3D Design Mk2.1**, with its attribute sidecar.

Not vendored here: it is 3.6 MB and carries its own licence. Download it to re-run this.

```
rvm-converter --output-gltf=- WD1-PSUP.RVM WD1-PSUP.txt > huldra.glb

Successfully parsed WD1-PSUP.RVM
Successfully parsed WD1-PSUP.txt
Tessellated 2628 items into 62994 vertices and 36220 triangles   (2 ms)
  Boxes 936   Cylinders 624   Facet groups 334
```

35 ms wall clock for the whole run, and the console's viewer opens the 4.9 MB result in 0.7 s as
2185 meshes.

**The attributes are the part that matters.** 4084 of 4087 glTF nodes come through carrying
`extras.rvm-attributes` straight from PDMS:

```json
{ "Name": "/PS/WD1/0001/F1", "RefNo": "=41397/38", "Type": "STRU",
  "Position": "E 16565mm N 20300mm U 35450mm", "Description": "HO-WF-712011",
  "Matref": "/316L", "Description of Matref": "AISI 316L",
  "Spref": "/EZ-PRF/L100x10", "Discipline": "PSUP" }
```

Tag, discipline, material specification, profile and position, per part, with the node names
preserving the PDMS hierarchy (`SCTN 1 of FRMWORK 1 of STRUCTURE /PS/WD1/0001/F2`). That is the
join between geometry and the resource graph, and it survives the conversion intact.

Two things this exposed, both about presentation rather than data:

- The converter emits **one material for the whole model**, so everything is one colour.
- The viewer lights without an environment map, so the result renders very dark. Real plant models
  make this much more obvious than a test cube does.
