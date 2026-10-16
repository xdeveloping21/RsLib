package com.rs.lib.reward;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import com.rs.lib.Ph;
import com.rs.lib.action.Requirement;
import com.rs.lib.item.ItemAppearance;
import com.rs.lib.text.RsText;
import com.rs.lib.yml.YmlTestSupport.Captured;

/**
 * Rewards without a server: stacks that are a label and an amount, players whose inventory
 * behaves like Bukkit's ({@code addItem} fills partial stacks, then empty slots, and changes
 * the stacks it is given), and a host whose main thread is a queue.
 */
public final class RewardKit {

    private RewardKit() {
    }

    // ------------------------------------------------------------------ items

    /** A stack that is only a label, an amount and a stack size. */
    public static final class Stack extends ItemStack {
        public final String label;
        private int amount;
        private final int max;

        @SuppressWarnings("deprecation")
        public Stack(String label, int amount, int max) {
            super();
            this.label = label;
            this.amount = amount;
            this.max = max;
        }

        @Override
        public Stack clone() {
            return new Stack(label, amount, max);
        }

        @Override
        public Material getType() {
            return Material.STONE;
        }

        @Override
        public int getAmount() {
            return amount;
        }

        @Override
        public void setAmount(int amount) {
            this.amount = amount;
        }

        @Override
        public int getMaxStackSize() {
            return max;
        }

        @Override
        public boolean isSimilar(ItemStack other) {
            return other instanceof Stack stack && stack.label.equals(label) && stack.max == max;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Stack stack && stack.label.equals(label) && stack.amount == amount;
        }

        @Override
        public int hashCode() {
            return label.hashCode() * 31 + amount;
        }

        @Override
        public String toString() {
            return label + "*" + amount;
        }
    }

    public static Stack stack(String label, int amount) {
        return new Stack(label, amount, 64);
    }

    // ------------------------------------------------------------------ players

    /** A player with a 36-slot inventory, permissions and a record of what it received. */
    public static final class FakePlayer {
        public final UUID id = UUID.randomUUID();
        public final ItemStack[] slots = new ItemStack[36];
        public final List<ItemStack> dropped = new ArrayList<>();
        public final Set<String> permissions = new HashSet<>();
        public final List<Integer> exp = new ArrayList<>();
        public volatile boolean online = true;
        public final Player player;
        public final World world;

        public FakePlayer() {
            this("Steve");
        }

        public FakePlayer(String name) {
            world = (World) Proxy.newProxyInstance(RewardKit.class.getClassLoader(), new Class<?>[] {World.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "dropItem", "dropItemNaturally" -> {
                            dropped.add((ItemStack) args[1]);
                            yield null;
                        }
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "getName" -> "world";
                        default -> throw new UnsupportedOperationException("world." + method.getName());
                    });
            PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(RewardKit.class.getClassLoader(),
                    new Class<?>[] {PlayerInventory.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getStorageContents" -> slots.clone();
                        case "setStorageContents" -> {
                            ItemStack[] items = (ItemStack[]) args[0];
                            for (int i = 0; i < slots.length; i++) {
                                slots[i] = i < items.length ? items[i] : null;
                            }
                            yield null;
                        }
                        case "addItem" -> add((ItemStack[]) args[0]);
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException("inventory." + method.getName());
                    });
            Location feet = new Location(world, 1, 64, 1);
            player = (Player) Proxy.newProxyInstance(RewardKit.class.getClassLoader(), new Class<?>[] {Player.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> id;
                        case "getName" -> name;
                        case "isOnline" -> online;
                        case "hasPermission" -> permissions.contains((String) args[0]);
                        case "getInventory" -> inventory;
                        case "getLocation" -> feet.clone();
                        case "giveExp" -> {
                            exp.add((Integer) args[0]);
                            yield null;
                        }
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "FakePlayer[" + name + "]";
                        default -> throw new UnsupportedOperationException("player." + method.getName());
                    });
        }

        /**
         * Like CraftBukkit: fills similar partial stacks, then empty slots, changing the slot
         * stacks in place and the amounts of the given stacks; what does not fit comes back.
         */
        private Map<Integer, ItemStack> add(ItemStack[] items) {
            Map<Integer, ItemStack> leftover = new HashMap<>();
            for (int i = 0; i < items.length; i++) {
                ItemStack item = items[i];
                for (int slot = 0; slot < slots.length && item.getAmount() > 0; slot++) {
                    ItemStack there = slots[slot];
                    if (there != null && there.isSimilar(item) && there.getAmount() < there.getMaxStackSize()) {
                        int moved = Math.min(item.getAmount(), there.getMaxStackSize() - there.getAmount());
                        there.setAmount(there.getAmount() + moved);
                        item.setAmount(item.getAmount() - moved);
                    }
                }
                for (int slot = 0; slot < slots.length && item.getAmount() > 0; slot++) {
                    if (slots[slot] == null) {
                        ItemStack placed = item.clone();
                        placed.setAmount(Math.min(item.getAmount(), item.getMaxStackSize()));
                        slots[slot] = placed;
                        item.setAmount(item.getAmount() - placed.getAmount());
                    }
                }
                if (item.getAmount() > 0) {
                    leftover.put(i, item);
                }
            }
            return leftover;
        }

