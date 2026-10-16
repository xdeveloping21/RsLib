package com.rs.lib.account;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * The server's secret for hashing addresses: 32 random bytes, written once as hexadecimal to a
 * file in RsLib's folder and never to the database. Without it, a stolen database does not
 * reveal addresses by hashing every IPv4 address and comparing.
 */
final class AccountSalt {

    static final int BYTES = 32;

    private AccountSalt() {
    }

    /**
     * The salt in {@code file}, created on first use (readable only by the owner where the
     * file system allows). A file that exists but does not hold a salt is never replaced:
     * replacing it would make every recorded address unmatchable.
     *
     * @throws IOException when the file cannot be read or written, or does not hold a salt
     */
    static byte[] loadOrCreate(Path file) throws IOException {
        if (Files.exists(file)) {
            return read(file);
        }
        byte[] salt = new byte[BYTES];
        new SecureRandom().nextBytes(salt);
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try {
            Files.writeString(file, HexFormat.of().formatHex(salt) + "\n", StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException createdMeanwhile) {
            return read(file);
        }
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException notPosix) {
            // Windows: the folder's own permissions apply.
        }
        return salt;
    }

    private static byte[] read(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.US_ASCII).trim();
        try {
            byte[] salt = HexFormat.of().parseHex(text);
            if (salt.length == BYTES) {
                return salt;
            }
        } catch (IllegalArgumentException notHex) {
            // Reported below.
        }
        throw new IOException(file + " does not hold a salt (" + BYTES * 2 + " hexadecimal characters). Restore it,"
                + " or delete it to start over (the addresses recorded so far will no longer match)");
    }
}
