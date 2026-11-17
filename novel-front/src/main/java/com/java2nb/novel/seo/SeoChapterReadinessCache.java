package com.java2nb.novel.seo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.util.*;

/** Lazy database-body validation cache. Transactional content triggers invalidate every writer. */
@Service
public class SeoChapterReadinessCache {
    private static final int VALIDATOR_VERSION=1;
    private final JdbcTemplate jdbc;
    private final SeoRepository repository;
    @Value("${seo.readiness-cache-enabled:false}") private boolean enabled;
    public SeoChapterReadinessCache(DataSource source,SeoRepository repository){
        this.jdbc=new JdbcTemplate(source);this.jdbc.setQueryTimeout(15);this.repository=repository;
    }
    public record Result(Set<Long> ready,int cacheHits,int checkedRows) {}
    private record State(long id,long revision,Boolean ready,int validator) {}

    public Result check(List<Long> requested){
        if(requested.size()>100)throw new IllegalArgumentException("Readiness cache batch exceeds 100");
        if(requested.isEmpty())return new Result(Set.of(),0,0);
        if(requested.stream().anyMatch(id->id==null||id<=0))throw new IllegalArgumentException("Invalid chapter id");
        List<Long> ids=requested.stream().distinct().sorted().toList();
        if(!enabled)return new Result(repository.readyDatabaseIds(ids),0,ids.size());
        Map<Long,State> states=read(ids);
        List<Long> missing=ids.stream().filter(id->!states.containsKey(id)).toList();
        if(!missing.isEmpty()){
            // Create the invalidation target before reading any body. No historical backfill is needed.
            jdbc.update("INSERT INTO seo_chapter_readiness(index_id) VALUES "+String.join(",",Collections.nCopies(missing.size(),"(?)"))
                +" ON DUPLICATE KEY UPDATE index_id=VALUES(index_id)",missing.toArray());
            states.putAll(read(missing));
        }
        Set<Long> ready=new HashSet<>();List<State> pending=new ArrayList<>();int hits=0;
        for(long id:ids){
            State state=Objects.requireNonNull(states.get(id),"Missing readiness state");
            if(state.ready()!=null&&state.validator()==VALIDATOR_VERSION){hits++;if(state.ready())ready.add(id);}
            else pending.add(state);
        }
        if(!pending.isEmpty()){
            Set<Long> valid=repository.readyDatabaseIds(pending.stream().map(State::id).toList());
            List<Object> args=new ArrayList<>();StringBuilder cases=new StringBuilder();List<String> predicates=new ArrayList<>();
            for(State state:pending){cases.append(" WHEN ? THEN ?");args.add(state.id());args.add(valid.contains(state.id())?1:0);}
            for(State state:pending){predicates.add("(index_id=? AND revision=?)");args.add(state.id());args.add(state.revision());}
            // One bounded update/commit per page, not one fsync per chapter. A concurrent body mutation wins.
            int changed=jdbc.update("UPDATE seo_chapter_readiness SET ready=CASE index_id"+cases+" END,validator_version="+VALIDATOR_VERSION
                +" WHERE "+String.join(" OR ",predicates),args.toArray());
            if(changed!=pending.size())throw new ConcurrencyFailureException("Chapter content changed during readiness validation");
            ready.addAll(valid);
        }
        return new Result(Set.copyOf(ready),hits,pending.size());
    }
    private Map<Long,State> read(List<Long> ids){
        Map<Long,State> result=new HashMap<>();
        jdbc.query("SELECT index_id,revision,ready,validator_version FROM seo_chapter_readiness WHERE index_id IN ("
            +String.join(",",Collections.nCopies(ids.size(),"?"))+")",r->{
                long id=r.getLong(1);result.put(id,new State(id,r.getLong(2),r.getObject(3)==null?null:r.getBoolean(3),r.getInt(4)));
            },ids.toArray());
        return result;
    }
}
