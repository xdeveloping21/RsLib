package com.rs.lib.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

/** The text of /rslib integrations and /rslib iteminfo. */
class AdminInfoTest {

    private record Stored(PersistentDataType<?, ?> type, Object value) {
    }

    /** A container that answers has/get/getKeys from a map, as a server would. */
    private static PersistentDataContainer container(Map<NamespacedKey, Stored> data) {
        return (PersistentDataContainer) Proxy.newProxyInstance(AdminInfoTest.class.getClassLoader(),
                new Class<?>[] {PersistentDataContainer.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getKeys" -> data.keySet();
                    case "isEmpty" -> data.isEmpty();
                    case "has" -> {
                        Stored stored = data.get(args[0]);
                        yield stored != null && (args.length == 1 || stored.type() == args[1]);
                    }
                    case "get" -> {
                        Stored stored = data.get(args[0]);
                        yield stored != null && stored.type() == args[1] ? stored.value() : null;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void itemInfoListsEveryKeyWithItsType() {
        Map<NamespacedKey, Stored> inner = new LinkedHashMap<>();
        inner.put(new NamespacedKey("rsgifts", "count"), new Stored(PersistentDataType.INTEGER, 3));
        Map<NamespacedKey, Stored> data = new LinkedHashMap<>();
        data.put(new NamespacedKey("rskits", "id"), new Stored(PersistentDataType.STRING, "starter"));
        data.put(new NamespacedKey("rskits", "uses"), new Stored(PersistentDataType.LONG, 7L));
        data.put(new NamespacedKey("rsgifts", "box"), new Stored(PersistentDataType.TAG_CONTAINER, container(inner)));
        data.put(new NamespacedKey("rskits", "raw"), new Stored(PersistentDataType.BYTE_ARRAY, new byte[5]));
        List<String> lines = AdminInfo.itemInfo("DIAMOND", 2, container(data));
        assertEquals(List.of(
                "Item: DIAMOND x2",
                " Persistent data:",
                "  rsgifts:box (container):",
                "    rsgifts:count = 3 (int)",
                "  rskits:id = \"starter\" (string)",
                "  rskits:raw = 5 bytes (byte[])",
                "  rskits:uses = 7 (long)"), lines);
    }

    @Test
    void itemInfoWithoutDataSaysSo() {
        assertEquals(List.of("Item: STONE x1", " No persistent data."), AdminInfo.itemInfo("STONE", 1, null));
        assertEquals(List.of("Item: STONE x1", " No persistent data."),
                AdminInfo.itemInfo("STONE", 1, container(Map.of())));
    }

    @Test
    void longValuesAreCut() {
        String shown = AdminInfo.format("x".repeat(500));
        assertEquals(AdminInfo.MAX_VALUE + 3, shown.length());
        assertTrue(shown.endsWith("..."));
    }

    @Test
    void deepContainersStopOpening() {
        Map<NamespacedKey, Stored> level = new LinkedHashMap<>();
        level.put(new NamespacedKey("a", "leaf"), new Stored(PersistentDataType.BYTE, (byte) 1));
        for (int i = 0; i < AdminInfo.MAX_DEPTH + 2; i++) {
            Map<NamespacedKey, Stored> outer = new LinkedHashMap<>();
            outer.put(new NamespacedKey("a", "n" + i), new Stored(PersistentDataType.TAG_CONTAINER, container(level)));
            level = outer;
        }
        List<String> lines = AdminInfo.itemInfo("CHEST", 1, container(level));
        assertTrue(lines.stream().anyMatch(l -> l.endsWith("{1 keys} (container)")), lines.toString());
        assertTrue(lines.size() <= 2 + AdminInfo.MAX_DEPTH, lines.toString());
    }

    @Test
    void integrationsGroupByPlugin() {
        List<String> lines = AdminInfo.integrations(List.of(
                new AdminInfo.Hook("RsLib", "PlaceholderAPI", "2.11.6", true),
                new AdminInfo.Hook("RsLib", "Vault", null, false),
                new AdminInfo.Hook("RsKills", "Vault", null, false),
                new AdminInfo.Hook("RsGifts", "PlaceholderAPI", "2.11.6", false)));
        assertEquals(List.of(
                "Integrations:",
                " RsLib: PlaceholderAPI 2.11.6 on, Vault not installed",
                " RsKills: Vault not installed",
                " RsGifts: PlaceholderAPI 2.11.6 off"), lines);
        assertEquals(List.of("Integrations:", " none"), AdminInfo.integrations(List.of()));
    }
}
