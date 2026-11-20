package com.ease2k.blog.controller;

import com.ease2k.blog.model.Article;
import com.ease2k.blog.service.ArticleService;
import com.ease2k.blog.service.RatingsService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/** 公开博客的只读 JSON 接口。 */
@RestController
@RequestMapping("/api")
public class ArticleController {

    private final ArticleService articleService;
    private final RatingsService ratings;

    public ArticleController(ArticleService articleService, RatingsService ratings) {
        this.articleService = articleService;
        this.ratings = ratings;
    }

    /** 返回列表所需的摘要和元数据，正文由详情接口按需提供。 */
    @GetMapping("/index")
    public ArticleIndex index() {
        return new ArticleIndex(articleService.list(), true);
    }

    /** 保留原站的公开内容导出接口。 */
    @GetMapping("/blog")
    public Map<String, ?> blog() {
        return Map.of("entries", articleService.articlesForFeed(), "ready", true);
    }

    /** 未知 ID 和未公开的文章统一返回 404。 */
    @GetMapping("/entries/{id}")
    public ResponseEntity<?> entry(@PathVariable String id) {
        return articleService.findById(id)
                .<ResponseEntity<?>>map(article -> ResponseEntity.ok(new ArticleDetail(article)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "记录暂不可见")));
    }

    @GetMapping("/search")
    public SearchResults search(@RequestParam(defaultValue = "") String q) {
        return new SearchResults(articleService.search(q));
    }

    @GetMapping("/ratings")
    public JsonNode ratings() {
        return ratings.snapshot();
    }

    public record ArticleIndex(List<ObjectNode> entries, boolean ready) {
    }

    public record ArticleDetail(Article entry) {
    }

    public record SearchResults(List<ObjectNode> entries) {
    }
}
