package com.ease2k.blog.owner;

import com.fasterxml.jackson.annotation.JsonCreator;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** 站主工作台兼容原有前端。访问范围、会话与 CSRF 由 OwnerAccessFilter 统一校验。 */
@RestController
public class OwnerController {

    private static final MediaType WEBP = MediaType.parseMediaType("image/webp");
    private static final Map<String, Boolean> OK = Map.of("ok", true);
    private final OwnerSessions sessions;
    private final OwnerWorkbench workbench;
    private final OwnerEntries entries;
    private final OwnerSeries series;
    private final OwnerMedia media;
    private final ObjectMapper mapper;

    public OwnerController(OwnerSessions sessions, OwnerWorkbench workbench, OwnerEntries entries,
                           OwnerSeries series, OwnerMedia media, ObjectMapper mapper) {
        this.sessions = sessions;
        this.workbench = workbench;
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
    public ObjectNode session(@RequestAttribute(OwnerAccessFilter.ACCESS) OwnerAccessFilter.Access access) {
        ObjectNode view = mapper.createObjectNode().put("configured", access.credentials() != null)
                .put("authenticated", access.session() != null);
        if (access.session() != null) view.put("csrf", access.session().csrf());
        return view;
    }

    @PostMapping("/api/owner/login")
    public ResponseEntity<?> login(@RequestAttribute(OwnerAccessFilter.ACCESS) OwnerAccessFilter.Access access,
                                   @RequestBody LoginRequest input) {
        String token = sessions.login(access.credentials(), input.password(), input.remember());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,
                cookie(token, access.secure(), sessions.lifetime(input.remember())).toString()).body(OK);
    }

    @PostMapping("/api/owner/logout")
    public ResponseEntity<?> logout(@RequestAttribute(OwnerAccessFilter.ACCESS) OwnerAccessFilter.Access access) {
        sessions.logout(access.session());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,
                cookie("", access.secure(), Duration.ZERO).toString()).body(OK);
    }

    @GetMapping("/api/owner/entries")
    public OwnerWorkbench.Entries entries() {
        return workbench.entries();
    }

    @GetMapping("/api/owner/export")
    public ResponseEntity<OwnerWorkbench.Export> export() {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"ease2k-editor-backup.json\"")
                .body(workbench.export());
    }

    @PutMapping("/api/owner/entries/{id}")
    public Map<String, ObjectNode> write(@PathVariable String id, @RequestBody JsonNode input) {
        return Map.of("entry", entries.write(id, input));
    }

    @DeleteMapping("/api/owner/entries/{id}")
    public Map<String, Boolean> trash(@PathVariable String id, @RequestBody JsonNode input) {
        entries.trash(id, input);
        return OK;
    }

    @GetMapping("/api/owner/entries/{id}/history")
    public Map<String, ?> history(@PathVariable String id) {
        return Map.of("history", entries.history(id));
    }

    @PostMapping("/api/owner/entries/{id}/restore")
    public Map<String, ObjectNode> restore(@PathVariable String id, @RequestBody JsonNode input) {
        return Map.of("entry", entries.restore(id, input));
    }

    @PutMapping("/api/owner/drafts/{id}")
    public Map<String, ObjectNode> draft(@PathVariable String id, @RequestBody JsonNode input) {
        return Map.of("draft", entries.saveDraft(id, input));
    }

    @GetMapping("/api/owner/series")
    public Map<String, ?> series() {
        return Map.of("series", entries.series());
    }

    @PutMapping("/api/owner/series")
    public Map<String, ObjectNode> saveSeries(@RequestBody JsonNode input) {
        return Map.of("series", series.save(input));
    }

    @GetMapping("/api/owner/media")
    public OwnerWorkbench.Media media() {
        return workbench.media();
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
    public Map<String, Boolean> editMedia(@PathVariable String name, @RequestBody JsonNode input) {
        media.edit(name, input);
        return OK;
    }

    @PostMapping("/api/owner/media/{name}/replace")
    public Map<String, Integer> replaceMedia(@PathVariable String name, @RequestBody JsonNode input) {
        return Map.of("changed", media.replace(name, input));
    }

    private static ResponseCookie cookie(String token, boolean secure, Duration lifetime) {
        return ResponseCookie.from(secure ? "__Host-ease_owner" : "ease_owner", token)
                .httpOnly(true).sameSite("Strict").secure(secure).path(secure ? "/" : "/api/owner")
                .maxAge(lifetime).build();
    }

    public record LoginRequest(String password, boolean remember) {

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static LoginRequest from(JsonNode input) {
            OwnerInput.requireObject(input, "需要 JSON 对象。");
            boolean remember = OwnerInput.flag(input, "remember");
            return new LoginRequest(OwnerInput.text(input, "password", 512), remember);
        }
    }
}
