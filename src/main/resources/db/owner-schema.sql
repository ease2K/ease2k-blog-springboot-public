PRAGMA journal_mode=WAL;

-- 与原 Node.js 站使用相同的表和字段，备份副本可以直接读取。
CREATE TABLE IF NOT EXISTS entries (
    id TEXT PRIMARY KEY, payload TEXT NOT NULL, revision INTEGER NOT NULL,
    deleted INTEGER NOT NULL DEFAULT 0, updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS history (
    id TEXT NOT NULL, revision INTEGER NOT NULL, payload TEXT NOT NULL,
    deleted INTEGER NOT NULL, saved_at TEXT NOT NULL, PRIMARY KEY (id, revision)
);

CREATE TABLE IF NOT EXISTS working_copies (
    id TEXT PRIMARY KEY, payload TEXT NOT NULL, base_revision INTEGER NOT NULL,
    creating INTEGER NOT NULL, revision INTEGER NOT NULL, saved_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS series (
    id TEXT PRIMARY KEY, payload TEXT NOT NULL, revision INTEGER NOT NULL, updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS media_assets (
    name TEXT PRIMARY KEY, label TEXT NOT NULL, deleted INTEGER NOT NULL DEFAULT 0,
    revision INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS sessions (
    hash TEXT PRIMARY KEY, version TEXT NOT NULL, expires INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS attempts (
    version TEXT PRIMARY KEY, started INTEGER NOT NULL, count INTEGER NOT NULL
);
