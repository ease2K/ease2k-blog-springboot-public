package com.ease2k.blog.owner;

import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.time.Duration;

/** 站主工作台使用的会话操作和有效期。 */
public interface OwnerSessions {

    OwnerCredentials credentials() throws IOException;

    Session session(HttpServletRequest request, OwnerCredentials credentials, boolean secure);

    String login(OwnerCredentials credentials, String password, boolean remember);

    void logout(Session session);

    Duration lifetime(boolean remember);

    record Session(String hash, String csrf) {
    }
}
