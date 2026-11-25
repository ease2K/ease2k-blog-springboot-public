package com.ease2k.blog.service;

import com.ease2k.blog.model.Article;
import com.ease2k.blog.repository.ArticleRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class ArticleServiceImpl implements ArticleService {

    private static final Pattern CODE_BLOCK = Pattern.compile("(?s)```.*?```");
    private static final Pattern MARKDOWN_MARKS = Pattern.compile("[#*_`~>|]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

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
        return index(repository.snapshot()).searchEntries().stream()
                .map(entry -> searchEntry(entry, keyword))
                .flatMap(Optional::stream)
                .limit(30)
                .toList();
    }

    private Optional<ObjectNode> searchEntry(SearchEntry entry, String keyword) {
        for (int i = 0; i < entry.fields().size(); i++) {
            if (!entry.lowercaseFields().get(i).contains(keyword)) {
                continue;
            }
            // 摘录和语言提示取决于当前查询，只写入副本，避免串到列表或其他搜索。
            ObjectNode result = entry.summary().deepCopy();
            if (i == 1 || i == 3 || i == 5) {
                result.put("searchLanguage", "en");
            }
            result.put("searchSnippet", i < 2 ? result.path("summary").asString() : snippet(entry.fields().get(i), keyword));
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
            List<ObjectNode> summaries = new ArrayList<>(snapshot.entries().size());
            List<SearchEntry> searchEntries = new ArrayList<>(snapshot.entries().size());
            for (Article article : snapshot.entries()) {
                ObjectNode summary = indexEntry(mapper, article);
                // 字段顺序决定摘录来源和语言。随发布快照统一更新大小写索引。
                List<String> fields = List.of(article.title(), article.enTitle(), article.summary(), article.enSummary(),
                        article.body(), article.enBody(), String.join(" ", article.tags()));
                summaries.add(summary);
                searchEntries.add(new SearchEntry(summary, fields, fields.stream()
                        .map(text -> text.toLowerCase(Locale.ROOT)).toList()));
            }
            Index updated = new Index(snapshot.version(), List.copyOf(summaries), List.copyOf(searchEntries));
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
        String plain = CODE_BLOCK.matcher(text).replaceAll(" ");
        plain = MARKDOWN_MARKS.matcher(plain).replaceAll(" ");
        plain = WHITESPACE.matcher(plain).replaceAll(" ").strip();
        int match = plain.toLowerCase(Locale.ROOT).indexOf(keyword);
        int start = Math.max(0, match - 45);
        int end = Math.min(plain.length(), start + 140);
        return (start > 0 ? "…" : "") + plain.substring(start, end) + (end < plain.length() ? "…" : "");
    }

    private record SearchEntry(ObjectNode summary, List<String> fields, List<String> lowercaseFields) {
    }

    private record Index(long version, List<ObjectNode> entries, List<SearchEntry> searchEntries) {
    }
}
