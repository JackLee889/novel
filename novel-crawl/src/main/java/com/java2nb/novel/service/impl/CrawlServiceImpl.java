package com.java2nb.novel.service.impl;

import tools.jackson.databind.ObjectMapper;
import com.github.pagehelper.PageHelper;
import com.java2nb.novel.core.cache.CacheService;
import com.java2nb.novel.core.crawl.CrawlParser;
import com.java2nb.novel.core.crawl.CrawlBookBusyException;
import com.java2nb.novel.core.crawl.CrawlCollectionStore;
import com.java2nb.novel.core.crawl.CrawlDiscoveryStore;
import com.java2nb.novel.core.crawl.CrawlRuntimeMonitor;
import com.java2nb.novel.core.crawl.CrawlRefreshStore;
import com.java2nb.novel.core.crawl.RuleBean;
import com.java2nb.novel.core.crawl.UukanshuSourceAdapter;
import com.java2nb.novel.core.enums.ResponseStatus;
import com.java2nb.novel.entity.Book;
import com.java2nb.novel.entity.BookCategory;
import com.java2nb.novel.entity.BookIndex;
import com.java2nb.novel.entity.CrawlSingleTask;
import com.java2nb.novel.entity.CrawlSource;
import com.java2nb.novel.mapper.CrawlSingleTaskDynamicSqlSupport;
import com.java2nb.novel.mapper.CrawlSingleTaskMapper;
import com.java2nb.novel.mapper.CrawlSourceDynamicSqlSupport;
import com.java2nb.novel.mapper.CrawlSourceMapper;
import com.java2nb.novel.service.BookService;
import com.java2nb.novel.service.CrawlService;
import com.java2nb.novel.utils.CrawlHttpClient;
import com.java2nb.novel.utils.CrawlRequestContext;
import com.java2nb.novel.vo.CrawlSingleTaskVO;
import com.java2nb.novel.vo.CrawlSourceVO;
import io.github.xxyopen.model.page.PageBean;
import io.github.xxyopen.model.page.builder.pagehelper.PageBuilder;
import io.github.xxyopen.util.IdWorker;
import jakarta.annotation.PreDestroy;
import io.github.xxyopen.web.exception.BusinessException;
import io.github.xxyopen.web.util.BeanUtil;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.mybatis.dynamic.sql.render.RenderingStrategies;
import org.mybatis.dynamic.sql.select.render.SelectStatementProvider;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import com.java2nb.novel.utils.Constants;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;

import static com.java2nb.novel.mapper.CrawlSourceDynamicSqlSupport.*;
import static org.mybatis.dynamic.sql.SqlBuilder.isEqualTo;
import static org.mybatis.dynamic.sql.select.SelectDSL.select;

