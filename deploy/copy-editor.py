#!/usr/bin/env python3
"""Import an offline SQLite editor snapshot into a fresh PostgreSQL database."""

import argparse
from contextlib import closing
import os
from pathlib import Path
import sqlite3
import subprocess
import tempfile

from editor_files import staged_editor


CONTENT_TABLES = {
    "entries": ("id", "payload", "revision", "deleted", "updated_at"),
    "history": ("id", "revision", "payload", "deleted", "saved_at"),
    "working_copies": ("id", "payload", "base_revision", "creating", "revision", "saved_at"),
    "series": ("id", "payload", "revision", "updated_at"),
    "media_assets": ("name", "label", "deleted", "revision", "created_at"),
}
SCHEMA = Path(__file__).with_name("owner-schema.sql")
if not SCHEMA.is_file():
    SCHEMA = SCHEMA.parent.parent / "src/main/resources/db/owner-schema.sql"


def copy_field(value):
    if value is None:
        return "\\N"
    return str(value).replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")


def import_database(source):
    counts = {}
    with closing(sqlite3.connect(source.as_uri() + "?mode=ro", uri=True)) as original:
        with closing(sqlite3.connect(":memory:")) as snapshot, tempfile.TemporaryFile(mode="w+", encoding="utf-8") as sql:
            original.backup(snapshot)
            if snapshot.execute("PRAGMA quick_check").fetchall() != [("ok",)]:
                raise ValueError("SQLite 副本未通过完整性检查。")
            tables = {row[0] for row in snapshot.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            if "entries" not in tables:
                raise ValueError("SQLite 副本缺少 entries 表。")

            sql.write(SCHEMA.read_text(encoding="utf-8") + "\n")
            existing = " OR ".join(f"EXISTS (SELECT 1 FROM {table})"
                                   for table in (*CONTENT_TABLES, "sessions", "attempts"))
            sql.write(f"""DO $$
BEGIN
    IF {existing} THEN
        RAISE EXCEPTION '目标 PostgreSQL 库已有站主数据，请指定空库。';
    END IF;
END;
$$;
""")
            for table, fields in CONTENT_TABLES.items():
                counts[table] = 0
                if table not in tables:
                    continue
                source_columns = columns = ", ".join(fields)
                if table == "history":
                    source_columns += ", json_extract(payload, '$.visibility'), json_extract(payload, '$.date')"
                    columns += ", visibility, article_date"
                sql.write(f"COPY {table} ({columns}) FROM STDIN;\n")
                for row in snapshot.execute(f"SELECT {source_columns} FROM {table}"):
                    sql.write("\t".join(copy_field(value) for value in row) + "\n")
                    counts[table] += 1
                sql.write("\\.\n")

            sql.seek(0)
            subprocess.run(["psql", "-X", "-w", "-q", "-v", "ON_ERROR_STOP=1", "--single-transaction",
                            "--file=-"], stdin=sql, stdout=subprocess.DEVNULL, check=True,
                           env={**os.environ, "PGCLIENTENCODING": "UTF8"})
    return counts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("target", type=Path)
    args = parser.parse_args()
    os.umask(0o077)
    source = args.source.resolve()
    database = source / "content.sqlite"
    owner = source / "owner.json"
    try:
        if not os.environ.get("PGDATABASE"):
            raise ValueError("请明确设置 PGDATABASE，使用新的专用 PostgreSQL 数据库。")
        if any(path.is_symlink() or not path.is_file() for path in (database, owner)):
            raise ValueError("源目录需要包含普通文件 content.sqlite 与 owner.json。")
        with staged_editor(source, args.target.absolute()):
            counts = import_database(database)
        print("导入完成，旧登录会话已清除。")
        print(", ".join(f"{table}={count}" for table, count in counts.items()))
    except (OSError, ValueError, sqlite3.Error, subprocess.SubprocessError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
