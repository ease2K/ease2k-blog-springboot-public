package com.ease2k.blog.repository;

import com.ease2k.blog.model.Article;
import com.ease2k.blog.model.ArticleDates;
import com.ease2k.blog.owner.OwnerDatabase;
import com.ease2k.blog.owner.OwnerEntries;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 只读公开快照。站主提交更改后，下一次读取以新版本重建快照。 */
@Repository
public class ArticleRepository {

    private static final Pattern MEDIA = Pattern.compile(
            "/media/([A-Za-z0-9][A-Za-z0-9_-]{0,127}\\.(?:webp|png|jpe?g|avif|gif))(?![\\w./-])", Pattern.CASE_INSENSITIVE);
    private final Set<String> awardIds;
    private final ObjectMapper mapper;
    private final SeedArticles seeds;
    private final OwnerDatabase database;
    private final OwnerEntries owner;
    private volatile Snapshot current;

    public ArticleRepository(ObjectMapper mapper, SeedArticles seeds, OwnerDatabase database, OwnerEntries owner) throws IOException {
        this.mapper = mapper;
        this.seeds = seeds;
        this.database = database;
        this.owner = owner;
        try (InputStream input = new ClassPathResource("data/awards.json").getInputStream()) {
            awardIds = Set.copyOf(mapper.readValue(input, new TypeReference<List<String>>() {}));
        }
        snapshot();
    }

    public Snapshot snapshot() {
        long version = database.publicVersion();
        Snapshot existing = current;
        if (existing != null && existing.version() == version) return existing;
        synchronized (this) {
            version = database.publicVersion();
            if (current != null && current.version() == version) return current;
            List<ObjectNode> source = database.enabled() ? owner.published() : List.copyOf(seeds.entries().values());
            List<Article> entries = new ArrayList<>();
            Set<String> images = new HashSet<>();
            for (ObjectNode row : source) {
                ObjectNode canonical = row.deepCopy();
                canonical.remove(List.of("revision", "updatedAt", "publishedAt"));
                for (String key : List.of("enTitle", "summary", "enSummary", "enBody")) {
                    if (!canonical.hasNonNull(key)) canonical.put(key, "");
                }
                Article article = mapper.treeToValue(canonical, Article.class);
                validate(article);
                entries.add(article);
                for (String field : List.of("body", "enBody", "playground", "project", "series")) {
                    JsonNode value = canonical.path(field);
                    String text = value.isTextual() ? value.asString() : mapper.writeValueAsString(value);
                    MEDIA.matcher(text).results().forEach(match -> images.add(match.group(1)));
                }
            }
            entries.sort(Comparator.comparing((Article article) -> ArticleDates.parse(article.date()).toInstant()).reversed());
            current = new Snapshot(version, List.copyOf(entries), entries.stream()
                    .collect(Collectors.toUnmodifiableMap(Article::id, Function.identity())), Set.copyOf(images));
            return current;
        }
    }

    public List<Article> findAll() {
        return snapshot().entries();
    }

    public Optional<Article> findById(String id) {
        return Optional.ofNullable(snapshot().byId().get(id));
    }

    public boolean hasAward(String id) {
        return awardIds.contains(id);
    }

    public boolean exposesMedia(String name) {
        return snapshot().media().contains(name);
    }

    public static boolean validImageName(String name) {
        return MEDIA.matcher("/media/" + name).matches();
    }

    private static void validate(Article article) {
        if (article.id() == null || !article.id().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}")) {
            throw new IllegalArgumentException("公开文章 ID 无效: " + article.id());
        }
        if (article.title() == null || article.title().isBlank() || article.tags() == null || article.date() == null) {
            throw new IllegalArgumentException("公开文章缺少标题、标签或日期: " + article.id());
        }
        ArticleDates.parse(article.date());
        if (article.modifiedAt() != null) ArticleDates.parse(article.modifiedAt());
        if (article.series() != null && (article.series().id() == null
                || !article.series().id().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}"))) {
            throw new IllegalArgumentException("专栏 ID 无效: " + article.id());
        }
    }

    public record Snapshot(long version, List<Article> entries, Map<String, Article> byId, Set<String> media) {
    }
}
