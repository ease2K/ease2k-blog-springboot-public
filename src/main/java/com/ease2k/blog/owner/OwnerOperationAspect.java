package com.ease2k.blog.owner;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Aspect
@Component
class OwnerOperationAspect {

    private static final Logger LOG = LoggerFactory.getLogger(OwnerOperationAspect.class);

    @Around(value = "@annotation(operation) && args(articleId, ..)", argNames = "operation,articleId")
    public Object log(ProceedingJoinPoint joinPoint, OwnerOperation operation, String articleId) throws Throwable {
        String id = articleId != null && OwnerInput.ID.matcher(articleId).matches() ? articleId : "[invalid]";
        long start = System.nanoTime();
        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable error) {
            int status = error instanceof OwnerProblem problem ? problem.status().value() : 500;
            LOG.info("Owner operation={} articleId={} result=failure status={} durationMs={}",
                    operation.value(), id, status, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            throw error;
        }
        LOG.info("Owner operation={} articleId={} result=success durationMs={}",
                operation.value(), id, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        return result;
    }
}
