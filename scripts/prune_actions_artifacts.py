"""Keep the newest Actions artifact for each given name and delete older copies.

Prints ASCII only. GitHub Windows logs use cp1252.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

API = "https://api.github.com"


def request(token: str, method: str, url: str):
    req = urllib.request.Request(
        url,
        method=method,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "User-Agent": "prune-actions-artifacts",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    try:
        with urllib.request.urlopen(req) as resp:
            raw = resp.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", "replace")
        raise SystemExit(f"{method} {url} -> {exc.code} {body}") from exc


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--name", action="append", required=True)
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""))
    args = parser.parse_args()
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN") or ""
    if not token:
        sys.exit("missing GH_TOKEN")
    if not args.repo:
        sys.exit("missing GITHUB_REPOSITORY")

    wanted = set(args.name)
    grouped: dict[str, list[dict]] = {name: [] for name in wanted}
    page = 1
    while True:
        payload = request(
            token,
            "GET",
            f"{API}/repos/{args.repo}/actions/artifacts?per_page=100&page={page}",
        )
        arts = (payload or {}).get("artifacts") or []
        if not arts:
            break
        for art in arts:
            name = art.get("name") or ""
            if name in wanted and not art.get("expired"):
                grouped[name].append(art)
        if len(arts) < 100:
            break
        page += 1

    deleted = 0
    for name, items in grouped.items():
        items.sort(
            key=lambda art: (art.get("created_at") or "", int(art.get("id") or 0)),
            reverse=True,
        )
        if not items:
            print(f"keep none for {name}")
            continue
        keep = items[0]
        print(f"keep {keep['id']} {name} {keep.get('created_at')}")
        for art in items[1:]:
            print(f"delete {art['id']} {name} {art.get('created_at')}")
            request(token, "DELETE", f"{API}/repos/{args.repo}/actions/artifacts/{art['id']}")
            deleted += 1
    print(f"deleted {deleted}")


if __name__ == "__main__":
    main()
