package com.ease2k.blog.model;

import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 统一公开文章中引用的图片路径与媒体路由的文件名规则。 */
public final class ArticleMedia {

    private static final Pattern MEDIA = Pattern.compile(
            "/media/([A-Za-z0-9][A-Za-z0-9_-]{0,127}\\.(?:webp|png|jpe?g|avif|gif))(?![\\w./-])", Pattern.CASE_INSENSITIVE);

    private ArticleMedia() {
    }

    public static Stream<String> referencedImageNames(String text) {
        return MEDIA.matcher(text).results().map(match -> match.group(1));
    }

    public static boolean validImageName(String name) {
        return MEDIA.matcher("/media/" + name).matches();
    }
}
