package com.ease2k.blog.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.nio.file.Path;

@Component
public class BlogSite {

    private final boolean production;
    private final String publicOrigin;
    private final Path contentDirectory;

    public BlogSite(Environment environment,
                    @Value("${blog.public-origin:}") String origin,
                    @Value("${blog.content-directory:}") String directory) {
        production = environment.acceptsProfiles(Profiles.of("production"));
        if (production && origin.isBlank()) {
            throw new IllegalArgumentException("production 需要配置 BLOG_PUBLIC_ORIGIN");
        }
        publicOrigin = origin.isBlank() ? "" : validateOrigin(origin.strip());
        contentDirectory = directory.isBlank() ? null : Path.of(directory).toAbsolutePath().normalize();
    }

    public boolean production() {
        return production;
    }

    public String origin() {
        // 生产环境的规范链接始终以部署配置为准。
        return publicOrigin.isEmpty()
                ? ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString()
                : publicOrigin;
    }

    public Path contentDirectory() {
        return contentDirectory;
    }

    private String validateOrigin(String value) {
        URI uri = URI.create(value);
        String scheme = uri.getScheme();
        if (!("https".equals(scheme) || !production && "http".equals(scheme))
                || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || !("".equals(uri.getRawPath()) || "/".equals(uri.getRawPath()))) {
            throw new IllegalArgumentException("BLOG_PUBLIC_ORIGIN 应为站点来源，例如 https://blog.example.com");
        }
        return scheme + "://" + uri.getRawAuthority();
    }
}
