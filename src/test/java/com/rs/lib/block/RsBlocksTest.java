package com.rs.lib.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Clickable blocks: clicks, the repeat guard, protection, replacement and per-plugin cleanup. */
class RsBlocksTest {

    private static final World WORLD = world("world");
    private static final World NETHER = world("nether");

    private long now = 1_000_000L;
    private final BlockIndex index = new BlockIndex(() -> now);
    private final BlockListener listener = new BlockListener(index);
    private final List<String> warnings = new ArrayList<>();
    private final List<String> ran = new ArrayList<>();
    private RsBlocks crates;
    private RsBlocks shops;
    private final UUID steveId = UUID.randomUUID();
    private boolean sneaking;
    private Player steve;

    private static World world(String name) {
        return (World) Proxy.newProxyInstance(RsBlocksTest.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Block block(World world, int x, int y, int z) {
        return (Block) Proxy.newProxyInstance(RsBlocksTest.class.getClassLoader(), new Class<?>[] {Block.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWorld" -> world;
                    case "getX" -> x;
                    case "getY" -> y;
                    case "getZ" -> z;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Block[" + x + "," + y + "," + z + "]";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
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

    @BeforeEach
    void setUp() {
        RsBlocks.ActionCompiler actions = (lines, pos) -> click -> ran.add(String.join("|", lines) + "@" + pos);
        crates = new RsBlocks("Crates", logger(), actions, index);
        shops = new RsBlocks("Shops", logger(), actions, index);
        steve = (Player) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> steveId;
                    case "isSneaking" -> sneaking;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private PlayerInteractEvent click(Block block, Action action, EquipmentSlot hand) {
        PlayerInteractEvent event = new PlayerInteractEvent(steve, action, null, block, BlockFace.UP, hand);
        listener.onInteract(event);
        return event;
    }

    @Test
    void clicksRunTheirButtonAndCancelTheVanillaUse() {
        List<ClickedBlock> clicks = new ArrayList<>();
        crates.at(new Location(WORLD, 10.7, 64.2, -3.5)).onLeft(c -> clicks.add(c)).onRight(c -> clicks.add(c)).register();
        Block barrel = block(WORLD, 10, 64, -4);

        PlayerInteractEvent right = click(barrel, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND);
        assertEquals(Event.Result.DENY, right.useInteractedBlock(), "the barrel does not open");
        assertEquals(Event.Result.DENY, right.useItemInHand());
        assertEquals(1, clicks.size());
        assertTrue(clicks.get(0).right());
        assertSame(steve, clicks.get(0).player());
        assertSame(barrel, clicks.get(0).block());

        now += 1_000;
        sneaking = true;
        PlayerInteractEvent left = click(barrel, Action.LEFT_CLICK_BLOCK, EquipmentSlot.HAND);
        assertEquals(Event.Result.DENY, left.useInteractedBlock());
        assertEquals(2, clicks.size());
        assertTrue(clicks.get(1).left());
        assertTrue(clicks.get(1).sneaking());

        PlayerInteractEvent elsewhere = click(block(WORLD, 11, 64, -4), Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND);
        assertEquals(Event.Result.ALLOW, elsewhere.useInteractedBlock(), "other blocks are untouched");
        PlayerInteractEvent otherWorld = click(block(NETHER, 10, 64, -4), Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND);
        assertEquals(Event.Result.ALLOW, otherWorld.useInteractedBlock());
        assertEquals(2, clicks.size());
    }

    @Test
    void theOffHandAndStepsAreCancelledWithoutAClick() {
        List<ClickedBlock> clicks = new ArrayList<>();
        crates.at("world", 0, 64, 0).onClick(clicks::add).register();
        Block plate = block(WORLD, 0, 64, 0);
        PlayerInteractEvent offHand = click(plate, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.OFF_HAND);
        assertEquals(Event.Result.DENY, offHand.useInteractedBlock());
        PlayerInteractEvent step = click(plate, Action.PHYSICAL, null);
        assertEquals(Event.Result.DENY, step.useInteractedBlock(), "a registered plate does not press");
        assertTrue(clicks.isEmpty());
    }

    @Test
    @SuppressWarnings("removal")
    void aRepeatedClickWithinTheWindowCountsOnce() {
        List<ClickedBlock> clicks = new ArrayList<>();
        crates.at("world", 0, 64, 0).onClick(clicks::add).register();
        Block crate = block(WORLD, 0, 64, 0);
        click(crate, Action.LEFT_CLICK_BLOCK, EquipmentSlot.HAND);
        now += BlockIndex.REPEAT_MILLIS - 1;
        click(crate, Action.LEFT_CLICK_BLOCK, EquipmentSlot.HAND);
        assertEquals(1, clicks.size(), "a held button");
        click(crate, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND);
        assertEquals(2, clicks.size(), "the other button is a new click");
        now += BlockIndex.REPEAT_MILLIS;
        click(crate, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND);
        assertEquals(3, clicks.size(), "after the window it counts again");

        assertEquals(1, index.clicksRemembered());
        listener.onQuit(new PlayerQuitEvent(steve, (String) null));
        assertEquals(0, index.clicksRemembered(), "forgotten on quit");
    }

    @Test
    void aMissingButtonOrAThrowingHandlerCostsOnlyThatClick() {
        crates.at("world", 0, 64, 0).onRight(c -> {
            throw new IllegalStateException("boom");
        }).register();
        Block crate = block(WORLD, 0, 64, 0);
        PlayerInteractEvent left = click(crate, Action.LEFT_CLICK_BLOCK, EquipmentSlot.HAND);
        assertEquals(Event.Result.DENY, left.useInteractedBlock(), "no left handler: still cancelled");
        PlayerInteractEvent right = click(crate, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND);
        assertEquals(Event.Result.DENY, right.useInteractedBlock());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("world;0;64;0"));
    }

    @Test
    void actionListsRunWithTheBlockPosition() {
        crates.at("world", 3, 70, 9).rightActions(List.of("[message] hi")).leftActions(List.of("[console] say x"))
                .register();
        Block crate = block(WORLD, 3, 70, 9);
        click(crate, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND);
        click(crate, Action.LEFT_CLICK_BLOCK, EquipmentSlot.HAND);
        assertEquals(List.of("[message] hi@world;3;70;9", "[console] say x@world;3;70;9"), ran);
    }

    @Test
    void registeredBlocksCannotBeBrokenBurntOrPushed() {
        crates.at("world", 0, 64, 0).register();
        Block crate = block(WORLD, 0, 64, 0);
        Block dirt = block(WORLD, 1, 64, 0);

        BlockBreakEvent breakCrate = new BlockBreakEvent(crate, steve);
        listener.onBreak(breakCrate);
        assertTrue(breakCrate.isCancelled());
        BlockBreakEvent breakDirt = new BlockBreakEvent(dirt, steve);
        listener.onBreak(breakDirt);
        assertFalse(breakDirt.isCancelled());

        BlockBurnEvent burn = new BlockBurnEvent(crate, null);
        listener.onBurn(burn);
        assertTrue(burn.isCancelled());

        Block piston = block(WORLD, -2, 64, 0);
        BlockPistonExtendEvent push = new BlockPistonExtendEvent(piston, List.of(block(WORLD, -1, 64, 0), crate),
                BlockFace.EAST);
        listener.onPistonExtend(push);
        assertTrue(push.isCancelled());
        BlockPistonRetractEvent pull = new BlockPistonRetractEvent(piston, List.of(crate), BlockFace.WEST);
        listener.onPistonRetract(pull);
        assertTrue(pull.isCancelled());
        BlockPistonExtendEvent pushDirt = new BlockPistonExtendEvent(piston, List.of(dirt), BlockFace.EAST);
        listener.onPistonExtend(pushDirt);
        assertFalse(pushDirt.isCancelled());
    }

    @Test
    void theWorldCannotChangeARegisteredBlock() {
        crates.at("world", 0, 64, 0).register();
        Block crate = block(WORLD, 0, 64, 0);
        Block dirt = block(WORLD, 1, 64, 0);

        EntityChangeBlockEvent enderman = new EntityChangeBlockEvent(null, crate, null);
        listener.onEntityChange(enderman);
        assertTrue(enderman.isCancelled(), "an enderman, a falling block, a sheep...");
        EntityChangeBlockEvent elsewhere = new EntityChangeBlockEvent(null, dirt, null);
        listener.onEntityChange(elsewhere);
        assertFalse(elsewhere.isCancelled());

        BlockFromToEvent into = new BlockFromToEvent(dirt, crate);
        listener.onFlow(into);
        assertTrue(into.isCancelled(), "water flowing into it");
        BlockFromToEvent outOf = new BlockFromToEvent(crate, dirt);
        listener.onFlow(outOf);
        assertFalse(outOf.isCancelled(), "only the block it flows into counts");

        BlockFadeEvent melt = new BlockFadeEvent(crate, null);
        listener.onFade(melt);
        assertTrue(melt.isCancelled());
        BlockFadeEvent meltDirt = new BlockFadeEvent(dirt, null);
        listener.onFade(meltDirt);
        assertFalse(meltDirt.isCancelled());

        BlockFormEvent freeze = new BlockFormEvent(crate, null);
        listener.onForm(freeze);
        assertTrue(freeze.isCancelled());
        EntityBlockFormEvent frostWalker = new EntityBlockFormEvent(null, crate, null);
        listener.onForm(frostWalker);
        assertTrue(frostWalker.isCancelled());
        BlockFormEvent freezeDirt = new BlockFormEvent(dirt, null);
        listener.onForm(freezeDirt);
        assertFalse(freezeDirt.isCancelled());

        BlockSpreadEvent grass = new BlockSpreadEvent(crate, dirt, null);
        listener.onSpread(grass);
        assertTrue(grass.isCancelled());
        BlockSpreadEvent fromIt = new BlockSpreadEvent(dirt, crate, null);
        listener.onSpread(fromIt);
        assertFalse(fromIt.isCancelled(), "spreading from it changes another block");

        LeavesDecayEvent decay = new LeavesDecayEvent(crate);
        listener.onLeavesDecay(decay);
        assertTrue(decay.isCancelled());
        LeavesDecayEvent decayDirt = new LeavesDecayEvent(dirt);
        listener.onLeavesDecay(decayDirt);
        assertFalse(decayDirt.isCancelled());
    }

    @Test
    void anExplosionSparesTheRegisteredBlocksOnly() {
        crates.at("world", 0, 64, 0).register();
        Block crate = block(WORLD, 0, 64, 0);
        Block dirt = block(WORLD, 1, 64, 0);
        Block stone = block(WORLD, 2, 64, 0);
        List<Block> blown = new ArrayList<>(List.of(dirt, crate, stone));
        index.spare(blown);
        assertEquals(List.of(dirt, stone), blown);
    }

    @Test
    void oneBlockOneRegistration() {
        RsBlock first = crates.at("world", 0, 64, 0).register();
        List<String> who = new ArrayList<>();
        RsBlock second = shops.at("world", 0, 64, 0).onRight(c -> who.add("shop")).register();
        assertFalse(first.isRegistered());
        assertTrue(second.isRegistered());
        assertFalse(first.unregister(), "a replaced handle cannot take down its replacement");
        assertTrue(second.isRegistered());
        assertEquals(0, crates.count());
        assertEquals(1, shops.count());
        assertNull(crates.registered(new Location(WORLD, 0, 64, 0)), "it is not the crates' any more");
        assertSame(second, shops.registered(new Location(WORLD, 0.5, 64.9, 0.5)));
        click(block(WORLD, 0, 64, 0), Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND);
        assertEquals(List.of("shop"), who);
    }

    @Test
    void unregisteringAndShuttingDownTakeOnlyThatPluginsBlocks() {
        crates.at("world", 0, 64, 0).register();
        crates.at("world", 1, 64, 0).register();
        RsBlock shop = shops.at("world", 5, 64, 5).register();
        assertTrue(crates.unregister(new Location(WORLD, 1, 64, 0)));
        assertFalse(crates.unregister(new Location(WORLD, 1, 64, 0)));
        assertFalse(crates.unregister(new Location(WORLD, 5, 64, 5)), "not the crates' block");
        assertEquals(1, crates.count());
        crates.shutdown();
        assertEquals(0, crates.count());
        assertTrue(shop.isRegistered());
        assertTrue(shops.isRegistered(block(WORLD, 5, 64, 5)));
        assertFalse(shops.isRegistered(block(WORLD, 0, 64, 0)));
        BlockBreakEvent breakOld = new BlockBreakEvent(block(WORLD, 0, 64, 0), steve);
        listener.onBreak(breakOld);
        assertFalse(breakOld.isCancelled(), "a block of a disabled plugin is an ordinary block again");
    }

    @Test
    void aLocationNeedsAWorld() {
        assertThrows(IllegalArgumentException.class, () -> crates.at(new Location(null, 0, 0, 0)));
    }
}
