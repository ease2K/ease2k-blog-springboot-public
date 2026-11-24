package com.ease2k.blog.service;

import com.ease2k.blog.model.Article;
import com.ease2k.blog.repository.ArticleRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class ArticleServiceImpl implements ArticleService {

    private final ArticleRepository repository;
    private final ObjectMapper mapper;
    private volatile Index index;

    public ArticleServiceImpl(ArticleRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public List<ObjectNode> list() {
        ArticleRepository.Snapshot snapshot = repository.snapshot();
        return index(snapshot).entries();
    }

    @Override
    public Optional<Article> findById(String id) {
        return repository.findById(id);
    }

    @Override
    public List<Article> articlesForFeed() {
        return repository.findAll();
    }

    @Override
    public boolean hasSeries(String id) {
        return repository.findAll().stream()
                .anyMatch(article -> article.series() != null && article.series().id().equals(id));
    }

    @Override
    public boolean hasAward(String id) {
        return repository.hasAward(id);
    }

    @Override
    public List<ObjectNode> search(String query) {
        String value = query.strip().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return List.of();
        }
        String keyword = value.substring(0, Math.min(200, value.length()));
        ArticleRepository.Snapshot snapshot = repository.snapshot();
        Map<String, ObjectNode> summaries = index(snapshot).byId();
        return snapshot.entries().stream()
                .map(article -> searchEntry(article, keyword, summaries.get(article.id())))
                .flatMap(Optional::stream)
                .limit(30)
                .toList();
    }

    private Optional<ObjectNode> searchEntry(Article article, String keyword, ObjectNode summary) {
        // 字段顺序决定同一篇文章采用哪个摘录，也决定命中英文时是否切换阅读语言。
        String[] fields = {article.title(), article.enTitle(), article.summary(), article.enSummary(),
                article.body(), article.enBody(), String.join(" ", article.tags())};
        for (int i = 0; i < fields.length; i++) {
            String text = fields[i];
            if (!text.toLowerCase(Locale.ROOT).contains(keyword)) {
                continue;
            }
            // 摘录和语言提示取决于当前查询，只写入副本，避免串到列表或其他搜索。
            ObjectNode result = summary.deepCopy();
            if (i == 1 || i == 3 || i == 5) {
                result.put("searchLanguage", "en");
            }
            result.put("searchSnippet", i < 2 ? result.path("summary").asString() : snippet(text, keyword));
            return Optional.of(result);
        }
        return Optional.empty();
    }

    private Index index(ArticleRepository.Snapshot snapshot) {
        Index existing = index;
        if (existing != null && existing.version() == snapshot.version()) return existing;
        synchronized (this) {
            existing = index;
            if (existing != null && existing.version() == snapshot.version()) return existing;
            Map<String, ObjectNode> summaries = snapshot.entries().stream().collect(Collectors.toUnmodifiableMap(
                    Article::id, article -> indexEntry(mapper, article)));
            Index updated = new Index(snapshot.version(), snapshot.entries().stream()
                    .map(article -> summaries.get(article.id())).toList(), summaries);
            // 并发中的旧请求仍可以完成，旧快照不能覆盖已经生效的新缓存。
            if (existing == null || updated.version() > existing.version()) index = updated;
            return updated;
        }
    }

    private static ObjectNode indexEntry(ObjectMapper mapper, Article article) {
        ObjectNode result = mapper.valueToTree(article);
        result.remove(List.of("body", "enBody", "playground"));
        // 公开快照只包含有正文的文章，索引只返回正文可用标记。
        result.put("hasBody", true);
        result.put("hasEnglishBody", !article.enBody().isBlank());
        String summary = article.summary();
        if (summary.isBlank() || "-".equals(summary.strip()) || "—".equals(summary.strip())) {
            result.put("summary", snippet(article.body(), ""));
        }
        return result;
    }

    private static String snippet(String text, String keyword) {
        String plain = text.replaceAll("(?s)```.*?```", " ")
                .replaceAll("[#*_`~>|]", " ").replaceAll("\\s+", " ").strip();
        int match = plain.toLowerCase(Locale.ROOT).indexOf(keyword);
        int start = Math.max(0, match - 45);
        int end = Math.min(plain.length(), start + 140);
        return (start > 0 ? "…" : "") + plain.substring(start, end) + (end < plain.length() ? "…" : "");
    }

    private record Index(long version, List<ObjectNode> entries, Map<String, ObjectNode> byId) {
    }
}
