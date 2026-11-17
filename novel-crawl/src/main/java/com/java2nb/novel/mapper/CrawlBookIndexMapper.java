package com.java2nb.novel.mapper;

import com.java2nb.novel.entity.BookIndex;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * @author Administrator
 */
public interface CrawlBookIndexMapper extends BookIndexMapper {


    /**
     * 查询最后的章节
     * */
    BookIndex queryLastIndex(@Param("bookId") Long bookId);

    @Select("SELECT id,index_name,index_num,create_time,update_time FROM book_index "
        + "WHERE book_id=#{bookId} AND index_num < #{catalogSize} ORDER BY index_num DESC LIMIT 1")
    BookIndex queryLastCatalogIndex(@Param("bookId") Long bookId, @Param("catalogSize") int catalogSize);

    /**
     * [审查01] 章节号重排第一步：把「待重排的那些行」整体挪到高位临时区间。
     * 表上有唯一键 (book_id, index_num)，直接逐行改成新章节号会在中间态撞键，
     * 所以先挪走，再逐个赋回最终值。
     *
     * <p>两个刻意的约束：
     * <ul>
     *   <li>只挪传入 id 的行，不按 book_id 整本挪：采集快照是在解析前取的，期间若有别的
     *       写者新增了章节行，整本挪会把它们也挪走却没有任何赋回，index_num 永久错乱；</li>
     *   <li>偏移到正数高位而不是负数区间：负数一旦残留（异常中断、并发），既会和异常数据
     *       撞唯一键，又会让前台 order by index_num 把它排到目录最前。</li>
     * </ul>
     *
     * @param ids    需要重排的章节主键
     * @param offset 临时偏移量，需大于本书任何正常章节号
     * @return 受影响行数（正常情况下等于 ids.size()）
     */
    int parkBookIndexNums(@Param("bookId") Long bookId, @Param("ids") List<Long> ids,
        @Param("offset") Integer offset);

    /**
     * [审查01] 章节号重排第二步：按来源站目录顺序把某一章赋回最终章节号。
     */
    int updateBookIndexNum(@Param("id") Long id, @Param("indexNum") Integer indexNum);

    int updateBookIndexNums(@Param("bookId") Long bookId, @Param("rows") List<BookIndex> rows);

    int updateIdentityMetadata(@Param("bookId") Long bookId, @Param("rows") List<BookIndex> rows);
}
