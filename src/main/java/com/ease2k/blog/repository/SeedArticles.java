package com.ease2k.blog.repository;

import com.ease2k.blog.config.BlogSite;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Repository
public class SeedArticles {

    private final Map<String, ObjectNode> entries;

    public SeedArticles(ObjectMapper mapper, BlogSite site) throws IOException {
        try (InputStream input = site.contentDirectory() == null
                ? new ClassPathResource("data/articles.json").getInputStream()
                : Files.newInputStream(site.contentDirectory().resolve("articles.json"))) {
            JsonNode array = mapper.readTree(input);
            if (!array.isArray()) {
                throw new IllegalArgumentException("文章数据文件需要包含 JSON 数组");
            }
            Map<String, ObjectNode> seed = new LinkedHashMap<>();
            for (JsonNode value : array) {
                if (!(value instanceof ObjectNode entry) || !visible(entry)) continue;
                String id = entry.path("id").asString();
                if (seed.putIfAbsent(id, entry) != null) {
                    throw new IllegalArgumentException("文章数据中存在重复 ID: " + id);
                }
            }
            entries = Collections.unmodifiableMap(seed);
        }
    }

    public Map<String, ObjectNode> entries() {
        return entries;
    }

    public static boolean visible(JsonNode entry) {
        return !"private".equals(entry.path("visibility").asString())
                && !entry.path("sample").asBoolean()
                && !entry.path("body").asString().isBlank();
    }
}
