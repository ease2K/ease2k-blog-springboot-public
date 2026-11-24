package com.ease2k.blog.owner;

import com.ease2k.blog.config.BlogSite;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Configuration(proxyBeanMethods = false)
public class OwnerSecurity {

    private static final String OWNER = "OWNER";
    private static final String CSRF_HEADER = "X-CSRF-Token";

    @Bean
    AuthenticationManager ownerAuthenticationManager() {
        return request -> {
            if (!(request.getPrincipal() instanceof OwnerCredentials credentials)
                    || !(request.getCredentials() instanceof String password) || !credentials.verifies(password)) {
                throw new BadCredentialsException("密码不正确。");
            }
            return authenticatedOwner();
        };
    }

    @Bean
    SecurityFilterChain ownerSecurityFilterChain(HttpSecurity http, BlogSite site, OwnerDatabase database,
                                                 OwnerSessions sessions, ObjectMapper mapper,
                                                 @Value("${blog.owner-proxy-secret:}") String proxySecret) {
        OwnerAccessFilter access = new OwnerAccessFilter(site, database, sessions, mapper, proxySecret);
        return http.securityMatcher(OwnerAccessFilter::ownerPath)
                .sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .headers(AbstractHttpConfigurer::disable)
                .addFilterBefore(access, CsrfFilter.class)
                .csrf(config -> config.csrfTokenRepository(new SessionCsrfTokens())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler() {
                            @Override
                            public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken token) {
                                return request.getHeader(token.getHeaderName());
                            }
                        })
                        .requireCsrfProtectionMatcher(OwnerSecurity::requiresCsrf))
                .exceptionHandling(config -> config
                        .authenticationEntryPoint((request, response, error) ->
                                access.problem(response, HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录。"))
                        .accessDeniedHandler((request, response, error) ->
                                access.problem(response, HttpStatus.FORBIDDEN, "请求校验失败，请重新登录。")))
                .authorizeHttpRequests(config -> config
                        .requestMatchers(request -> !OwnerAccessFilter.ownerApi(request) || OwnerAccessFilter.publicEndpoint(request)).permitAll()
                        .anyRequest().hasRole(OWNER))
                .build();
    }

    static UsernamePasswordAuthenticationToken authenticatedOwner() {
        return UsernamePasswordAuthenticationToken.authenticated("owner", null, List.of(new SimpleGrantedAuthority("ROLE_" + OWNER)));
    }

    private static OwnerSessions.Session session(HttpServletRequest request) {
        return request.getAttribute(OwnerAccessFilter.ACCESS) instanceof OwnerAccessFilter.Access access ? access.session() : null;
    }

    private static boolean requiresCsrf(HttpServletRequest request) {
        // 未登录的请求交给授权规则返回 401，登录入口由访问 Filter 检查同源。
        return OwnerAccessFilter.writes(request) && !OwnerAccessFilter.publicEndpoint(request) && session(request) != null;
    }

    private static final class SessionCsrfTokens implements CsrfTokenRepository {

        @Override
        public CsrfToken generateToken(HttpServletRequest request) {
            return new DefaultCsrfToken(CSRF_HEADER, "_csrf", OwnerCredentials.token());
        }

        @Override
        public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
            // 工作台令牌由 SQLite 会话派生，退出时删除该会话即可失效。
        }

        @Override
        public CsrfToken loadToken(HttpServletRequest request) {
            OwnerSessions.Session current = session(request);
            return current == null ? null : new DefaultCsrfToken(CSRF_HEADER, "_csrf", current.csrf());
        }
    }
}
