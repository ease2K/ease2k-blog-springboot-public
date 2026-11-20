package com.ease2k.blog.controller;

import com.ease2k.blog.model.Article;
import com.ease2k.blog.service.ArticleService;
import com.ease2k.blog.service.PageRenderer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 提供 React 页面入口，并为直接访问的文章附上正文和元数据。 */
@RestController
public class PageController {

    private final ArticleService articles;
    private final PageRenderer pages;

    public PageController(ArticleService articles, PageRenderer pages) {
        this.articles = articles;
        this.pages = pages;
    }

    @GetMapping(value = {"/", "/daily", "/articles", "/projects", "/algorithms", "/cpp", "/java", "/mysql",
            "/html", "/css", "/javascript", "/about", "/series", "/subscribe", "/changelog",
            "/competitions", "/competitions/awards", "/competitions/problems", "/competitions/tutorials"},
            produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> section(HttpServletRequest request) {
        return html(request, null);
    }

    @GetMapping(value = "/entry/{id}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> article(@PathVariable String id, HttpServletRequest request) {
        Article article = articles.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return html(request, article);
    }

    @GetMapping(value = "/series/{id}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> series(@PathVariable String id, HttpServletRequest request) {
        if (!articles.hasSeries(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return html(request, null);
    }

    @GetMapping(value = "/award/{id}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> award(@PathVariable String id, HttpServletRequest request) {
        if (!articles.hasAward(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return html(request, null);
    }

    private ResponseEntity<String> html(HttpServletRequest request, Article article) {
        return ResponseEntity.ok().header(HttpHeaders.VARY, HttpHeaders.ACCEPT_LANGUAGE)
                .cacheControl(CacheControl.noCache()).contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .body(pages.render(request, article));
    }
}
