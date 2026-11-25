package com.ease2k.blog.owner;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Repository
public class OwnerDatabase {

    private static final String WRITE_LOCK = "SELECT pg_advisory_xact_lock(hashtext('ease2k-blog-owner'))";

    private final Path directory;
    private final HikariDataSource source;
    private final JdbcTemplate jdbc;
    private final SqlSessionTemplate sqlSession;
    private final TransactionTemplate transactions;
    private final TransactionTemplate reads;
    private final AtomicLong publicVersion = new AtomicLong();

    public OwnerDatabase(@Value("${blog.editor-directory:}") String configured,
                         @Value("${blog.database.url:}") String url,
                         @Value("${blog.database.username:}") String username,
                         @Value("${blog.database.password:}") String password) throws IOException {
        if (configured.isBlank()) {
            directory = null;
            source = null;
            jdbc = null;
            sqlSession = null;
            transactions = null;
            reads = null;
            return;
        }
        if (!url.startsWith("jdbc:postgresql:") || username.isBlank()) {
            throw new IllegalArgumentException("启用站主工作台时，请配置 BLOG_DATABASE_URL 与 BLOG_DATABASE_USERNAME。");
        }
        directory = Path.of(configured).toAbsolutePath().normalize();
        // 数据目录包含草稿和认证材料，首次启动时使用私有目录权限。
        Files.createDirectories(directory.resolve("media"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(8);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5000);
        source = new HikariDataSource(config);
        jdbc = new JdbcTemplate(source);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
        transactions = new TransactionTemplate(manager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        reads = new TransactionTemplate(manager);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        try {
            transactions.executeWithoutResult(status ->
                    new ResourceDatabasePopulator(new ClassPathResource("db/owner-schema.sql")).execute(source));

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
        } catch (RuntimeException error) {
            source.close();
            throw error;
        }
    }

    @PreDestroy
    void close() {
        if (source != null) source.close();
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

    <T> T read(Supplier<T> action) {
        return reads.execute(status -> action.get());
    }

    <T> T transaction(boolean changesPublicContent, Supplier<T> action) {
        T result = transactions.execute(status -> {
            // 先排队再读取版本，避免两个写事务同时通过版本检查。
            jdbc.execute(WRITE_LOCK);
            return action.get();
        });
        // 草稿和会话变化无需重建公开缓存。发布事务完成后下一次读取才需要更新。
        if (changesPublicContent) {
            publicVersion.incrementAndGet();
        }
        return result;
    }
}
