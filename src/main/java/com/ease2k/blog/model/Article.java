package com.ease2k.blog.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/** 文章数据文件与详情接口共用的模型，字段名需与前端保持一致。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Article(
        String id,
        String kind,
        String title,
        String enTitle,
        String summary,
        String enSummary,
        String body,
        String enBody,
        String date,
        List<String> tags,
        int read,
        String visibility,
        Boolean sample,
        String section,
        Map<String, String> playground,
        Boolean featured,
        String modifiedAt,
        String updateNote,
        String enUpdateNote,
        String environment,
        JsonNode project
) {
}
