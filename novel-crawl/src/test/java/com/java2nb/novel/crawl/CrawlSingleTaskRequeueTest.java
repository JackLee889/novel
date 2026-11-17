package com.java2nb.novel.crawl;

import com.java2nb.novel.core.crawl.CrawlParser;
import com.java2nb.novel.entity.CrawlSingleTask;
import com.java2nb.novel.mapper.CrawlSingleTaskMapper;
import com.java2nb.novel.mapper.CrawlSourceMapper;
import com.java2nb.novel.service.BookService;
import com.java2nb.novel.service.impl.CrawlServiceImpl;
import com.java2nb.novel.utils.CrawlHttpClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CrawlSingleTaskRequeueTest {
    @Test
    void requeueDoesNotExhaustTheDownloadFailureBudget() {
        var mapper = mock(CrawlSingleTaskMapper.class);
        var parser = mock(CrawlParser.class);
        var service = new CrawlServiceImpl(parser, mock(CrawlSourceMapper.class), mapper,
            mock(BookService.class), mock(CrawlHttpClient.class));
        var task = new CrawlSingleTask(); task.setId(8L); task.setExcCount((byte)4); task.setTaskStatus((byte)3);
        for (int attempt=0; attempt<6; attempt++) service.updateCrawlSingleTask(task, (byte)2);
        assertEquals((byte)4, task.getExcCount());
        assertEquals((byte)2, task.getTaskStatus());
        verify(mapper, times(6)).updateByPrimaryKeySelective(argThat(saved -> saved.getId().equals(8L)
            && saved.getTaskStatus()==2 && saved.getExcCount()==null));
        service.updateCrawlSingleTask(task, (byte)0);
        assertEquals((byte)5, task.getExcCount());
        assertEquals((byte)0, task.getTaskStatus(), "An actual fifth failed download still fails the task");
    }
}
