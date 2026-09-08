#!/usr/bin/env python3
"""Write a minimal AVEVA RVM file holding one box primitive.

Built from the chunk layout rvmparser reads (src/ParserRVM.cpp), so it exercises the converter
without needing a real PDMS export. It proves the pipeline, not compatibility with AVEVA's writer.
"""
import struct
import sys


def chunk_id(name):
    # Each of the four characters is stored as a big-endian word: 00 00 00 <char>.
    return b"".join(struct.pack(">I", ord(c)) for c in name)


def string(s):
    # A length in 4-byte words, then that many words, NUL padded.
    if not s:
        return struct.pack(">I", 0)
    padded = s.encode("ascii")
    words = (len(padded) + 3) // 4
    return struct.pack(">I", words) + padded.ljust(words * 4, b"\0")


def f32(*vals):
    return b"".join(struct.pack(">f", v) for v in vals)


def build():
    head = struct.pack(">I", 1) + string("") + string("") + string("") + string("")
    modl = struct.pack(">I", 1) + string("datahub") + string("test")
    cntb = struct.pack(">I", 2) + string("BOX1") + f32(0.0, 0.0, 0.0) + struct.pack(">I", 0)
    prim = (struct.pack(">I", 1) + struct.pack(">I", 2)          # version, kind 2 = box
            + f32(1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0)            # M_3x4, identity
            + f32(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5)               # local bbox
            + f32(1.0, 1.0, 1.0))                                # box lengths
    cnte = struct.pack(">I", 1)

    out = bytearray()
    for name, payload in [("HEAD", head), ("MODL", modl), ("CNTB", cntb),
                          ("PRIM", prim), ("CNTE", cnte)]:
        # next_chunk_offset is the absolute offset where this chunk's payload ends.
        end = len(out) + 24 + len(payload)
        out += chunk_id(name) + struct.pack(">II", end, 0) + payload
    out += chunk_id("END:") + struct.pack(">II", len(out) + 24, 0)
    return bytes(out)


if __name__ == "__main__":
    data = build()
    path = sys.argv[1] if len(sys.argv) > 1 else "box.rvm"
    with open(path, "wb") as fh:
        fh.write(data)
    print(f"{path}: {len(data)} bytes")
