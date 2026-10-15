package com.rs.lib.snapshot;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;

/**
 * Snapshots without a server: stacks that are only a label and an amount, a codec that writes
 * them as {@code label*amount}, players made of proxies and a host whose threads are queues.
 */
final class SnapKit {

    private SnapKit() {
    }

    // ------------------------------------------------------------------ items

    /** A stack that is only a label and an amount. */
    static final class Stack extends ItemStack {
        final String label;
        final int amount;

        @SuppressWarnings("deprecation")
        Stack(String label, int amount) {
            super();
            this.label = label;
            this.amount = amount;
        }

        @Override
        public Stack clone() {
            return new Stack(label, amount);
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
        public int getMaxStackSize() {
            return 64;
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

    static Stack stack(String label, int amount) {
        return new Stack(label, amount);
    }

    /** Items as {@code label*amount}; {@code BAD...} cannot be read; effects are never readable. */
    static final SnapshotCodec CODEC = new SnapshotCodec() {

        @Override
        public String encodeItem(ItemStack item) {
            if (item instanceof Stack stack) {
                if (stack.label.startsWith("UNWRITABLE")) {
                    throw new IllegalStateException("cannot write " + stack.label);
                }
                return stack.label + "*" + stack.amount;
            }
            throw new IllegalArgumentException("not a test stack");
        }

        @Override
        public ItemStack decodeItem(String text) {
            int star = text.lastIndexOf('*');
            if (text.startsWith("BAD") || star <= 0) {
                throw new IllegalArgumentException("unreadable item " + text);
            }
            return new Stack(text.substring(0, star), Integer.parseInt(text.substring(star + 1)));
        }

        @Override
        public Map<String, Object> encodeEffect(PotionEffect effect) {
            throw new UnsupportedOperationException("effects need a server");
        }

        @Override
        public PotionEffect decodeEffect(Map<String, Object> map) {
            throw new IllegalArgumentException("No potion effect type " + map.get("effect"));
        }
    };

    // ------------------------------------------------------------------ players

    /** A player whose state lives in plain fields. */
    static final class FakePlayer {
        final UUID id = UUID.randomUUID();
        final ItemStack[] slots;
        final ItemStack[] ender;
        ItemStack cursor;
        int held;
        double health = 20;
        double maxHealth = 20;
        int food = 20;
        float saturation = 5;
        float exhaustion;
        int air = 300;
        int maxAir = 300;
        int fireTicks;
        int level;
        float exp;
        int total;
        GameMode mode = GameMode.SURVIVAL;
        boolean allowFlight;
        boolean flying;
        boolean dead;
        int closes;
        final List<String> calls = new ArrayList<>();
        final Player player;

        FakePlayer() {
            this(41, 27);
        }

        FakePlayer(int size, int enderSize) {
            slots = new ItemStack[size];
            ender = new ItemStack[enderSize];
            PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(SnapKit.class.getClassLoader(),
                    new Class<?>[] {PlayerInventory.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getContents" -> slots.clone();
                        case "setContents" -> {
                            set(slots, (ItemStack[]) args[0]);
                            yield null;
                        }
                        case "getSize" -> slots.length;
                        case "getHeldItemSlot" -> held;
                        case "setHeldItemSlot" -> {
                            int slot = (Integer) args[0];
                            if (slot < 0 || slot > 8) {
                                throw new IllegalArgumentException("slot " + slot);
                            }
                            held = slot;
                            yield null;
                        }
                        case "addItem" -> add((ItemStack[]) args[0]);
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException("inventory." + method.getName());
                    });
            Inventory enderChest = (Inventory) Proxy.newProxyInstance(SnapKit.class.getClassLoader(),
                    new Class<?>[] {Inventory.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getContents" -> ender.clone();
                        case "setContents" -> {
                            set(ender, (ItemStack[]) args[0]);
                            yield null;
                        }
                        case "getSize" -> ender.length;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException("ender." + method.getName());
                    });
            player = (Player) Proxy.newProxyInstance(SnapKit.class.getClassLoader(), new Class<?>[] {Player.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.startsWith("set") || name.equals("closeInventory")) {
                            calls.add(name);
                        }
                        return switch (name) {
                            case "getUniqueId" -> id;
                            case "getName" -> "Steve";
                            case "isDead" -> dead;
                            case "closeInventory" -> {
                                closes++;
                                yield null;
                            }
                            case "getInventory" -> inventory;
                            case "getEnderChest" -> enderChest;
                            case "getItemOnCursor" -> cursor;
                            case "setItemOnCursor" -> {
                                cursor = (ItemStack) args[0];
                                yield null;
                            }
                            case "getHealth" -> health;
                            case "setHealth" -> {
                                double value = (Double) args[0];
                                if (value < 0 || value > maxHealth) {
                                    throw new IllegalArgumentException("health " + value);
                                }
                                health = value;
                                yield null;
                            }
                            case "getMaxHealth" -> maxHealth;
                            case "getFoodLevel" -> food;
                            case "setFoodLevel" -> {
                                food = (Integer) args[0];
                                yield null;
                            }
                            case "getSaturation" -> saturation;
                            case "setSaturation" -> {
                                saturation = (Float) args[0];
                                yield null;
                            }
                            case "getExhaustion" -> exhaustion;
                            case "setExhaustion" -> {
                                exhaustion = (Float) args[0];
                                yield null;
                            }
                            case "getRemainingAir" -> air;
                            case "setRemainingAir" -> {
                                air = (Integer) args[0];
                                yield null;
                            }
                            case "getMaximumAir" -> maxAir;
                            case "getFireTicks" -> fireTicks;
                            case "setFireTicks" -> {
                                fireTicks = (Integer) args[0];
                                yield null;
                            }
                            case "getLevel" -> level;
                            case "setLevel" -> {
                                level = (Integer) args[0];
                                yield null;
                            }
                            case "getExp" -> exp;
                            case "setExp" -> {
                                float value = (Float) args[0];
                                if (value < 0 || value > 1) {
                                    throw new IllegalArgumentException("exp " + value);
                                }
                                exp = value;
                                yield null;
                            }
                            case "getTotalExperience" -> total;
                            case "setTotalExperience" -> {
                                total = (Integer) args[0];
                                yield null;
                            }
                            case "getActivePotionEffects" -> (Collection<PotionEffect>) List.<PotionEffect>of();
                            case "getGameMode" -> mode;
                            case "setGameMode" -> {
                                mode = (GameMode) args[0];
                                allowFlight = mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR;
                                yield null;
                            }
                            case "getAllowFlight" -> allowFlight;
                            case "setAllowFlight" -> {
                                allowFlight = (Boolean) args[0];
                                if (!allowFlight) {
                                    flying = false;
                                }
                                yield null;
                            }
                            case "isFlying" -> flying;
                            case "setFlying" -> {
                                boolean value = (Boolean) args[0];
                                if (value && !allowFlight) {
                                    throw new IllegalArgumentException("Cannot make player fly if getAllowFlight() is false");
                                }
                                flying = value;
                                yield null;
                            }
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            case "toString" -> "FakePlayer";
                            default -> throw new UnsupportedOperationException(name);
                        };
                    });
        }

        private static void set(ItemStack[] target, ItemStack[] items) {
            if (items.length > target.length) {
                throw new IllegalArgumentException("too many items: " + items.length);
            }
            for (int i = 0; i < target.length; i++) {
                target[i] = i < items.length ? items[i] : null;
            }
        }

        /** Like Bukkit: each stack into the first empty storage slot; what does not fit comes back. */
        private Map<Integer, ItemStack> add(ItemStack[] items) {
            Map<Integer, ItemStack> leftover = new HashMap<>();
            outer:
            for (int i = 0; i < items.length; i++) {
                for (int slot = 0; slot < Math.min(36, slots.length); slot++) {
                    if (slots[slot] == null) {
                        slots[slot] = items[i];
                        continue outer;
                    }
                }
                leftover.put(i, items[i]);
            }
            return leftover;
        }

        int itemCount() {
            int count = cursor == null ? 0 : 1;
            for (ItemStack item : slots) {
                count += item == null ? 0 : 1;
            }
            for (ItemStack item : ender) {
                count += item == null ? 0 : 1;
            }
            return count;
        }
    }

    // ------------------------------------------------------------------ host

    /** A host whose async, main and player threads are queues run by the test. */
    static final class Host implements SnapshotHost {
        final Path folder;
        final List<String> logs = new ArrayList<>();
        final Logger logger = Logger.getAnonymousLogger();
        final Deque<Runnable> async = new ArrayDeque<>();
        final Deque<Runnable> main = new ArrayDeque<>();
        final Deque<Runnable[]> forPlayer = new ArrayDeque<>();
        /** Async tasks run at once when true. */
        boolean asyncInline = true;
        /** The test thread owns every player when true (Paper's main thread). */
        boolean owns = true;
        boolean onPlayerThread;
        boolean present = true;
        boolean shuttingDown;

        Host(Path folder) {
            this.folder = folder;
            logger.setUseParentHandlers(false);
            logger.addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    logs.add(record.getLevel() + " " + record.getMessage());
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            });
        }

