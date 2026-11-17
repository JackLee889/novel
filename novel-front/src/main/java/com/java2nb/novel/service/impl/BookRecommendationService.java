package com.java2nb.novel.service.impl;

import com.java2nb.novel.core.cache.CacheService;
import com.java2nb.novel.entity.Book;
import com.java2nb.novel.mapper.FrontBookMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/** Cache only candidate IDs; recheck publication and metadata in the database for each response. */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookRecommendationService {
    static final String KEY_PREFIX = "novel:recommendation:ids:v1:";
    private static final long LOCAL_TTL_NANOS = Duration.ofSeconds(30).toNanos();
    private static final int REDIS_TTL_SECONDS = 300;
    private static final int MAX_LOCAL_CATEGORIES = 32;
    private static final int RESULT_SIZE = 4;
    private static final int CANDIDATE_SIZE = 12;
    private final FrontBookMapper mapper;
    private final CacheService cache;
    private final Map<Integer, Pool> pools = new LinkedHashMap<>();
    private final Object[] loadLocks = new Object[32];
    private LongSupplier nanoTime = System::nanoTime;
    { Arrays.setAll(loadLocks, ignored -> new Object()); }

    public List<Book> list(Integer categoryId) {
        if (categoryId == null || categoryId <= 0) return List.of();
        List<Long> ids = pool(categoryId, false);
        List<Book> result = select(categoryId, ids);
        if (result.size() < RESULT_SIZE && ids.size() >= RESULT_SIZE) {
            // Stale IDs may have been unpublished. Refresh once; never serve cached visibility.
            result = select(categoryId, pool(categoryId, true));
        }
        return result;
    }

    private List<Book> select(int categoryId, List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        Set<Long> sample = new LinkedHashSet<>();
        if (ids.size() <= CANDIDATE_SIZE) sample.addAll(ids);
        else while (sample.size() < CANDIDATE_SIZE) sample.add(ids.get(ThreadLocalRandom.current().nextInt(ids.size())));
        List<Book> books = new ArrayList<>(mapper.listRecommendationsByIds(categoryId, new ArrayList<>(sample)));
        Collections.shuffle(books);
        return List.copyOf(books.subList(0, Math.min(RESULT_SIZE, books.size())));
    }

    private List<Long> pool(int categoryId, boolean refresh) {
        Pool cached = local(categoryId);
        if (!refresh && cached != null) return cached.ids();
        synchronized (loadLocks[Math.floorMod(categoryId, loadLocks.length)]) {
            cached = local(categoryId);
            if (!refresh && cached != null) return cached.ids();
            List<Long> ids = null;
            if (!refresh) {
                try { ids = cache.getList(KEY_PREFIX + categoryId, Long.class); }
                catch (RuntimeException e) { log.warn("Recommendation ID cache unavailable; using database: {}", e.getClass().getSimpleName()); }
            }
            if (ids == null || ids.isEmpty()) {
                ids = mapper.listRecommendationIds(categoryId);
                if (!ids.isEmpty()) {
                    try { cache.setObject(KEY_PREFIX + categoryId, ids, REDIS_TTL_SECONDS); }
                    catch (RuntimeException e) { log.warn("Recommendation IDs loaded; cache write unavailable: {}", e.getClass().getSimpleName()); }
                }
            }
            // Ignore malformed duplicate/null cache entries rather than risk an endless sample loop.
            ids = ids.stream().filter(Objects::nonNull).distinct().toList();
            synchronized (pools) {
                if (!pools.containsKey(categoryId) && pools.size() >= MAX_LOCAL_CATEGORIES)
                    pools.remove(pools.keySet().iterator().next());
                pools.put(categoryId, new Pool(ids, nanoTime.getAsLong()));
            }
            return ids;
        }
    }

    private Pool local(int categoryId) {
        synchronized (pools) {
            Pool value = pools.get(categoryId);
            return value != null && nanoTime.getAsLong() - value.loadedAt() < LOCAL_TTL_NANOS ? value : null;
        }
    }

    private record Pool(List<Long> ids, long loadedAt) {}
}