/**
 * @author Administrator
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CrawlServiceImpl implements CrawlService {

    private final CrawlParser crawlParser;

    private final CrawlSourceMapper crawlSourceMapper;

    private final CrawlSingleTaskMapper crawlSingleTaskMapper;

    private final BookService bookService;

    private final IdWorker idWorker = IdWorker.INSTANCE;

    private final CrawlHttpClient crawlHttpClient;

    private CrawlDiscoveryStore discoveryStore;
    private CrawlRefreshStore refreshStore;
    @org.springframework.beans.factory.annotation.Autowired
    public void setRefreshStore(CrawlRefreshStore store) {this.refreshStore=Objects.requireNonNull(store);}

    private CrawlRuntimeMonitor runtimeMonitor = CrawlRuntimeMonitor.disabled();

    @org.springframework.beans.factory.annotation.Autowired
    public void setRuntimeMonitor(CrawlRuntimeMonitor monitor) { this.runtimeMonitor = Objects.requireNonNull(monitor); }

    @Value("${crawl.update.max-chapters-per-book:25}")
    private int updateMaxChaptersPerBook = 25;

    @org.springframework.beans.factory.annotation.Autowired
    public void setDiscoveryStore(CrawlDiscoveryStore store) { this.discoveryStore = Objects.requireNonNull(store); }

    private final Map<Integer, Byte> crawlSourceStatusMap = new ConcurrentHashMap<>();

    private final Map<Integer, List<Thread>> runningCrawlThread = new HashMap<>();


    @Override
    public void addCrawlSource(CrawlSource source) {
        Date currentDate = new Date();
        source.setCreateTime(currentDate);
        source.setUpdateTime(currentDate);
        crawlSourceMapper.insertSelective(source);

    }

    @Override
    public synchronized void updateCrawlSource(CrawlSource source) {
        if (source.getId() != null) {
            Optional<CrawlSource> opt = crawlSourceMapper.selectByPrimaryKey(source.getId());
            if (opt.isPresent()) {
                // 编辑规则仍会停止来源；只更新编辑字段，避免把关闭前的状态写回数据库。
                openOrCloseCrawl(source.getId(), (byte) 0);
                CrawlSource changes = new CrawlSource();
                changes.setId(source.getId());
                changes.setUpdateTime(new Date());
                changes.setCrawlRule(source.getCrawlRule());
                changes.setSourceName(source.getSourceName());
                crawlSourceMapper.updateByPrimaryKeySelective(changes);
            }
        }
    }

    @Override
    public PageBean<CrawlSource> listCrawlByPage(int page, int pageSize) {
        PageHelper.startPage(page, pageSize);
        SelectStatementProvider render = select(id, sourceName, sourceStatus, createTime, updateTime)
            .from(crawlSource)
            .orderBy(updateTime.descending())
            .build()
            .render(RenderingStrategies.MYBATIS3);
        List<CrawlSource> crawlSources = crawlSourceMapper.selectMany(render);
        PageBean<CrawlSource> pageBean = PageBuilder.build(crawlSources);
        List<CrawlSourceVO> crawlSourceVOS = BeanUtil.copyList(crawlSources, CrawlSourceVO.class);
        crawlSourceVOS.forEach(crawlSource -> {
                crawlSource.setSourceStatus(
                    Optional.ofNullable(crawlSourceStatusMap.get(crawlSource.getId())).orElse((byte) 0));
                crawlSource.setChapterCount(crawlParser.getCrawlSourceChapterCount(crawlSource.getId()));
                crawlSource.setRuntime(runtimeMonitor.snapshot(crawlSource.getId()));
                if(refreshStore!=null) crawlSource.setQueue(refreshStore.stats(crawlSource.getId()));
            }
        );
        pageBean.setList(crawlSourceVOS);
        return pageBean;
    }

    @SneakyThrows
    @Override
    public synchronized void openOrCloseCrawl(Integer sourceId, Byte sourceStatus) {
        validateSourceStatus(sourceId, sourceStatus);
        if (sourceStatus == (byte) 0) {
            // 保存失败时保留原运行状态，让页面请求明确失败。
            updateCrawlSourceStatus(sourceId, (byte) 0);
            crawlSourceStatusMap.put(sourceId, (byte) 0);
            interruptSourceWorkers(sourceId);
            return;
        }
        if (crawlSourceStatusMap.getOrDefault(sourceId, (byte) 0) == (byte) 1) {
            // 重复开启只校准数据库，不重复创建分类线程。
            updateCrawlSourceStatus(sourceId, (byte) 1);
            return;
        }
        CrawlSource source = queryCrawlSource(sourceId);
        if (source == null) {
            throw new IllegalArgumentException("Crawl source does not exist: " + sourceId);
        }
        RuleBean ruleBean = new ObjectMapper().readValue(source.getCrawlRule(), RuleBean.class);
        List<BookCategory> categories = bookService.listBookCategories().stream()
            .filter(category -> category.getId() != null
                && !ruleBean.resolveCategoryRules(category.getId()).isEmpty())
            .toList();
        if (categories.isEmpty()) {
            throw new IllegalArgumentException("No configured book categories for source: " + sourceId);
        }
        List<Thread> threads = categories.stream().map(category -> {
            Thread thread = new Thread(() -> parseBookList(category.getId(), ruleBean, sourceId),
                "crawl-source-" + sourceId + "-category-" + category.getId());
            thread.setDaemon(true);
            return thread;
        }).toList();
        // 规则校验、状态保存成功后才启动，数据库故障不能留下未保存的采集线程。
        updateCrawlSourceStatus(sourceId, (byte) 1);
        crawlSourceStatusMap.put(sourceId, (byte) 1);
        runningCrawlThread.put(sourceId, threads);
        try {
            threads.forEach(Thread::start);
        } catch (RuntimeException | Error failure) {
            crawlSourceStatusMap.put(sourceId, (byte) 0);
            interruptSourceWorkers(sourceId);
            try {
                updateCrawlSourceStatus(sourceId, (byte) 0);
            } catch (RuntimeException persistenceFailure) {
                failure.addSuppressed(persistenceFailure);
            }
            throw failure;
        }
    }

    private static void validateSourceStatus(Integer sourceId, Byte sourceStatus) {
        if (sourceId == null || sourceId <= 0 || sourceStatus == null
            || (sourceStatus != (byte) 0 && sourceStatus != (byte) 1)) {
            throw new IllegalArgumentException("Invalid crawl source or status");
        }
    }

    private void interruptSourceWorkers(Integer sourceId) {
        List<Thread> threads = runningCrawlThread.remove(sourceId);
        if (threads != null) {
            threads.forEach(Thread::interrupt);
        }
    }

    @PreDestroy
    public synchronized void shutdown() {
        // 应用退出只停止线程，保留已保存的开关供下次启动恢复。
        new ArrayList<>(runningCrawlThread.keySet()).forEach(this::interruptSourceWorkers);
        crawlSourceStatusMap.clear();
    }

    @Override
    public CrawlSource queryCrawlSource(Integer sourceId) {
        SelectStatementProvider render = select(CrawlSourceDynamicSqlSupport.sourceStatus,
            CrawlSourceDynamicSqlSupport.crawlRule)
            .from(crawlSource)
            .where(id, isEqualTo(sourceId))
            .build()
            .render(RenderingStrategies.MYBATIS3);
        return crawlSourceMapper.selectMany(render).stream().findFirst().orElse(null);
    }

    @Override
    public void addCrawlSingleTask(CrawlSingleTask singleTask) {

        if (bookService.queryIsExistByBookNameAndAuthorName(singleTask.getBookName(), singleTask.getAuthorName())) {
            throw new BusinessException(ResponseStatus.BOOK_EXISTS);

        }
        singleTask.setCreateTime(new Date());
        crawlSingleTaskMapper.insertSelective(singleTask);


    }

    @Override
    public PageBean<CrawlSingleTask> listCrawlSingleTaskByPage(int page, int pageSize) {
        PageHelper.startPage(page, pageSize);
        SelectStatementProvider render = select(CrawlSingleTaskDynamicSqlSupport.crawlSingleTask.allColumns())
            .from(CrawlSingleTaskDynamicSqlSupport.crawlSingleTask)
            .orderBy(CrawlSingleTaskDynamicSqlSupport.createTime.descending())
            .build()
            .render(RenderingStrategies.MYBATIS3);
        List<CrawlSingleTask> crawlSingleTasks = crawlSingleTaskMapper.selectMany(render);
        PageBean<CrawlSingleTask> pageBean = PageBuilder.build(crawlSingleTasks);
        pageBean.setList(BeanUtil.copyList(crawlSingleTasks, CrawlSingleTaskVO.class));
        for (CrawlSingleTask crawlSingleTask : pageBean.getList()) {
            if (crawlSingleTask.getTaskStatus() == 2
                && crawlParser.getCrawlTaskProgress(crawlSingleTask.getId()) != null) {
                // 如果排队中的任务有任务进度，将排队中的任务状态修改成采集中并设置任务进度
                crawlSingleTask.setTaskStatus((byte) 3);
                crawlSingleTask.setCrawlChapters(crawlParser.getCrawlTaskProgress(crawlSingleTask.getId()));
                // 只会有一个任务在采集中
                break;
            }
        }
        return pageBean;
    }

    @Override
    public void delCrawlSingleTask(Long id) {
        crawlSingleTaskMapper.deleteByPrimaryKey(id);
    }

    @Override
    public CrawlSingleTask getCrawlSingleTask() {

        List<CrawlSingleTask> list = crawlSingleTaskMapper.selectMany(
            select(CrawlSingleTaskDynamicSqlSupport.crawlSingleTask.allColumns())
                .from(CrawlSingleTaskDynamicSqlSupport.crawlSingleTask)
                .where(CrawlSingleTaskDynamicSqlSupport.taskStatus, isEqualTo((byte) 2))
                .orderBy(CrawlSingleTaskDynamicSqlSupport.createTime)
                .limit(1)
                .build()
                .render(RenderingStrategies.MYBATIS3));

        if (list.isEmpty()) {
            return null;
        }
        CrawlSingleTask task = list.get(0);
        // [审查06] 原来只 SELECT 排队状态，没有领取动作，多实例下会重复领取同一任务。
        // 领取后立即置为 3（采集中），使其不再满足 task_status = 2 的选取条件。
        CrawlSingleTask claim = new CrawlSingleTask();
        claim.setId(task.getId());
        claim.setTaskStatus((byte) 3);
        crawlSingleTaskMapper.updateByPrimaryKeySelective(claim);
        task.setTaskStatus((byte) 3);
        return task;
    }

    @Override
    public void updateCrawlSingleTask(CrawlSingleTask task, Byte status) {
        if (status == 2) {
            CrawlSingleTask queued = new CrawlSingleTask();
            queued.setId(task.getId());
            queued.setTaskStatus((byte) 2);
            crawlSingleTaskMapper.updateByPrimaryKeySelective(queued);
            task.setTaskStatus((byte) 2);
            crawlParser.removeCrawlTaskProgress(task.getId());
            return;
        }
        byte excCount = task.getExcCount();
        excCount += 1;
        task.setExcCount(excCount);
        if (status == 1 || excCount == 5) {
            // 当采集成功或者采集次数等于5，则更新采集最终状态，并停止采集
            task.setTaskStatus(status);
        } else {
            // [审查06] 领取时任务已被置为「采集中」(3)，未到最终状态必须放回排队(2)，
            // 否则该任务再也不会被 getCrawlSingleTask 选中，重试机制失效。
            task.setTaskStatus((byte) 2);
        }
        if (status == 1) {
            // 当采集成功，保存采集的章节数量
            task.setCrawlChapters(crawlParser.getCrawlTaskProgress(task.getId()));
        }
        crawlSingleTaskMapper.updateByPrimaryKeySelective(task);
        // 删除任务进度
        crawlParser.removeCrawlTaskProgress(task.getId());

    }

    @Override
    public CrawlSource getCrawlSource(Integer id) {
        return crawlSourceMapper.selectByPrimaryKey(id).orElse(null);
    }

    @Override
    public Integer getTaskProgress(Long taskId) {
        return Optional.ofNullable(crawlParser.getCrawlTaskProgress(taskId)).orElse(0);
    }

    /**
     * 解析分类列表
     */
    @Override
    public void parseBookList(int catId, RuleBean ruleBean, Integer sourceId) {
        List<String> categoryRules = ruleBean.resolveCategoryRules(catId);
        if (categoryRules.isEmpty()) {
            return;
        }
        long initialTime = categoryTimeNanos();
        String policyHash = CrawlCollectionStore.hash(new ObjectMapper().writeValueAsString(ruleBean));
        List<CategoryCursor> cursors = categoryRules.stream()
            .flatMap(rule -> (refreshStore==null?java.util.stream.Stream.of(false):java.util.stream.Stream.of(true,false)).map(recent -> {
                CrawlDiscoveryStore.Cursor saved = discoveryStore.cursor(sourceId, catId,
                    CrawlCollectionStore.hash(policyHash + "\n" + rule+(recent?"\nrecent":"")));
                CategoryCursor cursor = new CategoryCursor(rule, initialTime);
                cursor.recent=recent;
                cursor.saved = saved; cursor.page = saved.page(); cursor.totalPage = saved.total();
                cursor.nextRequestNanos += Math.max(0, Duration.between(java.time.Instant.now(), saved.due()).toNanos());
                return cursor;
            })).toList();
        while (!Thread.currentThread().isInterrupted()) {
            for (CategoryCursor cursor : cursors) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                if (cursor.nextRequestNanos - categoryTimeNanos() > 0) {
                    continue;
                }
                try (var requestContext=CrawlRequestContext.open(CrawlRequestContext.Kind.SCAN);
                     CrawlRuntimeMonitor.Scope activity = runtimeMonitor.beginScan(sourceId, catId, cursor.page, cursor.totalPage)) {
                    if(cursor.recent) runtimeMonitor.title("近期书目 · 分类 "+catId);
                    String catBookListUrl = StringUtils.isNotBlank(ruleBean.getBookListUrl())
                        ? ruleBean.getBookListUrl().replace("{catId}", cursor.rule)
                            .replace("{page}", Integer.toString(cursor.page))
                        : cursor.rule.replace("{page}", Integer.toString(cursor.page));
                    log.info("catBookListUrl：{}", catBookListUrl);
                    String html = crawlHttpClient.get(catBookListUrl, ruleBean.getCharset());
                    if (html == null) {
                        throw new IllegalStateException("No category page content: " + catBookListUrl);
                    }
                    Matcher books = Pattern.compile(ruleBean.getBookIdPatten()).matcher(html);
                    List<String> discoveredBooks = new ArrayList<>();
                    while (books.find()) {
                        if (Thread.currentThread().isInterrupted()) {
                            return;
                        }
                        discoveredBooks.add(books.group(1));
                    }
                    if (StringUtils.isNotBlank(ruleBean.getTotalPagePatten())) {
                        Matcher total = Pattern.compile(ruleBean.getTotalPagePatten()).matcher(html);
                        if (total.find()) {
                            cursor.totalPage = Math.max(1, Integer.parseInt(total.group(1)));
                        }
                    }
                    if(cursor.recent) cursor.totalPage=Math.min(3,cursor.totalPage);
                    Duration cycleDelay=cursor.recent?Duration.ofMinutes(5):refreshStore==null?CATEGORY_RETRY_DELAY:refreshStore.scanCycleDelay();
                    if (!discoveryStore.commitPage(cursor.saved, cursor.totalPage, discoveredBooks, policyHash, cycleDelay)) {
                        cursor.saved = discoveryStore.cursor(sourceId, catId, cursor.saved.hash());
                        cursor.page = cursor.saved.page(); cursor.totalPage = cursor.saved.total();
                        cursor.nextRequestNanos = categoryTimeNanos() + CATEGORY_RETRY_DELAY.toNanos();
                        continue;
                    }
                    runtimeMonitor.scanPages(cursor.page, cursor.totalPage);
                    int savedNextPage = cursor.page >= cursor.totalPage ? 1 : cursor.page + 1;
                    cursor.saved = new CrawlDiscoveryStore.Cursor(sourceId, catId, cursor.saved.hash(), savedNextPage,
                        cursor.totalPage, cursor.saved.revision() + 1, java.time.Instant.now());
                    log.info("书目已入队：sourceId={}, catId={}, page={}, discovered={}", sourceId, catId, cursor.page, discoveredBooks.size());
                    if (cursor.page >= cursor.totalPage) {
                        // Full historical rescans have a long cooldown; other categories keep their cursors.
                        cursor.page = 1;
                        cursor.totalPage = 1;
                        cursor.nextRequestNanos = categoryTimeNanos() + cycleDelay.toNanos();
                    } else {
                        cursor.page++;
                        cursor.nextRequestNanos = categoryTimeNanos() + (refreshStore==null?0:cursor.recent?Duration.ofSeconds(3).toNanos():refreshStore.scanPageDelay(sourceId).toNanos());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    runtimeMonitor.failure(sourceId, e);
                    log.error(e.getMessage(), e);
                    // 保留失败页，退避后重试；不能让失效的列表阻塞其余分类。
                    cursor.nextRequestNanos = categoryTimeNanos() + CATEGORY_RETRY_DELAY.toNanos();
                }
            }
            long now = categoryTimeNanos();
            long delay = cursors.stream().mapToLong(cursor -> cursor.nextRequestNanos - now).min().orElse(0);
            if (delay > 0) {
                try {
                    waitForCategory(Duration.ofNanos(delay));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static final Duration CATEGORY_RETRY_DELAY = Duration.ofMinutes(1);

    protected long categoryTimeNanos() {
        return System.nanoTime();
    }

    protected void waitForCategory(Duration delay) throws InterruptedException {
        Thread.sleep(delay);
    }

    private static final class CategoryCursor {
        private final String rule;
        private boolean recent;
        private int page = 1;
        private int totalPage = 1;
        private long nextRequestNanos;
        private CrawlDiscoveryStore.Cursor saved;

        private CategoryCursor(String rule, long now) {
            this.rule = rule;
            this.nextRequestNanos = now;
        }
    }

    @Override
    public boolean parseBookAndSave(int catId, RuleBean ruleBean, Integer sourceId, String bookId, CrawlSingleTask task)
        throws InterruptedException {
        return parseBookAndSaveResult(catId, ruleBean, sourceId, bookId, task, null).success();
    }

    public record DiscoveryResult(boolean success, String outcome, Long bookId) { }

    @Override
    public boolean enqueueBookUpdate(Long bookId) {
        Book book = bookService.queryBookById(bookId);
        if (book == null || Byte.valueOf((byte)1).equals(book.getCrawlIsStop())) return false;
        CrawlSource source = queryCrawlSource(book.getCrawlSourceId());
        if (source == null || !Byte.valueOf((byte)1).equals(source.getSourceStatus())) return false;
        String policyHash=CrawlCollectionStore.hash(source.getCrawlRule());
        // The store retries complete, rolled-back transactions with bounded jittered backoff.
        return discoveryStore.enqueueExistingBook(book,policyHash);
    }

    public DiscoveryResult parseDiscoveredBook(CrawlDiscoveryStore.Job job, RuleBean ruleBean)
        throws InterruptedException {
        Book queuedBook = null;
        if (job.bookId() != null) {
            queuedBook = bookService.queryBookById(job.bookId());
            if (queuedBook == null || Byte.valueOf((byte)1).equals(queuedBook.getCrawlIsStop())
                || !Objects.equals(queuedBook.getCrawlSourceId(),job.sourceId())
                || !Objects.equals(queuedBook.getCrawlBookId(),job.sourceBookId()))
                return new DiscoveryResult(true,"EXISTING",job.bookId());
            if (discoveryStore.isVerifiedCompleted(queuedBook))
                return new DiscoveryResult(true,"COMPLETE",job.bookId());
        }
        if(queuedBook!=null) try(var request=CrawlRequestContext.bound(CrawlRequestContext.Kind.BACKGROUND,
            job.sourceBookId(),queuedBook.getBookName(),queuedBook.getAuthorName())) {
            return parseBookAndSaveResult(job.catId(),ruleBean,job.sourceId(),job.sourceBookId(),null,job,queuedBook);
        }
        return parseBookAndSaveResult(job.catId(), ruleBean, job.sourceId(), job.sourceBookId(), null, job, queuedBook);
    }

    public DiscoveryResult refreshExistingBook(CrawlDiscoveryStore.Job job,RuleBean rule,CrawlRefreshStore store)
        throws InterruptedException {
        Book book=bookService.queryBookById(job.bookId());
        if(book==null || Byte.valueOf((byte)1).equals(book.getCrawlIsStop())
            || !Objects.equals(book.getCrawlSourceId(),job.sourceId())
            || !Objects.equals(book.getCrawlBookId(),job.sourceBookId()))
            return new DiscoveryResult(true,"EXISTING",job.bookId());
        try(var request=CrawlRequestContext.bound(job.sourceBookId(),book.getBookName(),book.getAuthorName());
            var refresh=crawlParser.refreshScope(store.session(job))) {
            return parseBookAndSaveResult(job.catId(),rule,job.sourceId(),job.sourceBookId(),null,job,book);
        }
    }

    private DiscoveryResult parseBookAndSaveResult(int catId, RuleBean ruleBean, Integer sourceId, String bookId,
        CrawlSingleTask task, CrawlDiscoveryStore.Job discoveryJob) throws InterruptedException {
        return parseBookAndSaveResult(catId,ruleBean,sourceId,bookId,task,discoveryJob,null);
    }

    private DiscoveryResult parseBookAndSaveResult(int catId, RuleBean ruleBean, Integer sourceId, String bookId,
        CrawlSingleTask task, CrawlDiscoveryStore.Job discoveryJob, Book queuedBook) throws InterruptedException {

        String kind = CrawlRequestContext.refresh()?"REFRESH":task != null ? "MANUAL" : queuedBook != null ? "UPDATE" : "DOWNLOAD";
        try (CrawlRuntimeMonitor.Scope activity = runtimeMonitor.beginBook(sourceId, kind, bookId)) {
            try {
                DiscoveryResult result = parseBookAndSaveMonitored(catId, ruleBean, sourceId, bookId, task, discoveryJob, queuedBook);
                activity.result(result.outcome());
                return result;
            } catch (InterruptedException interrupted) {
                activity.result("INTERRUPTED");
                throw interrupted;
            } catch (CrawlBookBusyException busy) {
                activity.result("BUSY");
                throw busy;
            } catch (RuntimeException failure) {
                activity.result("FAILED");
                runtimeMonitor.failure(sourceId, failure);
                throw failure;
            }
        }
    }

    private DiscoveryResult parseBookAndSaveMonitored(int catId, RuleBean ruleBean, Integer sourceId, String bookId,
        CrawlSingleTask task, CrawlDiscoveryStore.Job discoveryJob, Book queuedBook) throws InterruptedException {

        boolean discovery = discoveryJob != null;
        crawlParser.resetRunProgress();

        final AtomicBoolean parseResult = new AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicReference<String> outcome = new java.util.concurrent.atomic.AtomicReference<>("RETRY");
        final java.util.concurrent.atomic.AtomicReference<Long> resultBookId = new java.util.concurrent.atomic.AtomicReference<>();

        crawlParser.parseBook(ruleBean, bookId, book -> {
            runtimeMonitor.title(book.getBookName());
            if (book.getBookName() == null || book.getAuthorName() == null) {
                throw new com.java2nb.novel.utils.CrawlMetadataException(book.getBookName() == null
                    ? com.java2nb.novel.utils.CrawlMetadataException.Reason.TITLE_MISSING
                    : com.java2nb.novel.utils.CrawlMetadataException.Reason.AUTHOR_MISSING,
                    ruleBean.getBookDetailUrl().replace("{bookId}", bookId), bookId, book.getBookName());
            }
            // A pending author is not an identity shared by all same-name books across sources.
            Book sourceBook = queuedBook != null ? queuedBook
                : bookService.queryBookBySource(book.getBookName(), sourceId, bookId);
            Book existBook = sourceBook != null ? sourceBook
                : bookService.queryBookByBookNameAndAuthorName(book.getBookName(), book.getAuthorName());
            if (sourceBook != null && Byte.valueOf((byte)1).equals(sourceBook.getCrawlIsStop())) {
                resultBookId.set(sourceBook.getId()); parseResult.set(true); outcome.set("EXISTING"); return;
            }
            if (com.java2nb.novel.core.crawl.CrawlBookMetadata.pendingAuthor(book.getAuthorName())
                && existBook != null && (!Objects.equals(existBook.getCrawlSourceId(), sourceId)
                    || !Objects.equals(existBook.getCrawlBookId(), bookId))) {
                throw new com.java2nb.novel.utils.CrawlMetadataException(
                    com.java2nb.novel.utils.CrawlMetadataException.Reason.BOOK_IDENTITY_CONFLICT,
                    ruleBean.getBookDetailUrl().replace("{bookId}", bookId), bookId, book.getBookName());
            }
            if (sourceBook != null
                && Objects.equals(sourceBook.getBookName(), book.getBookName())
                && com.java2nb.novel.core.crawl.CrawlBookMetadata.pendingAuthor(sourceBook.getAuthorName())
                && com.java2nb.novel.core.crawl.CrawlBookMetadata.usableAuthor(book.getAuthorName())) {
                String enriched = bookService.fillPendingAuthor(sourceBook.getId(), sourceId, bookId, book.getAuthorName());
                if (enriched != null) sourceBook.setAuthorName(enriched);
            }
            //如果该小说不存在，则可以解析入库，但是标记该小说正在入库，30分钟之后才允许再次入库
            if (existBook == null) {
                // 仅筛选批量采集的新书；已有书及手动单本任务继续使用原流程。
                if (task == null && !ruleBean.acceptsNewBookStatus(book.getBookStatus())) {
                    log.debug("按新书采集范围跳过：sourceId={}, bookId={}, status={}, selection={}",
                        sourceId, bookId, book.getBookStatus(), ruleBean.getNewBookStatus());
                    parseResult.set(true);
                    outcome.set("FILTERED");
                    return;
                }
                //没有该书，可以入库
                // UU 看书从全部书库扫描，批量新书按详情页归类；单本任务采用用户选择的分类。
                int targetCatId = catId;
                if (task == null && book.getCatId() != null && StringUtils.isNotBlank(ruleBean.getBookDetailUrl())
                    && UukanshuSourceAdapter.supports(ruleBean.getBookDetailUrl().replace("{bookId}", bookId))) {
                    targetCatId = book.getCatId();
                }
                BookCategory category = bookService.queryBookCategoryByCatId(targetCatId);
                if (category == null || StringUtils.isBlank(category.getName())
                    || category.getWorkDirection() == null
                    || (category.getWorkDirection() != (byte) 0 && category.getWorkDirection() != (byte) 1)) {
                    throw new IllegalArgumentException("Missing or invalid book category: " + targetCatId);
                }
                book.setCatId(category.getId());
                book.setCatName(category.getName());
                book.setWorkDirection(category.getWorkDirection());
                book.setCrawlBookId(bookId);
                book.setCrawlSourceId(sourceId);
                book.setCrawlLastTime(new Date());
                book.setId(idWorker.nextId());
                if (discovery && !crawlParser.beginBookCrawl(book.getId(), 25)) throw new CrawlBookBusyException(book.getId());
                AtomicBoolean progressMarked = new AtomicBoolean();
                //解析章节目录
                try {
                boolean parseIndexContentResult = crawlParser.parseBookIndexAndContent(bookId, book, ruleBean, sourceId,
                    new HashMap<>(0), chapter -> {
                        bookService.saveBookAndIndexAndContent(book, chapter.getBookIndexList(),
                            chapter.getBookContentList());
                        if (!chapter.getBookIndexList().isEmpty() && !progressMarked.get()) {
                            if (discovery) discoveryStore.checkpointBook(discoveryJob, book.getId());
                            crawlParser.markPendingNewBook(sourceId, book.getId());
                            progressMarked.set(true);
                            resultBookId.set(book.getId());
                        }
                    }, task);
                if (parseIndexContentResult) {
                    crawlParser.completePendingNewBook(sourceId, book.getId());
                }
                parseResult.set(parseIndexContentResult);
                outcome.set(parseIndexContentResult ? "COMPLETE" : progressMarked.get() && crawlParser.lastRunMadeProgress() ? "PARTIAL" : "RETRY");
                } finally { if (discovery) crawlParser.endBookCrawl(book.getId()); }

            } else {
                resultBookId.set(existBook.getId());
                // [审查03] 原来对已存在的书只更新来源字段就直接返回成功，
                // 导致单本采集任务对着缺章的书也会「成功」结束，缺失章节永远补不回来。
                // 单本任务以及首批入库后未完成的新书必须补齐目录；其他已有书保持只更新来源。
                boolean pendingNewBook = task == null && (crawlParser.isPendingNewBook(sourceId, existBook.getId())
                    || discovery && (sourceBook != null || Objects.equals(discoveryJob.bookId(), existBook.getId())));
                if (task != null || pendingNewBook) {
                    if (!(discovery ? crawlParser.beginBookCrawl(existBook.getId(), CrawlRequestContext.refresh()?3:updateMaxChaptersPerBook) : crawlParser.beginBookCrawl(existBook.getId()))) {
                        throw new CrawlBookBusyException(existBook.getId());
                    }
                    try {
                        book.setId(existBook.getId());
                        book.setBookName(existBook.getBookName());
                        book.setAuthorName(existBook.getAuthorName());
                        book.setCatId(existBook.getCatId());
                        book.setCatName(existBook.getCatName());
                        book.setWorkDirection(existBook.getWorkDirection());
                        if (queuedBook != null && existBook.getPicUrl() != null
                            && existBook.getPicUrl().contains(Constants.LOCAL_PIC_PREFIX)) book.setPicUrl(null);
                        Map<Integer, BookIndex> existBookIndexMapForTask = CrawlParser.deferIndexes(() -> bookService.queryExistBookIndexMap(existBook.getId()));
                        // wordCount 交给解析器按已存在章节重算，这里不预设，避免汇总算错
                        boolean parseIndexContentResult = crawlParser.parseBookIndexAndContent(bookId, book, ruleBean,
                            sourceId, existBookIndexMapForTask, chapter -> {bookService.updateBookAndIndexAndContent(book,
                                chapter.getBookIndexList(), chapter.getBookContentList(), CrawlParser.prepareIndexes(existBookIndexMapForTask),
                                chapter.getBookIndexMetaList(), chapter.getReindexList(), chapter.getCatalogSize());
                                if(!chapter.getBookIndexList().isEmpty()) crawlParser.markRefreshSaved();}, task);
                        parseResult.set(parseIndexContentResult);
                        outcome.set(parseIndexContentResult ? "COMPLETE" : CrawlRequestContext.refresh() || crawlParser.lastRunMadeProgress() ? "PARTIAL" : "RETRY");
                        if (parseIndexContentResult) {
                            crawlParser.completePendingNewBook(sourceId, existBook.getId());
                        }
                    } finally {
                        crawlParser.endBookCrawl(existBook.getId());
                    }
                } else {
                    //只更新书籍的爬虫相关字段
                    if (!discovery && (!Objects.equals(existBook.getCrawlSourceId(), sourceId)
                        || !Objects.equals(existBook.getCrawlBookId(), bookId))) {
                        bookService.updateCrawlProperties(existBook.getId(), sourceId, bookId);
                    }
                    parseResult.set(true);
                    outcome.set("EXISTING");
                }

            }
        });

        return new DiscoveryResult(parseResult.get(), outcome.get(), resultBookId.get());

    }

    @Override
    public void updateCrawlSourceStatus(Integer sourceId, Byte sourceStatus) {
        validateSourceStatus(sourceId, sourceStatus);
        CrawlSource source = new CrawlSource();
        source.setId(sourceId);
        source.setSourceStatus(sourceStatus);
        if (crawlSourceMapper.updateByPrimaryKeySelective(source) != 1) {
            throw new IllegalStateException("Cannot save crawl source status: " + sourceId);
        }
    }

    @Override
    public List<CrawlSource> queryCrawlSourceByStatus(Byte sourceStatus) {
        SelectStatementProvider render = select(CrawlSourceDynamicSqlSupport.id,
            CrawlSourceDynamicSqlSupport.sourceStatus, CrawlSourceDynamicSqlSupport.crawlRule)
            .from(crawlSource)
            .where(CrawlSourceDynamicSqlSupport.sourceStatus, isEqualTo(sourceStatus))
            .build()
            .render(RenderingStrategies.MYBATIS3);
        return crawlSourceMapper.selectMany(render);
    }

}
