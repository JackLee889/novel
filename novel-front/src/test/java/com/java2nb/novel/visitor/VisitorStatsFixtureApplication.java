package com.java2nb.novel.visitor;

import com.java2nb.novel.reader.FrontendFixtureApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.Map;

/** Test classpath only. Real statistics and templates; original book fixtures and isolated QA storage. */
@SpringBootConfiguration
@Import({FrontendFixtureApplication.class, VisitorStatsProperties.class, VisitorStatsRepository.class,
    RedisVisitorOnlineStore.class, VisitorStatsService.class, VisitorStatsController.class})
public class VisitorStatsFixtureApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(VisitorStatsFixtureApplication.class);
        app.setDefaultProperties(Map.of(
            "server.port", "18988", "server.address", "127.0.0.1",
            "spring.config.name", "visitor-fixture", "spring.thymeleaf.cache", "false",
            "logging.level.root", "WARN", "reader.enabled", "true", "reader.tts.enabled", "false",
            "jwt.secret", "visitor-fixture-only", "jwt.expiration", "3600"));
        app.run(args);
    }

    @Bean DataSource visitorQaDataSource() {
        String url = required("VISITOR_QA_MYSQL_URL");
        if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/novel_visitor_qa_[0-9]+(?:\\?.*)?"))
            throw new IllegalArgumentException("Visitor preview requires a dedicated loopback QA database");
        return new DriverManagerDataSource(url, required("VISITOR_QA_MYSQL_USERNAME"), required("VISITOR_QA_MYSQL_PASSWORD"));
    }

    @Bean RedisConnectionFactory visitorQaRedisConnectionFactory() {
        int port = Integer.parseInt(System.getenv().getOrDefault("VISITOR_QA_REDIS_PORT", "18989"));
        return new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", port));
    }

    @Bean StringRedisTemplate visitorQaRedis(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing QA setting: " + name);
        return value;
    }
}
