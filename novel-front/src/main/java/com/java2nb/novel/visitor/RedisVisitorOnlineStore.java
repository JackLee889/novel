package com.java2nb.novel.visitor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

@Component
public class RedisVisitorOnlineStore implements VisitorOnlineStore {
    public static final String KEY = "visitor-stats:v1:online";
    private static final DefaultRedisScript<Long> TOUCH = new DefaultRedisScript<>(
        "redis.call('ZADD',KEYS[1],ARGV[1],ARGV[2]); "
        + "redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',ARGV[3]); "
        + "redis.call('EXPIRE',KEYS[1],ARGV[4]); return redis.call('ZCARD',KEYS[1]);", Long.class);
    private static final DefaultRedisScript<Long> COUNT = new DefaultRedisScript<>(
        "redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',ARGV[1]); return redis.call('ZCARD',KEYS[1]);", Long.class);
    private final StringRedisTemplate redis;
    private final Clock clock;
    private final String key;

    @Autowired public RedisVisitorOnlineStore(StringRedisTemplate redis) { this(redis, Clock.systemUTC()); }
    public RedisVisitorOnlineStore(StringRedisTemplate redis, Clock clock) { this(redis, clock, KEY); }
    RedisVisitorOnlineStore(StringRedisTemplate redis, Clock clock, String key) {
        if (key == null || !key.startsWith("visitor-stats:v1:")) throw new IllegalArgumentException("Visitor Redis keys require an isolated namespace");
        this.redis = redis; this.clock = clock; this.key = key;
    }

    @Override public void touch(String visitorHash) {
        if (visitorHash == null || !visitorHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid anonymous identifier hash");
        long now = clock.millis();
        Long value = redis.execute(TOUCH, List.of(key), Long.toString(now), visitorHash,
            Long.toString(now - WINDOW_SECONDS * 1000L), Integer.toString(WINDOW_SECONDS * 2));
        if (value == null) throw new IllegalStateException("Redis did not acknowledge visitor activity");
    }

    @Override public long count() {
        Long value = redis.execute(COUNT, List.of(key), Long.toString(clock.millis() - WINDOW_SECONDS * 1000L));
        if (value == null) throw new IllegalStateException("Redis did not return the visitor count");
        return value;
    }
}
