package com.ease2k.blog.owner;

import com.ease2k.blog.owner.OwnerMediaMapper.Asset;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PostgresOwnerMedia implements OwnerMedia {

    private final OwnerDatabase database;
    private final OwnerMediaMapper mediaMapper;
    private final OwnerEntryMapper entryMapper;
    private final OwnerSeriesMapper seriesMapper;
    private final OwnerEntries entries;
    private final ObjectMapper mapper;

    public PostgresOwnerMedia(OwnerDatabase database, OwnerEntries entries, ObjectMapper mapper) throws IOException {
        this.database = database;
        this.mediaMapper = database.mapper(OwnerMediaMapper.class);
        this.entryMapper = database.mapper(OwnerEntryMapper.class);
        this.seriesMapper = database.mapper(OwnerSeriesMapper.class);
        this.entries = entries;
        this.mapper = mapper;
        if (database.enabled()) {
            try (var files = Files.list(database.directory().resolve("media"))) {
                files.filter(path -> OwnerInput.IMAGE_NAME.matcher(path.getFileName().toString()).matches())
                        .filter(path -> Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                        .forEach(path -> register(path.getFileName().toString(), ""));
            }
        }
    }

    @Override
    public ObjectNode upload(InputStream input, String label) throws IOException {
        int limit = 3 * 1024 * 1024;
        byte[] body = input.readNBytes(limit + 1);
        if (body.length > limit) throw new OwnerProblem(HttpStatus.PAYLOAD_TOO_LARGE, "压缩后的图片不能超过 3 MiB。");
        Dimensions dimensions = dimensions(body, body.length);
        String name = UUID.randomUUID() + ".webp";
        Path folder = database.directory().resolve("media");
        Path temporary = Files.createTempFile(folder, ".upload-", ".tmp");
        Path target = folder.resolve(name);
        try {
            Files.write(temporary, body);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            try {
                register(name, label);
            } catch (RuntimeException error) {
                Files.deleteIfExists(target);
                throw error;
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return mapper.createObjectNode().put("url", "/media/" + name)
                .put("width", dimensions.width()).put("height", dimensions.height()).put("bytes", body.length);
    }

    @Override
    public Path file(String name) throws IOException {
        if (!database.enabled() || !OwnerInput.IMAGE_NAME.matcher(name).matches()) return null;
        Path folder = database.directory().resolve("media").toRealPath();
        Path requested = folder.resolve(name);
        if (!Files.isRegularFile(requested, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null;
        return requested.toRealPath().startsWith(folder) ? requested : null;
    }

    @Override
    public List<ObjectNode> list() {
        Map<String, List<ObjectNode>> references = references();
        return mediaMapper.assets().stream().map(asset -> {
            String name = asset.name();
            ObjectNode view = mapper.createObjectNode().put("name", name).put("label", asset.label())
                    .put("deleted", asset.deleted()).put("revision", asset.revision())
                    .put("createdAt", asset.createdAt()).put("url", "/media/" + name).put("bytes", 0);
            view.set("references", mapper.valueToTree(references.getOrDefault(name, List.of())));
            try {
                Path path = file(name);
                if (path != null) {
                    long size = Files.size(path);
                    view.put("bytes", size);
                    try (InputStream input = Files.newInputStream(path)) {
                        Dimensions geometry = dimensions(input.readNBytes(32), size);
                        view.put("width", geometry.width()).put("height", geometry.height());
                    }
                }
            } catch (IOException | OwnerProblem ignored) {
                // 旧数据可保留不可用条目，站主仍能从引用列表定位问题。
            }
            return view;
        }).toList();
    }

    @Override
    public void edit(String name, JsonNode input) {
        validName(name);
        int revision = OwnerInput.revision(input, "revision");
        database.transaction(false, () -> {
            Asset asset = mediaMapper.asset(name);
            if (asset == null || asset.revision() != revision) throw OwnerProblem.conflict("图片已更新，请重新加载。");
            String action = input.path("action").asString();
            String label = asset.label();
            boolean deleted = asset.deleted();
            switch (action) {
                case "rename" -> {
                    label = OwnerInput.text(input, "label", 200).strip();
                    if (label.isBlank()) throw OwnerProblem.bad("请填写图片名称。");
                }
                case "trash", "restore" -> {
                    boolean restoring = "restore".equals(action);
                    if (deleted != restoring) throw OwnerProblem.conflict("图片状态已改变。");
                    if (!restoring && references().containsKey(name)) {
                        throw OwnerProblem.conflict("图片仍被文章、草稿、历史或封面引用。");
                    }
                    deleted = !restoring;
                }
                default -> throw OwnerProblem.bad("图片操作不正确。");
            }
            mediaMapper.edit(name, label, deleted);
            return null;
        });
    }

    @Override
    public int replace(String name, JsonNode input) {
        validName(name);
        String next = input.path("next").asString();
        validName(next);
        if (name.equals(next)) throw OwnerProblem.bad("替换图片不能与原图相同。");
        int revision = OwnerInput.revision(input, "revision");
        return database.transaction(true, () -> {
            Asset original = mediaMapper.asset(name), replacement = mediaMapper.asset(next);
            if (original == null || original.deleted() || original.revision() != revision) {
                throw OwnerProblem.conflict("原图片已改变，请重新加载。");
            }
            if (replacement == null || replacement.deleted()) throw OwnerProblem.bad("替换图片不可用。");
            String from = "/media/" + name, to = "/media/" + next;
            Map<String, Integer> revised = new LinkedHashMap<>();
            int changed = 0;
            for (ObjectNode entry : entries.all()) {
                String before = mapper.writeValueAsString(entry);
                if (!before.contains(from)) continue;
                ObjectNode rewritten = (ObjectNode) mapper.readTree(before.replace(from, to));
                int base = entry.path("revision").asInt();
                String id = entry.path("id").asString();
                entries.save(id, rewritten, base, false, false, false, false, true);
                revised.put(id, base + 1);
                changed++;
            }
            String now = Instant.now().toString();
            for (ObjectNode series : entries.series()) {
                if (!from.equals(series.path("cover").asString())) continue;
                series.remove(List.of("revision", "updatedAt"));
                series.put("cover", to);
                seriesMapper.reviseSeries(series.path("id").asString(), mapper.writeValueAsString(series), now);
                changed++;
            }
            for (ObjectNode draft : entries.drafts()) {
                String before = mapper.writeValueAsString(draft.path("entry"));
                String after = before.replace(from, to);
                String id = draft.path("entry").path("id").asString();
                if (!before.equals(after) || revised.containsKey(id)) {
                    entryMapper.reviseDraft(id, after, revised.getOrDefault(id, draft.path("baseRevision").asInt()), now);
                }
            }
            return changed;
        });
    }

    private Map<String, List<ObjectNode>> references() {
        Map<String, List<ObjectNode>> refs = new LinkedHashMap<>();
        for (ObjectNode entry : entries.all()) collect(refs, entry,
                "private".equals(entry.path("visibility").asString()) ? "draft" : "published");
        for (ObjectNode entry : entries.trash()) collect(refs, entry, "trash");
        for (ObjectNode draft : entries.drafts()) collect(refs, draft.path("entry"), "working");
        for (String payload : entryMapper.historyPayloads()) collect(refs, mapper.readTree(payload), "history");
        for (ObjectNode series : entries.series()) collect(refs, series, "cover");
        return refs;
    }

    private void collect(Map<String, List<ObjectNode>> refs, JsonNode entry, String kind) {
        String id = entry.path("id").asString();
        OwnerInput.IMAGE_PATH.matcher(mapper.writeValueAsString(entry)).results().map(match -> match.group(1)).distinct().forEach(name -> {
            List<ObjectNode> references = refs.computeIfAbsent(name, ignored -> new ArrayList<>());
            if ("history".equals(kind)) {
                for (ObjectNode previous : references) {
                    if (kind.equals(previous.path("kind").asString()) && id.equals(previous.path("id").asString())) {
                        previous.put("count", previous.path("count").asInt(1) + 1);
                        return;
                    }
                }
            }
            ObjectNode value = mapper.createObjectNode().put("kind", kind).put("id", id)
                    .put("title", entry.path("title").asString(id));
            if ("history".equals(kind)) value.put("count", 1);
            references.add(value);
        });
    }

    private void register(String name, String label) {
        mediaMapper.register(name, label.strip().isEmpty() ? name : label.strip(), Instant.now().toString());
    }

    private static void validName(String name) {
        if (!OwnerInput.IMAGE_NAME.matcher(name).matches()) throw OwnerProblem.bad("图片地址不正确。");
    }

    private static Dimensions dimensions(byte[] bytes, long total) {
        if (bytes.length < 30 || !tag(bytes, 0).equals("RIFF") || !tag(bytes, 8).equals("WEBP")
                || Integer.toUnsignedLong(number(bytes, 4, 4)) + 8 != total) {
            throw OwnerProblem.bad("图片需要先转换成 WebP 格式。");
        }
        String type = tag(bytes, 12);
        int width = 0, height = 0;
        if ("VP8 ".equals(type) && part(bytes, 23) == 0x9d && part(bytes, 24) == 1 && part(bytes, 25) == 0x2a) {
            width = number(bytes, 26, 2) & 16383;
            height = number(bytes, 28, 2) & 16383;
        } else if ("VP8L".equals(type) && part(bytes, 20) == 0x2f) {
            width = 1 + ((part(bytes, 22) & 63) << 8 | part(bytes, 21));
            height = 1 + ((part(bytes, 24) & 15) << 10 | part(bytes, 23) << 2 | part(bytes, 22) >> 6);
        } else if ("VP8X".equals(type) && (part(bytes, 20) & 2) == 0) {
            width = number(bytes, 24, 3) + 1;
            height = number(bytes, 27, 3) + 1;
        }
        if (width < 1 || height < 1 || width > 4096 || height > 4096) {
            throw OwnerProblem.bad("请上传边长不超过 4096 像素的静态图片。");
        }
        return new Dimensions(width, height);
    }

    private static String tag(byte[] bytes, int offset) {
        return new String(bytes, offset, 4, StandardCharsets.US_ASCII);
    }

    private static int number(byte[] bytes, int offset, int length) {
        int value = 0;
        for (int i = 0; i < length; i++) value |= part(bytes, offset + i) << (i * 8);
        return value;
    }

    private static int part(byte[] bytes, int offset) {
        return bytes[offset] & 255;
    }

    private record Dimensions(int width, int height) {
    }
}
