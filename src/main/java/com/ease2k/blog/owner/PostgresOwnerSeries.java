package com.ease2k.blog.owner;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class PostgresOwnerSeries implements OwnerSeries {

    private final OwnerDatabase database;
    private final OwnerSeriesMapper seriesMapper;
    private final OwnerMediaMapper mediaMapper;
    private final OwnerEntries entries;
    private final ObjectMapper mapper;

    public PostgresOwnerSeries(OwnerDatabase database, OwnerEntries entries, ObjectMapper mapper) {
        this.database = database;
        this.seriesMapper = database.mapper(OwnerSeriesMapper.class);
        this.mediaMapper = database.mapper(OwnerMediaMapper.class);
        this.entries = entries;
        this.mapper = mapper;
    }

    @Override
    public ObjectNode save(JsonNode input) {
        int revision = OwnerInput.revision(input, "revision");
        boolean create = OwnerInput.flag(input, "create");
        ObjectNode series = OwnerInput.series(input.path("series"), true, false);
        String id = series.path("id").asString();
        return database.transaction(true, () -> {
            ObjectNode previous = entries.series().stream().filter(value -> id.equals(value.path("id").asString()))
                    .findFirst().orElse(null);
            if (create ? previous != null || revision != 0 : previous == null || previous.path("revision").asInt() != revision) {
                throw OwnerProblem.conflict("专栏已被其他窗口修改，请重新加载。");
            }
            Set<String> members = new LinkedHashSet<>();
            for (ObjectNode entry : entries.all()) {
                if (id.equals(entry.path("series").path("id").asString())) members.add(entry.path("id").asString());
            }
            ArrayNode chapters = (ArrayNode) series.path("chapterOrder");
            for (JsonNode value : chapters) {
                if (!members.remove(value.asString())) throw OwnerProblem.bad("章节不属于当前专栏。");
            }
            members.forEach(chapters::add);
            if (series.has("cover") && !mediaMapper.active(series.path("cover").asString().substring(7))) {
                throw OwnerProblem.bad("封面图片不存在或已移入回收区。");
            }
            String now = Instant.now().toString();
            int next = revision + 1;
            seriesMapper.saveSeries(id, mapper.writeValueAsString(series), next, now);
            return series.put("revision", next).put("updatedAt", now);
        });
    }
}
