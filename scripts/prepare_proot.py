#!/usr/bin/env python3
"""Extract proot + its loader and termux shared libraries from .deb packages.

Termux ships proot as a bionic-linked ELF plus a fully-static loader that proot
uses to trace guest processes. The .deb container is an `ar` archive wrapping a
data.tar.xz, so the `ar` header is parsed manually before handing the payload to
tarfile.

Output layout (intended for AGP jniLibs / assets):
  jniLibs/arm64-v8a/libproot.so              <- proot binary itself
  jniLibs/arm64-v8a/libproot-loader.so       <- static loader
  jniLibs/arm64-v8a/libproot-loader32.so     <- static loader (32-bit)
  assets/opencode/proot/libtalloc.so.2       <- libtalloc (SONAME lookup)
  assets/opencode/proot/libandroid-shmem.so  <- libandroid-shmem

Usage:
  prepare_proot.py <proot.deb> <libtalloc.deb> <libandroid-shmem.deb> \
                   <jniLibs-arm64-v8a-dir> <assets-proot-dir>
"""
import io
import os
import sys
import tarfile

AR_MAGIC = b"!<arch>\n"


def ar_data(deb: str) -> bytes:
    """Return the contents of the `data.tar.*` member of a .deb (ar) archive."""
    with open(deb, "rb") as f:
        head = f.read(8)
        if head != AR_MAGIC:
            raise SystemExit(f"{deb}: not an ar archive")
        while True:
            hdr = f.read(60)
            if len(hdr) < 60:
                raise SystemExit(f"{deb}: corrupt ar header")
            name = hdr[0:16].decode("ascii", "replace").rstrip()
            try:
                size = int(hdr[48:58].decode("ascii", "replace").strip())
            except ValueError:
                raise SystemExit(f"{deb}: bad member size")
            data = f.read(size)
            if size % 2 == 1:
                f.read(1)  # ar pads members to even byte count
            if name.startswith("data.tar"):
                return data
    raise SystemExit(f"{deb}: no data.tar member")


def deb_member(deb: str, want: str):
    """Yield (name, fileobj) for the tar member(s) of `deb` whose path ends
    with `want`, or whose basename starts with the basename of `want`
    (handles versioned filenames like libtalloc.so.2.4.3)."""
    want_base = os.path.basename(want)
    data = ar_data(deb)
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:*") as tar:
        for m in tar:
            if not m.isfile():
                continue
            base = os.path.basename(m.name)
            if m.name.endswith(want) or (base.startswith(want_base) and want_base):
                f = tar.extractfile(m)
                if f is not None:
                    yield m.name, f.read()


def take(deb: str, want: str) -> bytes:
    for _, data in deb_member(deb, want):
        return data
    raise SystemExit(f"{deb}: member ending {want} not found")


def write(dst: str, data: bytes, mode: int) -> None:
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(dst, "wb") as f:
        f.write(data)
    os.chmod(dst, mode)


def main() -> None:
    if len(sys.argv) != 6:
        raise SystemExit(__doc__)
    proot_deb, talloc_deb, shmem_deb, jni_dir, asset_dir = sys.argv[1:]

    proot = take(proot_deb, "usr/bin/proot")
    loader = take(proot_deb, "usr/libexec/proot/loader")
    loader32 = take(proot_deb, "usr/libexec/proot/loader32")
    print(f"proot {len(proot)}B, loader {len(loader)}B, loader32 {len(loader32)}B")

    # Real file inside libtalloc deb is libtalloc.so.2.4.3; it must keep the
    # SONAME libtalloc.so.2 on disk because the linker resolves by SONAME.
    talloc = take(talloc_deb, "libtalloc.so.2")
    shmem = take(shmem_deb, "libandroid-shmem.so")
    print(f"libtalloc.so.2 {len(talloc)}B, libandroid-shmem.so {len(shmem)}B")

    write(os.path.join(jni_dir, "libproot.so"), proot, 0o755)
    write(os.path.join(jni_dir, "libproot-loader.so"), loader, 0o755)
    write(os.path.join(jni_dir, "libproot-loader32.so"), loader32, 0o755)
    write(os.path.join(asset_dir, "libtalloc.so.2"), talloc, 0o755)
    write(os.path.join(asset_dir, "libandroid-shmem.so"), shmem, 0o755)

    print(f"done: {jni_dir}, {asset_dir}")


if __name__ == "__main__":
    main()