package com.ease2k.blog.owner;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 记录以文章 ID 为第一个参数的站主写操作。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@interface OwnerOperation {
    String value();
}
