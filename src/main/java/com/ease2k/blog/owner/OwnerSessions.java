package com.ease2k.blog.owner;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
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
public class OwnerSessions {

    private static final long ATTEMPT_WINDOW = Duration.ofMinutes(15).toMillis();
    private final OwnerDatabase database;
    private final ObjectMapper mapper;

    public OwnerSessions(OwnerDatabase database, ObjectMapper mapper) throws IOException {
        this.database = database;
        this.mapper = mapper;
        if (database.enabled()) credentials();
    }

    OwnerCredentials credentials() throws IOException {
        return OwnerCredentials.load(database.directory(), mapper);
    }

    Session session(HttpServletRequest request, OwnerCredentials credentials, boolean secure) {
        if (credentials == null || request.getCookies() == null) return null;
        String name = secure ? "__Host-ease_owner" : "ease_owner";
        for (Cookie cookie : request.getCookies()) {
            String token = cookie.getValue();
            if (!name.equals(cookie.getName()) || !token.matches("[A-Za-z0-9_-]{43}")) continue;
            String hash = digest(token);
            boolean found = !database.sql().query("SELECT hash FROM sessions WHERE hash=? AND version=? AND expires>?",
                    (row, index) -> row.getString(1), hash, credentials.version(), System.currentTimeMillis()).isEmpty();
            if (found) return new Session(hash, csrf(token, credentials.secret()));
        }
        return null;
    }

    String login(OwnerCredentials config, String password, boolean remember) {
        if (config == null) throw new OwnerProblem(HttpStatus.SERVICE_UNAVAILABLE, "请先在服务器终端设置站主密码。");
        long now = System.currentTimeMillis();
        database.transaction(false, () -> {
            database.sql().update("DELETE FROM attempts WHERE started<=? OR version<>?", now - ATTEMPT_WINDOW, config.version());
            database.sql().update("INSERT OR IGNORE INTO attempts VALUES (?,?,0)", config.version(), now);
            if (database.sql().update("UPDATE attempts SET count=count+1 WHERE version=? AND count<8",
                    config.version()) == 0) {
                throw new OwnerProblem(HttpStatus.TOO_MANY_REQUESTS, "尝试次数过多，请在 15 分钟后重试。");
            }
            return null;
        });
        if (!config.verifies(password)) throw new OwnerProblem(HttpStatus.UNAUTHORIZED, "密码不正确。");
        String token = OwnerCredentials.token();
        database.transaction(false, () -> {
            database.sql().update("DELETE FROM attempts WHERE version=?", config.version());
            database.sql().update("DELETE FROM sessions WHERE expires<=? OR version<>?", now, config.version());
            database.sql().update("INSERT INTO sessions VALUES (?,?,?)",
                    digest(token), config.version(), now + lifetime(remember).toMillis());
            return null;
        });
        return token;
    }

    void logout(Session session) {
        database.sql().update("DELETE FROM sessions WHERE hash=?", session.hash());
    }

    static Duration lifetime(boolean remember) {
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

    record Session(String hash, String csrf) {
    }
}
