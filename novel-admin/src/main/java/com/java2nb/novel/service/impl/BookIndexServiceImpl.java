package com.java2nb.novel.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import com.java2nb.novel.dao.BookIndexDao;
import com.java2nb.novel.domain.BookIndexDO;
import com.java2nb.novel.service.BookIndexService;
import com.java2nb.novel.service.SeoMutationSupport;
import org.springframework.transaction.annotation.Transactional;



@Service
public class BookIndexServiceImpl implements BookIndexService {
	@Autowired
	private BookIndexDao bookIndexDao;
	@Autowired
	private SeoMutationSupport seo;
	
	@Override
	public BookIndexDO get(Long id){
		return bookIndexDao.get(id);
	}
	
	@Override
	public List<BookIndexDO> list(Map<String, Object> map){
		return bookIndexDao.list(map);
	}
	
	@Override
	public int count(Map<String, Object> map){
		return bookIndexDao.count(map);
	}
	
	@Override
	@Transactional(rollbackFor = Exception.class)
	public int save(BookIndexDO bookIndex){
		seo.begin();
		int rows = bookIndexDao.save(bookIndex);
		if (rows > 0) seo.dirty(bookIndex.getBookId());
		return rows;
	}
	
	@Override
	@Transactional(rollbackFor = Exception.class)
	public int update(BookIndexDO bookIndex){
		seo.begin();
		BookIndexDO before = bookIndexDao.get(bookIndex.getId());
		int rows = bookIndexDao.update(bookIndex);
		if (rows > 0 && SeoMutationSupport.indexChanged(before, bookIndex)) {
			seo.dirty(before.getBookId());
			if (bookIndex.getBookId() != null && !bookIndex.getBookId().equals(before.getBookId())) seo.dirty(bookIndex.getBookId());
		}
		return rows;
	}
	
	@Override
	@Transactional(rollbackFor = Exception.class)
	public int remove(Long id){
		seo.begin();
		BookIndexDO before = bookIndexDao.get(id);
		int rows = bookIndexDao.remove(id);
		if (rows > 0 && before != null) seo.dirty(before.getBookId());
		return rows;
	}
	
	@Override
	@Transactional(rollbackFor = Exception.class)
	public int batchRemove(Long[] ids){
		seo.begin();
		List<Long> books = java.util.Arrays.stream(ids).map(bookIndexDao::get).filter(java.util.Objects::nonNull)
			.map(BookIndexDO::getBookId).filter(java.util.Objects::nonNull).distinct().toList();
		int rows = bookIndexDao.batchRemove(ids);
		if (rows > 0) books.forEach(seo::dirty);
		return rows;
	}
	
}
