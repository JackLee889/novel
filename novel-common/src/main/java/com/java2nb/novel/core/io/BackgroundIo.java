package com.java2nb.novel.core.io;

import java.lang.annotation.*;

/** Admission is outside the transaction interceptor. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface BackgroundIo { }
