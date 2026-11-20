package com.ease2k.blog.owner;

import com.ease2k.blog.repository.SeedArticles;
import org.springframework.jdbc.core.RowMapper;
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
import java.util.Objects;

@Repository
public class OwnerEntries {

    private final OwnerDatabase database;
    private final SeedArticles seeds;
    private final ObjectMapper mapper;
    private final RowMapper<EntryRow> entryRow = (row, number) -> new EntryRow(
            object(row.getString("payload")), row.getInt("revision"), row.getBoolean("deleted"), row.getString("updated_at"));

    public OwnerEntries(OwnerDatabase database, SeedArticles seeds, ObjectMapper mapper) {
        this.database = database;
        this.seeds = seeds;
        this.mapper = mapper;
        if (database.enabled()) {
            database.transaction(false, () -> {
                seedSeries();
                return null;
            });
        }
    }

    public List<ObjectNode> published() {
        return database.transaction(false, () -> all().stream().filter(SeedArticles::visible).toList());
    }

    List<ObjectNode> all() {
        Map<String, ObjectNode> rows = new LinkedHashMap<>();
        seeds.entries().forEach((id, entry) -> rows.put(id, view(entry, 0, null)));
        database.sql().query("SELECT * FROM entries", entryRow).forEach(row -> {
            String id = row.value().path("id").asString();
            if (row.deleted()) rows.remove(id);
            else rows.put(id, view(row.value(), row.revision(), row.updatedAt()));
        });
        Map<String, ObjectNode> series = new LinkedHashMap<>();
        for (ObjectNode item : series()) {
            item.remove(List.of("revision", "updatedAt"));
            series.put(item.path("id").asString(), item);
        }
        for (ObjectNode entry : rows.values()) {
            JsonNode current = entry.path("series");
            ObjectNode metadata = series.get(current.path("id").asString());
            if (metadata == null) continue;
            int index = chapterIndex(metadata.path("chapterOrder"), entry.path("id").asString());
            ObjectNode merged = metadata.deepCopy();
            merged.remove("chapterOrder");
            merged.put("order", index < 0 ? current.path("order").asInt(1) : index + 1);
            entry.set("series", merged);
        }
        return new ArrayList<>(rows.values());
    }

    List<ObjectNode> trash() {
        return database.sql().query("SELECT * FROM entries WHERE deleted=1 ORDER BY updated_at DESC", entryRow)
                .stream().map(row -> view(row.value(), row.revision(), row.updatedAt())).toList();
    }

    List<ObjectNode> series() {
        return database.sql().query("SELECT * FROM series ORDER BY id", (row, index) -> object(row.getString("payload"))
                .put("revision", row.getInt("revision")).put("updatedAt", row.getString("updated_at")));
    }

    List<ObjectNode> drafts() {
        return database.sql().query("SELECT * FROM working_copies ORDER BY saved_at DESC", (row, index) -> draftView(
                object(row.getString("payload")), row.getInt("base_revision"),
                row.getBoolean("creating"), row.getInt("revision"), row.getString("saved_at")));
    }

    List<ObjectNode> history(String id) {
        OwnerInput.id(id);
        return database.sql().query("SELECT * FROM history WHERE id=? ORDER BY revision DESC", (row, index) ->
                mapper.createObjectNode().put("revision", row.getInt("revision"))
                        .put("deleted", row.getBoolean("deleted")).put("savedAt", row.getString("saved_at"))
                        .set("entry", object(row.getString("payload"))), id);
    }

    ObjectNode write(String id, JsonNode input) {
        int revision = OwnerInput.revision(input, "revision");
        boolean create = OwnerInput.flag(input, "create"), recordUpdate = OwnerInput.flag(input, "recordUpdate");
        ObjectNode article = OwnerInput.article(input.path("entry"), false);
        return database.transaction(true, () -> save(id, article, revision, create, false, false, recordUpdate, false));
    }

    void trash(String id, JsonNode input) {
        int revision = OwnerInput.revision(input, "revision");
        database.transaction(true, () -> save(id, null, revision, false, false, true, false, false));
    }

    ObjectNode restore(String id, JsonNode input) {
        int revision = OwnerInput.revision(input, "revision");
        return database.transaction(true, () -> {
            EntryRow previous = row(id);
            if (previous == null || !previous.deleted()) throw OwnerProblem.missing("回收站中没有这篇文章。");
            ObjectNode draft = previous.value().deepCopy().put("visibility", "private");
            return save(id, OwnerInput.article(draft, false), revision, false, true, false, false, false);
        });
    }

    ObjectNode saveDraft(String id, JsonNode input) {
        OwnerInput.id(id);
        int base = OwnerInput.revision(input, "baseRevision");
        int revision = OwnerInput.revision(input, "revision");
        boolean creating = OwnerInput.flag(input, "creating");
        ObjectNode entry = OwnerInput.article(input.path("entry"), true);
        if (!id.equals(entry.path("id").asString())) throw OwnerProblem.bad("草稿 ID 不一致。");
        return database.transaction(false, () -> {
            EntryRow row = row(id);
            ObjectNode previous = row == null ? seeds.entries().get(id) : row.value();
            int current = row == null ? 0 : row.revision();
            if (current != base || row != null && row.deleted() || creating == (previous != null)) {
                throw OwnerProblem.conflict("原文章已更新，请重新载入后再保存编辑草稿。");
            }
            int draftRevision = database.sql().query("SELECT revision FROM working_copies WHERE id=?",
                    (result, index) -> result.getInt(1), id).stream().findFirst().orElse(0);
            if (draftRevision != revision) {
                throw OwnerProblem.conflict("编辑草稿已在其他窗口更新，请重新加载。");
            }
            String now = Instant.now().toString();
            entry.put("date", previous == null ? now : previous.path("date").asString(now));
            entry.put("read", readingMinutes(entry.path("body").asString()));
            entry.put("visibility", previous == null ? "private" : previous.path("visibility").asString("public"));
            checkMedia(entry);
            database.sql().update("INSERT OR REPLACE INTO working_copies VALUES (?,?,?,?,?,?)",
                    id, json(entry), base, creating ? 1 : 0, revision + 1, now);
            return draftView(entry, base, creating, revision + 1, now);
        });
    }

