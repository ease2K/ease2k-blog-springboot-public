-- 在旧库上添加可索引的发布字段，生成列也会跟随旧版写入更新。
ALTER TABLE history ADD COLUMN visibility TEXT GENERATED ALWAYS AS (json_extract(payload, '$.visibility')) VIRTUAL;
ALTER TABLE history ADD COLUMN article_date TEXT GENERATED ALWAYS AS (json_extract(payload, '$.date')) VIRTUAL;
CREATE INDEX history_publication ON history (id, visibility, revision, article_date) WHERE visibility = 'public';
PRAGMA user_version = 1;
