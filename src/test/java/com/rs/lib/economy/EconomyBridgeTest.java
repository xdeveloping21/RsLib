package com.rs.lib.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import com.rs.lib.db.internal.MainHops;

class EconomyBridgeTest {

    /** A host without a server: a main-thread flag, a task queue, a console and a balance placeholder. */
    static final class FakeHost implements EconomyHost {

        final List<String> warnings = new ArrayList<>();
        final List<String> commands = new ArrayList<>();
        final Deque<Runnable> queue = new ArrayDeque<>();
        final Object owner = new Object();
        boolean primary = true;
        boolean refuseSync;
        boolean papi = true;
        /** What the balance placeholder resolves to. */
        Supplier<String> balance = () -> "0";
        /** Runs when a console command is dispatched. */
        java.util.function.Consumer<String> console = c -> { };
        private final Logger logger = Logger.getAnonymousLogger();

        FakeHost() {
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
        }

        void tick() {
            boolean was = primary;
            primary = true;
            while (!queue.isEmpty()) {
                queue.poll().run();
            }
            primary = was;
        }

        @Override
        public Logger logger() {
            return logger;
        }

        @Override
        public Object owner() {
            return owner;
        }

        @Override
        public boolean isPrimaryThread() {
            return primary;
        }

        @Override
        public void sync(Runnable task) {
            if (refuseSync) {
                throw new IllegalStateException("disabled");
            }
            queue.add(task);
        }

        @Override
        public void debug(Supplier<String> message) {
        }

        @Override
        public boolean dispatchConsole(String command) {
            commands.add(command);
            console.accept(command);
            return true;
        }

        @Override
        public String placeholders(Player player, String text) {
            return papi ? balance.get() : null;
        }
    }

    /** A backend with a balance in memory. */
    static final class MemoryBackend implements EconomyBridge.Backend {

        double money;
        boolean available = true;
        int calls;

        MemoryBackend(double money) {
            this.money = money;
        }

        @Override
        public double getBalance(OfflinePlayer player) {
            calls++;
            return money;
        }

