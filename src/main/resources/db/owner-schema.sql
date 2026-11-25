-- 应用建表和旧数据导入共用同一把事务锁。
SELECT pg_advisory_xact_lock(hashtext('ease2k-blog-owner'));

CREATE TABLE IF NOT EXISTS entries (
    id TEXT PRIMARY KEY, payload TEXT NOT NULL, revision INTEGER NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE, updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS history (
    id TEXT NOT NULL, revision INTEGER NOT NULL, payload TEXT NOT NULL,
    deleted BOOLEAN NOT NULL, saved_at TEXT NOT NULL,
    visibility TEXT, article_date TEXT,
    PRIMARY KEY (id, revision)
);

CREATE INDEX IF NOT EXISTS history_publication ON history (id, revision, article_date) WHERE visibility = 'public';

CREATE TABLE IF NOT EXISTS working_copies (
    id TEXT PRIMARY KEY, payload TEXT NOT NULL, base_revision INTEGER NOT NULL,
    creating BOOLEAN NOT NULL, revision INTEGER NOT NULL, saved_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS series (
    id TEXT PRIMARY KEY, payload TEXT NOT NULL, revision INTEGER NOT NULL, updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS media_assets (
    name TEXT PRIMARY KEY, label TEXT NOT NULL, deleted BOOLEAN NOT NULL DEFAULT FALSE,
    revision INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS sessions (
    hash TEXT PRIMARY KEY, version TEXT NOT NULL, expires BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS attempts (
    version TEXT PRIMARY KEY, started BIGINT NOT NULL, count INTEGER NOT NULL
);
