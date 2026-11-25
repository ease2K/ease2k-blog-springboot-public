#!/usr/bin/env python3
"""Create verified editor snapshots and serve them over the read-only SSH channel."""

import argparse
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
from uuid import uuid4

from editor_files import MEDIA_NAME, copy_files, source_files, staged_editor


SNAPSHOT_NAME = re.compile(r"\d{4}-\d\d-\d\dT[\dZ-]+-[a-f0-9]{8}")
DATABASE_ARCHIVE = "content.pgdump"
MIN_FREE_BYTES = 1024 ** 3


def checksums(folder):
    result = {}
    for path in sorted(folder.rglob("*")):
        name = path.relative_to(folder).as_posix()
        if path.is_symlink():
            raise ValueError("备份中不允许符号链接。")
        if name == "media" and path.is_dir() or name == "manifest.json" and path.is_file():
            continue
        if not path.is_file() or not (name in {DATABASE_ARCHIVE, "owner.json"}
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
    if not isinstance(manifest, dict) or manifest.get("version") != 2 or DATABASE_ARCHIVE not in files or manifest.get("files") != files:
        raise ValueError("备份文件与清单不一致。")
    subprocess.run(["pg_restore", "--file", os.devnull, str(folder / DATABASE_ARCHIVE)], check=True)
    return {"files": len(files)}


def snapshots(folder):
    return sorted(path for path in folder.iterdir()
                  if SNAPSHOT_NAME.fullmatch(path.name) and not path.is_symlink()
                  and path.is_dir() and (path / "manifest.json").is_file())


def create(editor, folder, keep):
    if editor == folder or editor in folder.parents or folder in editor.parents:
        raise ValueError("备份目录与站主数据目录必须独立。")
    if not editor.is_dir():
        raise ValueError("找不到站主数据目录。")
    folder.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (folder / ".backup.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        sources = list(source_files(editor))
        if any(path.is_symlink() or not path.is_file() for path in sources):
            raise ValueError("站主数据必须是普通文件。")
        size = sum(path.stat().st_size for path in sources)
        minimum = int(os.environ.get("BLOG_BACKUP_MIN_FREE_BYTES", MIN_FREE_BYTES))
        if shutil.disk_usage(folder).free < max(minimum, size * 2):
            raise ValueError("备份空间不足。")

        created_at = datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
        target = folder / (created_at.replace(":", "-").replace(".", "-") + "-" + uuid4().hex[:8])
        with tempfile.TemporaryDirectory(prefix=".backup-", dir=folder) as temporary:
            stage = Path(temporary) / "snapshot"
            stage.mkdir()
            subprocess.run(["pg_dump", "--no-password", "--format=custom", "--no-privileges",
                            "--exclude-table-data=public.sessions", "--exclude-table-data=public.attempts",
                            "--file", str(stage / DATABASE_ARCHIVE)], check=True)
            # 先取得数据库快照，再复制快照中已经登记的图片。
            copy_files(editor, stage, missing_media_ok=True)
            (stage / "manifest.json").write_text(json.dumps({
                "version": 2, "createdAt": created_at, "files": checksums(stage),
            }, indent=4) + "\n", encoding="utf-8")
            stats = verify(stage)
            stage.rename(target)

        for old in snapshots(folder)[:-keep]:
            shutil.rmtree(old)
        return {"directory": str(target), "verified": True, **stats}


def restore(snapshot, editor):
    verify(snapshot)
    occupied = subprocess.check_output(["psql", "-X", "-w", "-At", "-v", "ON_ERROR_STOP=1", "-c",
                                       "SELECT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname='public')"],
                                      text=True).strip()
    if occupied != "f":
        raise ValueError("恢复目标需要是新建的空 PostgreSQL 数据库。")
    with staged_editor(snapshot.resolve(), editor):
        # -d 让 pg_restore 直接连接数据库。其余连接项由 libpq 环境变量提供。
        subprocess.run(["pg_restore", "--no-password", "--single-transaction", "--no-owner", "--no-privileges",
                        "--dbname", os.environ["PGDATABASE"], str(snapshot / DATABASE_ARCHIVE)], check=True)
    return {"directory": str(editor), "restored": True}


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
                "/bin/systemctl", "show", f"ease2k-blog-postgresql-backup.{unit}", "-p", attribute, "--value",
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
    recovery = commands.add_parser("restore")
    recovery.add_argument("snapshot", type=Path)
    recovery.add_argument("editor", type=Path)
    remote = commands.add_parser("offsite")
    remote.add_argument("folder", type=Path)
    remote.add_argument("command")
    args = parser.parse_args()
    os.umask(0o077)
    try:
        if args.action in {"create", "restore"} and not os.environ.get("PGDATABASE"):
            raise ValueError("请明确设置 PGDATABASE，使用专用 PostgreSQL 数据库。")
        if args.action == "create":
            if args.keep < 1:
                raise ValueError("备份保留数至少为 1。")
            result = create(args.editor.resolve(), args.folder.resolve(), args.keep)
        elif args.action == "verify":
            result = verify(args.snapshot)
        elif args.action == "restore":
            result = restore(args.snapshot, args.editor.absolute())
        else:
            result = offsite(args.folder, args.command)
        if result is not None:
            print(json.dumps(result, ensure_ascii=False))
    except (OSError, ValueError, subprocess.SubprocessError, tarfile.TarError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
