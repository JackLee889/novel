package com.java2nb.system.shiro;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Invalidates only affected users, after the permission transaction commits. */
@Component
public class AuthorizationCacheInvalidator {
    private final UserRealm realm;

    public AuthorizationCacheInvalidator(UserRealm realm) {
        this.realm = realm;
    }

    public void afterCommit(Collection<Long> userIds) {
        Set<Long> affected = new LinkedHashSet<>(userIds);
        affected.removeIf(Objects::isNull);
        if (affected.isEmpty()) {
            return;
        }
        Runnable evict = () -> affected.forEach(realm::clearAuthorizationCacheForUser);
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict.run();
                }
            });
        } else {
            evict.run();
        }
    }
}
