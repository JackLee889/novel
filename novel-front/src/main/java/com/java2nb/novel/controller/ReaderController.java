package com.java2nb.novel.controller;

import com.java2nb.novel.service.*;
import com.java2nb.novel.vo.ReaderChapterVO;
import io.github.xxyopen.model.resp.RestResult;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import com.java2nb.novel.seo.SeoMetadataService;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequiredArgsConstructor
@RequestMapping("/book/reader")
public class ReaderController extends BaseController {
    private final ReaderChapterService chapters;
    private final BookService books;
    private final SeoMetadataService seoMetadata;

    @GetMapping(value="/chapter", produces="application/json")
    public RestResult<Map<String,Object>> chapter(@RequestParam Long bookId, @RequestParam Long bookIndexId,
        HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        var user = getUserDetails(request);
        var access = chapters.read(bookId, bookIndexId, user == null ? null : user.getId());
        var view=access.view();var meta=seoMetadata.chapter(access.book(),access.index(),view);
        response.setHeader("X-Robots-Tag","noindex,follow");
        // Preserve the site's 1001 login code, with safe purchase metadata only.
        var data=new LinkedHashMap<String,Object>(new ObjectMapper().convertValue(view,Map.class));data.put("seo",meta);
        RestResult<Map<String,Object>> result = RestResult.ok(data);
        if (!"allowed".equals(view.access())) {
            boolean login = "need_login".equals(view.access());
            response.setStatus(login ? 401 : 403);
            result.setCode(login ? 1001 : 5002);
            result.setMsg(login ? "未登录或登录失效" : "此章需要购买");
        }
        return result;
    }

    public record CatalogEntry(String id, String name, Integer number, boolean vip) {}
    public record CatalogPage(List<CatalogEntry> entries, int page, boolean hasMore) {}

    @GetMapping(value="/catalog", produces="application/json")
    public RestResult<CatalogPage> catalog(@RequestParam Long bookId,
        @RequestParam(defaultValue = "1") int page, HttpServletResponse response) {
        noStore(response);
        response.setHeader("X-Robots-Tag","noindex,follow");
        if (bookId <= 0 || page < 1 || page > 100000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        try {
            if (books.queryBookDetail(bookId) == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        } catch (IndexOutOfBoundsException | NoSuchElementException error) {
            // Existing storage queries use get(0) for an absent book.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        var list = books.queryIndexList(bookId, "index_num", page, 100);
        var entries = list.stream().map(i -> new CatalogEntry(i.getId().toString(), i.getIndexName(),
            i.getIndexNum(), Byte.valueOf((byte)1).equals(i.getIsVip()))).toList();
        return RestResult.ok(new CatalogPage(entries, page, (long)page * 100 < books.queryIndexCount(bookId)));
    }
    public static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        response.setHeader("Vary", "Cookie, Authorization");
    }
    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<RestResult<Void>> failure(ResponseStatusException error,
        HttpServletResponse response) {
        noStore(response);
        response.setHeader("X-Robots-Tag","noindex,follow");
        if(error.getStatusCode().value()==503)response.setHeader("Retry-After","60");
        var result = RestResult.ok();
        result.setCode(error.getStatusCode().value() == 404 ? 5003 : 5004);
        result.setMsg(error.getReason() == null ? "章节请求失败" : error.getReason());
        return org.springframework.http.ResponseEntity.status(error.getStatusCode()).body(result);
    }
}
