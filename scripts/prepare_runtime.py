#!/usr/bin/env python3
"""Extract real shared libraries from alpine .apk (tar.gz) into outdir.

apk 内的 .so 很多是符号链接, gradle tarTree / Windows tar 处理不可靠,
这里用 python tarfile 直接找常规文件并按目标名落盘。

用法:
  prepare_runtime.py <libgcc.apk> <libstdcpp.apk> <ca-cert.apk> <outdir>
"""
import os
import shutil
import sys
import tarfile


def extract_real(apk: str, outdir: str, wanted: dict):
    """wanted: {name_prefix: target_filename}"""
    t = tarfile.open(apk, "r:gz")
    try:
        for m in t.getmembers():
            if not m.isfile():
                continue
            base = os.path.basename(m.name)
            for prefix, target in wanted.items():
                if base.startswith(prefix):
                    out = os.path.join(outdir, target)
                    src = t.extractfile(m)
                    with open(out, "wb") as f:
                        shutil.copyfileobj(src, f)
                    print(f"{base} -> {target}")
    finally:
        t.close()


def main():
    if len(sys.argv) != 5:
        raise SystemExit("usage: prepare_runtime.py <libgcc.apk> <libstdcpp.apk> <ca-cert.apk> <outdir>")
    gcc_apk, stdcpp_apk, ca_apk, outdir = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
    os.makedirs(outdir, exist_ok=True)
    # libgcc 主包: libgcc_s.so.1 为常规文件
    extract_real(gcc_apk, outdir, {"libgcc_s.so.1": "libgcc_s.so.1"})
    # libstdc++ 主包: libstdc++.so.6.0.34 为常规文件
    extract_real(stdcpp_apk, outdir, {"libstdc++.so.6": "libstdc++.so.6"})
    # ca-certificates 包: /etc/ssl/certs/ca-certificates.crt
    extract_real(ca_apk, outdir, {"ca-certificates.crt": "ca-certificates.crt"})
    missing = [n for n in ("libgcc_s.so.1", "libstdc++.so.6", "ca-certificates.crt")
               if not os.path.exists(os.path.join(outdir, n))]
    if missing:
        raise SystemExit(f"missing extracted files: {missing}")


if __name__ == "__main__":
    main()