        /** Fills every free slot with junk, leaving {@code free} empty. */
        public void fill(int free) {
            for (int i = 0; i < slots.length - free; i++) {
                slots[i] = stack("junk", 64);
            }
        }

        /** How many items labelled {@code label} the inventory holds. */
        public int count(String label) {
            int count = 0;
            for (ItemStack item : slots) {
                if (item instanceof Stack stack && stack.label.equals(label)) {
                    count += stack.getAmount();
                }
            }
            return count;
        }

        /** How many items labelled {@code label} were dropped. */
        public int droppedCount(String label) {
            int count = 0;
            for (ItemStack item : dropped) {
                if (item instanceof Stack stack && stack.label.equals(label)) {
                    count += stack.getAmount();
                }
            }
            return count;
        }
    }

    // ------------------------------------------------------------------ host

    /** A plugin without a server; its main thread is a queue the test runs. */
    static final class FakeHost implements RewardsHost {
        final Captured log = new Captured();
        final Object owner = new Object();
        final Map<UUID, Player> players = new HashMap<>();
        final List<String> commands = new CopyOnWriteArrayList<>();
        final List<String> messages = new CopyOnWriteArrayList<>();
        final List<String> potions = new CopyOnWriteArrayList<>();
        final List<String> sent = new CopyOnWriteArrayList<>();
        final List<Double> money = new CopyOnWriteArrayList<>();
        volatile boolean moneyWorks = true;
        volatile boolean commandsWork = true;
        volatile boolean shuttingDown;
        volatile boolean refuseSchedule;
        private final BlockingQueue<Runnable> main = new LinkedBlockingQueue<>();
        private volatile Thread mainThread;

        void join(FakePlayer player) {
            players.put(player.id, player.player);
            player.online = true;
        }

        void quit(FakePlayer player) {
            players.remove(player.id);
            player.online = false;
        }

        @Override
        public Logger logger() {
            return log.logger;
        }

        @Override
        public Object owner() {
            return owner;
        }

        @Override
        public boolean shuttingDown() {
            return shuttingDown;
        }

        @Override
        public boolean ownsHere(Player player) {
            return Thread.currentThread() == mainThread;
        }

        @Override
        public void runFor(Player player, Runnable task, Runnable retired) {
            if (refuseSchedule) {
                throw new IllegalStateException("Plugin attempted to register task while disabled");
            }
            main.add(task);
        }

        @Override
        public Player online(UUID id) {
            return players.get(id);
        }

        @Override
        public boolean test(Requirement requirement, Player player, Ph[] phs) {
            return requirement.test(player, operand -> RsText.applyLocals(operand, phs));
        }

        @Override
        public String resolve(Player player, String raw, Ph[] phs, boolean text) {
            String s = RsText.applyLocals(raw, phs);
            return text ? s : RsText.unwrapLiterals(s);
        }

        @Override
        public ItemStack render(ItemAppearance appearance, Player player, Ph[] phs) {
            throw new UnsupportedOperationException("appearances need a server");
        }

        @Override
        public String encode(ItemStack item) {
            if (item instanceof Stack stack) {
                return stack.label + "*" + stack.getAmount() + "*" + stack.getMaxStackSize();
            }
            throw new IllegalArgumentException("not a test stack");
        }

        @Override
        public ItemStack decode(String text) {
            String[] parts = text.split("\\*");
            if (text.startsWith("BAD") || parts.length != 3) {
                throw new IllegalArgumentException("unreadable item " + text);
            }
            return new Stack(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        }

        @Override
        public CompletableFuture<Boolean> money(Player player, double amount) {
            if (moneyWorks) {
                money.add(amount);
            }
            return CompletableFuture.completedFuture(moneyWorks);
        }

        @Override
        public boolean console(String command) {
            commands.add(command);
            return commandsWork;
        }

        @Override
        public void message(Player player, String text) {
            messages.add(text);
        }

        @Override
        public boolean potion(Player player, String effect, int ticks, int amplifier) {
            if (effect.equals("nope")) {
                return false;
            }
            potions.add(effect + " " + ticks + " " + amplifier);
            return true;
        }

        @Override
        public void send(Player player, String key, Ph... phs) {
            StringBuilder line = new StringBuilder(key);
            for (Ph ph : phs) {
                line.append(' ').append(ph.key()).append('=').append(ph.value());
            }
            sent.add(line.toString());
        }

        /** Runs {@code action} as if on the main thread. */
        void asMain(Runnable action) {
            Thread previous = mainThread;
            mainThread = Thread.currentThread();
            try {
                action.run();
            } finally {
                mainThread = previous;
            }
        }

        /** Tasks waiting for the main thread. */
        int queued() {
            return main.size();
        }

        /** Runs main-thread tasks as they arrive until {@code done} holds; fails after five seconds. */
        void runMainUntil(BooleanSupplier done) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            asMain(() -> {
                while (!done.getAsBoolean()) {
                    if (System.nanoTime() > deadline) {
                        throw new AssertionError("Timed out waiting on the main thread");
                    }
                    try {
                        Runnable task = main.poll(10, TimeUnit.MILLISECONDS);
                        if (task != null) {
                            task.run();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }
            });
        }

        /** Waits for {@code future} on the main thread, running its tasks, and returns its value. */
        <T> T await(CompletableFuture<T> future) {
            runMainUntil(future::isDone);
            return future.join();
        }
    }
}