    // 调用方持有写事务。更新正文、追加历史与清除已提交草稿一起提交。
    ObjectNode save(String id, ObjectNode input, int revision, boolean create, boolean restore,
                    boolean remove, boolean recordUpdate, boolean mediaOnly) {
        OwnerInput.id(id);
        EntryRow row = row(id);
        ObjectNode previous = row == null ? seeds.entries().get(id) : row.value();
        int current = row == null ? 0 : row.revision();
        boolean deleted = row != null && row.deleted();
        if (current != revision || (create ? previous != null : previous == null || deleted != restore)) {
            throw OwnerProblem.conflict("文章已在其他窗口修改或删除，请重新加载。");
        }
        String now = Instant.now().toString();
        String first = previous == null ? null : publishedAt(previous);
        ObjectNode entry = remove ? previous.deepCopy() : input;
        if (!id.equals(entry.path("id").asString())) throw OwnerProblem.bad("文章 ID 不可更改。");
        if (!remove && first == null && "public".equals(entry.path("visibility").asString())) first = now;
        if (first == null) entry.putNull("publishedAt");
        else entry.put("publishedAt", first);

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
            if (recordUpdate && previous != null && publishedAt(previous) != null
                    && "public".equals(entry.path("visibility").asString())) {
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
            attachSeries(entry, now);
        }

        int next = current + 1;
        if (previous != null && row == null) database.sql().update("INSERT OR IGNORE INTO history VALUES (?,?,?,?,?)",
                id, 0, json(previous), 0, now);
        String text = json(entry);
        database.sql().update("INSERT OR REPLACE INTO entries VALUES (?,?,?,?,?)", id, text, next, remove ? 1 : 0, now);
        database.sql().update("INSERT INTO history VALUES (?,?,?,?,?)", id, next, text, remove ? 1 : 0, now);
        database.sql().update("DELETE FROM history WHERE id=? AND revision<?", id, next - 29);
        if (!mediaOnly) database.sql().update("DELETE FROM working_copies WHERE id=?", id);
        if (remove) return view(entry, next, now);
        return all().stream().filter(value -> id.equals(value.path("id").asString())).findFirst().orElseThrow();
    }

    ObjectNode object(String text) {
        return (ObjectNode) mapper.readTree(text);
    }

    String json(JsonNode value) {
        return mapper.writeValueAsString(value);
    }

    private EntryRow row(String id) {
        return database.sql().query("SELECT * FROM entries WHERE id=?", entryRow, id).stream().findFirst().orElse(null);
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
        return database.sql().query("""
                SELECT json_extract(payload, '$.date') FROM history
                WHERE id=? AND json_extract(payload, '$.visibility')='public' ORDER BY revision LIMIT 1
                """, (row, index) -> row.getString(1), id).stream().filter(Objects::nonNull).findFirst().orElse(null);
    }

    private ObjectNode draftView(ObjectNode entry, int base, boolean creating, int revision, String savedAt) {
        return mapper.createObjectNode().put("baseRevision", base).put("creating", creating)
                .put("revision", revision).put("savedAt", savedAt).set("entry", entry);
    }

    private void checkMedia(ObjectNode entry) {
        OwnerInput.IMAGE_PATH.matcher(json(entry)).results().map(match -> match.group(1)).distinct().forEach(name -> {
            if (!database.sql().query("SELECT deleted FROM media_assets WHERE name=? AND deleted=1",
                    (row, index) -> row.getBoolean(1), name).isEmpty()) {
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
            database.sql().update("INSERT OR IGNORE INTO series VALUES (?,?,1,?)", id, json(first), Instant.now().toString());
        });
    }

    private void attachSeries(ObjectNode entry, String now) {
        if (!(entry.path("series") instanceof ObjectNode series)) return;
        String id = series.path("id").asString();
        String chapter = entry.path("id").asString();
        ObjectNode current = database.sql().query("SELECT payload FROM series WHERE id=?",
                (row, index) -> object(row.getString(1)), id).stream().findFirst().orElse(null);
        if (current == null) {
            if (series.path("title").asString().isBlank()) return;
            ObjectNode metadata = series.deepCopy();
            metadata.remove("order");
            metadata.putArray("chapterOrder").add(chapter);
            database.sql().update("INSERT INTO series VALUES (?,?,1,?)", id, json(metadata), now);
            return;
        }
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
            database.sql().update("UPDATE series SET payload=?,revision=revision+1,updated_at=? WHERE id=?",
                    json(current), now, id);
        }
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

    private record EntryRow(ObjectNode value, int revision, boolean deleted, String updatedAt) {
    }
}
