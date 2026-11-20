package com.ease2k.blog.owner;

import org.bouncycastle.crypto.generators.SCrypt;
import tools.jackson.databind.ObjectMapper;

import java.io.Console;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

public record OwnerCredentials(String salt, String hash, String secret, String version) {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    static OwnerCredentials load(Path directory, ObjectMapper mapper) throws IOException {
        Path file = directory.resolve("owner.json");
        if (!Files.exists(file)) return null;
        OwnerCredentials value = mapper.readValue(file.toFile(), OwnerCredentials.class);
        if (!matches(value.salt, "[a-f0-9]{32}") || !matches(value.hash, "[a-f0-9]{128}")
                || !matches(value.secret, "[a-f0-9]{64}") || value.version == null || value.version.isBlank()) {
            throw new IOException("站主配置文件格式不正确");
        }
        return value;
    }

    boolean verifies(String password) {
        return password.length() >= 12 && password.length() <= 512 && equal(hash, hash(password, salt));
    }

    public static void setup(Path directory) throws IOException {
        Console console = System.console();
        if (console == null) throw new IOException("请在交互式终端运行 setup-owner。");
        char[] password = console.readPassword("输入站主密码（至少 12 个字符）: ");
        char[] confirmation = console.readPassword("再次输入站主密码: ");
        if (password == null || confirmation == null) throw new IOException("未读取到密码。");
        try {
            if (password.length < 12 || password.length > 512 || !Arrays.equals(password, confirmation)) {
                throw new IOException("密码长度不正确，或两次输入不一致。");
            }
            Files.createDirectories(directory,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            String salt = HEX.formatHex(random(16));
            OwnerCredentials value = new OwnerCredentials(salt, hash(new String(password), salt),
                    HEX.formatHex(random(32)), UUID.randomUUID().toString());
            Path temp = Files.createTempFile(directory, ".owner-", ".json",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try {
                new ObjectMapper().writeValue(temp.toFile(), value);
                Files.move(temp, directory.resolve("owner.json"), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
            console.printf("站主配置已保存，已有会话失效。%n");
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(confirmation, '\0');
        }
    }

    static String token() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random(32));
    }

    static boolean equal(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    private static String hash(String password, String salt) {
        // 原站 scrypt 使用十六进制盐字符串的 UTF-8 字节，保留该编码才能校验旧密码。
        return HEX.formatHex(SCrypt.generate(password.getBytes(StandardCharsets.UTF_8),
                salt.getBytes(StandardCharsets.UTF_8), 32768, 8, 1, 64));
    }

    private static byte[] random(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static boolean matches(String value, String pattern) {
        return value != null && value.matches(pattern);
    }
}
