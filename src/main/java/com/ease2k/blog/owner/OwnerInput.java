package com.ease2k.blog.owner;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

final class OwnerInput {

    static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,99}");
    static final Pattern SERIES_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,79}");
    static final Pattern IMAGE_NAME = Pattern.compile("[a-f0-9-]{36}\\.webp");
    static final Pattern IMAGE_PATH = Pattern.compile("/media/([a-f0-9-]{36}\\.webp)(?![\\w./-])");

    private OwnerInput() {
    }

    static ObjectNode article(JsonNode value, boolean working) {
        requireObject(value, "文章数据不正确。");
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("id", id(value.path("id").asString()));
        String title = text(value, "title", 240);
        if (!working && title.isBlank()) throw OwnerProblem.bad("请填写文章标题。");
        result.put("title", title);
        copyText(result, value, 240, "enTitle");
        copyText(result, value, 2000, "summary", "enSummary");
        copyText(result, value, 700000, "body", "enBody");
        copyText(result, value, 1000, "updateNote", "enUpdateNote");
        copyText(result, value, 300, "environment");

        String kind = enumValue(value, "kind", Set.of("article", "daily", "project"));
        String visibility = working ? "private" : enumValue(value, "visibility", Set.of("public", "private"));
        if ("public".equals(visibility) && result.path("body").asString().isBlank()) {
            throw OwnerProblem.bad("发布前请填写正文。");
        }
        result.put("kind", kind).put("visibility", visibility).put("featured", flag(value, "featured"));
        result.set("tags", list(value.path("tags"), 20, 40, true));
        if (value.hasNonNull("section")) {
            result.put("section", enumValue(value, "section", Set.of("competitions")));
        }
        if (value.hasNonNull("series")) {
            result.set("series", series(value.path("series"), false, !"public".equals(visibility)));
        }
        if (value.hasNonNull("playground")) {
            JsonNode playground = value.path("playground");
            requireObject(playground, "在线示例格式不正确。");
            ObjectNode code = result.putObject("playground");
            copyText(code, playground, 50000, "html", "css", "js");
        }
        if ("project".equals(kind) && value.hasNonNull("project")) {
            result.set("project", project(value.path("project")));
        }
        return result;
    }

    static ObjectNode series(JsonNode value, boolean registry, boolean allowBlankTitle) {
        requireObject(value, "专栏数据不正确。");
        String id = value.path("id").asString();
        if (!SERIES_ID.matcher(id).matches()) throw OwnerProblem.bad("专栏地址不正确。");
        ObjectNode result = JsonNodeFactory.instance.objectNode().put("id", id);
        copyText(result, value, 120, "title", "enTitle");
        copyText(result, value, 2000, "description", "enDescription");
        if (!allowBlankTitle && result.path("title").asString().isBlank()) {
            throw OwnerProblem.bad("请填写专栏标题。");
        }
        if (registry) {
            String cover = text(value, "cover", 100);
            if (!cover.isBlank()) {
                if (!IMAGE_PATH.matcher(cover).matches()) throw OwnerProblem.bad("请从图片库选择封面。");
                result.put("cover", cover);
            }
            ArrayNode chapters = list(value.path("chapterOrder"), 10000, 100, false);
            chapters.forEach(entry -> id(entry.asString()));
            result.set("chapterOrder", chapters);
        } else {
            result.put("order", integer(value, "order", 1, 10000));
        }
        return result;
    }

    private static ObjectNode project(JsonNode value) {
        requireObject(value, "项目数据不正确。");
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("status", enumValue(value, "status", Set.of("active", "complete", "paused")));
        copyText(result, value, 2000, "problem", "enProblem");
        copyText(result, value, 300, "screenshotAlt");
        for (String key : new String[]{"demoUrl", "sourceUrl"}) {
            String url = text(value, key, 1000).strip();
            if (!url.isEmpty() && !safeLink(url)) throw OwnerProblem.bad("项目链接需要使用 HTTP(S) 或站内路径。");
            result.put(key, url);
        }
        String picture = text(value, "screenshot", 500).strip();
        if (!picture.isEmpty() && !IMAGE_PATH.matcher(picture).matches()
                && !picture.matches("/projects/[A-Za-z0-9_-]+\\.(webp|png|jpg|jpeg|avif)")) {
            throw OwnerProblem.bad("项目截图需要使用已上传图片或项目资源。");
        }
        result.put("screenshot", picture);
        result.set("stack", list(value.path("stack"), 20, 40, false));
        result.set("outcomes", list(value.path("outcomes"), 20, 500, false));
        result.set("enOutcomes", list(value.path("enOutcomes"), 20, 500, false));
        return result;
    }

    private static boolean safeLink(String url) {
        if (url.matches("/(?!/|\\\\)[^\\s\\\\]*")) return true;
        try {
            URI uri = URI.create(url);
            return Set.of("http", "https").contains(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return false;
        }
    }

    static String id(String id) {
        if (!ID.matcher(id).matches()) throw OwnerProblem.bad("文章 ID 格式不正确。");
        return id;
    }

    static int revision(JsonNode value, String name) {
        return integer(value, name, 0, Integer.MAX_VALUE);
    }

    static int integer(JsonNode value, String key, int min, int max) {
        JsonNode field = value.path(key);
        if (!field.isIntegralNumber() || !field.canConvertToInt() || field.asInt() < min || field.asInt() > max) {
            throw OwnerProblem.bad("数值字段不正确：" + key);
        }
        return field.asInt();
    }

    static boolean flag(JsonNode value, String key) {
        JsonNode field = value.path(key);
        if (!field.isMissingNode() && !field.isNull() && !field.isBoolean()) {
            throw OwnerProblem.bad("开关字段不正确：" + key);
        }
        return field.asBoolean();
    }

    static String text(JsonNode value, String key, int limit) {
        JsonNode field = value.path(key);
        if (field.isMissingNode() || field.isNull()) return "";
        if (!field.isTextual() || field.asString().length() > limit) {
            throw OwnerProblem.bad("内容格式或长度不正确：" + key);
        }
        return field.asString();
    }

    static void requireObject(JsonNode value, String error) {
        if (value == null || !value.isObject()) throw OwnerProblem.bad(error);
    }

    private static void copyText(ObjectNode result, JsonNode value, int limit, String... keys) {
        for (String key : keys) result.put(key, text(value, key, limit));
    }

    private static String enumValue(JsonNode value, String key, Set<String> allowed) {
        String text = value.path(key).asString();
        if (!allowed.contains(text)) throw OwnerProblem.bad("字段值不正确：" + key);
        return text;
    }

    private static ArrayNode list(JsonNode array, int max, int length, boolean required) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        if (!required && (array.isMissingNode() || array.isNull())) return result;
        if (!array.isArray() || array.size() > max) throw OwnerProblem.bad("列表格式不正确。");
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : array) {
            if (!item.isTextual() || item.asString().length() > length || required && item.asString().isBlank()) {
                throw OwnerProblem.bad("列表条目的格式或长度不正确。");
            }
            String text = item.asString().strip();
            if (!text.isBlank() && seen.add(text)) result.add(text);
        }
        return result;
    }
}
