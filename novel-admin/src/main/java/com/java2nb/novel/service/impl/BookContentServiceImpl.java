package com.java2nb.novel.service.impl;

import org.apache.ibatis.annotations.Param;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import com.java2nb.novel.dao.BookContentDao;
import com.java2nb.novel.domain.BookContentDO;
import com.java2nb.novel.service.BookContentService;
import com.java2nb.novel.service.SeoMutationSupport;
import com.java2nb.novel.dao.BookIndexDao;
import com.java2nb.novel.domain.BookIndexDO;
import org.springframework.transaction.annotation.Transactional;



@Service
public class BookContentServiceImpl implements BookContentService {
	@Autowired
	private BookContentDao bookContentDao;
	@Autowired
	private BookIndexDao bookIndexDao;
	@Autowired
	private SeoMutationSupport seo;
	
	@Override
	public BookContentDO get(Long id){
		return bookContentDao.get(id);
	}
	
	@Override
	public List<BookContentDO> list(Map<String, Object> map){
		return bookContentDao.list(map);
	}
	
	@Override
	public int count(Map<String, Object> map){
		return bookContentDao.count(map);
	}
	
	@Override
	@Transactional(rollbackFor = Exception.class)
	public int save(BookContentDO bookContent){
		seo.begin();
		int rows = bookContentDao.save(bookContent);
		if (rows > 0) dirtyIndex(bookContent.getIndexId());
		return rows;
	}
	
	@Override
	@Transactional(rollbackFor = Exception.class)
	public int update(BookContentDO bookContent){
		seo.begin();
		BookContentDO before = bookContentDao.get(bookContent.getId());
		int rows = bookContentDao.update(bookContent);
		if (rows > 0 && before != null && (SeoMutationSupport.changed(before.getContent(), bookContent.getContent())
			|| SeoMutationSupport.changed(before.getIndexId(), bookContent.getIndexId()))) {
			dirtyIndex(before.getIndexId());
			if (bookContent.getIndexId() != null && !bookContent.getIndexId().equals(before.getIndexId())) dirtyIndex(bookContent.getIndexId());
		}
		return rows;
	}
	
	@Override
	@Transactional(rollbackFor = Exception.class)
	public int remove(Long id){
		seo.begin();
		BookContentDO before = bookContentDao.get(id);
		int rows = bookContentDao.remove(id);
		if (rows > 0 && before != null) dirtyIndex(before.getIndexId());
		return rows;
	}
	
	@Override
	@Transactional(rollbackFor = Exception.class)
	public int batchRemove(Long[] ids){
		seo.begin();
		List<Long> indexes = java.util.Arrays.stream(ids).map(bookContentDao::get).filter(java.util.Objects::nonNull)
			.map(BookContentDO::getIndexId).filter(java.util.Objects::nonNull).distinct().toList();
		int rows = bookContentDao.batchRemove(ids);
		if (rows > 0) indexes.forEach(this::dirtyIndex);
		return rows;
	}

	@Override
	public List<BookContentDO> listByIndexIds(List<Long> indexIds) {
		return bookContentDao.listByIndexIds(indexIds);
	}
	private void dirtyIndex(Long indexId) {
		if (indexId == null) return;
		BookIndexDO index = bookIndexDao.get(indexId);
		if (index != null) seo.dirty(index.getBookId());
	}

}
