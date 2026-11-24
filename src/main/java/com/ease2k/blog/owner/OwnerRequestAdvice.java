package com.ease2k.blog.owner;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.Map;

/** 保留工作台 JSON 的大小限制和错误格式，正文由 Spring MVC 反序列化。 */
@RestControllerAdvice(assignableTypes = OwnerController.class)
public class OwnerRequestAdvice extends RequestBodyAdviceAdapter {

    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;

    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter, Type targetType,
                                           Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        requireJson(input);
        // 流式上传也要按实际字节计数，不能只依赖 Content-Length。
        byte[] bytes = input.getBody().readNBytes(MAX_JSON_BYTES + 1);
        if (bytes.length > MAX_JSON_BYTES) throw new OwnerProblem(HttpStatus.PAYLOAD_TOO_LARGE, "文章内容过大。");
        InputStream body = new ByteArrayInputStream(bytes);
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return body;
            }

            @Override
            public HttpHeaders getHeaders() {
                return input.getHeaders();
            }
        };
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage input, MethodParameter parameter, Type targetType,
                                Class<? extends HttpMessageConverter<?>> converterType) {
        if (body == null || body instanceof JsonNode node && !node.isObject()) {
            throw OwnerProblem.bad("需要 JSON 对象。");
        }
        return body;
    }

    @Override
    public Object handleEmptyBody(Object body, HttpInputMessage input, MethodParameter parameter, Type targetType,
                                  Class<? extends HttpMessageConverter<?>> converterType) {
        requireJson(input);
        throw OwnerProblem.bad("需要 JSON 对象。");
    }

    @ExceptionHandler(OwnerProblem.class)
    public ResponseEntity<Map<String, String>> error(OwnerProblem problem) {
        ResponseEntity.BodyBuilder status = ResponseEntity.status(problem.status());
        if (problem.status() == HttpStatus.TOO_MANY_REQUESTS) status.header(HttpHeaders.RETRY_AFTER, "900");
        return status.body(Map.of("error", problem.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> invalidJson(HttpMessageNotReadableException exception) {
        if (exception.getMostSpecificCause() instanceof OwnerProblem problem) return error(problem);
        return error(OwnerProblem.bad("JSON 格式不正确。"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, String>> invalidMediaType() {
        return error(jsonRequired());
    }

    private static void requireJson(HttpInputMessage input) {
        MediaType type = input.getHeaders().getContentType();
        if (type == null || !MediaType.APPLICATION_JSON.isCompatibleWith(type)) throw jsonRequired();
    }

    private static OwnerProblem jsonRequired() {
        return new OwnerProblem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "请求需要使用 JSON 格式。");
    }
}
