package com.ease2k.blog.service;

import com.ease2k.blog.model.Article;
import jakarta.servlet.http.HttpServletRequest;

/** 将当前路由和可选的文章渲染为页面 HTML。 */
public interface PageRenderer {

    String render(HttpServletRequest request, Article article);
}
