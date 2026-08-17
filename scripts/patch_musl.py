#!/usr/bin/env python3
"""Patch musl libc's /etc/resolv.conf and /etc/hosts paths to app-writable paths.

Android has no /etc/resolv.conf, so musl (opencode's libc) fails DNS resolution
entirely and reports bogus "Unable to connect" / "typo in url or port" errors.
This relocates the path strings into free space at the end of a readable segment
and repoints every ADRP+ADD reference to them. The APP then writes a real
resolv.conf at the new path.
"""
import struct
import sys

NEW_PATHS = {
    b"/etc/resolv.conf": b"/data/user/0/com.opencode.android/files/opencode/resolv.conf\x00",
    b"/etc/hosts": b"/data/user/0/com.opencode.android/files/opencode/hosts\x00",
}


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
            if struct.unpack_from("<I", data, off)[0] == 1:  # PT_LOAD
                segs.append((
                    struct.unpack_from("<Q", data, off + 8)[0],   # p_offset
                    struct.unpack_from("<Q", data, off + 16)[0],  # p_vaddr
                    struct.unpack_from("<Q", data, off + 32)[0],  # p_filesz
                    struct.unpack_from("<I", data, off + 4)[0],   # p_flags
                ))

        def off2vma(off):
            for po, pv, pz, pf in segs:
                if po <= off < po + pz:
                    return pv + (off - po)
            return None

        # ---- find free space: tail of .rodata section within its PT_LOAD ----
        e_shoff = struct.unpack_from("<Q", data, 40)[0]
        e_shentsize = struct.unpack_from("<H", data, 58)[0]
        e_shnum = struct.unpack_from("<H", data, 60)[0]
        e_shstrndx = struct.unpack_from("<H", data, 62)[0]
        need = sum(len(v) for v in NEW_PATHS.values())
        gap = None
        if e_shoff and e_shstrndx < e_shnum:
            shstr_off = e_shoff + e_shstrndx * e_shentsize
            st_off = struct.unpack_from("<Q", data, shstr_off + 24)[0]
            st_size = struct.unpack_from("<Q", data, shstr_off + 32)[0]
            shstr = data[st_off:st_off + st_size]
            rodata = None
            for i in range(e_shnum):
                s = e_shoff + i * e_shentsize
                nm = struct.unpack_from("<I", data, s + 0)[0]
                name = shstr[nm:shstr.find(b"\0", nm)]
                if name == b".rodata":
                    rodata = (struct.unpack_from("<Q", data, s + 24)[0],  # sh_offset
                              struct.unpack_from("<Q", data, s + 32)[0])  # sh_size
                    break
            if rodata:
                ro_off, ro_size = rodata
                ro_end = ro_off + ro_size
                seg = None
                for po, pv, pz, pf in segs:
                    if po <= ro_end < po + pz and ro_end + need <= po + pz:
                        seg = (po, pv, pz, pf)
                        break
                if seg:
                    gap = (ro_end, seg)
        if not gap:
            # fallback: gap at end of a readable segment (before next segment)
            read_segs = sorted([s for s in segs if s[3] & 4], key=lambda s: s[0])
            for i, s in enumerate(read_segs):
                end = s[0] + s[2]
                nxt = min((t[0] for t in read_segs[i + 1:]), default=None)
                if nxt and nxt - end >= need:
                    gap = (end, s)
                    break
        if not gap:
            raise SystemExit("no usable gap found")
        gap_off, seg = gap
        gap_vma = seg[1] + (gap_off - seg[0])
        print(f"gap: file off {gap_off} vma 0x{gap_vma:x}")

        text_segs = [s for s in segs if s[3] & 1]
        cursor = gap_off
        for needle, new_path in NEW_PATHS.items():
            idx = data.find(needle)
            if idx < 0:
                print(f"skip {needle.decode()}: not found")
                continue
            old_vma = off2vma(idx)
            f.seek(cursor)
            f.write(new_path)
            new_vma = gap_vma + (cursor - gap_off)
            print(f"{needle.decode()} @ 0x{old_vma:x} -> new path @ 0x{new_vma:x} ({len(new_path)}B)")

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
                                    new_page = new_vma & ~0xFFF
                                    insn_page = (pv + i) & ~0xFFF
                                    imm21 = ((new_page - insn_page) >> 12) & 0x1FFFFF
                                    adrp = 0x90000000 | ((imm21 & 3) << 29) | ((imm21 >> 2) << 5) | rd
                                    add = 0x91000000 | ((new_vma & 0xFFF) << 10) | (rd2 << 5) | rd2
                                    f.seek(po + i)
                                    f.write(struct.pack("<I", adrp))
                                    f.seek(po + j)
                                    f.write(struct.pack("<I", add))
                                    patched += 1
                                    break
                    i += 4
            if not patched:
                raise SystemExit(f"no reference patched for {needle.decode()}")
            cursor += len(new_path)
        print("OK")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_musl.py <ld-musl-aarch64.so.1>")
    main(sys.argv[1])
