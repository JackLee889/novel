package com.java2nb.novel.utils;

import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Actual local HTTP responses and an adjustable clock; no production service connections. */
class CrawlFetchFailureTest {
    HttpServer server;
    ImpersonatingHttpClient client;
    String base;
    final MutableClock clock=new MutableClock();
    final AtomicInteger requests=new AtomicInteger(),status=new AtomicInteger(404);

    static class MutableClock extends Clock {
        long millis=Instant.parse("2026-10-20T00:00:00Z").toEpochMilli();
        void advance(long seconds) {millis+=seconds*1000;}
        @Override public long millis() {return millis;}
        @Override public Instant instant() {return Instant.ofEpochMilli(millis);}
        @Override public ZoneId getZone() {return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone) {return this;}
    }
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/missing",exchange->{
            requests.incrementAndGet();byte[] body="源站页面".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(),body.length);
            try(var out=exchange.getResponseBody()){out.write(body);}
        });
        server.createContext("/normal",exchange->{
            byte[] body=("正常正文"+" ".repeat(1500)).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);try(var out=exchange.getResponseBody()){out.write(body);}
        });
        server.start();base="http://127.0.0.1:"+server.getAddress().getPort();
        client=new ImpersonatingHttpClient(true,new OkHttpClient(),300,clock);
    }
    @AfterEach void close() {client.close();server.stop(0);}

    @Test void missingPageDefersOnlyThatUrlAndAHealthyPageClearsItsThreadDiagnostic() throws Exception {
        assertNull(client.get(base+"/missing","UTF-8"));
        assertEquals(404,client.lastFailure().httpStatus());assertEquals(900,client.lastFailure().retryAfterSeconds());
        assertFalse(client.lastFailure().deferred());
        assertNull(client.get(base+"/missing","UTF-8"));assertTrue(client.lastFailure().deferred());assertEquals(1,requests.get());
        assertNotNull(client.get(base+"/normal","UTF-8"));assertNull(client.lastFailure());
        assertNull(client.get(base+"/missing?different=1","UTF-8"));assertEquals(2,requests.get());
    }

    @Test void laterRechecksBackOffAndARecoveredPageIsActuallyFetchedAndClearsTheFailure() throws Exception {
        assertNull(client.get(base+"/missing","UTF-8"));clock.advance(901);
        assertNull(client.get(base+"/missing","UTF-8"));assertEquals(1800,client.lastFailure().retryAfterSeconds());
        clock.advance(1801);status.set(200);
        assertEquals("源站页面",client.get(base+"/missing","UTF-8"));assertNull(client.lastFailure());assertEquals(3,requests.get());
        status.set(404);assertNull(client.get(base+"/missing","UTF-8"));assertEquals(900,client.lastFailure().retryAfterSeconds());
    }

    @Test void gonePageGetsALongerRecheckWithoutAnOriginWideBlock() throws Exception {
        status.set(410);assertNull(client.get(base+"/missing","UTF-8"));assertEquals(21600,client.lastFailure().retryAfterSeconds());
        assertNull(client.get(base+"/missing","UTF-8"));assertEquals(1,requests.get());
        assertNotNull(client.get(base+"/normal","UTF-8"));
    }

    @Test void temporaryServerErrorsRemainRetryableWithoutTheMissingPageCache() throws Exception {
        status.set(503);assertNull(client.get(base+"/missing","UTF-8"));assertEquals(503,client.lastFailure().httpStatus());
        assertFalse(client.lastFailure().missing());assertNull(client.get(base+"/missing","UTF-8"));assertEquals(2,requests.get());
    }

    @Test void crawlerDoesNotImmediatelyRepeat404AndPreservesTheDiagnosticOnACachedMiss() throws Exception {
        var http=new CrawlHttpClient();ReflectionTestUtils.setField(http,"pageClient",client);
        assertNull(http.get(base+"/missing","UTF-8"));assertEquals(1,requests.get());assertEquals(404,http.lastFailure().httpStatus());
        assertNull(http.get(base+"/missing","UTF-8"));assertEquals(1,requests.get());assertTrue(http.lastFailure().deferred());
        assertNotNull(http.get(base+"/normal","UTF-8"));assertNull(http.lastFailure());
    }

    @Test void failureCannotLeakIntoAnotherWorkerThread() throws Exception {
        try(var executor=Executors.newSingleThreadExecutor()) {
            assertNull(client.get(base+"/missing","UTF-8"));
            assertTrue(executor.submit(()->{assertNull(client.lastFailure());client.get(base+"/normal","UTF-8");return client.lastFailure()==null;}).get(3,TimeUnit.SECONDS));
            assertEquals(404,client.lastFailure().httpStatus());
        }
    }

    @Test void diagnosticContainsTheChapterAndRetryButNeverUrlCredentialsQueryOrMarkup() {
        String message=new CrawlFetchFailure(CrawlFetchFailure.Reason.HTTP,404,"https://user:password@site.invalid/book/1/2.html?token=private#secret",900,false)
            .describe("1","书名<script>","2","第二章\n标题");
        assertTrue(message.contains("[CHAPTER_HTTP_404]"));assertTrue(message.contains("章号=2"));assertTrue(message.contains("15分钟"));
        for(String secret:new String[]{"user:","password","token","private","secret","<script>","\n"})assertFalse(message.contains(secret));
    }
}
