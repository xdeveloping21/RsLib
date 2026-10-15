package com.rs.lib.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

/** The text of /rslib plugins, integrations and iteminfo, as SnLib's /snlib shows it. */
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
    void itemInfoShowsStringTagsAndMarksTheRest() {
        Map<NamespacedKey, Stored> data = new LinkedHashMap<>();
        data.put(new NamespacedKey("rskits", "snlib_item_id"), new Stored(PersistentDataType.STRING, "starter"));
        data.put(new NamespacedKey("rskits", "uses"), new Stored(PersistentDataType.LONG, 7L));
        data.put(new NamespacedKey("rsgifts", "box"), new Stored(PersistentDataType.TAG_CONTAINER, container(Map.of())));
        assertEquals(List.of(
                "PDC tags of DIAMOND (3):",
                "- rsgifts:box = <non-string tag>",
                "- rskits:snlib_item_id = starter",
                "- rskits:uses = <non-string tag>"), AdminInfo.itemInfo("DIAMOND", container(data)));
    }

    @Test
    void itemInfoWithoutTags() {
        assertEquals(List.of("PDC tags of STONE (0):"), AdminInfo.itemInfo("STONE", null));
        assertEquals(List.of("PDC tags of STONE (0):"), AdminInfo.itemInfo("STONE", container(Map.of())));
    }

    @Test
    void pluginsAreSortedByName() {
        assertEquals(List.of(
                "Consumers hooked to RsLib (2):",
                "- RsFlyBoostLimit v1.2.0",
                "- rsKills v1.1.0"), AdminInfo.plugins(List.of(
                        new AdminInfo.Consumer("rsKills", "1.1.0"),
                        new AdminInfo.Consumer("RsFlyBoostLimit", "1.2.0"))));
        assertEquals(List.of("No consumers are hooked to RsLib."), AdminInfo.plugins(List.of()));
    }

    @Test
    void integrationsListEveryHookWithItsState() {
        assertEquals(List.of(
                "Registered integrations (3):",
                "- RsGifts -> PlaceholderAPI: inactive",
                "- RsKills -> PlaceholderAPI: active",
                "- RsKills -> Vault: inactive"), AdminInfo.integrations(List.of(
                        new AdminInfo.Hook("RsKills", "Vault", false),
                        new AdminInfo.Hook("RsKills", "PlaceholderAPI", true),
                        new AdminInfo.Hook("RsGifts", "PlaceholderAPI", false))));
        assertEquals(List.of("Registered integrations (0):"), AdminInfo.integrations(List.of()));
    }
}
