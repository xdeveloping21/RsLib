package com.rs.lib.yml.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Text and file helpers shared by the YAML classes. Not API: plugins should not call it.
 *
 * <p>Every file RsLib writes uses {@code '\n'} line endings on every OS and is replaced through
 * a temporary file in the same folder, so a crash in the middle of a write never leaves a
 * half-written YAML behind.</p>
 */
public final class YamlText {

    private static final char BOM = '﻿';

    private YamlText() {
    }

    /** Decodes UTF-8, replacing invalid sequences, and drops a leading byte order mark. */
    public static String decode(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        String text;
        try {
            text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException cannotHappenWithReplace) {
            text = new String(bytes, StandardCharsets.UTF_8);
        }
        return stripBom(text);
    }

    public static String stripBom(String text) {
        return !text.isEmpty() && text.charAt(0) == BOM ? text.substring(1) : text;
    }

    public static String read(Path file) throws IOException {
        return decode(Files.readAllBytes(file));
    }

    /** Reads a whole stream as text and closes it. */
    public static String read(InputStream in) throws IOException {
        try (InputStream stream = in) {
            return decode(stream.readAllBytes());
        }
    }

    /**
     * Splits text into lines on any line ending. A final line ending does not create an extra
     * empty line, so {@code "a\n"} and {@code "a"} both give {@code [a]}.
     */
    public static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        int start = 0;
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                out.add(text.substring(start, i));
                if (c == '\r' && i + 1 < length && text.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            }
        }
        if (start < length) {
            out.add(text.substring(start));
        }
        return out;
    }

    /** Joins lines with {@code '\n'} and ends a non-empty text with one. */
    public static String join(List<String> lines) {
        if (lines.isEmpty()) {
            return "";
        }
        return String.join("\n", lines) + "\n";
    }

    /** Writes {@code text} to {@code target} through a temporary file (see {@link #commit}). */
    public static void write(Path target, String text) throws IOException {
        Path temp = writeTemp(target, text);
        commit(temp, target, text);
    }

    /**
     * Writes {@code text} to a new temporary file next to {@code target}, creating folders. The
     * bytes are forced to the disk before returning, so the move that follows never exposes a
     * file whose content is still only in the OS cache after a power loss.
     */
    public static Path writeTemp(Path target, String text) throws IOException {
        Path absolute = target.toAbsolutePath();
        Path dir = absolute.getParent();
        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, "." + absolute.getFileName() + ".", ".tmp");
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(true);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        return temp;
    }

    /**
     * Moves {@code temp} over {@code target} atomically. When the file system cannot do that
     * (or refuses the move, as Windows does for a file open elsewhere) {@code text} is written
     * straight to {@code target} instead. The temporary file is always removed.
     */
    public static void commit(Path temp, Path target, String text) throws IOException {
        try {
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AtomicMoveNotSupportedException unsupported) {
                // fall through to the direct write
            } catch (IOException refused) {
                // fall through to the direct write
            }
            Files.write(target, text.getBytes(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
