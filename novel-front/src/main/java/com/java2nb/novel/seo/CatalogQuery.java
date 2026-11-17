package com.java2nb.novel.seo;

import com.java2nb.novel.vo.BookSpVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One bounded parser for the public catalog URL, including legacy aliases. */
public final class CatalogQuery {
    private final LinkedHashMap<String,String> values=new LinkedHashMap<>();
    private final BookSpVO search=new BookSpVO();
    private int page=1;
    public static CatalogQuery parse(HttpServletRequest request) {
        var query=new CatalogQuery();
        for(var item:request.getParameterMap().entrySet()) if(item.getValue().length!=1) throw bad();
        String keyword=read(request,"k"); if(keyword.length()>100)throw bad();
        if(!keyword.isBlank()){query.values.put("k",keyword);query.search.setKeyword(keyword);}
        String category=read(request,"c"),legacy=read(request,"catId");
        if(!category.isEmpty()&&!legacy.isEmpty()&&!category.equals(legacy))throw bad();
        if(category.isEmpty())category=legacy;
        if(!category.isEmpty()){int id=integer(category,1,Integer.MAX_VALUE);query.values.put("c",String.valueOf(id));query.search.setCatId(id);}
        for(String key:List.of("workDirection","bookStatus")) {
            String value=read(request,key);if(value.isEmpty())continue;int number=integer(value,0,1);query.values.put(key,String.valueOf(number));
            if(key.equals("workDirection"))query.search.setWorkDirection((byte)number);else query.search.setBookStatus((byte)number);
        }
        String words=read(request,"words");
        if(!words.isEmpty()){
            if(!words.matches("\\d{0,9}:\\d{0,9}")||words.equals(":"))throw bad();
            String[] range=words.split(":",-1);Integer min=range[0].isEmpty()?null:integer(range[0],0,Integer.MAX_VALUE);
            Integer max=range[1].isEmpty()?null:integer(range[1],0,Integer.MAX_VALUE);if(min!=null&&max!=null&&min>=max)throw bad();
            query.search.setWordCountMin(min);query.search.setWordCountMax(max);query.values.put("words",(min==null?"":min)+":"+(max==null?"":max));
        }
        String period=read(request,"updatePeriod");if(!period.isEmpty()){int days=integer(period,1,365);query.search.setUpdatePeriod((long)days);query.values.put("updatePeriod",String.valueOf(days));}
        String sort=read(request,"sort");if(!sort.isEmpty()&&!List.of("last_index_update_time","word_count","visit_count").contains(sort))throw bad();
        query.search.setSort(sort.isEmpty()?"last_index_update_time":sort);if(!sort.isEmpty()&&!sort.equals("last_index_update_time"))query.values.put("sort",sort);
        String page=read(request,"page");if(!page.isEmpty())query.page=integer(page,1,100000);
        return query;
    }
    public boolean indexableShape() {
        if(values.isEmpty())return true;
        if(values.size()!=1)return false;
        return values.containsKey("c")||values.containsKey("workDirection")||"1".equals(values.get("bookStatus"));
    }
    public String url(int page) { return urlWith(null,null,page); }
    public String filterUrl(String key,String value) { return urlWith(key,value,1); }
    private String urlWith(String key,String value,int page) {
        var copy=new LinkedHashMap<>(values);if(key!=null){if(value==null||value.isEmpty())copy.remove(key);else copy.put(key,value);if(key.equals("workDirection"))copy.remove("c");}
        if(page>1)copy.put("page",String.valueOf(page));
        return "/book/bookclass.html"+(copy.isEmpty()?"":"?"+copy.entrySet().stream().map(e->encode(e.getKey())+"="+encode(e.getValue())).reduce((a,b)->a+"&"+b).orElse(""));
    }
    public BookSpVO search(){return search;} public int page(){return page;} public Map<String,String> values(){return Collections.unmodifiableMap(values);}
    public static int integer(String value,int min,int max){try{if(!value.matches("\\d+"))throw bad();int parsed=Integer.parseInt(value);if(parsed<min||parsed>max)throw bad();return parsed;}catch(NumberFormatException error){throw bad();}}
    private static String read(HttpServletRequest request,String key){return Objects.toString(request.getParameter(key),"").strip();}
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20");}
    private static ResponseStatusException bad(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"筛选参数无效");}
}
