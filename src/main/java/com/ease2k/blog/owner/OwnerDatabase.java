package com.ease2k.blog.owner;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Repository
public class OwnerDatabase {

    private final Path directory;
    private final JdbcTemplate sql;
    private final TransactionTemplate transactions;
    private final AtomicLong publicVersion = new AtomicLong();

    public OwnerDatabase(@Value("${blog.editor-directory:}") String configured) throws IOException {
        if (configured.isBlank()) {
            directory = null;
            sql = null;
            transactions = null;
            return;
        }
        directory = Path.of(configured).toAbsolutePath().normalize();
        // 数据目录包含草稿和认证材料，首次启动时使用私有目录权限。
        Files.createDirectories(directory.resolve("media"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));

        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(5000);
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        SQLiteDataSource source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + directory.resolve("content.sqlite"));
        sql = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        sql.queryForObject("PRAGMA journal_mode=WAL", String.class);
        initTables();
    }

    private void initTables() {
        // 与原 Node.js 站使用相同的表和字段，备份副本可以直接读取。
        sql.execute("""
                CREATE TABLE IF NOT EXISTS entries (
                    id TEXT PRIMARY KEY, payload TEXT NOT NULL, revision INTEGER NOT NULL,
                    deleted INTEGER NOT NULL DEFAULT 0, updated_at TEXT NOT NULL)
                """);
        sql.execute("""
                CREATE TABLE IF NOT EXISTS history (
                    id TEXT NOT NULL, revision INTEGER NOT NULL, payload TEXT NOT NULL,
                    deleted INTEGER NOT NULL, saved_at TEXT NOT NULL, PRIMARY KEY (id, revision))
                """);
        sql.execute("""
                CREATE TABLE IF NOT EXISTS working_copies (
                    id TEXT PRIMARY KEY, payload TEXT NOT NULL, base_revision INTEGER NOT NULL,
                    creating INTEGER NOT NULL, revision INTEGER NOT NULL, saved_at TEXT NOT NULL)
                """);
        sql.execute("""
                CREATE TABLE IF NOT EXISTS series (
                    id TEXT PRIMARY KEY, payload TEXT NOT NULL, revision INTEGER NOT NULL, updated_at TEXT NOT NULL)
                """);
        sql.execute("""
                CREATE TABLE IF NOT EXISTS media_assets (
                    name TEXT PRIMARY KEY, label TEXT NOT NULL, deleted INTEGER NOT NULL DEFAULT 0,
                    revision INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL)
                """);
        sql.execute("""
                CREATE TABLE IF NOT EXISTS sessions (hash TEXT PRIMARY KEY, version TEXT NOT NULL, expires INTEGER NOT NULL)
                """);
        sql.execute("""
                CREATE TABLE IF NOT EXISTS attempts (version TEXT PRIMARY KEY, started INTEGER NOT NULL, count INTEGER NOT NULL)
                """);
    }

    public boolean enabled() {
        return directory != null;
    }

    public Path directory() {
        return directory;
    }

    public long publicVersion() {
        return publicVersion.get();
    }

    JdbcTemplate sql() {
        return sql;
    }

    <T> T transaction(boolean changesPublicContent, Supplier<T> action) {
        T result = transactions.execute(status -> action.get());
        // 草稿和会话变化无需重建公开缓存。发布事务完成后下一次读取才需要更新。
        if (changesPublicContent) {
            publicVersion.incrementAndGet();
        }
        return result;
    }
}
