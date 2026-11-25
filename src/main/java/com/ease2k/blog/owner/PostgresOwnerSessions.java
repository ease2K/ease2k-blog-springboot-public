package com.ease2k.blog.owner;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class PostgresOwnerSessions implements OwnerSessions {

    private static final long ATTEMPT_WINDOW = Duration.ofMinutes(15).toMillis();
    private final OwnerDatabase database;
    private final OwnerSessionMapper sessionMapper;
    private final ObjectMapper mapper;
    private final AuthenticationManager authentication;

    public PostgresOwnerSessions(OwnerDatabase database, ObjectMapper mapper, AuthenticationManager authentication) throws IOException {
        this.database = database;
        this.sessionMapper = database.mapper(OwnerSessionMapper.class);
        this.mapper = mapper;
        this.authentication = authentication;
        if (database.enabled()) credentials();
    }

    @Override
    public OwnerCredentials credentials() throws IOException {
        return OwnerCredentials.load(database.directory(), mapper);
    }

    @Override
    public Session session(HttpServletRequest request, OwnerCredentials credentials, boolean secure) {
        if (credentials == null || request.getCookies() == null) return null;
        String name = secure ? "__Host-ease_owner" : "ease_owner";
        for (Cookie cookie : request.getCookies()) {
            String token = cookie.getValue();
            if (!name.equals(cookie.getName()) || !token.matches("[A-Za-z0-9_-]{43}")) continue;
            String hash = digest(token);
            boolean found = sessionMapper.active(hash, credentials.version(), System.currentTimeMillis());
            if (found) return new Session(hash, csrf(token, credentials.secret()));
        }
        return null;
    }

    @Override
    public String login(OwnerCredentials config, String password, boolean remember) {
        if (config == null) throw new OwnerProblem(HttpStatus.SERVICE_UNAVAILABLE, "请先在服务器终端设置站主密码。");
        long now = System.currentTimeMillis();
        database.transaction(false, () -> {
            sessionMapper.pruneAttempts(now - ATTEMPT_WINDOW, config.version());
            sessionMapper.startAttempts(config.version(), now);
            if (sessionMapper.addAttempt(config.version()) == 0) {
                throw new OwnerProblem(HttpStatus.TOO_MANY_REQUESTS, "尝试次数过多，请在 15 分钟后重试。");
            }
            return null;
        });
        try {
            authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(config, password));
        } catch (BadCredentialsException error) {
            throw new OwnerProblem(HttpStatus.UNAUTHORIZED, error.getMessage());
        }
        String token = OwnerCredentials.token();
        database.transaction(false, () -> {
            sessionMapper.clearAttempts(config.version());
            sessionMapper.pruneSessions(now, config.version());
            sessionMapper.addSession(digest(token), config.version(), now + lifetime(remember).toMillis());
            return null;
        });
        return token;
    }

    @Override
    public void logout(Session session) {
        sessionMapper.deleteSession(session.hash());
    }

    @Override
    public Duration lifetime(boolean remember) {
        return remember ? Duration.ofDays(30) : Duration.ofHours(8);
    }

    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String csrf(String token, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(token.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }
}
