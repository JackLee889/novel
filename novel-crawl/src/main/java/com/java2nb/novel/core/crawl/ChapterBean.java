package com.java2nb.novel.core.crawl;

import com.java2nb.novel.entity.BookContent;
import com.java2nb.novel.entity.BookIndex;
import lombok.Data;

import java.util.List;

/**
 * 章节数据封装bean
 * @author Administrator
 */
@Data
public class ChapterBean {

    /**
     * 章节索引集合
     * */
    List<BookIndex> bookIndexList;

    /**
     * 章节内容集合
     * */
    List<BookContent> bookContentList;

    /**
     * [审查01] 只回写身份信息的章节（标题一致、不重新抓正文）：
     * 补录来源章节 ID / 章节号，不涉及正文写入。
     * */
    List<BookIndex> bookIndexMetaList;

    /**
     * [审查01] 章节号重排表（仅在目录顺序与库中章节号不一致时非空）：
     * 每项只带 id 与目标 index_num，由服务端在唯一键安全的前提下一次性赋值。
     * */
    List<BookIndex> reindexList;

    /** Current source catalog boundary; retained historical rows beyond it are not new releases. */
    Integer catalogSize;
}
