package com.ease2k.blog.service;

import com.ease2k.blog.model.Article;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;

/** 公开文章查询，供页面、JSON 接口、站点地图与订阅源使用。 */
public interface ArticleService {

    /** 返回列表所需的摘要与元数据。 */
    List<ObjectNode> list();

    Optional<Article> findById(String id);

    List<Article> articlesForFeed();

    boolean hasSeries(String id);

    boolean hasAward(String id);

    /** 按发布时间从新到旧返回最多 30 条命中摘要。空查询返回空列表。 */
    List<ObjectNode> search(String query);
}
