#!/usr/bin/env python3
"""Patch musl's hardcoded /etc/resolv.conf path in the opencode binary to an app-writable path.

Android has no /etc/resolv.conf, so musl (Bun's libc) fails DNS resolution entirely.
We relocate the path string into a gap in a readable PT_LOAD segment and repoint the
ADRP+ADD reference(s) to it. The APP then writes a real resolv.conf at that path.
"""
import struct
import sys

NEW_PATH = b"/data/user/0/com.opencode.android/files/opencode/resolv.conf\x00"


def main(path: str) -> None:
    with open(path, "r+b") as f:
        data = f.read()

        # ---- program headers ----
        e_phoff = struct.unpack_from("<Q", data, 32)[0]
        e_phentsize = struct.unpack_from("<H", data, 54)[0]
        e_phnum = struct.unpack_from("<H", data, 56)[0]
        segs = []
        for i in range(e_phnum):
            off = e_phoff + i * e_phentsize
            p_type = struct.unpack_from("<I", data, off)[0]
            if p_type == 1:  # PT_LOAD
                p_offset = struct.unpack_from("<Q", data, off + 8)[0]
                p_vaddr = struct.unpack_from("<Q", data, off + 16)[0]
                p_filesz = struct.unpack_from("<Q", data, off + 32)[0]
                p_flags = struct.unpack_from("<I", data, off + 4)[0]
                segs.append((p_offset, p_vaddr, p_filesz, p_flags))

        def off2vma(off):
            for po, pv, pz, pf in segs:
                if po <= off < po + pz:
                    return pv + (off - po)
            return None

        idx = data.find(b"/etc/resolv.conf")
        if idx < 0:
            raise SystemExit("resolv.conf string not found")
        old_vma = off2vma(idx)
        print(f"old resolv.conf path at vma 0x{old_vma:x}")

        # ---- find a gap (>= len(new_path)) at end of a readable segment ----
        read_segs = sorted([s for s in segs if s[3] & 4], key=lambda s: s[0])
        gap = None
        for i, s in enumerate(read_segs):
            end = s[0] + s[2]
            nxt = min((t[0] for t in read_segs[i + 1:]), default=None)
            if nxt and nxt - end >= len(NEW_PATH):
                gap = (end, nxt, s)
                break
        if not gap:
            raise SystemExit("no suitable gap found")
        gap_off, gap_end, seg = gap
        gap_vma = seg[1] + (gap_off - seg[0])
        print(f"gap at off {gap_off} ({gap_end - gap_off} bytes) vma 0x{gap_vma:x}")

        # ---- write new path string ----
        f.seek(gap_off)
        f.write(NEW_PATH)

        # ---- find and patch ADRP+ADD references to old_vma ----
        text_segs = [s for s in segs if s[3] & 1]
        patched = 0
        for po, pv, pz, pf in text_segs:
            i = 0
            while i < pz - 8:
                insn = struct.unpack_from("<I", data, po + i)[0]
                if (insn & 0x9F000000) == 0x90000000:  # ADRP
                    immhi = (insn >> 5) & 0x7FFFF
                    immlo = (insn >> 29) & 0x3
                    imm = (immhi << 2) | immlo
                    if imm & (1 << 20):
                        imm -= (1 << 21)
                    rd = insn & 0x1F
                    base = ((pv + i) & ~0xFFF) + (imm << 12)
                    for j in range(i + 4, min(i + 16, pz - 4), 4):
                        insn2 = struct.unpack_from("<I", data, po + j)[0]
                        if (insn2 & 0xFF000000) == 0x91000000:  # ADD imm
                            rn = (insn2 >> 5) & 0x1F
                            rd2 = insn2 & 0x1F
                            imm12 = (insn2 >> 10) & 0xFFF
                            if rn == rd and base + imm12 == old_vma:
                                new_page = gap_vma & ~0xFFF
                                insn_page = (pv + i) & ~0xFFF
                                imm21 = ((new_page - insn_page) >> 12) & 0x1FFFFF
                                adrp = 0x90000000 | ((imm21 & 3) << 29) | ((imm21 >> 2) << 5) | rd
                                add = 0x91000000 | ((gap_vma & 0xFFF) << 10) | (rd2 << 5) | rd2
                                f.seek(po + i)
                                f.write(struct.pack("<I", adrp))
                                f.seek(po + j)
                                f.write(struct.pack("<I", add))
                                patched += 1
                                print(f"  patched ref at vma 0x{pv + i:x} -> 0x{gap_vma:x}")
                                break
                i += 4
        if not patched:
            raise SystemExit("no reference patched")
        print(f"OK: patched {patched} reference(s)")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_resolv.py <binary>")
    main(sys.argv[1])
