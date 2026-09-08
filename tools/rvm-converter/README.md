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

**It does not prove compatibility with AVEVA's writer.** The fixture is derived from the same
parser it feeds, so it validates the plumbing and nothing about how a real PDMS export is shaped.
Confirming that needs a real model.
