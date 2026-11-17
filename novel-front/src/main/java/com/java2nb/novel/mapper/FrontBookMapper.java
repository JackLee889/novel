package com.java2nb.novel.mapper;

import com.java2nb.novel.entity.Book;
import com.java2nb.novel.vo.BookSpVO;
import com.java2nb.novel.vo.BookVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @author Administrator
 */
public interface FrontBookMapper extends BookMapper {


    /** PageHelper counts and pages narrow IDs; only the selected page loads full book rows. */
    default List<BookVO> searchByPage(BookSpVO params) {
        List<BookVO> page = searchPageIds(params);
        if (page.isEmpty()) return page;
        List<Long> ids = page.stream().map(BookVO::getId).toList();
        var details = searchPageDetails(ids).stream().collect(java.util.stream.Collectors.toMap(BookVO::getId, b -> b));
        // Mutate the existing Page so its count, page number and page-size metadata survive.
        page.clear();
        for (Long id : ids) {
            BookVO book = details.get(id);
            if (book != null) page.add(book);
        }
        return page;
    }

    List<BookVO> searchPageIds(BookSpVO params);

    List<BookVO> searchPageDetails(@Param("ids") List<Long> ids);

    void addVisitCount(@Param("bookId") Long bookId, @Param("visitCount") Integer visitCount);

    List<Long> listRecommendationIds(@Param("catId") Integer catId);

    List<Book> listRecommendationsByIds(@Param("catId") Integer catId,
        @Param("ids") List<Long> ids);

    void addCommentCount(@Param("bookId") Long bookId);

    List<Book> queryNetworkPicBooks(@Param("localPicPrefix") String localPicPrefix, @Param("limit") Integer limit);

    /**
     * 按评分随机查询小说集合
     * @param limit 查询条数
     * @return 小说集合
     * */
    List<Book> selectIdsByScoreAndRandom(@Param("limit") int limit);
}
