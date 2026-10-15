package com.rs.lib.update.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Random;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

/** Builds small jars for the tests. */
final class TestJars {

    private TestJars() {
    }

    /** A plugin.yml with the given values. */
    static String pluginYml(String name, String main, String version) {
        return "name: " + name + "\nmain: " + main + "\nversion: '" + version + "'\napi-version: '1.20'\n";
    }

    static String rsLibYml(String version) {
        return pluginYml("RsLib", "com.rs.lib.RsLibPlugin", version);
    }

    /** A jar holding {@code pluginYml} (null for none) and {@code padding} random bytes. */
    static byte[] jar(String pluginYml, int padding) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            if (pluginYml != null) {
                jar.putNextEntry(new ZipEntry("plugin.yml"));
                jar.write(pluginYml.getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
            byte[] noise = new byte[padding];
            new Random(7).nextBytes(noise);
            jar.putNextEntry(new ZipEntry("pad.bin"));
            jar.write(noise);
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }

    static Path write(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.write(file, bytes);
    }

    /** The SHA-256 of {@code bytes}, lower-case hex. */
    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
