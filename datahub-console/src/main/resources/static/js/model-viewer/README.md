# Vendored: Online3DViewer

The 3D model viewer used by `/files/view/{externalId}`.

| | |
|---|---|
| Upstream | https://github.com/kovacsv/Online3DViewer |
| Version | 0.18.0 |
| License | MIT (`o3dv.license.md`) |
| Source | `o3dv.zip` from the 0.18.0 release, sha256 `866f3a58…4dfdf` |
| `o3dv.min.js` | sha256 `3b3bf3ad87a6bb697c001db40c0640674849b20e27c7e58abf29a64691c00b08` before patching |

**Patched**, by `patch-cdn-urls.py`, and only in that one respect. The zip also ships
environment-map images, which we do not use.

Kept out of `assets.gradle` for the same reason `graph-network/` is: running Closure over an
already-minified megabyte buys nothing. It loads from a plain `<script defer>` on the one template
that needs it.

## Formats that need an external decoder

Most formats are parsed by the bundle itself. Six are not, and 0.18.0 fetches their decoders from
**jsdelivr at runtime**, with the URLs baked into the minified file and no setting to redirect
them. The console is deployed on closed networks and should not pull third-party code into its own
origin, so `patch-cdn-urls.py` rewrites those four URLs to `libs/` and the decoders are served from
here like any other static asset:

| Format | Decoder | Vendored as |
|---|---|---|
| STEP, IGES, BREP, FCStd | `occt-import-js@0.0.22` | `occt-import-js.{js,wasm}`, `occt-import-js-worker.js` |
| IFC | `web-ifc@0.0.68` | `web-ifc-api-iife.js`, `web-ifc.wasm` |
| 3DM | `rhino3dm@8.17.0` | `rhino3dm.min.js`, `rhino3dm.wasm` |
| Draco-compressed glTF | `draco3d@1.5.7` | `draco_decoder_nodejs.min.js` |

Each is fetched only when a file of that type is opened, so `libs/` costs nothing until then. They
sit in one directory because each locates its own `.wasm` relative to its script URL.

The rewritten URLs are absolute, built from `location.origin` at runtime rather than written as
`/static/...`. The occt decoder runs in a worker created from a `blob:` URL, and a blob URL has an
opaque base, so a root-relative path is not resolvable there and `importScripts` rejects it.

The upstream text files ship with CRLF line endings and are stored here with LF, which is the only
other change made to any of them.

Everything else (OBJ, STL, PLY, 3MF, glTF/GLB, FBX, DAE, OFF, WRL, AMF, 3DS) is parsed by the
bundle with no extra request at all.

## Upgrading

1. Take `o3dv.zip` from the new release and replace `o3dv.min.js`.
2. Re-run `python3 patch-cdn-urls.py o3dv.min.js` and check it reports four rewrites. A different
   count means the upstream decoder set changed, so `libs/` needs revisiting.
3. Refresh the pinned versions in the table above if the release moved them.
