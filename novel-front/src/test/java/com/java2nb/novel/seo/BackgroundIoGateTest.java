package com.java2nb.novel.seo;
import com.java2nb.novel.core.io.*;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.interceptor.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class BackgroundIoGateTest {
    @Test void nestedPermitDoesNotReleaseAnotherOwnerAndDisallowsUpgrade(){
        var redis=mock(StringRedisTemplate.class);AtomicInteger calls=new AtomicInteger();
        when(redis.execute(any(),anyList(),any(Object[].class))).thenAnswer(call->{calls.incrementAndGet();return 1L;});
        try(var gate=newGate(redis)){
            try(var outer=gate.acquire(false)){
                try(var inner=gate.acquire(false)){inner.check();assertEquals(1,calls.get());}
                assertEquals(1,calls.get());outer.check();assertThrows(IllegalStateException.class,()->gate.acquire(true));
            }
            assertEquals(2,calls.get());
        }
    }
    @Test void unavailableOrPausedRedisNeverAdmitsAndTransactionCannotWait(){
        var redis=mock(StringRedisTemplate.class);
        try(var gate=newGate(redis)){
            when(redis.execute(any(),anyList(),any(Object[].class))).thenThrow(new IllegalStateException("offline"));
            assertThrows(BackgroundIoGate.BusyException.class,()->gate.acquire(false));
            when(redis.hasKey(anyString())).thenThrow(new IllegalStateException("offline"));assertTrue(gate.paused());
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try{assertThrows(IllegalStateException.class,()->gate.acquire(false));}finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
        }
    }
    @Test void admissionAndReleaseSurroundTheEntireTransaction(){
        var redis=mock(StringRedisTemplate.class);AtomicInteger calls=new AtomicInteger();
        when(redis.execute(any(),anyList(),any(Object[].class))).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());calls.incrementAndGet();return 1L;});
        try(var gate=newGate(redis)){
            var manager=new DataSourceTransactionManager(new DriverManagerDataSource("jdbc:h2:mem:gate;DB_CLOSE_DELAY=-1","sa",""));
            var attributes=new AnnotationTransactionAttributeSource();
            var advice=new TransactionInterceptor(manager,attributes);
            var advisor=new BeanFactoryTransactionAttributeSourceAdvisor();advisor.setTransactionAttributeSource(attributes);advisor.setAdvice(advice);
            var proxy=new AspectJProxyFactory(new TransactionalWork());proxy.addAspect(new BackgroundIoAspect(gate));proxy.addAdvisor(advisor);
            ((TransactionalWork)proxy.getProxy()).write();assertEquals(2,calls.get());
        }
    }
    private BackgroundIoGate newGate(StringRedisTemplate redis){var gate=new BackgroundIoGate(redis);ReflectionTestUtils.setField(gate,"enabled",true);return gate;}
    public static class TransactionalWork {
        @BackgroundIo @Transactional public void write(){assertTrue(TransactionSynchronizationManager.isActualTransactionActive());}
    }
}
