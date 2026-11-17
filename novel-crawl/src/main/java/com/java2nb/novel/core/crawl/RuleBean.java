package com.java2nb.novel.core.crawl;

import com.java2nb.novel.utils.Constants;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 爬虫解析规则bean
 *
 * @author Administrator
 */
@Data
public class RuleBean {

    /**
     * 网页字符编码
     */
    private String charset = Constants.CRAWL_DEFAULT_CHARSET;


    /**
     * 小说更新列表url
     */
    private String updateBookListUrl;

    /**
     * 分类列表页URL规则
     */
    private String bookListUrl;

    private Map<String, String> catIdRule;

    /** 同一本站分类可对应多个源站分类，旧版单值规则继续兼容。 */
    private Map<String, List<String>> catIdRules;

    public List<String> resolveCategoryRules(int categoryId) {
        String key = "catId" + categoryId;
        if (catIdRules != null && catIdRules.containsKey(key)) {
            List<String> values = catIdRules.get(key);
            return values == null ? List.of() : values.stream()
                .filter(Objects::nonNull).map(String::trim).filter(value -> !value.isEmpty())
                .distinct().toList();
        }
        String legacy = catIdRule == null ? null : catIdRule.get(key);
        return legacy == null || legacy.isBlank() ? List.of() : List.of(legacy.trim());
    }

    private Map<String, Byte> bookStatusRule;

    /** 批量新书采集范围；旧规则未配置时采集全部，不影响已有书更新或单本任务。 */
    private NewBookStatus newBookStatus = NewBookStatus.ALL;

    public enum NewBookStatus {
        ALL, COMPLETED, SERIALIZING
    }

    public boolean acceptsNewBookStatus(Byte bookStatus) {
        NewBookStatus selection = newBookStatus == null ? NewBookStatus.ALL : newBookStatus;
        return switch (selection) {
            case ALL -> true;
            case COMPLETED -> Byte.valueOf((byte) 1).equals(bookStatus);
            case SERIALIZING -> Byte.valueOf((byte) 0).equals(bookStatus);
        };
    }

    private String bookIdPatten;
    private String pagePatten;
    private String totalPagePatten;
    private String bookDetailUrl;
    private String bookNamePatten;
    private String authorNamePatten;
    private String picUrlPatten;
    private String statusPatten;
    private String scorePatten;
    private String visitCountPatten;
    private String descStart;
    private String descEnd;
    private String filterDesc;
    private String upadateTimePatten;
    private String upadateTimeFormatPatten;
    private String bookIndexUrl;
    private String indexIdPatten;
    private String indexNamePatten;
    private String bookContentUrl;
    private String contentStart;
    private String contentEnd;


    private String picUrlPrefix;

    private String bookIndexStart;

    private String filterContent;


    /**
     * 目录分页URL模板，需含 {bookId} 与 {page}，例如
     * https://www.example.com/index/{bookId}/{page}/
     * 为空时只抓取 bookIndexUrl 单页，即原有行为。
     */
    private String bookIndexPageUrl;

    /**
     * 目录页总页数正则，捕获组1为页码；实现会取所有匹配中的最大值。
     * 仅在 bookIndexPageUrl 非空时生效；匹配不到时按 1 页处理。
     */
    private String bookIndexTotalPagePatten;

    /**
     * 章节内容页「下一页」正则，捕获组1为链接（支持根相对与相对路径）。
     * 只有当解析出的链接仍属于同一章（形如 {indexId}_N.html）时才会继续抓取，
     * 否则视为下一章并停止，避免把下一章正文併入本章。
     */
    private String contentNextPagePatten;


}
