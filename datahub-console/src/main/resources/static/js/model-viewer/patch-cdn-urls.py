#!/usr/bin/env python3
"""Point the vendored viewer at our own decoder copies instead of a public CDN.

Online3DViewer 0.18.0 hardcodes jsdelivr URLs for the decoders that STEP, IGES, BREP, FCStd, IFC,
3DM and Draco-compressed glTF need, and offers no setting to redirect them. The console is deployed
on closed networks and should not pull third-party code into its own origin, so the URLs are
rewritten to libs/ next to the bundle.

Re-run after replacing o3dv.min.js with a new upstream release, then check the reported count.

    python3 patch-cdn-urls.py o3dv.min.js
"""
import pathlib
import re
import sys

LIBS = "/static/js/model-viewer/libs/"

# The whole string literal, quotes included, so the replacement can be an expression.
CDN = re.compile(r"\"https://cdn\.jsdelivr\.net/npm/[^\"]*/([^\"/]*)\"")


def replacement(match: "re.Match[str]") -> str:
    # Absolute, not a root-relative path. The occt decoder is loaded by a worker built from a
    # blob: URL, which has an opaque base, so "/static/..." is not a resolvable URL there.
    return f'(location.origin+"{LIBS}{match.group(1)}")'


def main() -> int:
    target = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "o3dv.min.js")
    source = target.read_text(encoding="utf-8")
    patched, count = CDN.subn(replacement, source)
    if count == 0:
        print(f"{target}: no CDN urls found, already patched?")
        return 1
    target.write_text(patched, encoding="utf-8")
    print(f"{target}: rewrote {count} cdn url(s) to {LIBS}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
