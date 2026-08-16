#!/usr/bin/env python3
"""Rewrite PT_INTERP of an ELF64 aarch64 binary to point at an app-private loader path.

Usage: python patch_interp.py <binary> <new_interp_path>
The new interpreter string is appended at the end of the file, and the
PT_INTERP program header (p_offset/p_filesz) is updated accordingly.
"""
import struct
import sys


def patch(path, new_interp: str) -> None:
    with open(path, "r+b") as f:
        data = f.read()
        if len(data) < 64 or data[:4] != b"\x7fELF":
            raise SystemExit(f"not an ELF: {path}")
        ei_class = data[4]
        ei_data = data[5]
        if ei_class != 2 or ei_data != 1:
            raise SystemExit("only 64-bit little-endian ELF supported")

        e_phoff = struct.unpack_from("<Q", data, 32)[0]
        e_phentsize = struct.unpack_from("<H", data, 54)[0]
        e_phnum = struct.unpack_from("<H", data, 56)[0]

        patched = False
        for i in range(e_phnum):
            off = e_phoff + i * e_phentsize
            p_type = struct.unpack_from("<I", data, off)[0]
            if p_type != 3:  # PT_INTERP
                continue
            p_filesz = struct.unpack_from("<Q", data, off + 32)[0]
            old = data[struct.unpack_from("<Q", data, off + 8)[0]:
                        struct.unpack_from("<Q", data, off + 8)[0] + p_filesz].split(b"\0")[0]
            print(f"PT_INTERP: {old.decode()!r} -> {new_interp!r}")
            new_bytes = new_interp.encode() + b"\0"
            # append the new string at the end of the file
            f.seek(0, 2)
            new_off = f.tell()
            f.write(new_bytes)
            # update p_offset (offset 8) and p_filesz (offset 32)
            f.seek(off + 8)
            f.write(struct.pack("<Q", new_off))
            f.seek(off + 32)
            f.write(struct.pack("<Q", len(new_bytes)))
            patched = True
            break

        if not patched:
            raise SystemExit("no PT_INTERP found")
        print("patched OK")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: patch_interp.py <binary> <new_interp_path>")
    patch(sys.argv[1], sys.argv[2])
