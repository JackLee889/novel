package com.java2nb.novel.seo;

import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** HTTP reads immutable snapshots only. GET/HEAD never enumerate URLs or start jobs. */
@Controller
@RequiredArgsConstructor
public class SeoSitemapController {
    private final SeoService seo;
    private final SeoRepository repository;
    private final SeoProperties properties;
    @GetMapping("/robots.txt")
    public void robots(HttpServletRequest request,HttpServletResponse response)throws IOException {
        String value="User-agent: *\n"+(seo.siteIndexEnabled()?"Sitemap: "+seo.publicSiteUrl()+"/sitemap.xml\n":"Disallow: /\n");
        byte[] body=value.getBytes(StandardCharsets.UTF_8);String tag="\""+hash(body)+"\"";headers(response,tag,null,"text/plain;charset=UTF-8");
        if(conditional(request,response,tag,null))return;response.setContentLengthLong(body.length);if(!"HEAD".equals(request.getMethod()))response.getOutputStream().write(body);
    }
    @GetMapping("/sitemap.xml")
    public void index(HttpServletRequest request,HttpServletResponse response)throws IOException {
        var state=activeCore();serve((String)state.get("indexFile"),request,response,"application/xml;charset=UTF-8",instant(state.get("modifiedAt")));
    }
    @GetMapping({"/sitemap-core-{part}.xml","/sitemap-core.xml"})
    public void core(@PathVariable(value="part",required=false)Integer part,HttpServletRequest request,HttpServletResponse response)throws IOException {
        var state=activeCore();String files=(String)state.get("coreFiles");String[] parts=files==null?new String[0]:files.split("\n");int p=part==null?1:part;if(p<1||p>parts.length)throw missing();
        response.setHeader("Content-Encoding","gzip");serve(parts[p-1],request,response,"application/xml;charset=UTF-8",null);
    }
    @GetMapping({"/sitemap-{file:.+}","/sitemaps/{file:.+}"})
    public void part(@PathVariable String file,HttpServletRequest request,HttpServletResponse response)throws IOException {
        requireEnabled();var settings=seo.settings();
        if(file.matches("core-[0-9a-f]{64}-e[0-9]{1,19}-v[0-9]{1,19}-p[0-9]{1,8}\\.xml\\.gz")){
            var state=activeCore();String files=(String)state.get("coreFiles");if(files==null||!Arrays.asList(files.split("\n")).contains(file))throw missing();
        }else if(!settings.chaptersIndexEnabled()||!settings.chapterSitemapEnabled()||!file.matches("chapters-[0-9]{1,19}-r[0-9]{1,19}-e[0-9]{1,19}-p[0-9]{1,8}-[0-9a-f-]{36}\\.xml\\.gz")||!repository.filePublished(file,settings.epoch(),seo.publicSiteUrl()))throw missing();
        serve(file,request,response,"application/gzip",null);
    }
    private Map<String,Object> activeCore(){
        requireEnabled();var settings=seo.settings();Map<String,Object> state=repository.coreState();
        if(state==null||!(state.get("builtEpoch") instanceof Number epoch)||epoch.longValue()!=settings.epoch()||!Objects.equals(state.get("builtOrigin"),seo.publicSiteUrl())
            ||!Objects.equals(state.get("visibilityRevision"),state.get("builtVisibilityRevision"))||state.get("indexFile")==null)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"网站地图正在生成，请稍后重试");
        return state;
    }
    private void serve(String file,HttpServletRequest request,HttpServletResponse response,String type,Instant modified)throws IOException {
        if(file==null||!file.matches("[a-z0-9-]+\\.xml(?:\\.gz)?"))throw missing();Path root=Path.of(properties.getSitemapDirectory()).toAbsolutePath().normalize();Path target=root.resolve(file).normalize();
        if(!target.startsWith(root)||!Files.isRegularFile(target)||Files.isSymbolicLink(target))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"网站地图文件暂不可用");
        if(modified==null)modified=Files.getLastModifiedTime(target).toInstant();String tag="\""+file+"\"";headers(response,tag,modified,type);
        if(conditional(request,response,tag,modified))return;response.setContentLengthLong(Files.size(target));if(!"HEAD".equals(request.getMethod()))Files.copy(target,response.getOutputStream());
    }
    private static void headers(HttpServletResponse response,String tag,Instant modified,String type){response.setHeader("ETag",tag);response.setHeader("Cache-Control","public, max-age=0, must-revalidate");response.setHeader("X-Content-Type-Options","nosniff");response.setContentType(type);if(modified!=null)response.setDateHeader("Last-Modified",modified.toEpochMilli());}
    private static boolean conditional(HttpServletRequest request,HttpServletResponse response,String etag,Instant modified){
        String match=request.getHeader("If-None-Match");if(match!=null){for(String tag:match.split(",")){String value=tag.strip();if(value.equals("*")||value.equals(etag)||value.equals("W/"+etag)){response.setStatus(304);return true;}}return false;}
        if(modified!=null){try{long time=request.getDateHeader("If-Modified-Since");if(time>=0&&modified.toEpochMilli()/1000<=time/1000){response.setStatus(304);return true;}}catch(IllegalArgumentException ignored){}}
        return false;
    }
    private static Instant instant(Object value){return value instanceof Date d?d.toInstant():value instanceof Instant i?i:null;}
    private void requireEnabled(){if(!seo.siteIndexEnabled())throw missing();}
    private static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND);}
    private static String hash(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
