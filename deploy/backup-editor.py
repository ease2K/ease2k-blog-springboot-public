#!/usr/bin/env python3
"""Create verified editor snapshots and serve them over the read-only SSH channel."""

import argparse
from contextlib import closing
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import sys
import tarfile
import tempfile
from uuid import uuid4


SNAPSHOT_NAME = re.compile(r"\d{4}-\d\d-\d\dT[\dZ-]+-[a-f0-9]{8}")
MEDIA_NAME = re.compile(r"[a-f0-9-]{36}\.webp")
MIN_FREE_BYTES = 1024 ** 3


def checksums(folder):
    result = {}
    for path in sorted(folder.rglob("*")):
        name = path.relative_to(folder).as_posix()
        if path.is_symlink():
            raise ValueError("备份中不允许符号链接。")
        if name == "media" and path.is_dir() or name == "manifest.json" and path.is_file():
            continue
        if not path.is_file() or not (name in {"content.sqlite", "owner.json"}
                                      or name.startswith("media/") and MEDIA_NAME.fullmatch(name[6:])):
            raise ValueError(f"备份中存在意外路径：{name}")
        digest = hashlib.sha256()
        with path.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
        result[name] = digest.hexdigest()
    return result


def verify(folder):
    manifest_path = folder / "manifest.json"
    if folder.is_symlink() or manifest_path.is_symlink():
        raise ValueError("备份目录和清单必须是本地文件。")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    files = checksums(folder)
    if not isinstance(manifest, dict) or manifest.get("version") != 1 or "content.sqlite" not in files or manifest.get("files") != files:
        raise ValueError("备份文件与清单不一致。")
    with closing(sqlite3.connect((folder / "content.sqlite").resolve().as_uri() + "?mode=ro&immutable=1", uri=True)) as db:
        if db.execute("PRAGMA quick_check").fetchall() != [("ok",)]:
            raise ValueError("SQLite 完整性校验失败。")
        entries = db.execute("SELECT count(*) FROM entries").fetchone()[0]
        drafts = db.execute("SELECT count(*) FROM working_copies").fetchone()[0]
    return {"entries": entries, "drafts": drafts, "files": len(files)}


def snapshots(folder):
    return sorted(path for path in folder.iterdir()
                  if SNAPSHOT_NAME.fullmatch(path.name) and not path.is_symlink()
                  and path.is_dir() and (path / "manifest.json").is_file())


def source_files(editor):
    owner = editor / "owner.json"
    if owner.exists() or owner.is_symlink():
        yield owner
    media = editor / "media"
    if media.is_symlink():
        raise ValueError("图片目录不能是符号链接。")
    if media.is_dir():
        yield from (path for path in media.iterdir() if MEDIA_NAME.fullmatch(path.name))


def create(editor, folder, keep):
    if editor == folder or editor in folder.parents or folder in editor.parents:
        raise ValueError("备份目录与站主数据目录必须独立。")
    folder.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (folder / ".backup.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        database = editor / "content.sqlite"
        sources = list(source_files(editor))
        if any(path.is_symlink() or not path.is_file() for path in [database, *sources]):
            raise ValueError("站主数据必须是普通文件，且 SQLite 数据库必须存在。")
        size = sum(path.stat().st_size for path in [database, *sources])
        wal = editor / "content.sqlite-wal"
        try:
            size += wal.stat().st_size
        except FileNotFoundError:
            pass
        minimum = int(os.environ.get("BLOG_BACKUP_MIN_FREE_BYTES", MIN_FREE_BYTES))
        if shutil.disk_usage(folder).free < max(minimum, size * 2):
            raise ValueError("备份空间不足。")

        created_at = datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
        target = folder / (created_at.replace(":", "-").replace(".", "-") + "-" + uuid4().hex[:8])
        with tempfile.TemporaryDirectory(prefix=".backup-", dir=folder) as temporary:
            stage = Path(temporary) / "snapshot"
            (stage / "media").mkdir(parents=True)
            with closing(sqlite3.connect(database.as_uri() + "?mode=ro", uri=True)) as source:
                with closing(sqlite3.connect(stage / "content.sqlite")) as copy:
                    source.backup(copy)
                    copy.execute("PRAGMA journal_mode=DELETE")
            # SQLite 快照取得后再枚举图片，快照前已登记的新图片也会被复制。
            for path in source_files(editor):
                if path.is_symlink():
                    raise ValueError("备份源不允许符号链接。")
                try:
                    shutil.copyfile(path, stage / path.relative_to(editor))
                except FileNotFoundError:
                    # 上传回滚时，未写入数据库的临时图片可能从源目录消失。
                    if path.parent != editor / "media":
                        raise
            (stage / "manifest.json").write_text(json.dumps({
                "version": 1, "createdAt": created_at, "files": checksums(stage),
            }, indent=4) + "\n", encoding="utf-8")
            stats = verify(stage)
            restored = Path(temporary) / "restored"
            shutil.copytree(stage, restored)
            verify(restored)
            stage.rename(target)

        for old in snapshots(folder)[:-keep]:
            shutil.rmtree(old)
        return {"directory": str(target), "verified": True, "restorationTest": True, **stats}


def offsite(folder, command):
    action, _, explicit = command.partition(" ")
    if command not in {"status", "latest"} and not (action == "latest" and SNAPSHOT_NAME.fullmatch(explicit)):
        raise ValueError("仅允许 status 和 latest。")
    available = {path.name: path for path in snapshots(folder)}
    if not available or explicit and explicit not in available:
        raise ValueError("没有找到指定的备份。")
    name = explicit or next(reversed(available))
    path = available[name]
    verify(path)
    if action == "status":
        manifest = json.loads((path / "manifest.json").read_text(encoding="utf-8"))
        properties = {}
        for unit, attribute, key in (("service", "Result", "backupResult"), ("timer", "ActiveState", "timerActive")):
            properties[key] = subprocess.check_output([
                "/bin/systemctl", "show", f"ease2k-blog-backup.{unit}", "-p", attribute, "--value",
            ], text=True, timeout=5).strip()
        total = sum(item.stat().st_size for item in path.rglob("*") if item.is_file())
        return {"name": name, "createdAt": manifest["createdAt"], "bytes": total,
                "availableBytes": shutil.disk_usage(folder).free, "minimumFreeBytes": MIN_FREE_BYTES, **properties}
    with tarfile.open(fileobj=sys.stdout.buffer, mode="w|gz", format=tarfile.USTAR_FORMAT) as archive:
        archive.add(path, arcname=name, recursive=True)
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    backup = commands.add_parser("create")
    backup.add_argument("editor", type=Path)
    backup.add_argument("folder", type=Path)
    backup.add_argument("--keep", type=int, default=14)
    commands.add_parser("verify").add_argument("snapshot", type=Path)
    remote = commands.add_parser("offsite")
    remote.add_argument("folder", type=Path)
    remote.add_argument("command")
    args = parser.parse_args()
    os.umask(0o077)
    try:
        if args.action == "create":
            if args.keep < 1:
                raise ValueError("备份保留数至少为 1。")
            result = create(args.editor.resolve(), args.folder.resolve(), args.keep)
        elif args.action == "verify":
            result = verify(args.snapshot)
        else:
            result = offsite(args.folder, args.command)
        if result is not None:
            print(json.dumps(result, ensure_ascii=False))
    except (OSError, ValueError, sqlite3.Error, subprocess.SubprocessError, tarfile.TarError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
