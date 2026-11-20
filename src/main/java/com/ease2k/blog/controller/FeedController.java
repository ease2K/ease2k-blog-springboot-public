package com.ease2k.blog.controller;

import com.ease2k.blog.config.BlogSite;
import com.ease2k.blog.model.Article;
import com.ease2k.blog.model.ArticleDates;
import com.ease2k.blog.service.ArticleService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.StringWriter;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

@RestController
public class FeedController {

    private static final String RSS = "application/rss+xml;charset=UTF-8";
    private final ArticleService articleService;
    private final BlogSite site;

    public FeedController(ArticleService articleService, BlogSite site) {
        this.articleService = articleService;
        this.site = site;
    }

    @GetMapping(value = "/feed.xml", produces = RSS)
    public String all() throws XMLStreamException {
        return feed("ease2k · 全部内容", articleService.articlesForFeed());
    }

    @GetMapping(value = "/feed/technical.xml", produces = RSS)
    public String technical() throws XMLStreamException {
        List<Article> articles = articleService.articlesForFeed().stream()
                .filter(article -> !"daily".equals(article.kind())).toList();
        return feed("ease2k · 技术文章", articles);
    }

    @GetMapping(value = "/feed/series/{id}.xml", produces = RSS)
    public String series(@PathVariable String id) throws XMLStreamException {
        List<Article> articles = articleService.articlesForFeed().stream()
                .filter(article -> article.series() != null && id.equals(article.series().id())).toList();
        if (articles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return feed("ease2k · " + articles.getFirst().series().title(), articles);
    }

    private String feed(String title, List<Article> articles) throws XMLStreamException {
        String origin = site.origin();
        StringWriter output = new StringWriter();
        XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(output);
        xml.writeStartDocument("UTF-8", "1.0");
        xml.writeStartElement("rss");
        xml.writeAttribute("version", "2.0");
        xml.writeStartElement("channel");
        element(xml, "title", title);
        element(xml, "link", origin + "/");
        element(xml, "description", "记录日常，分享学习与实践。");
        element(xml, "language", "zh-CN");
        // 实质更新会让文章排到前面，pubDate 始终是首次发布时间。
        List<Article> recent = articles.stream()
                .sorted(Comparator.comparing(FeedController::lastUpdate).reversed()).limit(50).toList();
        for (Article article : recent) {
            String link = origin + "/entry/" + article.id();
            xml.writeStartElement("item");
            element(xml, "title", article.title());
            element(xml, "link", link);
            element(xml, "guid", link);
            element(xml, "pubDate", ArticleDates.parse(article.date()).withOffsetSameInstant(ZoneOffset.UTC)
                    .format(DateTimeFormatter.RFC_1123_DATE_TIME));
            element(xml, "description", article.summary());
            for (String tag : article.tags()) {
                element(xml, "category", tag);
            }
            xml.writeEndElement();
        }
        xml.writeEndDocument();
        xml.close();
        return output.toString();
    }

    private static OffsetDateTime lastUpdate(Article article) {
        return ArticleDates.parse(article.modifiedAt() == null ? article.date() : article.modifiedAt())
                .withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static void element(XMLStreamWriter xml, String tag, String text) throws XMLStreamException {
        xml.writeStartElement(tag);
        xml.writeCharacters(text == null ? "" : text);
        xml.writeEndElement();
    }
}
