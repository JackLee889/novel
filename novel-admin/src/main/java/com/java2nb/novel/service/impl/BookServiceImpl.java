package com.java2nb.novel.service.impl;

import com.java2nb.novel.dao.BookContentDao;
import com.java2nb.novel.dao.BookDao;
import com.java2nb.novel.dao.BookIndexDao;
import com.java2nb.novel.domain.BookDO;
import com.java2nb.novel.service.BookService;
import com.java2nb.novel.service.SeoMutationSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@Service
public class BookServiceImpl implements BookService {

    @Autowired
    private BookDao bookDao;
    @Autowired
    private BookIndexDao bookIndexDao;
    @Autowired
    private BookContentDao bookContentDao;
    @Autowired
    private SeoMutationSupport seo;

    @Override
    public BookDO get(Long id) {
        return bookDao.get(id);
    }

    @Override
    public List<BookDO> list(Map<String, Object> map) {
        return bookDao.list(map);
    }

    @Override
    public int count(Map<String, Object> map) {
        return bookDao.count(map);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int save(BookDO book) {
        seo.begin();
        int rows = bookDao.save(book);
        if (rows > 0) seo.dirty(book.getId());
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int update(BookDO book) {
        seo.begin();
        BookDO before = bookDao.get(book.getId());
        int rows = bookDao.update(book);
        if (rows > 0 && SeoMutationSupport.bookChanged(before, book)) seo.dirty(book.getId());
        return rows;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public int remove(Long id) {
        seo.begin();
        List<Long> indexIds = bookIndexDao.getIdsByBookId(id);
        if (!CollectionUtils.isEmpty(indexIds)) {
            Long[] indexIdArray = indexIds.toArray(new Long[0]);
            bookIndexDao.batchRemove(indexIdArray);
            bookContentDao.removeByIndexIds(indexIdArray);
        }
        int rows = bookDao.remove(id);
        if (rows > 0) seo.removed(id);
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchRemove(Long[] ids) {
        seo.begin();
        List<Long> existing = java.util.Arrays.stream(ids).filter(id -> bookDao.get(id) != null).distinct().toList();
        int rows = bookDao.batchRemove(ids);
        if (rows > 0) existing.forEach(seo::removed);
        return rows;
    }

    @Override
    public Map<Object, Object> tableSta(Date minDate) {
        List<Map<Object, Object>> maps = bookDao.tableSta(minDate);

        return maps.stream().collect(Collectors.toMap(x -> x.get("staDate"), x -> x.get("bookCount")));

    }

}
