package com.java2nb.novel.core.listener;

import tools.jackson.databind.ObjectMapper;
import com.java2nb.novel.core.crawl.CrawlParser;
import com.java2nb.novel.core.crawl.CrawlBookBusyException;
import com.java2nb.novel.core.crawl.RuleBean;
import com.java2nb.novel.core.crawl.CrawlRefreshStore;
import com.java2nb.novel.entity.Book;
import com.java2nb.novel.entity.BookIndex;
import com.java2nb.novel.entity.CrawlSingleTask;
import com.java2nb.novel.entity.CrawlSource;
import com.java2nb.novel.service.BookService;
import com.java2nb.novel.service.CrawlService;
import com.java2nb.novel.utils.Constants;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.time.DateUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @author Administrator
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class StarterListener {

    private final List<Thread> workers = new CopyOnWriteArrayList<>();

    private volatile boolean stopping;

    @Value("${crawl.jobs.enabled:true}")
    private boolean jobsEnabled = true;

    /**
     * [审查09] 轮转窗口：距上次抓取超过该时长的书重新进入自动更新队列
     */
    private static final int UPDATE_ROTATE_MINUTES = 30;

    /**
     * 自动更新的正常轮询间隔（分钟）
     */
    private static final long UPDATE_INTERVAL_MINUTES = 10L;

    @Value("${crawl.update.batch-pause-ms:1000}")
    private long updateBatchPauseMillis = 1000L;

    @Value("${crawl.update.max-chapters-per-book:25}")
    private int updateMaxChaptersPerBook = 25;

    /**
     * [审查19] 连续失败时的最大退避倍数
     */
    private static final int MAX_FAILURE_BACKOFF = 6;

    private final BookService bookService;

    private final CrawlService crawlService;

    private final CrawlParser crawlParser;
    private CrawlRefreshStore refreshStore;
    @org.springframework.beans.factory.annotation.Autowired
    public void setRefreshStore(CrawlRefreshStore store) {this.refreshStore=store;}

    @Value("${crawl.update.thread}")
    private int updateThreadCount;

    // Servlet initialization is too early: transactions can race transaction-manager creation.
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void onStartup() {
        if (!jobsEnabled || stopping || !workers.isEmpty()) {
            return;
        }
        if (updateBatchPauseMillis < 1 || updateBatchPauseMillis > 600000) {
            throw new IllegalArgumentException("crawl.update.batch-pause-ms must be between 1 and 600000");
        }
        if (updateMaxChaptersPerBook < 1 || updateMaxChaptersPerBook > 1000) {
            throw new IllegalArgumentException("crawl.update.max-chapters-per-book must be between 1 and 1000");
        }
        // 只恢复数据库中已开启的来源；关闭的来源保持关闭。
        for (CrawlSource source : crawlService.queryCrawlSourceByStatus((byte) 1)) {
            try {
                crawlService.openOrCloseCrawl(source.getId(), (byte) 1);
            } catch (Exception e) {
                log.error("恢复爬虫来源失败：sourceId={}", source.getId(), e);
                // 无法启动时同步为关闭，避免页面和追更队列继续认为来源可用。
                crawlService.updateCrawlSourceStatus(source.getId(), (byte) 0);
            }
        }
        for (int i = 0; i < updateThreadCount; i++) {
            startWorker(() -> {
                log.info("程序启动,开始执行自动更新线程。。。");
                int consecutiveFailures = 0;
                while (!stopping) {
                    boolean foundWork = false;
                    boolean batchHadFailures = false;
                    boolean sourcesDisabled = false;
                    try {
                        // [审查09] 用「最近未抓取」轮转取代「本地最新章节 30 天内」，
                        // 断更很久的书在源站恢复后也能被重新检查。
                        // Persist each book's job and rotation timestamp together. Network work is
                        // handled by the bounded leased workers, never by this scheduling loop.
                        Date currentDate = new Date();
                        Date startDate = DateUtils.addMinutes(currentDate, -UPDATE_ROTATE_MINUTES);
                        List<Book> bookList;
                        synchronized (this) {
                            if(refreshStore!=null) {
                                sourcesDisabled=refreshStore.enabledSources().isEmpty();
                                foundWork=refreshStore.seedSerials(500)>0 || refreshStore.seedSweepPending();
                                // Bootstrap all serial deadlines before spending a batch on
                                // completed-book backfill scheduling.
                                bookList=foundWork?List.of():refreshStore.completedCandidates(startDate,100);
                                foundWork=foundWork || refreshStore.completedSweepPending();
                            } else bookList = bookService.queryNeedUpdateBook(startDate, 100);
                        }
                        foundWork = foundWork || !bookList.isEmpty();
                        for (Book needUpdateBook : bookList) {
                            if (stopping) {
                                return;
                            }
                            try {
                                boolean queued = crawlService.enqueueBookUpdate(needUpdateBook.getId());
                                if (queued) log.info("已有书更新已入续采队列：bookId={}, sourceId={}",
                                    needUpdateBook.getId(),needUpdateBook.getCrawlSourceId());
                            }
                            catch (Exception e) {
                                batchHadFailures = true;
                                log.error(e.getMessage(), e);
                            }

                        }
                        consecutiveFailures = batchHadFailures ? consecutiveFailures + 1 : 0;
                    }
                    catch (Exception e) {
                        consecutiveFailures++;
                        log.error("自动更新轮次失败（连续第 {} 次）", consecutiveFailures, e);
                    }
                    finally {
                        // [审查19] 原来两个 sleep 都放在 try 尾部，前面抛异常时会跳过等待，
                        // 持续数据库故障下会形成没有退避的高频查询。改为必定执行的 finally，
                        // 且连续失败时逐步拉长间隔。
                        long factor = Math.min(Math.max(consecutiveFailures, 1), MAX_FAILURE_BACKOFF);
                        try {
                            // Drain an existing backlog; keep the long idle/error backoff.
                            long delay = foundWork && consecutiveFailures == 0 ? updateBatchPauseMillis
                                : TimeUnit.MINUTES.toMillis(UPDATE_INTERVAL_MINUTES * factor);
                            // Poll only the cached source switch while off; resume queued work promptly.
                            if(sourcesDisabled && consecutiveFailures==0) delay=Math.min(delay,5000L);
                            TimeUnit.MILLISECONDS.sleep(delay);
                        }
                        catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            log.warn("自动更新线程被中断，退出");
                            return;
                        }
                    }
                }
            }, "crawl-update-" + i);

        }

        startWorker(() -> {
            log.info("程序启动,开始执行单本采集任务线程。。。");
            while (!stopping) {
                CrawlSingleTask task = null;
                byte crawlStatus = 0;
                boolean interrupted = false;
                try {
                    // [审查06] 领取时即把任务置为「采集中」，避免多个实例重复领取同一任务
                    task = crawlService.getCrawlSingleTask();

                    if (task != null) {
                        //查询爬虫规则
                        CrawlSource source = crawlService.queryCrawlSource(task.getSourceId());
                        RuleBean ruleBean = new ObjectMapper().readValue(source.getCrawlRule(), RuleBean.class);
                        if (crawlService.parseBookAndSave(task.getCatId(), ruleBean, task.getSourceId(),
                            task.getSourceBookId(), task)) {
                            //采集成功
                            crawlStatus = 1;
                        } else if (crawlParser.lastRunIsResumable()) {
                            // Bounded validation made progress; keep the task queued without spending a failure retry.
                            crawlStatus = 2;
                        }
                    }
                }
                catch (CrawlBookBusyException e) {
                    crawlStatus = 2;
                    log.info("本书已有采集线程，单本任务重新排队：taskId={}", task.getId());
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    interrupted = true;
                    crawlStatus = 2;
                    log.warn("单本采集线程被中断");
                }
                catch (Exception e) {
                    log.error(e.getMessage(), e);
                }
                finally {
                    // [审查07] 任务结果保存原来在 try/catch 之外，保存时数据库异常会直接退出 while
                    // 并杀死这个唯一的任务线程，后续排队任务再无人处理。现在纳入受保护流程并单独兜底，
                    // 保存失败也不会终止队列；同时把保存提到休眠之前，缩短状态未落库的窗口。
                    if (task != null) {
                        boolean restoreInterrupt = interrupted && Thread.interrupted();
                        try {
                            crawlService.updateCrawlSingleTask(task, crawlStatus);
                        }
                        catch (Exception e) {
                            log.error("保存单本采集任务结果失败，已忽略以免终止任务线程：taskId={}", task.getId(), e);
                        }
                        finally {
                            if (restoreInterrupt) Thread.currentThread().interrupt();
                        }
                    }
                }

                if (interrupted) {
                    return;
                }

                try {
                    //休眠1分钟
                    TimeUnit.MINUTES.sleep(1);
                }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }

            }
        }, "crawl-single");
    }

    private void startWorker(Runnable task, String name) {
        Thread worker = new Thread(task, name);
        worker.setDaemon(true);
        workers.add(worker);
        worker.start();
    }

    @PreDestroy
    public void shutdown() {
        stopping = true;
        workers.forEach(Thread::interrupt);
        // Keep the datasource alive long enough for an interrupted single task to requeue.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        for (Thread worker : workers) {
            if (worker == Thread.currentThread()) continue;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            try {
                worker.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
