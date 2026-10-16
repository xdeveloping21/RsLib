package com.rs.lib.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import com.rs.lib.db.internal.MainHops;
import com.rs.lib.economy.EconomyBridgeTest.FakeHost;
import com.rs.lib.economy.EconomyBridgeTest.FakeLedger;

class EconomyTransferTest {

    /** One balance per player, in memory, with deposits that can be refused per player. */
    static final class Accounts implements EconomyBridge.Backend {

        final Map<UUID, Double> money = new HashMap<>();
        final Set<UUID> refuseDeposits = new HashSet<>();
        final List<String> calls = new ArrayList<>();
        boolean offline = true;
        /** When set, takes complete only when the test completes this future. */
        CompletableFuture<Boolean> pendingTake;

        double of(OfflinePlayer player) {
            return money.getOrDefault(player.getUniqueId(), 0.0);
        }

        @Override
        public double getBalance(OfflinePlayer player) {
            calls.add("balance " + player.getName());
            return of(player);
        }

        @Override
        public CompletableFuture<Boolean> give(OfflinePlayer player, double amount) {
            calls.add("give " + player.getName() + " " + amount);
            if (refuseDeposits.contains(player.getUniqueId())) {
                return CompletableFuture.completedFuture(false);
            }
            money.merge(player.getUniqueId(), amount, Double::sum);
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Boolean> tryTake(OfflinePlayer player, double amount) {
            calls.add("take " + player.getName() + " " + amount);
            if (of(player) < amount) {
                return CompletableFuture.completedFuture(false);
            }
            money.merge(player.getUniqueId(), -amount, Double::sum);
            return pendingTake != null ? pendingTake : CompletableFuture.completedFuture(true);
        }

        @Override
        public boolean servesOffline() {
            return offline;
        }
    }

