#!/usr/bin/env python3
"""Clone the vendored upstream repositories into perception/third_party/ at pinned commits.

The perception blocks import a few upstream model definitions straight from source (sys.path / synthetic
packages, nothing is pip-installed and nothing is modified). This script recreates that folder on a fresh
clone of this repo:

    python scripts/fetch_third_party.py              # all repos (about 160 MB download, 0.33 GB on disk)
    python scripts/fetch_third_party.py --minimal    # only what perception/config_realtime.yaml needs
    python scripts/fetch_third_party.py PIDNet openpilot
    python scripts/fetch_third_party.py --check      # verify only, exit 1 if anything is missing / at another commit
    python scripts/fetch_third_party.py --list       # print the pinned table (repo, commit, licence, user)

Run from perception_engine/ (any Python 3.9+, stdlib only; needs `git` on PATH). The destination is
perception/third_party/ unless PERCEPTION_THIRD_PARTY_DIR or --dest says otherwise.

How: `git init` + `git fetch --depth 1 origin <sha>` (GitHub serves any commit by SHA), then a detached
checkout, so each repo holds exactly one commit. openpilot is a partial clone (--filter=blob:none) with a
non-cone sparse checkout of the handful of files the openpilot block uses; Git LFS smudge is disabled, so
its .onnx files are never downloaded (scripts/download_models.py fetches the one model it needs). If a
server refuses fetch-by-SHA, the script falls back to a full clone + checkout.

Idempotent: a repo that is already at its pinned commit (and, for openpilot, has the pinned sparse
patterns) is left alone. A repo at another commit is moved to the pinned one unless it has local changes
(then it is reported and skipped; --force discards them). A non-git folder of the same name is never
touched unless --force is given (it is then renamed to <name>.bak-<time>, not deleted).

Licences are summarised in the table below and written to <dest>/THIRD_PARTY_NOTICES.md next to the
checkouts; each upstream LICENSE file stays in its checkout. Engineering summary, not legal advice.
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]           # perception_engine/
DEFAULT_DEST = PROJECT_ROOT / "perception" / "third_party"

# openpilot v0.11.1 files used by perception/openpilot (tests_geometry.py compares op_geometry.py's numpy
# ports against common/transformations + selfdrive/modeld/constants.py; the rest is reference for the
# ports: model output parsing, calibration filter, on-road renderer).
OPENPILOT_SPARSE = [
    "/selfdrive/modeld/*.py",
    "/selfdrive/modeld/models/README.md",
    "/common/transformations/*.py",
    "/common/transformations/README.md",
    "/cereal/log.capnp",
    "/LICENSE",
    "/RELEASES.md",
    "/selfdrive/locationd/calibrationd.py",
    "/selfdrive/ui/onroad/model_renderer.py",
    "/selfdrive/ui/onroad/augmented_road_view.py",
]

REPOS = [
    {"name": "Depth-Anything-3", "url": "https://github.com/ByteDance-Seed/Depth-Anything-3.git",
     "sha": "3d835ec1a5802d64a8b8b15f817a1ab54809bfe4", "licence": "Apache-2.0", "minimal": True,
     "used_by": "depth: DA3 network code (src/depth_anything_3/model/...) for the da3_metric_large backend "
                "(realtime default)"},
    {"name": "addict", "url": "https://github.com/mewwts/addict.git",
     "sha": "75284f9593dfb929cadd900aff9e35e7c7aec54b", "licence": "MIT", "minimal": True,
     "used_by": "depth: addict.Dict, imported by the DA3 model code"},
    {"name": "TwinLiteNetPlus", "url": "https://github.com/chequanghuy/TwinLiteNetPlus.git",
     "sha": "90f1b8695ae311d5123b05f8534b2e11e42499d2", "licence": "MIT", "minimal": True,
     "used_by": "lanes: model/model.py + model/config.py for the twinlitenetplus_* backends (realtime default)"},
    {"name": "PIDNet", "url": "https://github.com/XuJiacong/PIDNet.git",
     "sha": "4c158cf24ce432f0a8cb43364fae38d93cee0dc3", "licence": "MIT", "minimal": False,
     "used_by": "segmentation: models/pidnet.py + models/model_utils.py (pidnet_s backend)"},
    {"name": "efficientvit", "url": "https://github.com/mit-han-lab/efficientvit.git",
     "sha": "de7d7733cc0329f391b33f1f459271562ec27bd5", "licence": "Apache-2.0", "minimal": False,
     "used_by": "segmentation: efficientvit/models/{efficientvit,nn,utils} (efficientvit_b0/b1/b2 backends)"},
    {"name": "openpilot", "url": "https://github.com/commaai/openpilot.git",
     "sha": "4df40d2c1946a57242230186edd073c4073060a6", "tag": "v0.11.1", "licence": "MIT", "minimal": False,
     "sparse": OPENPILOT_SPARSE,
     "used_by": "openpilot (experimental block): reference sources for op_geometry.py and tests_geometry.py; "
                "sparse, no LFS"},
]


def log(msg: str) -> None:
    print(msg, flush=True)


def git(*args: str, cwd: Path | None = None, check: bool = True, quiet: bool = False) -> subprocess.CompletedProcess:
    env = dict(os.environ, GIT_LFS_SKIP_SMUDGE="1", GIT_TERMINAL_PROMPT="0")
    cmd = ["git", "-c", "advice.detachedHead=false", *args]
    if not quiet:
        log(f"    $ git {' '.join(args)}")
    return subprocess.run(cmd, cwd=str(cwd) if cwd else None, env=env, check=check, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def head_sha(d: Path) -> str | None:
    if not (d / ".git").exists():
        return None
    r = git("rev-parse", "HEAD", cwd=d, check=False, quiet=True)
    return r.stdout.strip() if r.returncode == 0 else None


def sparse_patterns(d: Path) -> list[str]:
    r = git("sparse-checkout", "list", cwd=d, check=False, quiet=True)
    return [l.strip() for l in r.stdout.splitlines() if l.strip()] if r.returncode == 0 else []


def is_dirty(d: Path) -> bool:
    r = git("status", "--porcelain", "--untracked-files=no", cwd=d, check=False, quiet=True)
    return bool(r.stdout.strip())


def status(repo: dict, dest: Path) -> str:
    d = dest / repo["name"]
    if not d.exists():
        return "missing"
    sha = head_sha(d)
    if sha is None:
        return "not-a-git-checkout"
    if sha != repo["sha"]:
        return f"at {sha[:12]} (want {repo['sha'][:12]})"
    if repo.get("sparse") and sorted(sparse_patterns(d)) != sorted(repo["sparse"]):
        return "sparse patterns differ"
    return "ok"


def fetch_repo(repo: dict, dest: Path, force: bool) -> bool:
    d = dest / repo["name"]
    st = status(repo, dest)
    if st == "ok":
        log(f"[ok]   {repo['name']} @ {repo['sha'][:12]} (already there)")
        return True
    log(f"[get]  {repo['name']} @ {repo['sha'][:12]} ({st})")
    if st == "not-a-git-checkout":
        if not force:
            log(f"       {d} exists but is not a git checkout; left untouched (use --force to move it aside)")
            return False
        bak = d.with_name(f"{d.name}.bak-{time.strftime('%Y%m%d-%H%M%S')}")
        d.rename(bak)
        log(f"       moved the old folder to {bak}")
    if d.exists() and is_dirty(d):
        if not force:
            log(f"       {d} has local changes; left untouched (use --force to discard them)")
            return False
    new = not d.exists()
    try:
        if new:
            d.mkdir(parents=True)
            git("init", "-q", cwd=d)
            git("remote", "add", "origin", repo["url"], cwd=d)
        filt = ["--filter=blob:none"] if repo.get("sparse") else []
        if repo.get("sparse"):
            git("config", "remote.origin.promisor", "true", cwd=d)
            git("config", "remote.origin.partialclonefilter", "blob:none", cwd=d)
            git("sparse-checkout", "set", "--no-cone", *repo["sparse"], cwd=d)
        r = git("fetch", "--depth", "1", *filt, "origin", repo["sha"], cwd=d, check=False)
        if r.returncode != 0:
            log(f"       fetch by SHA failed ({r.stderr.strip()[:200]}); falling back to a full fetch")
            ref = [f"refs/tags/{repo['tag']}:refs/tags/{repo['tag']}"] if repo.get("tag") else []
            git("fetch", *filt, "origin", *ref, cwd=d)
            git("checkout", "-q", "--detach", repo["sha"], *(["--force"] if force else []), cwd=d)
        else:
            git("checkout", "-q", "--detach", "FETCH_HEAD", *(["--force"] if force else []), cwd=d)
    except subprocess.CalledProcessError as e:
        log(f"       FAILED: {' '.join(e.cmd[3:])}\n{(e.stderr or '').strip()}")
        return False
    got = head_sha(d)
    if got != repo["sha"]:
        log(f"       FAILED: HEAD is {got}, expected {repo['sha']}")
        return False
    log(f"       done: {d} @ {got[:12]}")
    return True


def write_notices(dest: Path, repos: list[dict]) -> None:
    lines = ["# Third-party code vendored by scripts/fetch_third_party.py", "",
             "Generated file. Each checkout keeps its upstream LICENSE; nothing here was modified. "
             "Engineering summary, not legal advice.", "",
             "| Folder | Upstream | Commit | Licence | Used by |", "|---|---|---|---|---|"]
    for r in REPOS:
        if (dest / r["name"]).exists():
            lines.append(f"| `{r['name']}/` | {r['url'].removesuffix('.git')} | `{r['sha']}`"
                         f"{' (' + r['tag'] + ')' if r.get('tag') else ''} | {r['licence']} | {r['used_by']} |")
    (dest / "THIRD_PARTY_NOTICES.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("names", nargs="*", help="repo folder names (default: all)")
    ap.add_argument("--minimal", action="store_true", help="only the repos the realtime default config needs")
    ap.add_argument("--dest", help="destination folder (default: PERCEPTION_THIRD_PARTY_DIR or perception/third_party)")
    ap.add_argument("--check", action="store_true", help="verify only; exit 1 if something is missing or differs")
    ap.add_argument("--list", action="store_true", help="print the pinned repos and exit")
    ap.add_argument("--force", action="store_true", help="discard local changes / move a non-git folder aside")
    a = ap.parse_args(argv)

    dest = Path(a.dest or os.environ.get("PERCEPTION_THIRD_PARTY_DIR") or DEFAULT_DEST).absolute()
    by_name = {r["name"].lower(): r for r in REPOS}
    unknown = [n for n in a.names if n.lower() not in by_name]
    if unknown:
        ap.error(f"unknown repo(s) {unknown}; choose from {[r['name'] for r in REPOS]}")
    sel = [by_name[n.lower()] for n in a.names] if a.names else [r for r in REPOS if r["minimal"] or not a.minimal]

    if a.list:
        for r in REPOS:
            log(f"{r['name']:18s} {r['sha']}  {r['licence']:10s} {'minimal ' if r['minimal'] else 'all     '} {r['used_by']}")
        return 0
    log(f"third_party folder: {dest}")
    if a.check:
        bad = 0
        for r in sel:
            st = status(r, dest)
            log(f"  {r['name']:18s} {st}")
            bad += st != "ok"
        return 1 if bad else 0
    if shutil.which("git") is None:
        log("git is not on PATH. Install Git for Windows (https://git-scm.com/download/win) and re-run.")
        return 2
    dest.mkdir(parents=True, exist_ok=True)
    ok = [fetch_repo(r, dest, a.force) for r in sel]
    write_notices(dest, REPOS)
    log(f"\n{sum(ok)}/{len(ok)} repo(s) at their pinned commit. Licences: {dest / 'THIRD_PARTY_NOTICES.md'}")
    return 0 if all(ok) else 1


if __name__ == "__main__":
    sys.exit(main())
