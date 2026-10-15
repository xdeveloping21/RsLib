package com.rs.lib.yml;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.rs.lib.yml.internal.YamlOutline;
import com.rs.lib.yml.internal.YamlText;

/**
 * Repairs the most common hand-editing mistake in YAML files: indenting with tabs.
 *
 * <p>Typical use for a file read outside {@link RsYml}:</p>
 * <pre>{@code
 * YamlPreprocessor.Result result = YamlPreprocessor.preprocess(YamlPreprocessor.read(file.toPath()));
 * if (!result.fixedLines().isEmpty()) {
 *     getLogger().warning("Indentation tabs fixed in " + file.getName() + " (lines " + result.fixedLines() + ")");
 * }
 * yaml.loadFromString(result.cleanText());
 * }</pre>
 *
 * <p>Rules: line endings become {@code '\n'}; in each line every tab inside the leading
 * indentation becomes two spaces and the rest of the line is left alone (a tab inside a value
 * is kept). The content of block scalars ({@code |} or {@code >}, with any chomping and
 * indentation indicators) is copied as written while its lines are blank or indented deeper
 * than the indicator line. One exception, an RsLib addition: a block-scalar line that
 * <em>starts</em> with a tab can never be valid YAML, so its leading tabs are repaired too (a
 * tab after leading spaces is content and is kept). Comments outside quotes are ignored when
 * looking for the indicator. The method never throws.</p>
 */
public final class YamlPreprocessor {

    /** Spaces a tab in the indentation is worth, both when repairing and when measuring. */
    private static final int TAB_WIDTH = 2;
    private static final String TAB_AS_SPACES = " ".repeat(TAB_WIDTH);

    /**
     * The repaired text and the 1-based numbers of the lines that changed.
     *
     * @param cleanText  the text to parse
     * @param fixedLines line numbers (from 1) whose indentation was repaired
     */
    public record Result(String cleanText, List<Integer> fixedLines) {

        public Result {
            cleanText = cleanText == null ? "" : cleanText;
            fixedLines = fixedLines == null ? List.of() : List.copyOf(fixedLines);
        }
    }

    private YamlPreprocessor() {
    }

    /** Repairs tab indentation in {@code rawText}; null gives an empty result. */
    public static Result preprocess(String rawText) {
        if (rawText == null) {
            return new Result("", List.of());
        }
        String text = YamlText.stripBom(rawText).replace("\r\n", "\n").replace('\r', '\n');
        try {
            return repair(text);
        } catch (RuntimeException unexpected) {
            return new Result(text, List.of());
        }
    }

    /**
     * Reads a file as UTF-8 (invalid bytes are replaced, a leading byte order mark is dropped).
     * The text is returned as it is; pass it to {@link #preprocess(String)}.
     */
    public static String read(Path file) throws IOException {
        return YamlText.read(file);
    }

    private static Result repair(String text) {
        String[] lines = text.split("\n", -1);
        List<Integer> fixed = new ArrayList<>();
        StringBuilder out = new StringBuilder(text.length() + 16);
        int blockIndent = -1;
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            String line = lines[i];
            if (blockIndent >= 0) {
                if (line.isBlank()) {
                    out.append(line);
                    continue;
                }
                if (width(line) > blockIndent) {
                    if (line.charAt(0) == '\t') {
                        out.append(repairLeading(line));
                        fixed.add(i + 1);
                    } else {
                        out.append(line);
                    }
                    continue;
                }
                blockIndent = -1;
            }
            String repaired = repairLeading(line);
            if (!repaired.equals(line)) {
                fixed.add(i + 1);
            }
            out.append(repaired);
            if (opensBlockScalar(repaired)) {
                blockIndent = YamlOutline.leadingSpaces(repaired);
            }
        }
        return new Result(out.toString(), fixed);
    }

    /** Replaces each tab in the leading whitespace with {@link #TAB_WIDTH} spaces. */
    private static String repairLeading(String line) {
        int end = 0;
        boolean tab = false;
        while (end < line.length() && (line.charAt(end) == ' ' || line.charAt(end) == '\t')) {
            tab |= line.charAt(end) == '\t';
            end++;
        }
        if (!tab) {
            return line;
        }
        StringBuilder sb = new StringBuilder(line.length() + 8);
        for (int i = 0; i < end; i++) {
            sb.append(line.charAt(i) == '\t' ? TAB_AS_SPACES : " ");
        }
        return sb.append(line, end, line.length()).toString();
    }

    /** Indentation width with a tab counted as {@link #TAB_WIDTH} spaces. */
    private static int width(String line) {
        int width = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ') {
                width++;
            } else if (c == '\t') {
                width += TAB_WIDTH;
            } else {
                break;
            }
        }
        return width;
    }

    private static boolean opensBlockScalar(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return false;
        }
        int comment = YamlOutline.commentStart(trimmed);
        String code = (comment < 0 ? trimmed : trimmed.substring(0, comment)).trim();
        // The value after "key:", after "- " or after "- key:".
        String value;
        int colon = YamlOutline.keyColon(code);
        if (colon >= 0) {
            value = code.substring(colon + 1);
        } else if (code.startsWith("- ") || code.equals("-")) {
            String item = code.substring(1).trim();
            int innerColon = YamlOutline.keyColon(item);
            value = innerColon >= 0 ? item.substring(innerColon + 1) : item;
        } else {
            return false;
        }
        return YamlOutline.isBlockScalarIndicator(value);
    }
}