    static OfflinePlayer offline(String name) {
        UUID id = UUID.randomUUID();
        return (OfflinePlayer) Proxy.newProxyInstance(EconomyTransferTest.class.getClassLoader(),
                new Class<?>[] {OfflinePlayer.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> name;
                    case "getPlayer" -> null;
                    case "isOnline" -> false;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private final Player alex = EconomyBridgeTest.player("Alex");
    private final Player steve = EconomyBridgeTest.player("Steve");
    private final OfflinePlayer notch = offline("Notch");

    private static EconomyBridge bridge(FakeHost host, EconomyBridge.Backend backend) {
        EconomyBridge bridge = new EconomyBridge(host);
        bridge.registerBackend("accounts", backend);
        return bridge;
    }

    // ---------------------------------------------------------------- offline players

    @Test
    void vaultServesOfflinePlayers() {
        FakeHost host = new FakeHost();
        FakeLedger ledger = new FakeLedger();
        ledger.money = 30;
        EconomyBridge bridge = new EconomyBridge(host);
        bridge.registerBackend(EconomyBridge.VAULT, new VaultBackend(() -> ledger, host.logger()));
        assertTrue(bridge.servesOffline());
        assertEquals(30, bridge.getBalance(notch));
        assertEquals(Optional.of(BigDecimal.valueOf(30.0)), bridge.balance(notch));
        assertTrue(bridge.tryTake(notch, 10).join());
        assertTrue(bridge.give(notch, 5).join());
        assertEquals(25, ledger.money);
    }

    @Test
    void anUnreadableVaultBalanceIsUnknownNotZero() {
        FakeHost host = new FakeHost();
        FakeLedger ledger = new FakeLedger();
        ledger.money = 30;
        EconomyBridge bridge = new EconomyBridge(host);
        bridge.registerBackend(EconomyBridge.VAULT, new VaultBackend(() -> ledger, host.logger()));
        ledger.failure = new IllegalStateException("database down");
        assertTrue(bridge.balance(notch).isPresent(), "reads do not withdraw");
        VaultBackend broken = new VaultBackend(() -> (VaultBackend.Ledger) Proxy.newProxyInstance(
                EconomyTransferTest.class.getClassLoader(), new Class<?>[] {VaultBackend.Ledger.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "ready" -> true;
                    default -> throw new IllegalStateException("database down");
                }), host.logger());
        bridge.registerBackend(EconomyBridge.VAULT, broken);
        assertEquals(Optional.empty(), bridge.balance(notch));
        assertEquals(0, bridge.getBalance(notch));
    }

    @Test
    void theCommandBackendRefusesOfflinePlayersWithoutRunningAnything() {
        FakeHost host = new FakeHost();
        host.balance = () -> "100";
        EconomyBridge bridge = new EconomyBridge(host);
        bridge.useCommandBackend("eco give %player% %amount%", "eco take %player% %amount%", "%vault_eco_balance%");
        assertFalse(bridge.servesOffline());
        assertFalse(bridge.give(notch, 5).join());
        assertFalse(bridge.tryTake(notch, 5).join());
        assertEquals(Optional.empty(), bridge.balance(notch));
        assertEquals(0, bridge.getBalance(notch));
        assertEquals(TransferOutcome.OFFLINE, bridge.transfer(steve, notch, 5).join());
        assertEquals(TransferOutcome.OFFLINE, bridge.transfer(notch, steve, 5).join());
        assertTrue(host.commands.isEmpty(), host.commands.toString());
        // Online players are served as before.
        assertEquals(Optional.of(BigDecimal.valueOf(100.0)), bridge.balance(steve));
        assertTrue(bridge.give(steve, 5).join());
        assertEquals(List.of("eco give Steve 5"), host.commands);
    }

    @Test
    void aBackendThatDoesNotDeclareOfflineSupportIsNeverCalledForOfflinePlayers() {
        FakeHost host = new FakeHost();
        EconomyBridgeTest.MemoryBackend memory = new EconomyBridgeTest.MemoryBackend(50);
        EconomyBridge bridge = bridge(host, memory);
        assertFalse(bridge.give(notch, 1).join());
        assertFalse(bridge.tryTake(notch, 1).join());
        assertEquals(Optional.empty(), bridge.balance(notch));
        assertEquals(0, memory.calls);
        assertEquals(Optional.of(BigDecimal.valueOf(50.0)), bridge.balance(steve));
    }

    @Test
    void balanceIsEmptyWithoutAnEconomyOrOffTheMainThread() {
        FakeHost host = new FakeHost();
        EconomyBridge bridge = new EconomyBridge(host);
        assertEquals(Optional.empty(), bridge.balance(steve));
        assertFalse(bridge.servesOffline());
        Accounts accounts = new Accounts();
        accounts.money.put(steve.getUniqueId(), 12.5);
        bridge.registerBackend("accounts", accounts);
        assertEquals(Optional.of(new BigDecimal("12.5")), bridge.balance(steve));
        host.primary = false;
        assertEquals(Optional.empty(), bridge.balance(steve));
        assertEquals(Optional.empty(), bridge.balance(null));
    }

    // ---------------------------------------------------------------- transfers

    @Test
    void aTransferTakesFirstThenGives() {
        FakeHost host = new FakeHost();
        Accounts accounts = new Accounts();
        accounts.money.put(steve.getUniqueId(), 100.0);
        EconomyBridge bridge = bridge(host, accounts);
        assertEquals(TransferOutcome.DONE, bridge.transfer(steve, notch, 60).join());
        assertEquals(40, accounts.of(steve));
        assertEquals(60, accounts.of(notch));
        assertEquals(List.of("take Steve 60.0", "give Notch 60.0"), accounts.calls);
        assertTrue(TransferOutcome.DONE.moved());
    }

    @Test
    void aPayerWithoutTheMoneyMovesNothing() {
        FakeHost host = new FakeHost();
        Accounts accounts = new Accounts();
        accounts.money.put(steve.getUniqueId(), 10.0);
        EconomyBridge bridge = bridge(host, accounts);
        TransferOutcome outcome = bridge.transfer(steve, alex, 11).join();
        assertEquals(TransferOutcome.NOT_TAKEN, outcome);
        assertTrue(outcome.nothingMoved());
        assertEquals(List.of("take Steve 11.0"), accounts.calls, "the receiver is never paid first");
        assertEquals(10, accounts.of(steve));
        assertEquals(0, accounts.of(alex));
    }

    @Test
    void aFailedGiveIsRefundedToThePayer() {
        FakeHost host = new FakeHost();
        Accounts accounts = new Accounts();
        accounts.money.put(steve.getUniqueId(), 100.0);
        accounts.refuseDeposits.add(alex.getUniqueId());
        EconomyBridge bridge = bridge(host, accounts);
        TransferOutcome outcome = bridge.transfer(steve, alex, 30).join();
        assertEquals(TransferOutcome.REFUNDED, outcome);
        assertTrue(outcome.nothingMoved());
        assertEquals(100, accounts.of(steve));
        assertEquals(0, accounts.of(alex));
        assertEquals(List.of("take Steve 30.0", "give Alex 30.0", "give Steve 30.0"), accounts.calls);
        assertEquals(1, host.warnings.size(), host.warnings.toString());
    }

    @Test
    void aFailedRefundIsReportedAndLoggedForAManualRefund() {
        FakeHost host = new FakeHost();
        Accounts accounts = new Accounts();
        accounts.money.put(steve.getUniqueId(), 100.0);
        accounts.refuseDeposits.add(alex.getUniqueId());
        accounts.refuseDeposits.add(steve.getUniqueId());
        EconomyBridge bridge = bridge(host, accounts);
        TransferOutcome outcome = bridge.transfer(steve, alex, 30).join();
        assertEquals(TransferOutcome.REFUND_FAILED, outcome);
        assertFalse(outcome.moved());
        assertFalse(outcome.nothingMoved());
        assertEquals(70, accounts.of(steve));
        assertEquals(0, accounts.of(alex), "no money is created");
        String severe = host.warnings.get(host.warnings.size() - 1);
        assertTrue(severe.contains(steve.getUniqueId().toString()) && severe.contains(alex.getUniqueId().toString())
                && severe.contains("30.0"), severe);
    }

    @Test
    void invalidTransfersTouchNoBalance() {
        FakeHost host = new FakeHost();
        Accounts accounts = new Accounts();
        accounts.money.put(steve.getUniqueId(), 100.0);
        EconomyBridge bridge = bridge(host, accounts);
        assertEquals(TransferOutcome.INVALID, bridge.transfer(steve, steve, 5).join());
        assertEquals(TransferOutcome.INVALID, bridge.transfer(null, steve, 5).join());
        assertEquals(TransferOutcome.INVALID, bridge.transfer(steve, null, 5).join());
        for (double bad : new double[] {0, -3, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertEquals(TransferOutcome.INVALID, bridge.transfer(steve, alex, bad).join(), "amount " + bad);
        }
        assertTrue(accounts.calls.isEmpty());
        assertEquals(TransferOutcome.NO_ECONOMY, new EconomyBridge(host).transfer(steve, alex, 5).join());
    }

    @Test
    void anOfflinePartyOnAnOnlineOnlyBackendIsRefusedBeforeTaking() {
        FakeHost host = new FakeHost();
        Accounts accounts = new Accounts();
        accounts.offline = false;
        accounts.money.put(steve.getUniqueId(), 100.0);
        EconomyBridge bridge = bridge(host, accounts);
        assertEquals(TransferOutcome.OFFLINE, bridge.transfer(steve, notch, 5).join());
        assertTrue(accounts.calls.isEmpty());
        assertEquals(TransferOutcome.DONE, bridge.transfer(steve, alex, 5).join());
    }

    @Test
    void theGiveWaitsForAnAsyncTake() {
        FakeHost host = new FakeHost();
        Accounts accounts = new Accounts();
        accounts.money.put(steve.getUniqueId(), 100.0);
        accounts.pendingTake = new CompletableFuture<>();
        EconomyBridge bridge = bridge(host, accounts);
        CompletableFuture<TransferOutcome> outcome = bridge.transfer(steve, alex, 20);
        assertFalse(outcome.isDone());
        assertEquals(List.of("take Steve 20.0"), accounts.calls);
        accounts.pendingTake.complete(true);
        assertEquals(TransferOutcome.DONE, outcome.join());
        assertEquals(20, accounts.of(alex));
    }

    @Test
    void aTransferOffTheMainThreadHopsThereAndADroppedHopMovesNothing() {
        FakeHost host = new FakeHost();
        Accounts accounts = new Accounts();
        accounts.money.put(steve.getUniqueId(), 100.0);
        EconomyBridge bridge = bridge(host, accounts);
        host.primary = false;
        CompletableFuture<TransferOutcome> outcome = bridge.transfer(steve, alex, 10);
        assertFalse(outcome.isDone());
        assertTrue(accounts.calls.isEmpty());
        host.tick();
        assertEquals(TransferOutcome.DONE, outcome.join());
        assertEquals(10, accounts.of(alex));

        CompletableFuture<TransferOutcome> dropped = bridge.transfer(steve, alex, 10);
        MainHops.release(host.owner);
        assertEquals(TransferOutcome.NO_ECONOMY, dropped.join());
        host.tick();
        assertEquals(90, accounts.of(steve), "a released hop does not run later");
    }

    // ---------------------------------------------------------------- BigDecimal

    @Test
    void bigDecimalAmountsMustBeExactlyADouble() {
        assertEquals(0.1, EconomyBridge.toDouble(new BigDecimal("0.10")));
        assertEquals(1500, EconomyBridge.toDouble(BigDecimal.valueOf(1500)));
        assertTrue(Double.isNaN(EconomyBridge.toDouble(new BigDecimal(0.1))));
        assertTrue(Double.isNaN(EconomyBridge.toDouble(new BigDecimal("1e400"))));
        assertTrue(Double.isNaN(EconomyBridge.toDouble(null)));

        FakeHost host = new FakeHost();
        Accounts accounts = new Accounts();
        accounts.money.put(steve.getUniqueId(), 1.0);
        EconomyBridge bridge = bridge(host, accounts);
        assertFalse(bridge.give(steve, new BigDecimal(0.1)).join());
        assertFalse(bridge.tryTake(steve, (BigDecimal) null).join());
        assertTrue(accounts.calls.isEmpty());
        assertTrue(bridge.tryTake(steve, new BigDecimal("0.25")).join());
        assertTrue(bridge.give(notch, new BigDecimal("0.5")).join());
        assertEquals(TransferOutcome.DONE, bridge.transfer(steve, notch, new BigDecimal("0.25")).join());
        assertEquals(0.75, accounts.of(notch));
        assertEquals(TransferOutcome.INVALID, bridge.transfer(steve, notch, new BigDecimal(0.1)).join());
    }
}
