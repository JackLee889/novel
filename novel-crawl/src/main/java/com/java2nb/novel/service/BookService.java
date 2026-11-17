package com.java2nb.novel.service;

import com.java2nb.novel.entity.Book;
import com.java2nb.novel.entity.BookCategory;
import com.java2nb.novel.entity.BookContent;
import com.java2nb.novel.entity.BookIndex;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * @author Administrator
 */
public interface BookService {


    /**
     * 根据小说名和作者名查询是否存在
     * @param bookName 小说名
     * @param authorName 作者名
     * @return 是否存在该小说名和作者名的小说
     */
    boolean queryIsExistByBookNameAndAuthorName(String bookName, String authorName);

    /**
     * 更新书籍的爬虫属性
     * @param id 本站小说ID
     * @param sourceId 爬虫源ID
     * @param bookId 源站小说ID  */
    void updateCrawlProperties(Long id, Integer sourceId, String bookId);

    /**
     * 通过分类ID查询分类名
     * @param catId 分类ID
     * @return 分类名
     * */
    String queryCatNameByCatId(int catId);

    /** 本站分类字典，按后台排序返回。 */
    List<BookCategory> listBookCategories();

    /** 返回分类名和作品方向；不存在时返回 null。 */
    BookCategory queryBookCategoryByCatId(int catId);

    /**
     * 保存小说表，目录表，内容表数据
     * @param book 小说数据
     * @param bookIndexList 目录集合
     * @param bookContentList 内容集合
     * */
    void saveBookAndIndexAndContent(Book book, List<BookIndex> bookIndexList, List<BookContent> bookContentList);

    /**
     * 查询需要更新的小说数据
     *
     * @param startDate 最新更新时间的起始时间
     * @param limit 查询条数
     * @return 小说集合
     * */
    List<Book> queryNeedUpdateBook(Date startDate, int limit);

    /** Current persisted book identity and metadata for a leased update. */
    Book queryBookById(Long bookId);

    /** Find an exact primary source binding, using the title index to bound the lookup. */
    Book queryBookBySource(String bookName, Integer sourceId, String sourceBookId);

    /** Fill only a pending author on the same book/source binding; never merge or renumber books. */
    String fillPendingAuthor(Long bookId, Integer sourceId, String sourceBookId, String author);

    /**
     * 查询已存在的章节
     * @param bookId 小说ID
     * @return 章节号和章节数据对映射map
     * */
    Map<Integer,BookIndex> queryExistBookIndexMap(Long bookId);

    /**
     * 更新小说表，目录表，内容表数据
     * @param book 小说数据
     * @param bookIndexList 目录集合
     * @param bookContentList 内容集合
     * @param existBookIndexMap  已存在的章节Map（按章节号索引）
     * @param bookIndexMetaList [审查01] 只回写身份信息的章节（补录来源章节ID/章节号），可为空
     * @param reindexList [审查01] 章节号重排表（仅在目录顺序变化时非空），可为空
     * */
    void updateBookAndIndexAndContent(Book book, List<BookIndex> bookIndexList, List<BookContent> bookContentList,
        Map<Integer, BookIndex> existBookIndexMap, List<BookIndex> bookIndexMetaList, List<BookIndex> reindexList);

    void updateBookAndIndexAndContent(Book book, List<BookIndex> bookIndexList, List<BookContent> bookContentList,
        Map<Integer, BookIndex> existBookIndexMap, List<BookIndex> bookIndexMetaList, List<BookIndex> reindexList,
        Integer catalogSize);

    /**
     * 更新一下最后一次的抓取时间
     * @param bookId 小说ID
     * */
    @Deprecated
    void updateCrawlLastTime(Long bookId);

    /**
     * 通过小说名和作者名查询已存在的书籍
     * @param bookName 小说名
     * @param authorName 作者名
     * @return 小说对象
     * */
    Book queryBookByBookNameAndAuthorName(String bookName, String authorName);
}
