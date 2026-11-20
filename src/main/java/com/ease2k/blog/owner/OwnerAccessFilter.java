package com.ease2k.blog.owner;

import com.ease2k.blog.config.BlogSite;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;

@Component
public class OwnerAccessFilter extends OncePerRequestFilter {

    static final String ACCESS = OwnerAccessFilter.class.getName() + ".ACCESS";
    private static final String PROXY_HEADER = "X-Ease2k-Owner-Proxy";

    private final BlogSite site;
    private final OwnerDatabase database;
    private final OwnerSessions sessions;
    private final ObjectMapper mapper;
    private final String proxySecret;

    public OwnerAccessFilter(BlogSite site, OwnerDatabase database, OwnerSessions sessions, ObjectMapper mapper,
                             @Value("${blog.owner-proxy-secret:}") String proxySecret) {
        this.site = site;
        this.database = database;
        this.sessions = sessions;
        this.mapper = mapper;
        this.proxySecret = proxySecret;
        if (site.production() && database.enabled() && !proxySecret.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("启用生产工作台需要配置 BLOG_OWNER_PROXY_SECRET");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !("/_owner".equals(path) || path.startsWith("/_owner/") || "/owner.html".equals(path)
                || "/api/owner".equals(path) || path.startsWith("/api/owner/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Robots-Tag", "noindex, nofollow, noarchive");

        String path = request.getServletPath();
        String origin = permittedOrigin(request);
        if (origin == null || "/owner.html".equals(path)) {
            problem(response, HttpStatus.NOT_FOUND, "页面不存在。");
            return;
        }
        if (path.startsWith("/api/owner")) {
            OwnerCredentials credentials = sessions.credentials();
            OwnerSessions.Session session = sessions.session(request, credentials, site.production());
            boolean publicEndpoint = "/api/owner/session".equals(path) && "GET".equals(request.getMethod())
                    || "/api/owner/login".equals(path) && "POST".equals(request.getMethod());
            boolean writes = !List.of("GET", "HEAD").contains(request.getMethod());
            if (writes && (!origin.equals(request.getHeader("Origin")) || "cross-site".equals(request.getHeader("Sec-Fetch-Site")))) {
                problem(response, HttpStatus.FORBIDDEN, "请求来源不正确，请从站主入口重新打开。");
                return;
            }
            if (!publicEndpoint && session == null) {
                problem(response, HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录。");
                return;
            }
            if (!publicEndpoint && writes && !OwnerCredentials.equal(request.getHeader("X-CSRF-Token"), session.csrf())) {
                problem(response, HttpStatus.FORBIDDEN, "请求校验失败，请重新登录。");
                return;
            }
            request.setAttribute(ACCESS, new Access(credentials, session, site.production()));
        }
        chain.doFilter(request, response);
    }

    private String permittedOrigin(HttpServletRequest request) throws IOException {
        if (!database.enabled() || !InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) return null;
        if (site.production()) {
            String origin = site.origin();
            // 只有本机 HTTPS 代理注入的密钥才允许使用生产工作台。
            return OwnerCredentials.equal(proxySecret, request.getHeader(PROXY_HEADER))
                    && URI.create(origin).getRawAuthority().equalsIgnoreCase(request.getHeader("Host")) ? origin : null;
        }
        if (!List.of("localhost", "127.0.0.1", "[::1]", "::1").contains(request.getServerName())) return null;
        if (List.of(PROXY_HEADER, "Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Real-IP")
                .stream().anyMatch(header -> request.getHeader(header) != null)) return null;
        return "http://" + request.getHeader("Host");
    }

    private void problem(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(mapper.writeValueAsString(Map.of("error", message)));
    }

    record Access(OwnerCredentials credentials, OwnerSessions.Session session, boolean secure) {
    }
}
