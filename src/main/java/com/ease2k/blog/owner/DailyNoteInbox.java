package com.ease2k.blog.owner;

import com.ease2k.blog.model.ArticleDates;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/** 由服务器本机的站点维护命令提交，沿用站主工作台的事务和公开快照。 */
@Component
public class DailyNoteInbox {

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private final OwnerDatabase database;
    private final OwnerEntryMapper entryMapper;
    private final OwnerEntries entries;
    private final ObjectMapper mapper;

    public DailyNoteInbox(OwnerDatabase database, OwnerEntries entries, ObjectMapper mapper) {
        this.database = database;
        this.entryMapper = database.mapper(OwnerEntryMapper.class);
        this.entries = entries;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelay = 3000)
    public void process() throws IOException {
        if (!database.enabled()) return;
        Path inbox = database.directory().resolve("daily-note-inbox");
        Path request = inbox.resolve("request.json");
        if (!Files.isRegularFile(request)) return;

        Map<String, String> result;
        try (var input = Files.newInputStream(request)) {
            byte[] data = input.readNBytes(32769);
            if (data.length > 32768) throw OwnerProblem.bad("小记数据过大。");
            result = submit(mapper.readTree(data));
        } catch (JacksonException error) {
            result = Map.of("status", "error", "message", "小记需要使用 JSON 对象。");
        } catch (OwnerProblem error) {
            result = Map.of("status", "error", "message", error.getMessage());
        }

        Path temporary = inbox.resolve("result.tmp");
        Files.writeString(temporary, mapper.writeValueAsString(result));
        Files.move(temporary, inbox.resolve("result.json"), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        Files.delete(request);
    }

    private Map<String, String> submit(JsonNode input) {
        OwnerInput.requireObject(input, "小记需要使用 JSON 对象。");
        if (input.path("check").isBoolean() && input.path("check").asBoolean()) {
            return Map.of("status", "ready");
        }
        LocalDate today = LocalDate.now(BEIJING);
        if (!today.toString().equals(input.path("day").asString())) {
            throw OwnerProblem.bad("小记日期需要是北京时间当天。");
        }
        String id = "daily-note-" + today;
        String body = OwnerInput.text(input, "body", 6000).strip();
        if (body.isBlank()) throw OwnerProblem.bad("请填写小记正文。");
        boolean draft = OwnerInput.flag(input, "draft");
        String title = OwnerInput.text(input, "title", 120).strip();
        ObjectNode article = mapper.createObjectNode().put("id", id).put("kind", "daily")
                .put("title", title.isBlank() ? today.toString().replace('-', '.') + " 小记" : title)
                .put("body", body).put("visibility", draft ? "private" : "public");
        article.putArray("tags").add("日常");
        ObjectNode validated = OwnerInput.article(article, false);

        return database.transaction(!draft, () -> {
            for (ObjectNode existing : entries.all()) {
                if (sameNote(existing, id, today)) {
                    String status = "public".equals(existing.path("visibility").asString()) ? "already_published" : "already_saved";
                    return Map.of("status", status, "id", existing.path("id").asString());
                }
            }
            for (ObjectNode working : entries.drafts()) {
                if (sameNote(working.path("entry"), id, today)) {
                    return Map.of("status", "existing_draft", "id", working.path("entry").path("id").asString());
                }
            }
            if (entryMapper.exists(id)) {
                return Map.of("status", "in_trash", "id", id);
            }
            entries.save(id, validated, 0, true, false, false, false, false);
            return Map.of("status", draft ? "draft_saved" : "published", "id", id);
        });
    }

    private static boolean sameNote(JsonNode entry, String id, LocalDate today) {
        if (id.equals(entry.path("id").asString())) return true;
        return "daily".equals(entry.path("kind").asString()) && entry.hasNonNull("date")
                && today.equals(ArticleDates.parse(entry.path("date").asString()).atZoneSameInstant(BEIJING).toLocalDate());
    }
}
