package com.ease2k.blog.owner;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** 站主工作台兼容原有前端。访问范围、会话与 CSRF 由 OwnerAccessFilter 统一校验。 */
@RestController
public class OwnerController {

    private static final MediaType WEBP = MediaType.parseMediaType("image/webp");
    private static final Map<String, Boolean> OK = Map.of("ok", true);
    private final OwnerSessions sessions;
    private final OwnerDatabase database;
    private final OwnerEntries entries;
    private final OwnerSeries series;
    private final OwnerMedia media;
    private final ObjectMapper mapper;

    public OwnerController(OwnerSessions sessions, OwnerDatabase database, OwnerEntries entries,
                           OwnerSeries series, OwnerMedia media, ObjectMapper mapper) {
        this.sessions = sessions;
        this.database = database;
        this.entries = entries;
        this.series = series;
        this.media = media;
        this.mapper = mapper;
    }

    @GetMapping(value = {"/_owner", "/_owner/{*path}"}, produces = "text/html;charset=UTF-8")
    public Resource workbench() {
        return new ClassPathResource("static/owner.html");
    }

    @GetMapping("/api/owner/session")
    public ObjectNode session(HttpServletRequest request) {
        OwnerAccessFilter.Access access = access(request);
        ObjectNode view = mapper.createObjectNode().put("configured", access.credentials() != null)
                .put("authenticated", access.session() != null);
        if (access.session() != null) view.put("csrf", access.session().csrf());
        return view;
    }

    @PostMapping("/api/owner/login")
    public ResponseEntity<?> login(HttpServletRequest request) throws IOException {
        OwnerAccessFilter.Access access = access(request);
        JsonNode data = body(request);
        boolean remember = OwnerInput.flag(data, "remember");
        String token = sessions.login(access.credentials(), OwnerInput.text(data, "password", 512), remember);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,
                cookie(token, access.secure(), sessions.lifetime(remember)).toString()).body(OK);
    }

    @PostMapping("/api/owner/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        OwnerAccessFilter.Access access = access(request);
        sessions.logout(access.session());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,
                cookie("", access.secure(), Duration.ZERO).toString()).body(OK);
    }

    @GetMapping("/api/owner/entries")
    public Map<String, ?> entries() {
        return database.transaction(false, () -> Map.of("entries", entries.all(), "trash", entries.trash(),
                "drafts", entries.drafts(), "series", entries.series()));
    }

    @GetMapping("/api/owner/export")
    public ResponseEntity<?> export() {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"ease2k-editor-backup.json\"")
                .body(database.transaction(false, () -> Map.of("version", 2, "exportedAt", Instant.now().toString(),
                        "entries", entries.all(), "trash", entries.trash(), "drafts", entries.drafts(),
                        "series", entries.series(), "media", media.list())));
    }

    @PutMapping("/api/owner/entries/{id}")
    public Map<String, ObjectNode> write(@PathVariable String id, HttpServletRequest request) throws IOException {
        return Map.of("entry", entries.write(id, body(request)));
    }

    @DeleteMapping("/api/owner/entries/{id}")
    public Map<String, Boolean> trash(@PathVariable String id, HttpServletRequest request) throws IOException {
        entries.trash(id, body(request));
        return OK;
    }

    @GetMapping("/api/owner/entries/{id}/history")
    public Map<String, ?> history(@PathVariable String id) {
        return Map.of("history", entries.history(id));
    }

    @PostMapping("/api/owner/entries/{id}/restore")
    public Map<String, ObjectNode> restore(@PathVariable String id, HttpServletRequest request) throws IOException {
        return Map.of("entry", entries.restore(id, body(request)));
    }

    @PutMapping("/api/owner/drafts/{id}")
    public Map<String, ObjectNode> draft(@PathVariable String id, HttpServletRequest request) throws IOException {
        return Map.of("draft", entries.saveDraft(id, body(request)));
    }

    @GetMapping("/api/owner/series")
    public Map<String, ?> series() {
        return Map.of("series", entries.series());
    }

    @PutMapping("/api/owner/series")
    public Map<String, ObjectNode> saveSeries(HttpServletRequest request) throws IOException {
        return Map.of("series", series.save(body(request)));
    }

    @GetMapping("/api/owner/media")
    public Map<String, ?> media() {
        return database.transaction(false, () -> Map.of("media", media.list()));
    }

    @PostMapping("/api/owner/media")
    public ObjectNode upload(HttpServletRequest request) throws IOException {
        if (!"image/webp".equals(request.getContentType())) {
            throw new OwnerProblem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "仅接受已压缩的 WebP 图片。");
        }
        String header = request.getHeader("X-Image-Name");
        String label;
        try {
            label = header == null ? "" : URLDecoder.decode(header.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            label = "";
        }
        return media.upload(request.getInputStream(), label.substring(0, Math.min(label.length(), 200)));
    }

    @GetMapping("/api/owner/media/{name}")
    public ResponseEntity<Resource> preview(@PathVariable String name) throws IOException {
        Path image = media.file(name);
        if (image == null) throw OwnerProblem.missing("图片不存在。");
        return ResponseEntity.ok().contentType(WEBP).body(new FileSystemResource(image));
    }

    @PutMapping("/api/owner/media/{name}")
    public Map<String, Boolean> editMedia(@PathVariable String name, HttpServletRequest request) throws IOException {
        media.edit(name, body(request));
        return OK;
    }

    @PostMapping("/api/owner/media/{name}/replace")
    public Map<String, Integer> replaceMedia(@PathVariable String name, HttpServletRequest request) throws IOException {
        return Map.of("changed", media.replace(name, body(request)));
    }

    @ExceptionHandler(OwnerProblem.class)
    public ResponseEntity<Map<String, String>> error(OwnerProblem problem) {
        ResponseEntity.BodyBuilder status = ResponseEntity.status(problem.status());
        if (problem.status() == HttpStatus.TOO_MANY_REQUESTS) status.header(HttpHeaders.RETRY_AFTER, "900");
        return status.body(Map.of("error", problem.getMessage()));
    }

    private JsonNode body(HttpServletRequest request) throws IOException {
        String contentType = request.getContentType();
        if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(contentType))) {
            throw new OwnerProblem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "请求需要使用 JSON 格式。");
        }
        int max = 2 * 1024 * 1024;
        byte[] bytes = request.getInputStream().readNBytes(max + 1);
        if (bytes.length > max) throw new OwnerProblem(HttpStatus.PAYLOAD_TOO_LARGE, "文章内容过大。");
        try {
            JsonNode result = mapper.readTree(bytes);
            OwnerInput.requireObject(result, "需要 JSON 对象。");
            return result;
        } catch (tools.jackson.core.JacksonException ignored) {
            throw OwnerProblem.bad("JSON 格式不正确。");
        }
    }

    private static OwnerAccessFilter.Access access(HttpServletRequest request) {
        return (OwnerAccessFilter.Access) request.getAttribute(OwnerAccessFilter.ACCESS);
    }

    private static ResponseCookie cookie(String token, boolean secure, Duration lifetime) {
        return ResponseCookie.from(secure ? "__Host-ease_owner" : "ease_owner", token)
                .httpOnly(true).sameSite("Strict").secure(secure).path(secure ? "/" : "/api/owner")
                .maxAge(lifetime).build();
    }
}
