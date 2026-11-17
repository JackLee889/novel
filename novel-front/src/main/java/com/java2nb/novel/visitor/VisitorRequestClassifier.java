package com.java2nb.novel.visitor;

import jakarta.servlet.http.HttpServletRequest;

import java.util.regex.Pattern;

/** Declared automation is excluded from visitor counters, without blocking access to the site. */
final class VisitorRequestClassifier {
    private static final Pattern AUTOMATED = Pattern.compile(
        "\\b(?:bot|spider|crawler|robot|slurp|scrapy|scraper|headlesschrome|phantomjs|"
            + "selenium|playwright|puppeteer|lighthouse|pagespeed|curl|wget|"
            + "python-requests|python-urllib|python-httpx|aiohttp|httpclient|apache-httpclient|"
            + "go-http-client|libwww-perl|mechanize|httpunit|htmlunit|postmanruntime|"
            + "insomnia|node-fetch|undici|axios)\\b"
            // Product tokens identify custom crawlers too. Do not mistake Android CUBOT models for bots.
            + "|\\b[a-z][a-z0-9_.-]*(?:bot|spider|crawler)(?:/|$)"
            + "|googlebot|googleother|google-inspectiontool|adsbot-google|mediapartners-google"
            + "|bingbot|bingpreview|baiduspider|yandexbot|yandeximages|yandexaccessibilitybot"
            + "|sogou|bytespider|petalbot|360spider|haosouspider|yisouspider"
            + "|gptbot|chatgpt-user|oai-searchbot|claudebot|claude-user|claude-searchbot"
            + "|anthropic-ai|perplexitybot|perplexity-user|amazonbot|applebot"
            + "|facebookexternalhit|facebookcatalog|meta-externalagent|meta-externalfetcher"
            + "|telegrambot|discordbot|skypeuripreview|slackbot|twitterbot|linkedinbot"
            + "|\\b(?:java|okhttp|ruby|perl|php)(?:/|$)",
        Pattern.CASE_INSENSITIVE);

    private VisitorRequestClassifier() {}

    static boolean isAutomated(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        // Browser fetches include a User-Agent. Missing, blank or oversized values are not human visits.
        return userAgent == null || userAgent.isBlank() || userAgent.length() > 4096
            || AUTOMATED.matcher(userAgent).find();
    }
}
