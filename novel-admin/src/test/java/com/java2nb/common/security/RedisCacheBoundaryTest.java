package com.java2nb.common.security;

import com.java2nb.common.redis.shiro.RedisCache;
import com.java2nb.common.redis.shiro.RedisManager;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import static org.mockito.Mockito.*;

class RedisCacheBoundaryTest {
    @Test void clearingShiroCachesCannotFlushOtherApplicationData() {
        var manager = mock(RedisManager.class);
        byte[] owned = "shiro-fixture:user".getBytes(StandardCharsets.UTF_8);
        when(manager.keys("shiro-fixture:*")).thenReturn(Set.of(owned));
        var cache = new RedisCache<String, Object>(manager, "shiro-fixture:");
        cache.clear();
        verify(manager).del(owned);
        verify(manager, never()).flushDB();
        verify(manager, never()).keys("*");
    }
}
