package com.ease2k.blog.controller;

import com.ease2k.blog.config.BlogSite;
import com.ease2k.blog.model.Article;
import com.ease2k.blog.model.ArticleDates;
import com.ease2k.blog.service.ArticleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class SiteController {

    private final BlogSite site;
    private final ArticleService articles;

    public SiteController(BlogSite site, ArticleService articles) {
        this.site = site;
        this.articles = articles;
    }

    @GetMapping("/healthz")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @GetMapping(value = "/robots.txt", produces = "text/plain;charset=UTF-8")
    public String robots() {
        if (!site.production()) {
            return "User-agent: *\nDisallow: /\n";
        }
        return "User-agent: *\nDisallow: /api/\nDisallow: /index.html\nSitemap: " + site.origin() + "/sitemap.xml\n";
    }

    @GetMapping(value = "/sitemap.xml", produces = "application/xml;charset=UTF-8")
    public String sitemap() {
        Map<String, String> paths = new LinkedHashMap<>();
        for (String path : new String[]{"/", "/articles", "/daily", "/projects", "/series", "/competitions",
                "/about", "/subscribe", "/changelog"}) {
            paths.put(path, null);
        }
        for (Article article : articles.articlesForFeed()) {
            String updated = article.modifiedAt() == null ? article.date() : article.modifiedAt();
            paths.put("/entry/" + article.id(), ArticleDates.parse(updated).toString());
            if (article.series() != null) {
                paths.put("/series/" + article.series().id(), null);
            }
        }

        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">");
        String origin = site.origin();
        paths.forEach((path, updated) -> {
            xml.append("<url><loc>").append(HtmlUtils.htmlEscape(origin + path)).append("</loc>");
            if (updated != null) {
                xml.append("<lastmod>").append(updated).append("</lastmod>");
            }
            xml.append("</url>");
        });
        return xml.append("</urlset>").toString();
    }
}
