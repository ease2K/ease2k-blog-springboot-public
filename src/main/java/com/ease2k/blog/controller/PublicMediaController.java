package com.ease2k.blog.controller;

import com.ease2k.blog.config.BlogSite;
import com.ease2k.blog.model.ArticleMedia;
import com.ease2k.blog.owner.OwnerDatabase;
import com.ease2k.blog.owner.OwnerMedia;
import com.ease2k.blog.repository.ArticleRepository;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

@RestController
public class PublicMediaController {

    private final ArticleRepository articles;
    private final OwnerDatabase database;
    private final OwnerMedia media;
    private final BlogSite site;

    public PublicMediaController(ArticleRepository articles, OwnerDatabase database, OwnerMedia media, BlogSite site) {
        this.articles = articles;
        this.database = database;
        this.media = media;
        this.site = site;
    }

    @GetMapping("/media/{name}")
    public ResponseEntity<Resource> image(@PathVariable String name) throws IOException {
        if (!ArticleMedia.validImageName(name) || database.enabled() && !articles.exposesMedia(name)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Path path = media.file(name);
        if (path == null && site.contentDirectory() != null) {
            Path directory = site.contentDirectory().resolve("public-media");
            Path candidate = directory.resolve(name);
            if (Files.isRegularFile(candidate) && candidate.toRealPath().startsWith(directory.toRealPath())) {
                path = candidate;
            }
        }
        if (path == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Resource resource = new FileSystemResource(path);
        // 撤下文章后会立即重新校验引用，浏览器不得长期缓存原先公开的图片。
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ZERO).cachePublic().mustRevalidate())
                .contentType(MediaTypeFactory.getMediaType(resource).orElseThrow()).body(resource);
    }
}
