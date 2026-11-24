package com.ease2k.blog.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class RatingsServiceImpl implements RatingsService {

    private static final Logger LOG = LoggerFactory.getLogger(RatingsService.class);
    private final ObjectMapper mapper;
    private final boolean live;
    private final String codeforcesHandle;
    private final String atcoderHandle;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private volatile ObjectNode current;

    public RatingsServiceImpl(ObjectMapper mapper,
                          @Value("${blog.ratings-live:false}") boolean live,
                          @Value("${blog.codeforces-handle:}") String codeforcesHandle,
                          @Value("${blog.atcoder-handle:}") String atcoderHandle) throws IOException {
        this.mapper = mapper;
        this.live = live;
        this.codeforcesHandle = codeforcesHandle;
        this.atcoderHandle = atcoderHandle;
        try (InputStream input = new ClassPathResource("data/ratings-snapshot.json").getInputStream()) {
            current = (ObjectNode) mapper.readTree(input);
            current.withObjectProperty("codeforces").put("stale", true);
            current.withObjectProperty("atcoder").put("stale", true);
        }
    }

    @Override
    public JsonNode snapshot() {
        return current;
    }

    @Scheduled(fixedDelay = 900_000)
    public void refresh() {
        if (!live) {
            return;
        }
        // 昵称只能作为固定上游地址中的一个参数。
        if (codeforcesHandle.matches("[A-Za-z0-9_.-]+")) {
            refresh("codeforces", URI.create("https://codeforces.com/api/user.rating?handle=" + codeforcesHandle));
        }
        if (atcoderHandle.matches("[A-Za-z0-9_]+")) {
            refresh("atcoder", URI.create("https://atcoder.jp/users/" + atcoderHandle + "/history/json"));
        }
    }

    private void refresh(String platform, URI endpoint) {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(6))
                    .header("Accept", "application/json").GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body().length > 2_000_000) {
                throw new IOException("上游返回 HTTP " + response.statusCode());
            }
            JsonNode payload = mapper.readTree(response.body());
            List<RatingPoint> history = history(platform, payload);
            ObjectNode value = mapper.createObjectNode();
            value.put("rating", history.getLast().rating());
            value.put("maxRating", history.stream().mapToInt(RatingPoint::rating).max().orElseThrow());
            value.put("fetchedAt", Instant.now().toString());
            value.put("stale", false);
            value.set("history", mapper.valueToTree(history));
            replace(platform, value);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException error) {
            // 两个来源分别更新。上游异常时保留该平台上一次完整记录。
            ObjectNode fallback = (ObjectNode) current.path(platform).deepCopy();
            fallback.put("stale", true);
            replace(platform, fallback);
            LOG.warn("{} Rating 更新失败，继续使用缓存 ({})", platform, error.toString());
        }
    }

    private void replace(String platform, ObjectNode value) {
        // 请求线程只读取完整快照，后台在副本上替换一个平台的数据。
        ObjectNode next = current.deepCopy();
        next.set(platform, value);
        current = next;
    }

    private List<RatingPoint> history(String platform, JsonNode payload) {
        boolean cf = "codeforces".equals(platform);
        JsonNode rows = cf ? payload.path("result") : payload;
        if (!(rows instanceof ArrayNode) || (cf && !"OK".equals(payload.path("status").asString()))) {
            throw new IllegalArgumentException("上游没有返回比赛历史");
        }

        List<RatingPoint> points = new ArrayList<>();
        for (JsonNode row : rows) {
            if (!cf && !row.path("IsRated").asBoolean()) {
                continue;
            }
            if (cf && !codeforcesHandle.equalsIgnoreCase(row.path("handle").asString())) {
                throw new IllegalArgumentException("Codeforces 用户不一致");
            }
            String id = cf ? row.path("contestId").asString()
                    : row.path("ContestScreenName").asString().replaceFirst("\\.contest\\.atcoder\\.jp$", "");
            String name = row.path(cf ? "contestName" : "ContestName").asString();
            int rating = row.path(cf ? "newRating" : "NewRating").asInt(-1);
            int oldRating = row.path(cf ? "oldRating" : "OldRating").asInt(-1);
            int rank = row.path(cf ? "rank" : "Place").asInt(-1);
            if (!id.matches(cf ? "[1-9][0-9]*" : "[A-Za-z0-9_-]+") || name.isBlank()
                    || rating < 0 || oldRating < 0 || rank <= 0) {
                throw new IllegalArgumentException("比赛记录缺少有效字段");
            }
            Instant date = cf ? Instant.ofEpochSecond(row.path("ratingUpdateTimeSeconds").asLong())
                    : OffsetDateTime.parse(row.path("EndTime").asString()).toInstant();
            String enName = cf ? name : row.path("ContestNameEn").asString(name);
            String link = (cf ? "https://codeforces.com/contest/" : "https://atcoder.jp/contests/") + id;
            points.add(new RatingPoint(id, name, enName, date.toString(), oldRating, rating, rank, link));
        }
        if (points.isEmpty()) {
            throw new IllegalArgumentException("上游返回空比赛历史");
        }
        // Instant.toString 的小数部分可省略，按解析后的时间排序。
        points.sort(Comparator.comparing(point -> Instant.parse(point.date())));
        return List.copyOf(points);
    }

    private record RatingPoint(String id, String name, String enName, String date, int oldRating,
                               int rating, int rank, String href) {
    }
}
