# Vendored: Online3DViewer

The 3D model viewer used by `/files/view/{externalId}`.

| | |
|---|---|
| Upstream | https://github.com/kovacsv/Online3DViewer |
| Version | 0.18.0 |
| License | MIT (`o3dv.license.md`) |
| Source | `o3dv.zip` from the 0.18.0 release, sha256 `866f3a58…4dfdf` |
| `o3dv.min.js` | sha256 `3b3bf3ad87a6bb697c001db40c0640674849b20e27c7e58abf29a64691c00b08` |

Unmodified. The zip also ships environment-map images, which we do not use.

Kept out of `assets.gradle` for the same reason `graph-network/` is: running Closure over an
already-minified megabyte buys nothing. It loads from a plain `<script defer>` on the one template
that needs it.

## Formats that need an external decoder

Most formats are parsed by the bundle itself. Six are not, and 0.18.0 fetches their decoders from
**jsdelivr at runtime**, with the URLs baked into the minified file and no setting to redirect them:

| Format | Decoder | Size |
|---|---|---|
| STEP, IGES, BREP, FCStd | `occt-import-js@0.0.22` | 7.8 MB |
| IFC | `web-ifc@0.0.68` | 6.7 MB |
| 3DM | `rhino3dm@8.17.0` | 2.7 MB |
| Draco-compressed glTF | `draco3d@1.5.7` | 59 KB |

That means those formats need outbound internet from the **browser**, and fetch third-party code
into the console's origin. Everything else (OBJ, STL, PLY, 3MF, glTF/GLB, FBX, DAE, OFF, WRL, AMF,
3DS, BIM) parses offline with no external request.

To make the CAD formats work offline, vendor the decoders next to this file and rewrite the four
CDN URLs in `o3dv.min.js`. That makes this a patched copy rather than an upstream artefact, so
record the patch here if it is done.
