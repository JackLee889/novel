package com.java2nb.common.security;

import com.java2nb.common.utils.Query;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SqlSortingTest {
    @ParameterizedTest @ValueSource(strings = {"id desc, sleep(1)", "id;drop table book", "(select password from sys_user)", "id--", "id`"})
    void invalidColumnsFailAtTheRequestBoundary(String sort) {
        assertThrows(IllegalArgumentException.class, () -> new Query(Map.of("offset", "0", "limit", "10", "sort", sort)));
    }

    @Test void invalidDirectionsAndPageSizesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Query(Map.of("offset", "0", "limit", "10", "order", "desc;drop table book")));
        assertThrows(IllegalArgumentException.class, () -> new Query(Map.of("offset", "0", "limit", "0")));
        assertEquals("asc", new Query(Map.of("offset", "0", "limit", "10", "order", "ASC")).get("order"));
    }

    @Test void everyAdminMapperRejectsSqlEvenWhenQueryValidationIsBypassed() throws Exception {
        var config = new Configuration(); config.setMapUnderscoreToCamelCase(true);
        try (var paths = Files.walk(Path.of("src/main/resources/mybatis"))) {
            for (var path : paths.filter(p -> p.toString().endsWith(".xml")).toList()) {
                try (var in = Files.newInputStream(path)) {
                    new XMLMapperBuilder(in, config, path.toString(), config.getSqlFragments()).parse();
                }
            }
        }
        int checked = 0;
        for (String name : config.getMappedStatementNames().stream().toList()) {
            if (!name.contains(".") || !(name.endsWith(".list") || name.endsWith(".listByPerm"))) continue;
            var params = new HashMap<String, Object>(); params.put("offset", 0); params.put("limit", 10);
            params.put("sort", "id desc, sleep(654321)"); params.put("order", "desc;drop table book");
            var sql = config.getMappedStatement(name).getBoundSql(params).getSql().toLowerCase();
            assertFalse(sql.contains("sleep"), name); assertFalse(sql.contains("drop table"), name);
            assertFalse(sql.contains("${"), name); checked++;
        }
        assertTrue(checked >= 27, "All management sort clauses must be exercised");
    }
}
