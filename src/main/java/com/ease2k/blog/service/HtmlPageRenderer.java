package com.ease2k.blog.service;

import com.ease2k.blog.config.BlogSite;
import com.ease2k.blog.model.Article;
import com.ease2k.blog.owner.OwnerDatabase;
import jakarta.servlet.http.HttpServletRequest;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class HtmlPageRenderer implements PageRenderer {

    private static final String ROOT = "<div id=\"root\"></div>";
    private static final String NOINDEX = "<meta name=\"robots\" content=\"noindex\">";
    private static final String TEMPLATE_TITLE = "<title>eAs1nGG-_- / ease2k</title>";
    private static final String DESCRIPTION_ZH = "记录日常，分享算法、C++、Java、MySQL、HTML、CSS 与 JavaScript 的学习和实践。";
    private static final String DESCRIPTION_EN = "Personal notes on algorithms, programming, and everyday life.";

    private final BlogSite site;
    private final ArticleService articles;
    private final ObjectMapper mapper;
    private final String template;
    private final OwnerDatabase owner;
    private final Map<BodyKey, RenderedBody> renderedBodies = new ConcurrentHashMap<>();
    private final Parser markdown = Parser.builder().extensions(List.of(TablesExtension.create())).build();
    private final HtmlRenderer html = HtmlRenderer.builder().extensions(List.of(TablesExtension.create()))
            .escapeHtml(true).sanitizeUrls(true)
            .attributeProviderFactory(context -> (node, tag, attributes) -> {
                if ("img".equals(tag)) {
                    attributes.put("loading", "lazy");
                    attributes.put("decoding", "async");
                }
            }).build();

    public HtmlPageRenderer(BlogSite site, ArticleService articles, ObjectMapper mapper, OwnerDatabase owner) throws IOException {
        this.site = site;
        this.articles = articles;
        this.mapper = mapper;
        this.owner = owner;
        template = new ClassPathResource("static/index.html").getContentAsString(StandardCharsets.UTF_8);
        if (!template.contains(ROOT) || !template.contains(TEMPLATE_TITLE) || !template.contains(NOINDEX)) {
            throw new IllegalStateException("前端产物缺少页面模板标记，请重新运行 Vite 构建");
        }
    }

    @Override
    public String render(HttpServletRequest request, Article article) {
        String path = request.getRequestURI();
        String language = request.getParameter("lang");
        boolean english = "en".equals(language) || !"zh".equals(language)
                && request.getHeader("Accept-Language") != null
                && !request.getHeader("Accept-Language").toLowerCase(Locale.ROOT).startsWith("zh");
        String lang = english ? "en" : "zh";
        String title = article == null ? sectionTitle(path, english) : articleTitle(article, english);
        String description = english ? DESCRIPTION_EN : DESCRIPTION_ZH;
        if (article != null) {
            String summary = english && hasText(article.enSummary()) ? article.enSummary() : article.summary();
            if (hasText(summary) && !"-".equals(summary.strip()) && !"—".equals(summary.strip())) {
                description = summary;
            }
        }

        String canonical = site.origin() + (article == null ? path : "/entry/" + article.id());
        String fullTitle = title + " · eAs1nGG-_-";
        StringBuilder head = new StringBuilder();
        head.append("<meta name=\"description\" content=\"").append(escaped(description)).append("\">")
                .append("<link rel=\"canonical\" href=\"").append(escaped(canonical)).append("\">")
                .append(meta("og:title", fullTitle)).append(meta("og:description", description))
                .append(meta("og:url", canonical)).append(meta("og:type", article == null ? "website" : "article"));
        if (article != null) {
            head.append("<script type=\"application/ld+json\">").append(json(Map.of(
                    "@context", "https://schema.org", "@type", "BlogPosting",
                    "headline", title, "description", description, "mainEntityOfPage", canonical,
                    "datePublished", article.date(), "dateModified",
                    article.modifiedAt() == null ? article.date() : article.modifiedAt()
            ))).append("</script>");
        }

        String initial = "<script id=\"blog-bootstrap\" type=\"application/json\">"
                + json(new Bootstrap(new InitialData(articles.list(), true), article, lang, owner.enabled())) + "</script>";
        String body = "<div id=\"root\">" + initialContent(path, article, title, english) + "</div>" + initial;
        String document = site.production() ? template.replace(NOINDEX, "") : template;
        return document
                .replace("<html lang=\"zh-CN\">", "<html lang=\"" + (english ? "en" : "zh-CN") + "\">")
                .replace(TEMPLATE_TITLE, "<title>" + escaped(fullTitle) + "</title>")
                .replace("</head>", head + "</head>")
                .replace(ROOT, body);
    }

    private String initialContent(String path, Article article, String title, boolean english) {
        if (article != null) {
            String summary = english && hasText(article.enSummary()) ? article.enSummary() : article.summary();
            BodyKey key = new BodyKey(article.id(), english && hasText(article.enBody()));
            String text = key.english() ? article.enBody() : article.body();
            // 同一地址重新发布后以新正文重建缓存。
            String body = renderedBodies.compute(key, (unused, cached) -> cached != null && cached.source().equals(text)
                    ? cached : new RenderedBody(text, html.render(markdown.parse(text.replaceFirst("^# [^\\r\\n]+\\R+", ""))))).html();
            return "<main class=\"wrap main-layout\"><article class=\"detail\"><h1>" + escaped(title) + "</h1>"
                    + (hasText(summary) ? "<p class=\"detail-summary\">" + escaped(summary) + "</p>" : "")
                    + "<div class=\"prose\">" + body + "</div></article></main>";
        }
        if ("/".equals(path) || "/articles".equals(path)) {
            StringBuilder links = new StringBuilder();
            articles.articlesForFeed().stream().limit(8).forEach(entry -> links.append("<li><a href=\"/entry/")
                    .append(entry.id()).append("\">").append(escaped(articleTitle(entry, english))).append("</a></li>"));
            return "<main class=\"wrap\"><h1>" + escaped(title) + "</h1><ul>" + links + "</ul></main>";
        }
        return "";
    }

    private String json(Object value) {
        // 脚本数据内的尖括号使用 JSON 转义，避免内容提前结束 script 元素。
        return mapper.writeValueAsString(value).replace("<", "\\u003c");
    }

    private static String meta(String property, String value) {
        return "<meta property=\"" + property + "\" content=\"" + escaped(value) + "\">";
    }

    private static String articleTitle(Article article, boolean english) {
        return english && hasText(article.enTitle()) ? article.enTitle() : article.title();
    }

    private static String sectionTitle(String path, boolean english) {
        return switch (path) {
            case "/daily" -> english ? "Journal" : "日常";
            case "/articles", "/cpp", "/java", "/mysql", "/html", "/css", "/javascript", "/algorithms" ->
                    english ? "Writing" : "文章";
            case "/projects" -> english ? "Projects" : "项目";
            case "/about" -> english ? "About" : "关于我";
            case "/subscribe" -> english ? "Subscribe" : "订阅更新";
            case "/changelog" -> english ? "Changelog" : "更新日志";
            default -> {
                if (path.startsWith("/series")) yield english ? "Learning series" : "学习专栏";
                if (path.startsWith("/competitions") || path.startsWith("/award/"))
                    yield english ? "Contests" : "竞赛";
                yield english ? "Personal notes" : "个人手记";
            }
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String escaped(String value) {
        return HtmlUtils.htmlEscape(value);
    }

    private record BodyKey(String id, boolean english) {
    }

    private record RenderedBody(String source, String html) {
    }

    private record Bootstrap(InitialData initialData, Article initialEntry, String initialLang, boolean ownerEnabled) {
    }

    private record InitialData(List<ObjectNode> entries, boolean ready) {
    }
}
