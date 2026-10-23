#!/usr/bin/env python3
"""
Keeps Deltacord in step with Revenge.

Takes every change Revenge made since the last sync, applies the same Revenge -> Deltacord
rename that Deltacord was created with, applies it on a new branch, and opens a pull request.
Hunks that no longer fit (because Deltacord changed the same lines) are saved as .rej files
in the pull request so they can be merged by hand.

Usage (run from the repo root, normally by .github/workflows/upstream-sync.yml):
    python3 scripts/upstream-sync.py [--dry-run] [--upstream-dir DIR]

Config lives in .github/upstream.json: {"upstream": "<git url>", "base": "<last synced sha>"}.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import tempfile

OWNER = "Narehatechi"
CONFIG = ".github/upstream.json"

# Files taken from Revenge as-is (no rename): licences, lockfiles, and the Revenge Next plugin
# manifest whose ids must keep matching Revenge's plugin repos.
RAW_FILES = re.compile(r"(^|/)(LICENSE|bun\.lock|package-lock\.json)$|app/src/main/assets/manifest\.json$")
# Files Deltacord maintains itself. Workflow files can't be pushed by GitHub Actions at all,
# so Revenge's workflow changes are listed for a manual look instead.
SKIP_FILES = re.compile(r"(^|/)CHANGELOG\.md$|^\.github/workflows/")
# Version lines: Deltacord numbers its own releases, so Revenge's bumps are dropped.
VERSION_LINE = re.compile(r'^\s*("version"\s*:|version\s*=|versionCode\s*=|versionName\s*=)')

INVITES = re.compile(r"https://discord\.(gg|com/invite)/(ddcQf3s2Uq|n9QQ4XhhJP)")
KEEP = "\x00KEEP_DEVTOOLS\x00"


def rename(text: str) -> str:
    """The exact Revenge -> Deltacord rename Deltacord was created with."""
    t = text.replace("@revenge-mod/devtools-", KEEP)
    t = re.sub(r"github\.com/revenge-mod/revenge-(manager|xposed|bundle)", rf"github.com/{OWNER}/deltacord-\1", t)
    t = t.replace("github.com/revenge-mod", f"github.com/{OWNER}")
    t = t.replace("revenge-mod.github.io", f"{OWNER}.github.io")
    t = t.replace("cdn.jsdelivr.net/gh/revenge-mod/", f"cdn.jsdelivr.net/gh/{OWNER}/")
    t = t.replace('"@revenge-mod/revenge"', '"deltacord-bundle"')
    t = t.replace("revenge-mod/bundle", f"{OWNER}/deltacord-bundle")
    t = t.replace("revenge-mod/revenge-", f"{OWNER}/deltacord-")
    t = INVITES.sub(f"https://github.com/{OWNER}", t)
    t = t.replace("Revenge", "Deltacord").replace("REVENGE", "DELTACORD").replace("revenge", "deltacord")
    return t.replace(KEEP, "@revenge-mod/devtools-")


def lines_of(text: str):
    """Splits on \n only, keeping line endings (\r\n files must survive untouched)."""
    return re.findall(r"[^\n]*\n|[^\n]+$", text)


def split_files(patch: str):
    """Splits a git patch into per-file chunks."""
    chunks, current = [], []
    for line in lines_of(patch):
        if line.startswith("diff --git ") and current:
            chunks.append("".join(current))
            current = []
        current.append(line)
    if current:
        chunks.append("".join(current))
    return chunks


def path_of(chunk: str) -> str:
    m = re.match(r"diff --git a/(.*?) b/(.*?)\r?\n", lines_of(chunk)[0])
    return m.group(2) if m else ""


def drop_version_hunks(chunk: str) -> str:
    """Removes hunks whose only changes are version lines."""
    head, *hunks = re.split(r"(?m)^(?=@@ )", chunk)
    kept = []
    for hunk in hunks:
        changed = [l[1:] for l in lines_of(hunk) if l[:1] in "+-" and not l.startswith(("+++", "---"))]
        if changed and all(VERSION_LINE.match(l) for l in changed):
            continue
        kept.append(hunk)
    return head + "".join(kept) if kept else ""


def transform(patch: str):
    out, notes = [], []
    for chunk in split_files(patch):
        path = path_of(chunk)
        if SKIP_FILES.search(path):
            why = "workflow files need a manual update" if path.startswith(".github/workflows/") else "Deltacord keeps its own"
            notes.append(f"skipped {path} ({why})")
            continue
        if "GIT binary patch" in chunk:
            # Only rename the header; binary data must stay byte-exact.
            header, _, body = chunk.partition("GIT binary patch")
            out.append(rename(header) + "GIT binary patch" + body)
            continue
        if RAW_FILES.search(path):
            out.append(chunk)
            continue
        chunk = drop_version_hunks(chunk)
        if not chunk:
            notes.append(f"skipped version bump in {path}")
            continue
        out.append(rename(chunk))
    return "".join(out), notes


def run(*cmd, cwd=None, check=True):
    return subprocess.run(cmd, cwd=cwd, check=check, text=True, capture_output=True)


def git_text(*cmd, cwd=None) -> str:
    """Git output as text without newline translation (keeps \r\n and odd bytes exact)."""
    out = subprocess.run(cmd, cwd=cwd, check=True, capture_output=True).stdout
    return out.decode("utf-8", "surrogateescape")


def bump_xposed_version():
    """The Xposed module releases when its versionCode changes, so a sync bumps it."""
    path = "app/build.gradle.kts"
    if not os.path.exists(path) or "xposed" not in open(path).read():
        return None
    src = open(path).read()
    code = int(re.search(r"versionCode = (\d+)", src).group(1)) + 1
    name = re.search(r'versionName = "([^"]+)"', src).group(1)
    parts = name.split(".")
    parts[-1] = str(int(re.sub(r"\D.*", "", parts[-1]) or 0) + 1)
    new_name = ".".join(parts)
    src = re.sub(r"versionCode = \d+", f"versionCode = {code}", src)
    src = re.sub(r'versionName = "[^"]+"', f'versionName = "{new_name}"', src)
    open(path, "w").write(src)
    return f"{new_name} ({code})"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true", help="apply locally, don't commit/push/open a PR")
    ap.add_argument("--upstream-dir", help="use an existing clone of upstream instead of cloning")
    args = ap.parse_args()

    cfg = json.load(open(CONFIG))
    up = args.upstream_dir
    if not up:
        up = tempfile.mkdtemp(prefix="upstream-")
        run("git", "clone", "--filter=blob:none", "--quiet", cfg["upstream"], up)
    head = run("git", "rev-parse", "HEAD", cwd=up).stdout.strip()
    base = cfg["base"]

    if head == base:
        print("Already in sync with", cfg["upstream"], head[:7])
        return 0

    short = head[:7]
    branch = f"upstream-sync/{short}"
    if not args.dry_run and run("git", "ls-remote", "--exit-code", "--heads", "origin", branch, check=False).returncode == 0:
        print(f"{branch} already exists; waiting for it to be merged")
        return 0

    log = run("git", "log", "--format=- %h %s", f"{base}..{head}", cwd=up).stdout.strip()
    patch = git_text("git", "diff", "--binary", base, head, cwd=up)
    transformed, notes = transform(patch)

    if not args.dry_run:
        run("git", "checkout", "-b", branch)

    rejects = []
    if transformed.strip():
        with tempfile.NamedTemporaryFile("wb", suffix=".patch", delete=False) as f:
            f.write(transformed.encode("utf-8", "surrogateescape"))
        res = run("git", "apply", "--reject", "--whitespace=nowarn", f.name, check=False)
        rejects = [l for l in res.stderr.splitlines() if "Rejected" in l or "patch failed" in l or "error:" in l]
        print(res.stderr.strip() or "Patch applied cleanly")

    bumped = bump_xposed_version()

    cfg["base"] = head
    with open(CONFIG, "w") as f:
        json.dump(cfg, f, indent=2)
        f.write("\n")

    title = f"fix: sync with Revenge up to {short}"
    body = [f"Brings in Revenge's changes from `{base[:7]}` to `{short}`, renamed for Deltacord.", "", "**Revenge commits:**", log or "(none)"]
    if notes:
        body += ["", "**Skipped on purpose:**", *[f"- {n}" for n in notes]]
    if bumped:
        body += ["", f"Module version bumped to {bumped} so this ships as a new release."]
    if rejects:
        body += ["", "**Needs a look:** some changes didn't fit because Deltacord changed the same lines. "
                 "Each one is saved next to its file as a `.rej` file; merge it in by hand, then delete the `.rej`.",
                 "```", *rejects[:60], "```"]
    else:
        body += ["", "Everything applied cleanly. Merge to release it."]
    body_text = "\n".join(body)

    if args.dry_run:
        print("\n" + title + "\n\n" + body_text)
        return 0

    run("git", "add", "-A")
    run("git", "-c", "user.name=github-actions[bot]",
        "-c", "user.email=41898282+github-actions[bot]@users.noreply.github.com",
        "commit", "-m", title, "-m", body_text)
    run("git", "push", "origin", branch)

    pr = run("gh", "pr", "create", "--title", title, "--body", body_text, "--head", branch, check=False)
    if pr.returncode == 0:
        print("Opened", pr.stdout.strip())
        return 0

    # Repos where Actions may not open pull requests: open an issue with a one-tap link instead.
    repo = os.environ.get("GITHUB_REPOSITORY", "")
    compare = f"https://github.com/{repo}/compare/main...{branch}?expand=1"
    issue_body = body_text + f"\n\n**Open the pull request:** {compare}\n\n" \
        "(GitHub Actions isn't allowed to open pull requests in this repo. To let it, turn on " \
        "Settings > Actions > General > *Allow GitHub Actions to create and approve pull requests*.)"
    run("gh", "issue", "create", "--title", title, "--body", issue_body, check=False)
    print("Couldn't open a PR; opened an issue with the link instead.", pr.stderr.strip())
    return 0


if __name__ == "__main__":
    sys.exit(main())
