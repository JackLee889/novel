package com.java2nb.novel.visitor;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Only an explicitly configured local QA Redis is contacted; the test key expires by itself. */
class VisitorStatsRedisIntegrationTest {
    @Test void actualLuaDeduplicatesTabsExpiresAtNinetySecondsAndSetsShortTtl() {
        String portValue = System.getenv("VISITOR_STATS_REDIS_TEST_PORT");
        assumeTrue(portValue != null && !portValue.isBlank(), "Dedicated local Redis QA server not configured");
        int port = Integer.parseInt(portValue); assertTrue(port > 0 && port <= 65535);
        // Use the Lettuce client supplied by the project's Redis starter; Jedis is not a Maven dependency.
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", port));
        factory.afterPropertiesSet(); factory.start();
        try {
            var redis = new StringRedisTemplate(factory); var clock = new VisitorStatsServiceTest.MutableClock();
            String key = "visitor-stats:v1:qa:" + UUID.randomUUID();
            var first = new RedisVisitorOnlineStore(redis, clock, key);
            var second = new RedisVisitorOnlineStore(redis, clock, key);
            String visitor = VisitorStatsRepositoryTest.hash(), another = VisitorStatsRepositoryTest.hash();
            assertEquals(0, first.count()); first.touch(visitor); second.touch(visitor); assertEquals(1, second.count());
            second.touch(another); assertEquals(2, first.count());
            Long ttl = redis.getExpire(key); assertNotNull(ttl); assertTrue(ttl >= 170 && ttl <= 180);
            clock.advance(89000); assertEquals(2, first.count()); first.touch(visitor);
            clock.advance(1000); assertEquals(1, second.count());
            clock.advance(89000); assertEquals(0, first.count());
            // No DEL/FLUSH: the dedicated test ZSET retains the 180-second inactivity TTL.
        } finally { factory.destroy(); }
    }
}
