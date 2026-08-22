#!/usr/bin/env python3
"""Build the Alpine rootfs tarball embedded into the APK (proot container mode).

Streams the alpine-minirootfs tarball into a new .tar.gz without touching the
host filesystem, then appends the opencode binary, CA certificates and any
number of alpine .apk payloads (shared libraries / tools such as git).

Everything comes from the alpine "main" repo, so no local compilation
toolchain is required:

    --lib-apk FILE   merge everything under usr/lib/ into usr/lib/
                     (regular files + symlinks, layout preserved). Pass one
                     apk per shared-library package (zlib, libcurl, libssl3,
                     libgcc, libstdc++, pcre2, ...).
    --bin-apk FILE   merge usr/bin/ and usr/libexec/git-core/ into the rootfs
                     (e.g. the git apk, which ships git + its git-core
                     helpers and symlinks).

Usage:
  build_rootfs.py --minirootfs minirootfs.tar.gz --opencode opencode-binary \
                  --out rootfs.tar.gz [--lib-apk file.apk]... [--bin-apk file.apk]...
                  [--ca-apk file.apk]
"""
import argparse
import io
import os
import sys
import tarfile


def add_file(out: tarfile.TarFile, name: str, data: bytes, mode: int) -> None:
    info = tarfile.TarInfo(name)
    info.size = len(data)
    info.mode = mode
    info.mtime = 0
    out.addfile(info, io.BytesIO(data))


def apk_file(apk: str, want: str) -> bytes:
    """Read the first regular file inside the apk whose path ends with `want`."""
    with tarfile.open(apk, "r:*") as tar:
        for m in tar:
            if m.isfile() and m.name.endswith(want):
                f = tar.extractfile(m)
                if f is None:
                    raise SystemExit(f"{apk}: {m.name} not readable")
                return f.read()
    raise SystemExit(f"{apk}: file ending {want} not found")


def merge_dir(out: tarfile.TarFile, apk: str, prefixes: tuple, seen: set) -> None:
    """Copy every member under `prefixes` from `apk` into `out`, preserving
    regular files and symlinks (apks ship libfoo.so.1 -> libfoo.so.1.2.3
    symlinks that the dynamic loader needs)."""
    with tarfile.open(apk, "r:*") as tar:
        for m in tar:
            name = m.name[2:] if m.name.startswith("./") else m.name
            if not any(name.startswith(prefix) for prefix in prefixes):
                continue
            if not (m.isfile() or m.issym()):
                continue
            base = name.rsplit("/", 1)[-1]
            if base in seen:
                continue
            seen.add(base)
            if m.isfile():
                f = tar.extractfile(m)
                out.addfile(m, f)
            else:
                out.addfile(m)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--minirootfs", required=True)
    ap.add_argument("--opencode", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--lib-apk", action="append", default=[])
    ap.add_argument("--bin-apk", action="append", default=[])
    ap.add_argument("--ca-apk")
    # 额外软链, 格式 路径=目标 (相对 rootfs 根), 如 lib/libdl.so.2=libc.so.6。
    # 用于补齐包缺失的链接: gcompat 不带 libdl.so.2, 而 opencode 的 pty
    # 原生库 DT_NEEDED 里显式要求它 (glibc >= 2.34 已把 dl 并入 libc)。
    ap.add_argument("--symlink", action="append", default=[])
    args = ap.parse_args()

    with open(args.opencode, "rb") as f:
        opencode_bytes = f.read()
    print(f"opencode binary: {len(opencode_bytes)} bytes")

    seen = set()
    # 输出压缩方式由 --out 后缀决定: *.tar.gz 用 gzip, 否则纯 tar。
    # (Android AGP 打包 assets 时会把 .gz 资产解压改名, 所以 App 内固定用纯 .tar)
    mode = "w:gz" if args.out.endswith(".gz") else "w"
    kw = {"compresslevel": 9} if mode == "w:gz" else {}
    with tarfile.open(args.out, mode, **kw) as out_tar, \
            tarfile.open(args.minirootfs, "r:*") as in_tar:
        for m in in_tar:
            if m.isfile():
                f = in_tar.extractfile(m)
                out_tar.addfile(m, f)
            elif m.isdir() or m.issym() or m.islnk():
                out_tar.addfile(m)
            if m.isfile() or m.issym():
                seen.add(m.name.rsplit("/", 1)[-1])

        add_file(out_tar, "usr/local/bin/opencode", opencode_bytes, 0o755)

        for apk in args.bin_apk:
            print(f"merge bin: {os.path.basename(apk)}")
            merge_dir(out_tar, apk, ("./usr/bin/", "usr/bin/", "./usr/libexec/git-core/", "usr/libexec/git-core/"), seen)
        for apk in args.lib_apk:
            print(f"merge lib: {os.path.basename(apk)}")
            # gcompat 等包装在 /lib (非 usr/lib), 两种前缀都收
            merge_dir(out_tar, apk, ("./usr/lib/", "usr/lib/", "./lib/", "lib/"), seen)

        for spec in args.symlink:
            path, _, target = spec.partition("=")
            base = path.rsplit("/", 1)[-1]
            if base in seen:
                continue
            seen.add(base)
            info = tarfile.TarInfo(path)
            info.type = tarfile.SYMTYPE
            info.linkname = target
            info.mode = 0o777
            info.mtime = 0
            out_tar.addfile(info)
            print(f"symlink: {path} -> {target}")

        if args.ca_apk:
            cacert = apk_file(args.ca_apk, "etc/ssl/certs/ca-certificates.crt")
            print(f"ca-certificates.crt: {len(cacert)} bytes")
            add_file(out_tar, "etc/ssl/certs/ca-certificates.crt", cacert, 0o644)

        # /etc/resolv.conf is not shipped in minirootfs; write an empty one
        # (ServerManager bind-mounts the real one via proot -b).
        add_file(out_tar, "etc/resolv.conf", b"", 0o644)

    print(f"done: {args.out} ({os.path.getsize(args.out)} bytes)")


if __name__ == "__main__":
    main()