package com.java2nb.novel.core.io;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Order(0)
@RequiredArgsConstructor
public class BackgroundIoAspect {
    private final BackgroundIoGate gate;
    @Around("@annotation(com.java2nb.novel.core.io.BackgroundIo)")
    public Object around(ProceedingJoinPoint call) throws Throwable {
        try (var permit = gate.acquire(false)) { permit.check(); return call.proceed(); }
    }
}
