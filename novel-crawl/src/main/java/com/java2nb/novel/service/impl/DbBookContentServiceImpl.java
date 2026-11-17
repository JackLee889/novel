package com.java2nb.novel.service.impl;

import com.java2nb.novel.entity.BookContent;
import com.java2nb.novel.mapper.BookContentDynamicSqlSupport;
import com.java2nb.novel.mapper.BookContentMapper;
import com.java2nb.novel.service.BookContentService;
import lombok.RequiredArgsConstructor;
import org.mybatis.dynamic.sql.render.RenderingStrategies;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import static org.mybatis.dynamic.sql.SqlBuilder.select;

import static org.mybatis.dynamic.sql.SqlBuilder.isEqualTo;
import static org.mybatis.dynamic.sql.SqlBuilder.update;


@Service(value = "db")
@RequiredArgsConstructor
public class DbBookContentServiceImpl implements BookContentService {

    private final BookContentMapper bookContentMapper;

    @Override
    public void saveBookContent(List<BookContent> bookContentList,Long bookId) {
        bookContentMapper.insertMultiple(bookContentList);
    }

    @Override
    public void saveBookContent(BookContent bookContent,Long bookId) {
        bookContentMapper.insertSelective(bookContent);
    }

    @Override
    public void updateBookContent(BookContent bookContent,Long bookId) {
        int changed = bookContentMapper.update(update(BookContentDynamicSqlSupport.bookContent)
                .set(BookContentDynamicSqlSupport.content)
                .equalTo(bookContent.getContent())
                .where(BookContentDynamicSqlSupport.indexId,isEqualTo(bookContent.getIndexId()))
                .build()
                .render(RenderingStrategies.MYBATIS3));
        if (changed == 0) {
            // An existing chapter can have no body row at all. UPDATE alone silently left the hole.
            try { bookContentMapper.insertSelective(bookContent); }
            catch (org.springframework.dao.DuplicateKeyException concurrentInsert) {
                bookContentMapper.update(update(BookContentDynamicSqlSupport.bookContent)
                    .set(BookContentDynamicSqlSupport.content).equalTo(bookContent.getContent())
                    .where(BookContentDynamicSqlSupport.indexId,isEqualTo(bookContent.getIndexId()))
                    .build().render(RenderingStrategies.MYBATIS3));
            }
        }
    }

    @Override
    public boolean sameContent(BookContent content,Long bookId) {
        var stored=bookContentMapper.selectMany(select(BookContentDynamicSqlSupport.content)
            .from(BookContentDynamicSqlSupport.bookContent).where(BookContentDynamicSqlSupport.indexId,isEqualTo(content.getIndexId()))
            .build().render(RenderingStrategies.MYBATIS3));
        return !stored.isEmpty()&&Objects.equals(stored.getFirst().getContent(),content.getContent());
    }
}
