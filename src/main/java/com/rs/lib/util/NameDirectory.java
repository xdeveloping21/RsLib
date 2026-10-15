package com.rs.lib.util;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * The name/UUID table behind {@link PlayerNames}, with its file format; no Bukkit.
 *
 * <p>Reads never lock. Writes are serialized; when two players have held the same name, the
 * one seen last owns it. The file has one line per player, {@code uuid<TAB>name<TAB>lastSeenMillis},
 * and is replaced atomically through a {@code .tmp} sibling.</p>
 */
final class NameDirectory {

    /** Longest name kept. */
    static final int MAX_NAME_LENGTH = 64;

    /** Vanilla writes {@code expiresOn} one month after the last sighting. */
    private static final long USERCACHE_LIFETIME = TimeUnit.DAYS.toMillis(30);
    private static final DateTimeFormatter USERCACHE_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT);

    record Entry(String name, long seenAt) {
    }

    /** What a file read gave: lines taken, and lines skipped because they could not be read. */
    record LoadResult(int read, int skipped) {
    }

    private final int maxEntries;
    private final Map<UUID, Entry> byId = new ConcurrentHashMap<>();
    /** Lower-case name to the UUID that owns it. */
    private final Map<String, UUID> byName = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();
    private boolean dirty;

    NameDirectory(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    Optional<String> name(UUID id) {
        Entry entry = id == null ? null : byId.get(id);
        return entry == null ? Optional.empty() : Optional.of(entry.name());
    }

    Optional<UUID> uuid(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(byName.get(name.toLowerCase(Locale.ROOT)));
    }

    boolean contains(UUID id) {
        return byId.containsKey(id);
    }

    int size() {
        return byId.size();
    }

    /** True for a name that can be stored: 1 to 64 characters, none of them a control character. */
    static boolean validName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Records that {@code id} was called {@code name} at {@code seenAt}. Ignored when the name
     * is invalid or an equal or newer sighting of {@code id} is already known with the same name.
     *
     * @return true when something changed
     */
    boolean remember(UUID id, String name, long seenAt) {
        if (id == null || !validName(name)) {
            return false;
        }
        synchronized (writeLock) {
            if (!put(id, name, seenAt)) {
                return false;
            }
            dirty = true;
            // Pruning sorts everything, so let the table run a little over before doing it.
            if (byId.size() > maxEntries + Math.max(16, maxEntries / 64)) {
                prune();
            }
            return true;
        }
    }

    /** Must hold the write lock. */
    private boolean put(UUID id, String name, long seenAt) {
        Entry old = byId.get(id);
        if (old != null && (old.seenAt() > seenAt || old.seenAt() == seenAt && old.name().equals(name))) {
            return false;
        }
        byId.put(id, new Entry(name, seenAt));
        String lower = name.toLowerCase(Locale.ROOT);
        if (old != null && !old.name().toLowerCase(Locale.ROOT).equals(lower)) {
            release(old.name().toLowerCase(Locale.ROOT), id);
        }
        UUID holder = byName.get(lower);
        if (holder == null || holder.equals(id)) {
            byName.put(lower, id);
        } else {
            Entry other = byId.get(holder);
            if (other == null || other.seenAt() <= seenAt) {
                byName.put(lower, id);
            }
        }
        return true;
    }

    /** {@code id} no longer uses {@code lower}: hand the name to whoever was seen with it last. */
    private void release(String lower, UUID id) {
        if (!id.equals(byName.get(lower))) {
            return;
        }
        UUID best = null;
        long bestSeen = Long.MIN_VALUE;
        for (Map.Entry<UUID, Entry> e : byId.entrySet()) {
            Entry entry = e.getValue();
            if (!e.getKey().equals(id) && entry.seenAt() >= bestSeen
                    && entry.name().toLowerCase(Locale.ROOT).equals(lower)) {
                best = e.getKey();
                bestSeen = entry.seenAt();
            }
        }
        if (best == null) {
            byName.remove(lower);
        } else {
            byName.put(lower, best);
        }
    }

    /** Drops the players seen longest ago until at most the maximum is left. */
    void prune() {
        synchronized (writeLock) {
            int excess = byId.size() - maxEntries;
            if (excess <= 0) {
                return;
            }
            List<Map.Entry<UUID, Entry>> all = new ArrayList<>(byId.entrySet());
            all.sort(Comparator.comparingLong(e -> e.getValue().seenAt()));
            for (int i = 0; i < excess; i++) {
                byId.remove(all.get(i).getKey());
            }
            // Rebuild the name index in one pass, the newest sighting owning each name.
            Map<String, UUID> owners = new HashMap<>(byId.size() * 2);
            Map<String, Long> seen = new HashMap<>(byId.size() * 2);
            for (Map.Entry<UUID, Entry> e : byId.entrySet()) {
                String lower = e.getValue().name().toLowerCase(Locale.ROOT);
                Long previous = seen.get(lower);
                if (previous == null || previous <= e.getValue().seenAt()) {
                    owners.put(lower, e.getKey());
                    seen.put(lower, e.getValue().seenAt());
                }
            }
            byName.keySet().retainAll(owners.keySet());
            byName.putAll(owners);
            dirty = true;
        }
    }

    boolean isDirty() {
        synchronized (writeLock) {
            return dirty;
        }
    }

    void markDirty() {
        synchronized (writeLock) {
            dirty = true;
        }
    }

    /**
     * Merges the TSV file at {@code file}, newer sightings already known winning, then prunes.
     * Blank lines are ignored; malformed ones are skipped and counted. Loading does not by itself
     * make the table dirty (the file already holds what it adds).
     */
    LoadResult load(Path file) throws IOException {
        int read = 0;
        int skipped = 0;
        try (BufferedReader in = new BufferedReader(utf8(file))) {
            String line;
            boolean first = true;
            while ((line = in.readLine()) != null) {
                if (first && !line.isEmpty() && line.charAt(0) == '﻿') {
                    line = line.substring(1);
                }
                first = false;
                if (line.isBlank()) {
                    continue;
                }
                if (readLine(line)) {
                    read++;
                } else {
                    skipped++;
                }
            }
        }
        prune();
        return new LoadResult(read, skipped);
    }

    private boolean readLine(String line) {
        String[] parts = line.split("\t", -1);
        if (parts.length != 3 || !validName(parts[1])) {
            return false;
        }
        UUID id;
        long seenAt;
        try {
            id = UUID.fromString(parts[0].trim());
            seenAt = Long.parseLong(parts[2].trim());
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        if (seenAt < 0) {
            return false;
        }
        synchronized (writeLock) {
            put(id, parts[1], seenAt);
            if (byId.size() > 2L * maxEntries) {
                prune();
            }
        }
        return true;
    }

    /**
     * Adds the players of the server's {@code usercache.json} whose UUID is not known yet, with
     * their last sighting estimated from {@code expiresOn}.
     *
     * @return how many were added
     */
    int importUsercache(Path file, long now) throws IOException {
        JsonElement root;
        try (Reader in = utf8(file)) {
            root = JsonParser.parseReader(in);
        } catch (JsonParseException e) {
            throw new IOException("usercache.json is not valid JSON", e);
        }
        if (!root.isJsonArray()) {
            return 0;
        }
        int added = 0;
        for (JsonElement element : root.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String name = text(entry, "name");
            UUID id;
            try {
                String raw = text(entry, "uuid");
                id = raw == null ? null : UUID.fromString(raw);
            } catch (IllegalArgumentException malformed) {
                continue;
            }
            if (id == null || contains(id)) {
                continue;
            }
            if (remember(id, name, lastSeen(text(entry, "expiresOn"), now))) {
                added++;
            }
        }
        return added;
    }

    /** When vanilla last saw a player, from its {@code expiresOn}; 0 when unreadable. */
    static long lastSeen(String expiresOn, long now) {
        if (expiresOn == null) {
            return 0L;
        }
        try {
            long expires = OffsetDateTime.parse(expiresOn.trim(), USERCACHE_DATE).toInstant().toEpochMilli();
            return Math.max(0L, Math.min(now, expires - USERCACHE_LIFETIME));
        } catch (DateTimeParseException unreadable) {
            return 0L;
        }
    }

    /**
     * Writes every entry to {@code file} through {@code file.tmp} and an atomic rename, and
     * clears the dirty flag (set again if the write fails, so the next save retries).
     */
    void save(Path file) throws IOException {
        List<String> lines;
        synchronized (writeLock) {
            lines = new ArrayList<>(byId.size());
            for (Map.Entry<UUID, Entry> e : byId.entrySet()) {
                lines.add(e.getKey() + "\t" + e.getValue().name() + "\t" + e.getValue().seenAt());
            }
            dirty = false;
        }
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (BufferedWriter out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                for (String line : lines) {
                    out.write(line);
                    out.write('\n');
                }
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            markDirty();
            throw e;
        }
    }

    private static String text(JsonObject object, String member) {
        JsonElement value = object.get(member);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    /** A UTF-8 reader that replaces bad bytes instead of failing the whole file. */
    private static Reader utf8(Path file) throws IOException {
        return new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8);
    }
}
