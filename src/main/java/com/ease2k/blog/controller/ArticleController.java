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

import java.net.URI;
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

    /** 旧链接转到公开文章导出地址，导出格式可独立演进。 */
    @GetMapping("/blog")
    public ResponseEntity<Void> legacyExport() {
        return ResponseEntity.status(HttpStatus.PERMANENT_REDIRECT).location(URI.create("/api/exports/articles")).build();
    }

    @GetMapping("/exports/articles")
    public ArticleExport export() {
        return new ArticleExport(1, articleService.articlesForFeed(), true);
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

    public record ArticleExport(int version, List<Article> entries, boolean ready) {
    }

    public record SearchResults(List<ObjectNode> entries) {
    }
}
