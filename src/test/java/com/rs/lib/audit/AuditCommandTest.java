package com.rs.lib.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.rs.lib.internal.LibMessages;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

class AuditCommandTest {

    private static final long WHEN = 1_760_620_980_000L; // 2025-10-16 13:23 UTC

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static AuditEntry entry(String details) {
        return new AuditEntry(1, "Market", "market-buy", UUID.randomUUID(), "Steve", UUID.randomUUID(), "Alex",
                "world", 10.7, 64, -3.2, new BigDecimal("4500"), "16x Diamond", details, WHEN);
    }

    @Test
    void anEntryIsOneLineWithEverythingItHolds() {
        assertEquals("2025-10-16 13:23 Market market-buy: Steve -> Alex | 4,500 | 16x Diamond | listing #4 | world 10, 64, -4",
                plain(AuditCommand.line(entry("listing #4"), ZoneOffset.UTC)));
        AuditEntry bare = new AuditEntry(2, "Crates", "reload", null, null, null, null, null, 0, 0, 0, null, null, null, WHEN);
        assertEquals("2025-10-16 13:23 Crates reload: -", plain(AuditCommand.line(bare, ZoneOffset.UTC)));
    }

    /** Trap: details and names are whatever players typed; they must never become formatting or clicks. */
    @Test
    void playerTextIsShownLiterally() {
        String typed = "&cRed <click:run_command:'/op me'>x</click> %player%";
        Component line = AuditCommand.line(entry(typed), ZoneOffset.UTC);
        assertEquals(true, plain(line).contains(typed));
        for (Component child : line.children()) {
            assertNull(child.clickEvent());
        }
    }

    @Test
    void aPageHasAHeaderAndAClickableNextPage() {
        AuditPage page = new AuditPage(List.of(entry("a"), entry("b")), 5, 1, 2);
        List<Component> lines = AuditCommand.lines(page, "Steve", null, "/rslib logs Steve * 2", ZoneOffset.UTC);
        assertEquals(4, lines.size());
        assertEquals("Audit log of Steve in every plugin: 5 entries, page 1 of 3", plain(lines.get(0)));
        Component next = lines.get(3);
        assertEquals("Next page: /rslib logs Steve * 2", plain(next));
        assertEquals(ClickEvent.runCommand("/rslib logs Steve * 2"), next.clickEvent());

        AuditPage last = new AuditPage(List.of(entry("e")), 5, 3, 2);
        assertEquals(2, AuditCommand.lines(last, "Steve", "Market", "/x", ZoneOffset.UTC).size(), "no next page on the last");
    }

    @Test
    void inSpanishWhenRsLibIsInSpanish() {
        LibMessages.use("es");
        try {
            AuditPage page = new AuditPage(List.of(entry("a"), entry("b")), 5, 1, 2);
            List<Component> lines = AuditCommand.lines(page, null, null, "/rslib logs * * 2", ZoneOffset.UTC);
            assertEquals("Registro de auditoría de todos en todos los plugins: 5 entradas, página 1 de 3",
                    plain(lines.get(0)));
            assertEquals("Página siguiente: /rslib logs * * 2", plain(lines.get(3)));
            assertEquals("No hay entradas de auditoría de Steve en Market.", plain(AuditCommand.lines(
                    new AuditPage(List.of(), 0, 1, 10), "Steve", "Market", "/x", ZoneOffset.UTC).get(0)));
            assertEquals("La página 4 está vacía: hay 2 página(s).", plain(AuditCommand.lines(
                    new AuditPage(List.of(), 15, 4, 10), "Steve", null, "/x", ZoneOffset.UTC).get(0)));
        } finally {
            LibMessages.reset();
        }
    }

    @Test
    void emptyResultsSaySo() {
        assertEquals("No audit log entries for everybody in Market.", plain(AuditCommand.lines(
                new AuditPage(List.of(), 0, 1, 10), null, "Market", "/x", ZoneOffset.UTC).get(0)));
        assertEquals("Page 4 is empty: there are 2 page(s).", plain(AuditCommand.lines(
                new AuditPage(List.of(), 15, 4, 10), "Steve", null, "/x", ZoneOffset.UTC).get(0)));
    }
}
