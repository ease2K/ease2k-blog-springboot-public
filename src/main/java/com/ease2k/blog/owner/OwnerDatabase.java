package com.ease2k.blog.owner;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
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
    private final SqlSessionTemplate sqlSession;
    private final TransactionTemplate transactions;
    private final AtomicLong publicVersion = new AtomicLong();

    public OwnerDatabase(@Value("${blog.editor-directory:}") String configured) throws IOException {
        if (configured.isBlank()) {
            directory = null;
            sqlSession = null;
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
        new ResourceDatabasePopulator(new ClassPathResource("db/owner-schema.sql")).execute(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        migrate(source);

        // Mapper 与 Spring 事务使用同一个数据源，才能共用事务中的连接。
        Environment environment = new Environment("owner", new SpringManagedTransactionFactory(), source);
        Configuration mappings = new Configuration(environment);
        mappings.setMapUnderscoreToCamelCase(true);
        mappings.setArgNameBasedConstructorAutoMapping(true);
        mappings.setLocalCacheScope(LocalCacheScope.STATEMENT);
        mappings.addMapper(OwnerEntryMapper.class);
        mappings.addMapper(OwnerSeriesMapper.class);
        mappings.addMapper(OwnerMediaMapper.class);
        mappings.addMapper(OwnerSessionMapper.class);
        sqlSession = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(mappings));
    }

    private void migrate(SQLiteDataSource source) {
        JdbcTemplate jdbc = new JdbcTemplate(source);
        transactions.executeWithoutResult(status -> {
            int version = jdbc.queryForObject("PRAGMA user_version", Integer.class);
            if (version > 1) throw new IllegalStateException("站主数据库由更新版本创建，请使用对应版本启动。");
            if (version == 0) {
                new ResourceDatabasePopulator(new ClassPathResource("db/owner-migration-1.sql")).execute(source);
            }
        });
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

    <T> T mapper(Class<T> type) {
        return enabled() ? sqlSession.getMapper(type) : null;
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