        @Override
        public CompletableFuture<Boolean> give(OfflinePlayer player, double amount) {
            calls++;
            money += amount;
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Boolean> tryTake(OfflinePlayer player, double amount) {
            calls++;
            if (money < amount) {
                return CompletableFuture.completedFuture(false);
            }
            money -= amount;
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public boolean available() {
            return available;
        }
    }

    static Player player(String name) {
        UUID id = UUID.randomUUID();
        Object[] self = new Object[1];
        self[0] = Proxy.newProxyInstance(EconomyBridgeTest.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> name;
                    case "getPlayer" -> self[0];
                    case "isOnline" -> true;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (Player) self[0];
    }

    private final Player steve = player("Steve");

    // ---------------------------------------------------------------- selection and validation

    @Test
    void withoutBackendEverythingFailsWithOneWarning() {
        FakeHost host = new FakeHost();
        EconomyBridge bridge = new EconomyBridge(host);
        assertFalse(bridge.available());
        assertEquals(0, bridge.getBalance(steve));
        assertFalse(bridge.give(steve, 10).join());
        assertFalse(bridge.tryTake(steve, 10).join());
        assertEquals(1, host.warnings.size(), host.warnings.toString());
    }

    @Test
    void amountsThatAreNotFiniteOrPositiveAreRefusedBeforeTheBackend() {
        FakeHost host = new FakeHost();
        EconomyBridge bridge = new EconomyBridge(host);
        MemoryBackend backend = new MemoryBackend(100);
        bridge.registerBackend("memory", backend);
        for (double bad : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertFalse(bridge.give(steve, bad).join(), "give " + bad);
            assertFalse(bridge.tryTake(steve, bad).join(), "take " + bad);
        }
        assertEquals(0, backend.calls);
        assertFalse(bridge.give(null, 5).join());
    }

    @Test
    void theFirstAvailableBackendInInsertionOrderServes() {
        FakeHost host = new FakeHost();
        EconomyBridge bridge = new EconomyBridge(host);
        MemoryBackend first = new MemoryBackend(1);
        MemoryBackend second = new MemoryBackend(2);
        bridge.registerBackend("first", first);
        bridge.registerBackend("second", second);
        assertEquals(1, bridge.getBalance(steve));
        first.available = false;
        assertEquals(2, bridge.getBalance(steve));
        first.available = true;
        // Replacing a name keeps its place: "first" still comes before "second".
        bridge.registerBackend("first", new MemoryBackend(3));
        assertEquals(3, bridge.getBalance(steve));
    }

    @Test
    void theFutureCarriesTheRealOutcome() {
        FakeHost host = new FakeHost();
        EconomyBridge bridge = new EconomyBridge(host);
        MemoryBackend backend = new MemoryBackend(50);
        bridge.registerBackend("memory", backend);
        assertFalse(bridge.tryTake(steve, 60).join());
        assertEquals(50, backend.money);
        assertTrue(bridge.tryTake(steve, 50).join());
        assertTrue(bridge.give(steve, 5).join());
        assertEquals(5, backend.money);
    }

    @Test
    void aBackendThatThrowsFailsOrReturnsNullGivesFalse() {
        FakeHost host = new FakeHost();
        EconomyBridge bridge = new EconomyBridge(host);
        AtomicInteger mode = new AtomicInteger();
        bridge.registerBackend("odd", new EconomyBridge.Backend() {
            @Override
            public double getBalance(OfflinePlayer player) {
                throw new IllegalStateException("broken");
            }

            @Override
            public CompletableFuture<Boolean> give(OfflinePlayer player, double amount) {
                return switch (mode.get()) {
                    case 0 -> throw new IllegalStateException("broken");
                    case 1 -> CompletableFuture.failedFuture(new IllegalStateException("async broken"));
                    default -> null;
                };
            }

            @Override
            public CompletableFuture<Boolean> tryTake(OfflinePlayer player, double amount) {
                return CompletableFuture.completedFuture(null);
            }
        });
        assertEquals(0, bridge.getBalance(steve));
        assertFalse(bridge.give(steve, 1).join());
        mode.set(1);
        assertFalse(bridge.give(steve, 1).join());
        mode.set(2);
        assertFalse(bridge.give(steve, 1).join());
        assertFalse(bridge.tryTake(steve, 1).join());
    }

    // ---------------------------------------------------------------- threads

    @Test
    void balanceOffTheMainThreadIsZeroAndWarnsOncePerCallingLine() {
        FakeHost host = new FakeHost();
        EconomyBridge bridge = new EconomyBridge(host);
        bridge.registerBackend("memory", new MemoryBackend(100));
        host.primary = false;
        for (int i = 0; i < 3; i++) {
            assertEquals(0, bridge.getBalance(steve));
        }
        assertEquals(1, host.warnings.size());
        assertTrue(host.warnings.get(0).contains(EconomyBridgeTest.class.getName() + "#"), host.warnings.get(0));
        assertEquals(0, bridge.getBalance(steve));
        assertEquals(2, host.warnings.size(), "another line warns again");
        host.primary = true;
        assertEquals(100, bridge.getBalance(steve));
    }

    @Test
    void transactionsOffTheMainThreadHopThereAndCompleteThere() {
        FakeHost host = new FakeHost();
        EconomyBridge bridge = new EconomyBridge(host);
        MemoryBackend backend = new MemoryBackend(0);
        bridge.registerBackend("memory", backend);
        host.primary = false;
        CompletableFuture<Boolean> given = bridge.give(steve, 7.5);
        assertFalse(given.isDone());
        assertEquals(0, backend.money);
        host.tick();
        assertTrue(given.join());
        assertEquals(7.5, backend.money);
        assertEquals(0, MainHops.pending(host.owner));
    }

    @Test
    void aHopThatCannotBeScheduledOrIsReleasedOnShutdownGivesFalse() {
        FakeHost host = new FakeHost();
        EconomyBridge bridge = new EconomyBridge(host);
        MemoryBackend backend = new MemoryBackend(0);
        bridge.registerBackend("memory", backend);
        host.primary = false;
        host.refuseSync = true;
        assertFalse(bridge.give(steve, 1).join());
        host.refuseSync = false;
        CompletableFuture<Boolean> pending = bridge.give(steve, 1);
        MainHops.release(host.owner);
        assertFalse(pending.join());
        host.tick();
        assertEquals(0, backend.money, "a released hop does not run later");
    }

    // ---------------------------------------------------------------- Vault

    @Test
    void vaultIsNotOnTheTestClassPathAndNothingAlwaysLoadedNeedsIt() throws Exception {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("net.milkbowl.vault.economy.Economy"));
        ClassLoader loader = EconomyBridgeTest.class.getClassLoader();
        for (String name : new String[] {"com.rs.lib.economy.EconomyBridge", "com.rs.lib.economy.EconomyBridge$Backend",
                "com.rs.lib.economy.VaultBackend", "com.rs.lib.economy.VaultBackend$Ledger",
                "com.rs.lib.economy.CommandBackend", "com.rs.lib.economy.EconomyHost$Server"}) {
            Class.forName(name, true, loader);
        }
        FakeHost host = new FakeHost();
        EconomyBridge bridge = new EconomyBridge(host);
        bridge.registerBackend(EconomyBridge.VAULT, new VaultBackend(() -> null, host.logger()));
        assertFalse(bridge.available());
        assertFalse(bridge.give(steve, 1).join());
    }

    /** A Vault economy in memory. */
    static final class FakeLedger implements VaultBackend.Ledger {

        double money;
        boolean ready = true;
        boolean refuse;
        RuntimeException failure;
        final List<String> calls = new ArrayList<>();

        @Override
        public boolean ready() {
            return ready;
        }

        @Override
        public double balance(OfflinePlayer player) {
            calls.add("balance");
            return money;
        }

        @Override
        public boolean has(OfflinePlayer player, double amount) {
            calls.add("has");
            return money >= amount;
        }

        @Override
        public boolean withdraw(OfflinePlayer player, double amount) {
            calls.add("withdraw");
            if (failure != null) {
                throw failure;
            }
            if (refuse) {
                return false;
            }
            money -= amount;
            return true;
        }

        @Override
        public boolean deposit(OfflinePlayer player, double amount) {
            calls.add("deposit");
            if (refuse) {
                return false;
            }
            money += amount;
            return true;
        }
    }

    @Test
    void aProviderRegisteredLateIsUsedOnTheNextCall() {
        FakeHost host = new FakeHost();
        FakeLedger ledger = new FakeLedger();
        FakeLedger[] registered = new FakeLedger[1];
        EconomyBridge bridge = new EconomyBridge(host);
        bridge.registerBackend(EconomyBridge.VAULT, new VaultBackend(() -> registered[0], host.logger()));
        assertFalse(bridge.give(steve, 5).join());
        registered[0] = ledger;
        assertTrue(bridge.available());
        assertTrue(bridge.give(steve, 5).join());
        assertEquals(5, ledger.money);
    }

    @Test
    void vaultTakesCheckTheBalanceFirstAndReportTheProvidersAnswer() {
        FakeHost host = new FakeHost();
        FakeLedger ledger = new FakeLedger();
        ledger.money = 10;
        VaultBackend vault = new VaultBackend(() -> ledger, host.logger());
        assertFalse(vault.tryTake(steve, 20).join());
        assertEquals(List.of("has"), ledger.calls, "no withdrawal without the money");
        assertTrue(vault.tryTake(steve, 4).join());
        assertEquals(6, ledger.money);
        ledger.refuse = true;
        assertFalse(vault.tryTake(steve, 1).join());
        assertFalse(vault.give(steve, 1).join());
        ledger.refuse = false;
        ledger.failure = new IllegalStateException("database down");
        assertFalse(vault.tryTake(steve, 1).join());
        assertEquals(1, host.warnings.size());
        ledger.ready = false;
        assertFalse(vault.available());
    }

    // ---------------------------------------------------------------- commands

    @Test
    void amountsAreWrittenAsPlainDecimals() {
        assertEquals("1500", CommandBackend.plain(1500.0));
        assertEquals("2.5", CommandBackend.plain(2.5));
        assertEquals("0.0001", CommandBackend.plain(0.0001));
        assertEquals("10000000", CommandBackend.plain(1e7));
        assertEquals("100", CommandBackend.plain(100));
    }

    @Test
    void balancesLoseColourCodesAndCurrencySigns() {
        assertEquals(1500.5, CommandBackend.parseBalance("§a$1,500.50"));
        assertEquals(1500, CommandBackend.parseBalance("§e1.5K"));
        assertEquals(20, CommandBackend.parseBalance("€ 20"));
        assertTrue(Double.isNaN(CommandBackend.parseBalance("%vault_eco_balance%")));
        assertTrue(Double.isNaN(CommandBackend.parseBalance("lots")));
    }

    @Test
    void commandTakesSucceedOnlyWhenTheBalanceWentDown() {
        FakeHost host = new FakeHost();
        double[] money = {100};
        host.balance = () -> "$" + money[0];
        host.console = command -> {
            if (command.startsWith("eco take")) {
                money[0] -= Double.parseDouble(command.substring(command.lastIndexOf(' ') + 1));
            }
        };
        EconomyBridge bridge = new EconomyBridge(host);
        bridge.useCommandBackend("/eco give %player% %amount%", "eco take %player% %amount%", "%vault_eco_balance%");
        assertEquals(100, bridge.getBalance(steve));
        assertFalse(bridge.tryTake(steve, 150).join());
        assertTrue(host.commands.isEmpty(), "a short balance runs nothing");
        assertTrue(bridge.tryTake(steve, 40).join());
        assertEquals(List.of("eco take Steve 40"), host.commands);
        // A command that runs but does not move the balance is not a success.
        host.console = command -> { };
        assertFalse(bridge.tryTake(steve, 10).join());
        assertTrue(bridge.give(steve, 2.5).join());
        assertEquals("eco give Steve 2.5", host.commands.get(host.commands.size() - 1));
    }

    @Test
    void aPlaceholderThatIsNotANumberWarnsOnceAndEveryTakeFails() {
        FakeHost host = new FakeHost();
        host.balance = () -> "%vault_eco_balance%";
        EconomyBridge bridge = new EconomyBridge(host);
        bridge.useCommandBackend("eco give %player% %amount%", "eco take %player% %amount%", "%vault_eco_balance%");
        assertFalse(bridge.tryTake(steve, 1).join());
        assertFalse(bridge.tryTake(steve, 1).join());
        assertEquals(0, bridge.getBalance(steve));
        assertEquals(1, host.warnings.size(), host.warnings.toString());
        assertTrue(host.commands.isEmpty());
        host.papi = false;
        assertFalse(bridge.tryTake(steve, 1).join());
        assertEquals(2, host.warnings.size());
    }

    @Test
    void theCallSiteNamesTheCaller() {
        String site = EconomyBridge.callSite();
        assertTrue(site.startsWith(EconomyBridgeTest.class.getName() + "#theCallSiteNamesTheCaller:"), site);
        assertNotEquals("unknown", site);
    }
}
