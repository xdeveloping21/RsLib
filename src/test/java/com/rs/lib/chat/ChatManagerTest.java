package com.rs.lib.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.EventExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;

/** Interceptors and audience rules on the server's one chat event, without a server. */
class ChatManagerTest {

    private static final Listener OWNER = new Listener() {
    };

    private final List<String> warnings = new ArrayList<>();
    private final ChatManager first = new ChatManager("First", logger());
    private final ChatManager second = new ChatManager("Second", logger());

    private record Registered(Class<? extends Event> type, EventPriority priority, boolean ignoreCancelled,
                              EventExecutor executor) {
    }

    @AfterEach
    void reset() {
        ChatManager.reset();
    }

    private Logger logger() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }

    /** A player with an id, a name and the permissions in {@code perms}. */
    static Player player(String name, String... perms) {
        UUID id = UUID.randomUUID();
        Set<String> granted = Set.of(perms);
        return (Player) Proxy.newProxyInstance(ChatManagerTest.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> name;
                    case "hasPermission" -> args[0] instanceof String perm && granted.contains(perm);
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static AsyncChatEvent paper(Player speaker, String text, Set<Audience> viewers) {
        return new AsyncChatEvent(true, speaker, viewers, null, Component.text(text), Component.text(text), null);
    }

    private static List<Registered> registered() {
        List<Registered> list = new ArrayList<>();
        ChatManager.listen((type, priority, ignoreCancelled, executor) ->
                list.add(new Registered(type, priority, ignoreCancelled, executor)));
        return list;
    }

    private static Registered at(List<Registered> list, EventPriority priority) {
        return list.stream().filter(r -> r.priority() == priority).findFirst().orElseThrow();
    }

    @Test
    void onlyPapersChatEventIsHeardWhenItExists() {
        assertTrue(ChatEvents.paperChat(), "the test server API has AsyncChatEvent");
        List<Registered> list = registered();
        assertEquals(2, list.size());
        for (Registered each : list) {
            assertEquals(AsyncChatEvent.class, each.type(), "never the legacy event: it turns on Paper's legacy path");
            assertTrue(each.ignoreCancelled());
        }
        assertEquals(List.of(EventPriority.LOWEST, EventPriority.HIGHEST), list.stream().map(Registered::priority).toList());
    }

    @Test
    @SuppressWarnings("deprecation")
    void withoutPapersEventTheLegacyOneIsUsed() throws Exception {
        List<Registered> list = new ArrayList<>();
        ChatEvents.listen((type, priority, ignoreCancelled, executor) ->
                list.add(new Registered(type, priority, ignoreCancelled, executor)), false, EventPriority.LOWEST, true,
                ChatManager::takeOut);
        assertEquals(1, list.size());
        assertEquals(AsyncPlayerChatEvent.class, list.get(0).type());

        Player alex = player("Alex");
        first.intercept((player, text) -> text.equals("staff"));
        AsyncPlayerChatEvent legacy = new AsyncPlayerChatEvent(true, alex, "staff", new HashSet<>());
        list.get(0).executor().execute(OWNER, legacy);
        assertTrue(legacy.isCancelled());
    }

    @Test
    void anInterceptorTakesAMessageOnceAndTheRestAreNotAsked() throws Exception {
        Registered lowest = at(registered(), EventPriority.LOWEST);
        List<String> seen = new ArrayList<>();
        first.intercept((player, text) -> {
            seen.add("first:" + player.getName() + ":" + text);
            return text.startsWith("#");
        });
        second.intercept((player, text) -> {
            seen.add("second:" + text);
            return true;
        });
        Player alex = player("Alex");

        AsyncChatEvent staff = paper(alex, "# <red>&chi {player}", new HashSet<>());
        lowest.executor().execute(OWNER, staff);
        assertTrue(staff.isCancelled());
        assertEquals(List.of("first:Alex:# <red>&chi {player}"), seen, "the literal text, to one interceptor only");

        seen.clear();
        AsyncChatEvent other = paper(alex, "hello", new HashSet<>());
        lowest.executor().execute(OWNER, other);
        assertEquals(List.of("first:Alex:hello", "second:hello"), seen, "in registration order");
        assertTrue(other.isCancelled());

        seen.clear();
        AsyncChatEvent muted = paper(alex, "#muted", new HashSet<>());
        muted.setCancelled(true);
        ChatManager.takeOut(new PaperLine(muted));
        assertEquals(List.of(), seen, "a message already cancelled is not offered");
    }

    @Test
    void aBrokenInterceptorIsSkippedAndReportedOnce() {
        first.intercept((player, text) -> {
            throw new IllegalStateException("boom");
        });
        List<String> taken = new ArrayList<>();
        second.intercept((player, text) -> taken.add(text));
        Player alex = player("Alex");
        for (int i = 0; i < 2; i++) {
            AsyncChatEvent event = paper(alex, "line" + i, new HashSet<>());
            ChatManager.takeOut(new PaperLine(event));
            assertTrue(event.isCancelled());
        }
        assertEquals(List.of("line0", "line1"), taken);
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("First"));
    }

    @Test
    void closedHooksAndShutDownPluginsAreNotAsked() {
        List<String> calls = new ArrayList<>();
        ChatHook hook = first.intercept((player, text) -> calls.add("first"));
        second.intercept((player, text) -> {
            calls.add("second");
            return false;
        });
        hook.close();
        hook.close();
        assertFalse(hook.isOpen());
        ChatManager.takeOut(new PaperLine(paper(player("Alex"), "x", new HashSet<>())));
        assertEquals(List.of("second"), calls);

        second.shutdown();
        calls.clear();
        ChatManager.takeOut(new PaperLine(paper(player("Alex"), "x", new HashSet<>())));
        assertEquals(List.of(), calls);
        assertThrows(IllegalStateException.class, () -> second.rule((reader, speaker) -> true));
    }

    @Test
    void rulesCombineWithAndAndKeepTheSpeakerAndTheConsole() throws Exception {
        Registered highest = at(registered(), EventPriority.HIGHEST);
        Player speaker = player("Speaker");
        Player friend = player("Friend");
        Player stranger = player("Stranger");
        Player enemy = player("Enemy");
        Audience console = Audience.empty();
        first.rule((reader, from) -> reader != stranger);
        second.rule((reader, from) -> reader != enemy);
        List<String> askedAbout = new ArrayList<>();
        second.rule((reader, from) -> askedAbout.add(reader.getName()));

        Set<Audience> viewers = new LinkedHashSet<>(List.of(speaker, friend, stranger, enemy, console));
        AsyncChatEvent event = paper(speaker, "hi", viewers);
        highest.executor().execute(OWNER, event);

        assertEquals(Set.of(speaker, friend, console), event.viewers(), "one refusal from any plugin removes a reader");
        assertFalse(event.isCancelled(), "nothing is cancelled");
        assertFalse(askedAbout.contains("Speaker"), "the speaker always reads themselves");
        assertTrue(ChatManager.canRead(friend, speaker));
        assertFalse(ChatManager.canRead(stranger, speaker));
        assertFalse(ChatManager.canRead(enemy, speaker));
    }

    @Test
    void theBypassPermissionIsAboveEveryRule() {
        Player staff = player("Staff", ChatManager.BYPASS_PERMISSION);
        Player player = player("Player");
        Player other = player("Other");
        first.rule((reader, speaker) -> false);

        AsyncChatEvent fromPlayer = paper(player, "hi", new LinkedHashSet<>(List.of(player, staff, other)));
        ChatManager.trim(new PaperLine(fromPlayer));
        assertEquals(Set.of(player, staff), fromPlayer.viewers(), "staff read every message");

        AsyncChatEvent fromStaff = paper(staff, "hi", new LinkedHashSet<>(List.of(staff, player, other)));
        ChatManager.trim(new PaperLine(fromStaff));
        assertEquals(Set.of(staff, player, other), fromStaff.viewers(), "and everyone reads staff");
    }

    @Test
    @SuppressWarnings("deprecation")
    void brokenRulesAndUnmodifiableAudiencesFailOpen() {
        Player speaker = player("Speaker");
        Player reader = player("Reader");
        first.rule((r, s) -> {
            throw new IllegalStateException("boom");
        });
        AsyncChatEvent event = paper(speaker, "hi", new LinkedHashSet<>(List.of(speaker, reader)));
        ChatManager.trim(new PaperLine(event));
        assertEquals(Set.of(speaker, reader), event.viewers(), "a rule that throws has no say");

        second.rule((r, s) -> false);
        AsyncPlayerChatEvent legacy = new AsyncPlayerChatEvent(true, speaker, "hi",
                Collections.unmodifiableSet(new HashSet<>(List.of(speaker, reader))));
        ChatManager.trim(new LegacyLine(legacy));
        assertEquals(2, legacy.getRecipients().size());

        AsyncPlayerChatEvent open = new AsyncPlayerChatEvent(true, speaker, "hi", new HashSet<>(List.of(speaker, reader)));
        ChatManager.trim(new LegacyLine(open));
        assertEquals(Set.of(speaker), open.getRecipients());
    }

    @Test
    void withoutRulesNothingIsAsked() {
        Player speaker = player("Speaker");
        Set<Audience> viewers = new HashSet<>(List.of(speaker, player("Reader")));
        AsyncChatEvent event = paper(speaker, "hi", viewers);
        ChatManager.trim(new PaperLine(event));
        assertEquals(2, event.viewers().size());
    }
}
