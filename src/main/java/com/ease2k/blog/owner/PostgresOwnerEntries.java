package com.ease2k.blog.owner;

import com.ease2k.blog.owner.OwnerEntryMapper.EntryRow;
import com.ease2k.blog.repository.SeedArticles;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class PostgresOwnerEntries implements OwnerEntries {

    private final OwnerDatabase database;
    private final OwnerEntryMapper entryMapper;
    private final OwnerSeriesMapper seriesMapper;
    private final OwnerMediaMapper mediaMapper;
    private final SeedArticles seeds;
    private final ObjectMapper mapper;

    public PostgresOwnerEntries(OwnerDatabase database, SeedArticles seeds, ObjectMapper mapper) {
        this.database = database;
        this.entryMapper = database.mapper(OwnerEntryMapper.class);
        this.seriesMapper = database.mapper(OwnerSeriesMapper.class);
        this.mediaMapper = database.mapper(OwnerMediaMapper.class);
        this.seeds = seeds;
        this.mapper = mapper;
        if (database.enabled()) {
            database.transaction(false, () -> {
                seedSeries();
                return null;
            });
        }
    }

    @Override
    public List<ObjectNode> published() {
        return database.read(() -> entries(true));
    }

    @Override
    public List<ObjectNode> all() {
        return entries(false);
    }

    private List<ObjectNode> entries(boolean publishedOnly) {
        Map<String, ObjectNode> rows = new LinkedHashMap<>();
        seeds.entries().forEach((id, entry) -> rows.put(id,
                publishedOnly ? mapper.createObjectNode().setAll(entry) : view(entry, 0, null)));
        entryMapper.entries().forEach(row -> {
            ObjectNode entry = object(row.payload());
            String id = entry.path("id").asString();
            // 私有记录和回收站记录仍会覆盖同 ID 的基础文章。
            if (row.deleted() || publishedOnly && !SeedArticles.visible(entry)) rows.remove(id);
            else rows.put(id, publishedOnly ? entry : view(entry, row.revision(), row.updatedAt()));
        });
        Map<String, ObjectNode> series = new LinkedHashMap<>();
        for (var row : seriesMapper.series()) {
            ObjectNode item = object(row.payload());
            series.put(item.path("id").asString(), item);
        }
        for (ObjectNode entry : rows.values()) {
            mergeSeries(entry, series.get(entry.path("series").path("id").asString()));
        }
        return new ArrayList<>(rows.values());
    }

    @Override
    public List<ObjectNode> trash() {
        return entryMapper.trash().stream()
                .map(row -> view(object(row.payload()), row.revision(), row.updatedAt())).toList();
    }

    @Override
    public List<ObjectNode> series() {
        return seriesMapper.series().stream().map(row -> object(row.payload())
                .put("revision", row.revision()).put("updatedAt", row.updatedAt())).toList();
    }

    @Override
    public List<ObjectNode> drafts() {
        return entryMapper.drafts().stream().map(row -> draftView(
                object(row.payload()), row.baseRevision(), row.creating(), row.revision(), row.savedAt())).toList();
    }

    @Override
    public List<ObjectNode> history(String id) {
        OwnerInput.id(id);
        return entryMapper.history(id).stream().map(row -> {
            ObjectNode version = mapper.createObjectNode().put("revision", row.revision())
                    .put("deleted", row.deleted()).put("savedAt", row.savedAt());
            version.set("entry", object(row.payload()));
            return version;
        }).toList();
    }

    @Override
    public ObjectNode write(String id, JsonNode input) {
        int revision = OwnerInput.revision(input, "revision");
        boolean create = OwnerInput.flag(input, "create"), recordUpdate = OwnerInput.flag(input, "recordUpdate");
        ObjectNode article = OwnerInput.article(input.path("entry"), false);
        return database.transaction(true, () -> save(id, article, revision, create, false, false, recordUpdate, false));
    }

    @Override
    public void trash(String id, JsonNode input) {
        int revision = OwnerInput.revision(input, "revision");
        database.transaction(true, () -> save(id, null, revision, false, false, true, false, false));
    }

    @Override
    public ObjectNode restore(String id, JsonNode input) {
        int revision = OwnerInput.revision(input, "revision");
        return database.transaction(true, () -> {
            EntryRow previous = entryMapper.entry(id);
            if (previous == null || !previous.deleted()) throw OwnerProblem.missing("回收站中没有这篇文章。");
            ObjectNode draft = object(previous.payload()).put("visibility", "private");
            return save(id, OwnerInput.article(draft, false), revision, false, true, false, false, false);
        });
    }

    @Override
    public ObjectNode saveDraft(String id, JsonNode input) {
        OwnerInput.id(id);
        int base = OwnerInput.revision(input, "baseRevision");
        int revision = OwnerInput.revision(input, "revision");
        boolean creating = OwnerInput.flag(input, "creating");
        ObjectNode entry = OwnerInput.article(input.path("entry"), true);
        if (!id.equals(entry.path("id").asString())) throw OwnerProblem.bad("草稿 ID 不一致。");
        return database.transaction(false, () -> {
            EntryRow row = entryMapper.entry(id);
            ObjectNode previous = row == null ? seeds.entries().get(id) : object(row.payload());
            int current = row == null ? 0 : row.revision();
            if (current != base || row != null && row.deleted() || creating == (previous != null)) {
                throw OwnerProblem.conflict("原文章已更新，请重新载入后再保存编辑草稿。");
            }
            int draftRevision = entryMapper.draftRevision(id);
            if (draftRevision != revision) {
                throw OwnerProblem.conflict("编辑草稿已在其他窗口更新，请重新加载。");
            }
            String now = Instant.now().toString();
            entry.put("date", previous == null ? now : previous.path("date").asString(now));
            entry.put("read", readingMinutes(entry.path("body").asString()));
            entry.put("visibility", previous == null ? "private" : previous.path("visibility").asString("public"));
            checkMedia(entry);
            entryMapper.saveDraft(id, json(entry), base, creating, revision + 1, now);
            return draftView(entry, base, creating, revision + 1, now);
        });
    }

    // 调用方持有写事务。更新正文、追加历史与清除已提交草稿一起提交。
    @Override
    public ObjectNode save(String id, ObjectNode input, int revision, boolean create, boolean restore,
                    boolean remove, boolean recordUpdate, boolean mediaOnly) {
        OwnerInput.id(id);
        EntryRow row = entryMapper.entry(id);
        ObjectNode previous = row == null ? seeds.entries().get(id) : object(row.payload());
        int current = row == null ? 0 : row.revision();
        boolean deleted = row != null && row.deleted();
        if (current != revision || (create ? previous != null : previous == null || deleted != restore)) {
            throw OwnerProblem.conflict("文章已在其他窗口修改或删除，请重新加载。");
        }
        String now = Instant.now().toString();
        String previousPublication = previous == null ? null : publishedAt(previous);
        ObjectNode entry = remove ? previous.deepCopy() : input;
        if (!id.equals(entry.path("id").asString())) throw OwnerProblem.bad("文章 ID 不可更改。");
        boolean publicEntry = "public".equals(entry.path("visibility").asString());
        String first = !remove && previousPublication == null && publicEntry ? now : previousPublication;
        entry.put("publishedAt", first);

        ObjectNode series = null;
        if (!remove) {
            entry.put("date", first != null ? first : previous == null ? now : previous.path("date").asString(now));
            String summary = entry.path("summary").asString().strip();
            if (summary.isBlank() || "-".equals(summary) || "—".equals(summary)) {
                entry.put("summary", excerpt(entry.path("body").asString()));
            }
            if (entry.path("enSummary").asString().isBlank()) {
                entry.put("enSummary", excerpt(entry.path("enBody").asString()));
            }
            entry.put("read", readingMinutes(entry.path("body").asString()));
            if (recordUpdate && previousPublication != null && publicEntry) {
                if (entry.path("updateNote").asString().isBlank()) throw OwnerProblem.bad("请填写实质更新说明。");
                entry.put("modifiedAt", now);
            } else {
                for (String field : List.of("modifiedAt", "updateNote", "enUpdateNote")) {
                    if (previous != null && previous.hasNonNull(field)) entry.set(field, previous.path(field));
                    else entry.remove(field);
                }
            }
            if (mediaOnly && previous != null) {
                for (String field : List.of("summary", "enSummary", "read")) {
                    if (previous.has(field)) entry.set(field, previous.path(field));
                }
            }
            checkMedia(entry);
            series = attachSeries(entry, now);
        }

        int next = current + 1;
        if (previous != null && row == null) {
            entryMapper.seedHistory(id, json(previous), now,
                    previous.path("visibility").asString(null), previous.path("date").asString(null));
        }
        String text = json(entry);
        entryMapper.saveEntry(id, text, next, remove, now);
        // 与历史正文一起提交索引字段，使 JSON 中转义的空字符仍可原样存入正文。
        entryMapper.appendHistory(id, next, text, remove, now,
                entry.path("visibility").asString(null), entry.path("date").asString(null));
        entryMapper.pruneHistory(id, next - 29);
        if (!mediaOnly) entryMapper.deleteDraft(id);
        ObjectNode result = view(entry, next, now);
        mergeSeries(result, series);
        return result;
    }

    private ObjectNode object(String text) {
        return (ObjectNode) mapper.readTree(text);
    }

    private String json(JsonNode value) {
        return mapper.writeValueAsString(value);
    }

    private ObjectNode view(ObjectNode source, int revision, String updatedAt) {
        ObjectNode entry = source.deepCopy().put("revision", revision);
        if (updatedAt != null) entry.put("updatedAt", updatedAt);
        entry.put("publishedAt", publishedAt(source));
        return entry;
    }

    private String publishedAt(ObjectNode entry) {
        if (entry.has("publishedAt")) return entry.path("publishedAt").asString(null);
        if (!"private".equals(entry.path("visibility").asString())) return entry.path("date").asString(null);
        String id = entry.path("id").asString();
        ObjectNode seed = seeds.entries().get(id);
        if (seed != null) return seed.path("date").asString(null);
        return entryMapper.firstPublishedDate(id);
    }

    private ObjectNode draftView(ObjectNode entry, int base, boolean creating, int revision, String savedAt) {
        return mapper.createObjectNode().put("baseRevision", base).put("creating", creating)
                .put("revision", revision).put("savedAt", savedAt).set("entry", entry);
    }

    private void checkMedia(ObjectNode entry) {
        OwnerInput.IMAGE_PATH.matcher(json(entry)).results().map(match -> match.group(1)).distinct().forEach(name -> {
            if (mediaMapper.deleted(name)) {
                throw OwnerProblem.bad("图片已在回收区，请先恢复。");
            }
        });
    }

    private void seedSeries() {
        Map<String, List<ObjectNode>> groups = new LinkedHashMap<>();
        for (ObjectNode entry : all()) {
            if (entry.path("series") instanceof ObjectNode series && !series.path("title").asString().isBlank()) {
                groups.computeIfAbsent(series.path("id").asString(), ignored -> new ArrayList<>()).add(entry);
            }
        }
        groups.forEach((id, chapters) -> {
            chapters.sort(Comparator.comparingInt(article -> article.path("series").path("order").asInt(1)));
            ObjectNode first = ((ObjectNode) chapters.getFirst().path("series")).deepCopy();
            first.remove("order");
            ArrayNode order = first.putArray("chapterOrder");
            chapters.forEach(article -> order.add(article.path("id").asString()));
            seriesMapper.seedSeries(id, json(first), Instant.now().toString());
        });
    }

    private ObjectNode attachSeries(ObjectNode entry, String now) {
        if (!(entry.path("series") instanceof ObjectNode series)) return null;
        String id = series.path("id").asString();
        String chapter = entry.path("id").asString();
        String payload = seriesMapper.payload(id);
        if (payload == null) {
            if (series.path("title").asString().isBlank()) return null;
            ObjectNode metadata = series.deepCopy();
            metadata.remove("order");
            metadata.putArray("chapterOrder").add(chapter);
            seriesMapper.insertSeries(id, json(metadata), now);
            return metadata;
        }
        ObjectNode current = object(payload);
        JsonNode stored = current.path("chapterOrder");
        List<String> order = new ArrayList<>();
        stored.forEach(value -> order.add(value.asString()));
        int old = order.indexOf(chapter);
        order.remove(chapter);
        int next = Math.min(series.path("order").asInt(1) - 1, order.size());
        if (old != next) {
            order.add(next, chapter);
            ArrayNode updated = current.putArray("chapterOrder");
            order.forEach(updated::add);
            seriesMapper.reviseSeries(id, json(current), now);
        }
        return current;
    }

    private static void mergeSeries(ObjectNode entry, ObjectNode metadata) {
        if (metadata == null) return;
        int index = chapterIndex(metadata.path("chapterOrder"), entry.path("id").asString());
        ObjectNode merged = metadata.deepCopy();
        merged.remove(List.of("chapterOrder", "revision", "updatedAt"));
        merged.put("order", index < 0 ? entry.path("series").path("order").asInt(1) : index + 1);
        entry.set("series", merged);
    }

    static int chapterIndex(JsonNode order, String chapter) {
        for (int i = 0; i < order.size(); i++) {
            if (chapter.equals(order.path(i).asString())) return i;
        }
        return -1;
    }

    private static String excerpt(String markdown) {
        String text = markdown.replaceAll("(?s)```.*?```", " ")
                .replaceAll("!\\[[^]]*]\\([^)]*\\)", " ")
                .replaceAll("\\[([^]]+)]\\([^)]*\\)", "$1")
                .replaceAll("<[^>]+>|[#*_`~>|]", " ").replaceAll("\\s+", " ").strip();
        return text.codePointCount(0, text.length()) <= 140 ? text : text.substring(0, text.offsetByCodePoints(0, 140)) + "…";
    }

    private static int readingMinutes(String markdown) {
        String text = markdown.replaceAll("!\\[[^]]*]\\([^)]*\\)", " ");
        long chinese = text.codePoints().filter(code -> code >= 0x3400 && code <= 0x9fff).count();
        int words = text.replaceAll("[^A-Za-z0-9_]+", " ").strip().split(" ").length;
        return Math.clamp((int) Math.ceil(chinese / 350.0 + words / 200.0), 1, 10000);
    }
}
