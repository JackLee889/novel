package com.java2nb.common.security;

import com.java2nb.common.redis.shiro.RedisCacheManager;
import com.java2nb.common.redis.shiro.RedisManager;
import com.java2nb.system.domain.UserDO;
import com.java2nb.system.shiro.AuthorizationCacheInvalidator;
import com.java2nb.system.shiro.UserRealm;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.cache.MemoryConstrainedCacheManager;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthorizationCacheInvalidationTest {
    static class PermissionRealm extends UserRealm {
        final Map<Long, Set<String>> permissions = new ConcurrentHashMap<>();
        final Map<Long, Integer> queries = new ConcurrentHashMap<>();
        @Override protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
            Long id = ((UserDO) principals.getPrimaryPrincipal()).getUserId();
            queries.merge(id, 1, Integer::sum);
            var info = new SimpleAuthorizationInfo();
            info.setStringPermissions(permissions.getOrDefault(id, Set.of()));
            return info;
        }
    }

    @AfterEach void cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    PrincipalCollection principal(UserRealm realm, long id, String label) {
        var user = new UserDO(); user.setUserId(id); user.setName(label);
        return new SimplePrincipalCollection(user, realm.getName());
    }

    void startTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    @Test void sameUserAcrossSessionsSharesStableKeyAndGrantRevokeRefreshAfterCommit() {
        var realm = new PermissionRealm(); realm.setCacheManager(new MemoryConstrainedCacheManager());
        var invalidator = new AuthorizationCacheInvalidator(realm);
        var firstSession = principal(realm, 11, "before");
        var otherSession = principal(realm, 11, "after profile edit");
        var unrelated = principal(realm, 12, "unaffected");
        String permission = "novel:book:add";
        assertFalse(realm.isPermitted(firstSession, permission));
        assertFalse(realm.isPermitted(otherSession, permission));
        assertFalse(realm.isPermitted(unrelated, permission));
        assertEquals(1, realm.queries.get(11L));
        realm.permissions.put(11L, Set.of(permission));
        startTransaction();
        invalidator.afterCommit(List.of(11L, 11L));
        assertFalse(realm.isPermitted(otherSession, permission));
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        cleanup();
        assertTrue(realm.isPermitted(firstSession, permission));
        assertTrue(realm.isPermitted(otherSession, permission));
        assertFalse(realm.isPermitted(unrelated, permission));
        assertEquals(1, realm.queries.get(12L));
        realm.permissions.put(11L, Set.of());
        invalidator.afterCommit(List.of(11L));
        assertFalse(realm.isPermitted(firstSession, permission));
        assertEquals(3, realm.queries.get(11L));
    }

    @Test void rollbackDoesNotEvictAndCallerMutationDoesNotChangeCapturedUsers() {
        var realm = mock(UserRealm.class);
        var invalidator = new AuthorizationCacheInvalidator(realm);
        startTransaction();
        invalidator.afterCommit(List.of(11L));
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(realm);
        cleanup();
        startTransaction();
        var users = new java.util.ArrayList<>(List.of(11L));
        invalidator.afterCommit(users); users.add(12L);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(realm).clearAuthorizationCacheForUser(11L);
        verify(realm, never()).clearAuthorizationCacheForUser(12L);
    }

    @Test void namedRedisCachesCannotCollideAndEvictionUsesUserKeyOnly() {
        var redis = mock(RedisManager.class);
        var manager = new RedisCacheManager(); manager.setRedisManager(redis);
        var cacheA = manager.<String, String>getCache("realm-a.authorizationCache");
        var cacheB = manager.<String, String>getCache("realm-b.authorizationCache");
        cacheA.put("user:11", "a"); cacheB.put("user:11", "b");
        verify(redis).set(eq("shiro_redis_cache:realm-a.authorizationCache:user:11".getBytes(StandardCharsets.UTF_8)), any());
        verify(redis).set(eq("shiro_redis_cache:realm-b.authorizationCache:user:11".getBytes(StandardCharsets.UTF_8)), any());
        var realm = new UserRealm(); realm.setCacheManager(manager);
        realm.clearAuthorizationCacheForUser(11L);
        verify(redis).del(("shiro_redis_cache:" + realm.getAuthorizationCacheName() + ":user:11").getBytes(StandardCharsets.UTF_8));
        verify(redis, never()).flushDB();
        verify(redis, never()).keys(anyString());
    }
}
