package com.ease2k.blog.repository;

import com.ease2k.blog.model.Article;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 公开内容的读契约，所有读取都使用已发布的文章。 */
public interface ArticleRepository {

    Snapshot snapshot();

    List<Article> findAll();

    Optional<Article> findById(String id);

    boolean hasAward(String id);

    boolean exposesMedia(String name);

    /** 文章列表、按 ID 索引和图片引用来自同一个发布版本。 */
    record Snapshot(long version, List<Article> entries, Map<String, Article> byId, Set<String> media) {
    }
}
