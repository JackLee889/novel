package com.java2nb.novel.visitor;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class VisitorStatsBotExclusionTest {
    @Test void robotsDoNotPersistVisitorsOrPagesAndCannotExtendAnExistingOnlineVisitor() throws Exception {
        var clock = new VisitorStatsServiceTest.MutableClock();
        var source = VisitorStatsRepositoryTest.source();
        var db = new JdbcTemplate(source); VisitorStatsRepositoryTest.schema(db);
        var repository = new VisitorStatsRepository(source, clock);
        var online = new VisitorStatsServiceTest.TestOnline(clock);
        var properties = new VisitorStatsProperties();
        var service = new VisitorStatsService(repository, online, properties, clock);
        var mvc = MockMvcBuilders.standaloneSetup(new VisitorStatsController(service, properties, "")).build();
        String visitor = UUID.randomUUID().toString(), page = UUID.randomUUID().toString();
        mvc.perform(post("/site-stats/visit").contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .header("Origin", "http://localhost").header("User-Agent", VisitorStatsControllerTest.BROWSER)
            .param("visitorId", visitor).param("pageViewId", page))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.visitors").value("1"))
            .andExpect(jsonPath("$.data.pageViews").value("1")).andExpect(jsonPath("$.data.online").value("1"));
        clock.advance(89000);
        mvc.perform(post("/site-stats/heartbeat").contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .header("Origin", "http://localhost").header("User-Agent", "Googlebot/2.1").param("visitorId", visitor))
            .andExpect(status().isOk()).andExpect(header().string("X-Visitor-Stats-Excluded", "automated-client"));
        clock.advance(1000);
        assertEquals(0, online.count(), "The robot heartbeat must not extend the human's online window");
        for (String userAgent : new String[]{"Baiduspider/2.0", "curl/8.16.0", "HeadlessChrome/142", ""})
            mvc.perform(post("/site-stats/visit").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("Origin", "http://localhost").header("User-Agent", userAgent)
                .param("visitorId", UUID.randomUUID().toString()).param("pageViewId", UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andExpect(header().string("X-Visitor-Stats-Excluded", "automated-client"));
        assertEquals(1, repository.totals().visitors()); assertEquals(1, repository.totals().pageViews());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM site_visitor_seen", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM site_visitor_pageview", Integer.class));
        assertEquals(0, online.count());
        mvc.perform(post("/site-stats/heartbeat").contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .header("Origin", "http://localhost").header("User-Agent", VisitorStatsControllerTest.BROWSER)
            .param("visitorId", visitor)).andExpect(status().isOk()).andExpect(jsonPath("$.data.online").value("1"));
        assertEquals(1, repository.totals().visitors()); assertEquals(1, repository.totals().pageViews());
    }
}