        @Override
        public Logger logger() {
            return logger;
        }

        @Override
        public Path folder() {
            return folder;
        }

        @Override
        public boolean shuttingDown() {
            return shuttingDown;
        }

        @Override
        public boolean ownsHere(Player player) {
            return owns || onPlayerThread;
        }

        @Override
        public boolean present(Player player) {
            return present;
        }

        @Override
        public void async(Runnable task) {
            if (asyncInline) {
                task.run();
            } else {
                async.add(task);
            }
        }

        @Override
        public void main(Runnable task) {
            main.add(task);
        }

        @Override
        public void runFor(Player player, Runnable task, Runnable gone) {
            forPlayer.add(new Runnable[] {task, gone});
        }

        @Override
        public SnapshotCodec codec() {
            return CODEC;
        }

        /** Runs every queued task (and what they queue) in order: async first, then player, then main. */
        void runAll() {
            boolean ran = true;
            while (ran) {
                ran = false;
                Runnable next = async.poll();
                if (next != null) {
                    next.run();
                    ran = true;
                    continue;
                }
                Runnable[] pair = forPlayer.poll();
                if (pair != null) {
                    onPlayerThread = true;
                    try {
                        pair[0].run();
                    } finally {
                        onPlayerThread = false;
                    }
                    ran = true;
                    continue;
                }
                next = main.poll();
                if (next != null) {
                    next.run();
                    ran = true;
                }
            }
        }

        /** Folia: the player left, so every task queued for them retires. */
        void retirePlayerTasks() {
            Runnable[] pair;
            while ((pair = forPlayer.poll()) != null) {
                pair[1].run();
            }
        }

        boolean logged(String part) {
            return logs.stream().anyMatch(line -> line.contains(part));
        }
    }

    static Path file(Path folder, UUID id, String name) {
        return folder.resolve(id.toString()).resolve(name);
    }

    static String read(Path file) throws IOException {
        return Files.readString(file);
    }
}
