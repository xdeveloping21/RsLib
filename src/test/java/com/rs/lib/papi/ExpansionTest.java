package com.rs.lib.papi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;

/** How an expansion answers PlaceholderAPI: the names are a contract with server configs. */
class ExpansionTest {

    private final FakePapiHost host = new FakePapiHost();
    private final OfflinePlayer steve = FakePapiHost.player("Steve");

    private ExpansionBuilder builder(String identifier) {
        return new RsPapi(host).expansion(identifier);
    }

    @Test
    void namesMatchIgnoringCase() {
        PapiHolder holder = builder("rskills")
                .placeholder("Kills", p -> "12")
                .global("online", () -> "3")
                .build();
        assertEquals("12", holder.resolve(steve, "kills"));
        assertEquals("12", holder.resolve(steve, "KILLS"));
        assertEquals("3", holder.resolve(steve, "OnLiNe"));
        assertNull(holder.resolve(steve, "deaths"));
        assertNull(holder.resolve(steve, null));
    }

    @Test
    void exactNameWinsOverAPrefixDeclaredFirst() {
        PapiHolder holder = builder("top")
                .prefixed("name", (p, rest) -> "prefix:" + rest)
                .placeholder("name_1", p -> "exact")
                .build();
        assertEquals("exact", holder.resolve(steve, "name_1"));
        assertEquals("exact", holder.resolve(steve, "NAME_1"));
        assertEquals("prefix:_2", holder.resolve(steve, "name_2"));
    }

    @Test
    void prefixesAreTriedInDeclarationOrder() {
        PapiHolder holder = builder("top")
                .prefixed("top_", (p, rest) -> "short:" + rest)
                .prefixed("top_name_", (p, rest) -> "long:" + rest)
                .build();
        assertEquals("short:name_1", holder.resolve(steve, "top_name_1"));

        PapiHolder reversed = builder("top")
                .prefixed("top_name_", (p, rest) -> "long:" + rest)
                .prefixed("top_", (p, rest) -> "short:" + rest)
                .build();
        assertEquals("long:1", reversed.resolve(steve, "top_name_1"));
        assertEquals("short:kills_1", reversed.resolve(steve, "top_kills_1"));
    }

    @Test
    void theRestKeepsItsCapitals() {
        List<String> seen = new ArrayList<>();
        PapiHolder holder = builder("eco")
                .prefixed("Balance_", (p, rest) -> {
                    seen.add(rest);
                    return "1";
                })
                .globalPrefixed("format_", rest -> rest)
                .build();
        holder.resolve(steve, "BALANCE_SteveX");
        holder.resolve(steve, "balance_");
        assertEquals(List.of("SteveX", ""), seen);
        assertEquals("MiXeD", holder.resolve(null, "FORMAT_MiXeD"));
    }

    @Test
    void playerPlaceholdersNeedAPlayerAndGlobalOnesDoNot() {
        PapiHolder holder = builder("rs")
                .placeholder("name", OfflinePlayer::getName)
                .prefixed("p_", (p, rest) -> p.getName() + rest)
                .global("motd", () -> "hi")
                .globalPrefixed("g_", rest -> "g" + rest)
                .build();
        assertNull(holder.resolve(null, "name"));
        assertNull(holder.resolve(null, "p_x"));
        assertEquals("Steve", holder.resolve(steve, "name"));
        assertEquals("Stevex", holder.resolve(steve, "p_x"));
        assertEquals("hi", holder.resolve(null, "motd"));
        assertEquals("gx", holder.resolve(null, "g_x"));
    }

    @Test
    void aPlayerPrefixWithoutPlayerDoesNotFallThroughToALaterPrefix() {
        PapiHolder holder = builder("rs")
                .prefixed("a", (p, rest) -> "player")
                .globalPrefixed("ab", rest -> "global")
                .build();
        assertNull(holder.resolve(null, "abc"));
    }

    @Test
    void aFailingResolverLeavesTheTokenAndWarnsWithoutFlooding() {
        PapiHolder holder = builder("rs")
                .placeholder("boom", p -> {
                    throw new IllegalStateException("broken");
                })
                .placeholder("fine", p -> "ok")
                .build();
        assertNull(holder.resolve(steve, "boom"));
        assertNull(holder.resolve(steve, "BOOM"));
        assertEquals("ok", holder.resolve(steve, "fine"));
        List<LogRecord> warnings = host.log.records.stream().filter(r -> r.getLevel() == Level.WARNING).toList();
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).getMessage().contains("%rs_boom%"), warnings.get(0).getMessage());
        assertNotNull(warnings.get(0).getThrown());
    }

    @Test
    void laterFailuresAreSummarisedOncePerInterval() {
        PapiHolder.Entry entry = new PapiHolder.Entry(false, (p, r) -> null);
        assertEquals("", entry.failureNote(0));
        assertNull(entry.failureNote(1));
        assertNull(entry.failureNote(PapiHolder.WARN_INTERVAL_NANOS - 1));
        assertEquals(" (again; 2 more failures not logged)", entry.failureNote(PapiHolder.WARN_INTERVAL_NANOS));
        assertEquals(" (again)", entry.failureNote(3 * PapiHolder.WARN_INTERVAL_NANOS));
    }

    @Test
    void declaringTwiceKeepsTheLastResolverAndThePrefixPosition() {
        PapiHolder holder = builder("rs")
                .placeholder("a", p -> "1")
                .placeholder("A", p -> "2")
                .prefixed("x", (p, rest) -> "first")
                .prefixed("xy", (p, rest) -> "second")
                .prefixed("X", (p, rest) -> "replaced")
                .build();
        assertEquals("2", holder.resolve(steve, "a"));
        assertEquals("replaced", holder.resolve(steve, "xyz"));
    }

    @Test
    void metadataDefaultsToThePluginAndPersists() {
        PapiHolder holder = builder(" RsKills ").build();
        assertEquals("RsKills", holder.identifier());
        assertEquals("rskills", holder.key());
        assertEquals("Raul", holder.author());
        assertEquals("1.2.3", holder.version());
        assertTrue(holder.persist());

        PapiHolder custom = builder("rs").author("Someone").version("9").build();
        assertEquals("Someone", custom.author());
        assertEquals("9", custom.version());
    }

    @Test
    void theHolderIsASnapshot() {
        ExpansionBuilder builder = builder("rs").global("a", () -> "1");
        PapiHolder holder = builder.build();
        builder.global("b", () -> "2");
        assertNull(holder.resolve(null, "b"));
        assertEquals("2", builder.build().resolve(null, "b"));
    }

    @Test
    void badArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> builder(" "));
        assertThrows(IllegalArgumentException.class, () -> builder(null));
        ExpansionBuilder builder = builder("rs");
        assertThrows(NullPointerException.class, () -> builder.placeholder(null, p -> ""));
        assertThrows(NullPointerException.class, () -> builder.global("x", null));
    }

    @Test
    void identifiersPlaceholderApiCannotReadAreWarnedAbout() {
        assertTrue(builder("my_kills").global("a", () -> "1").register());
        assertFalse(host.log.warnings().isEmpty());
        assertTrue(host.log.warnings().get(0).contains("my_kills"));
    }
}
