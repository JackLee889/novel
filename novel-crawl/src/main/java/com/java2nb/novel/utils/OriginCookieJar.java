package com.java2nb.novel.utils;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 线程安全、有容量限制的内存会话；不同源站（协议、主机、端口）互不共享 Cookie。 */
final class OriginCookieJar implements CookieJar {
    private static final int MAX_ORIGINS = 128;
    private static final int MAX_COOKIES_PER_ORIGIN = 128;
    private final Map<String, List<Cookie>> sessions = new LinkedHashMap<>(16, 0.75f, true);

    static String origin(HttpUrl url) {
        return url.scheme() + "://" + url.host() + ":" + url.port();
    }

    @Override
    public synchronized void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
        long now = System.currentTimeMillis();
        List<Cookie> saved = sessions.computeIfAbsent(origin(url), key -> new ArrayList<>());
        saved.removeIf(cookie -> cookie.expiresAt() <= now);
        for (Cookie incoming : cookies) {
            saved.removeIf(cookie -> cookie.name().equals(incoming.name())
                && cookie.domain().equals(incoming.domain()) && cookie.path().equals(incoming.path()));
            if (incoming.expiresAt() > now) {
                saved.add(incoming);
            }
        }
        while (saved.size() > MAX_COOKIES_PER_ORIGIN) {
            saved.removeFirst();
        }
        while (sessions.size() > MAX_ORIGINS) {
            sessions.remove(sessions.keySet().iterator().next());
        }
    }

    @Override
    public synchronized List<Cookie> loadForRequest(HttpUrl url) {
        List<Cookie> saved = sessions.get(origin(url));
        if (saved == null) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        saved.removeIf(cookie -> cookie.expiresAt() <= now);
        return saved.stream().filter(cookie -> cookie.matches(url)).toList();
    }
}
