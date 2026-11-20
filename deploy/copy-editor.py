#!/usr/bin/env python3
"""Copy an offline editor database and its files into a new private directory."""

import argparse
from contextlib import closing
import os
from pathlib import Path
import re
import shutil
import sqlite3
import tempfile


def copy_editor(source, target):
    database = source / "content.sqlite"
    owner = source / "owner.json"
    if not database.is_file() or not owner.is_file():
        raise SystemExit("源目录需要包含 content.sqlite 和 owner.json。")
    if target.exists() or target.is_symlink():
        raise SystemExit("目标目录已存在，请指定一个新目录。")
    if source.resolve() in target.resolve().parents:
        raise SystemExit("目标目录不能位于源目录内。")
    os.umask(0o077)
    stage = Path(tempfile.mkdtemp(prefix=".editor-", dir=target.parent))
    try:
        with closing(sqlite3.connect(database.resolve().as_uri() + "?mode=ro", uri=True)) as original:
            with closing(sqlite3.connect(stage / "content.sqlite")) as copy:
                original.backup(copy)
                copy.execute("PRAGMA journal_mode=DELETE")
                with copy:
                    # 新站需要重新登录，文章、草稿和修订记录原样保留。
                    for table in ("sessions", "attempts"):
                        if copy.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (table,)).fetchone():
                            copy.execute(f"DELETE FROM {table}")
                if copy.execute("PRAGMA quick_check").fetchone() != ("ok",):
                    raise RuntimeError("SQLite 副本未通过完整性检查。")
        shutil.copyfile(owner, stage / "owner.json")
        (stage / "media").mkdir()
        media = source / "media"
        if media.is_dir():
            for path in media.iterdir():
                if re.fullmatch(r"[a-f0-9-]{36}\.webp", path.name) and path.is_file() and not path.is_symlink():
                    shutil.copyfile(path, stage / "media" / path.name)
        stage.rename(target)
    except BaseException:
        shutil.rmtree(stage)
        raise


def main():
    parser = argparse.ArgumentParser(description="复制已停止写入的站主数据目录")
    parser.add_argument("source", type=Path)
    parser.add_argument("target", type=Path)
    args = parser.parse_args()
    copy_editor(args.source.resolve(), args.target.absolute())
    print("数据副本已生成，SQLite 完整性检查通过。")


if __name__ == "__main__":
    main()
