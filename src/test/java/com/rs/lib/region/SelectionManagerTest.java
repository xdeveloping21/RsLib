package com.rs.lib.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.rs.lib.region.Fakes.FakePlayer;

/** The wand pipeline, sessions and the wand mark (Trampa of the region module: the exact PDC key). */
class SelectionManagerTest {

    private static final World WORLD = Fakes.world("world");
    private static final World NETHER = Fakes.world("nether");

    private final Fakes.Host host = new Fakes.Host("rsarena");
    private final List<SelectionManager> managers = new ArrayList<>();
    private final FakePlayer steve = new FakePlayer("Steve", new Location(WORLD, 5, 64, 5));
    private final List<String> calls = new ArrayList<>();

    private SelectionManager manager(Fakes.Host on) {
        SelectionManager manager = new SelectionManager(on);
        managers.add(manager);
        return manager;
    }

    @AfterEach
    void shutDown() {
        managers.forEach(SelectionManager::shutdown);
        assertTrue(SelectionManager.noneArmed(), "a manager that shut down is no longer asked about wands");
    }

    private SelectionSpec.Builder recording(String id) {
        return SelectionSpec.builder(id)
                .onUpdate(s -> calls.add("update"))
                .onSelect(box -> calls.add("select " + box.serialize()))
                .onCancel(uuid -> calls.add("cancel " + (uuid.equals(steve.id) ? "steve" : uuid)));
    }

    private static Location block(World world, int x, int y, int z) {
        return new Location(world, x + 0.5, y, z + 0.5);
    }

    // ------------------------------------------------------------------ the pipeline

    @Test
    void twoClicksCompleteTheSelectionInOrder() {
        SelectionManager manager = manager(host);
        manager.registerSpec(recording("arena").build());
        manager.handleWandClick(steve.player, "arena", true, block(WORLD, 0, 64, 0));
        assertEquals(List.of("pos1-set x=0 y=64 z=0 world=world"), host.sent);
        assertEquals(List.of("update"), calls);
        SelectionSession session = manager.current(steve.id);
        assertNotNull(session);
        assertEquals(64.0, session.pos1().getY());
        assertTrue(host.events.isEmpty());

        manager.handleWandClick(steve.player, "arena", false, block(WORLD, -3, 70, 9));
        assertEquals("pos2-set x=-3 y=70 z=9 world=world", host.sent.get(1));
        assertEquals(1, host.events.size());
        assertEquals("arena", host.events.get(0).specId());
        assertSame(steve.player, host.events.get(0).getPlayer());
        assertEquals(List.of("update", "update", "select world;-3;64;0;0;70;9"), calls);
        // completeEnds (the default): the session and its drawing end.
        assertNull(manager.current(steve.player));
        assertTrue(host.lastTimer().isCancelled());
        assertEquals(2, host.sent.size());
    }

    @Test
    void aCancelledEventSkipsOnSelectAndKeepsTheSession() {
        SelectionManager manager = manager(host);
        manager.registerSpec(recording("arena").build());
        host.cancel = e -> true;
        manager.handleWandClick(steve.player, "arena", true, block(WORLD, 0, 0, 0));
        manager.handleWandClick(steve.player, "arena", false, block(WORLD, 1, 1, 1));
        assertEquals(1, host.events.size());
        assertEquals(List.of("update", "update"), calls);
        assertNotNull(manager.current(steve.id));
        host.cancel = e -> false;
        manager.handleWandClick(steve.player, "arena", false, block(WORLD, 2, 2, 2));
        assertEquals("select world;0;0;0;2;2;2", calls.get(calls.size() - 1));
    }

    @Test
    void keepingTheSessionAfterCompletingIsAChoiceOfTheSpec() {
        SelectionManager manager = manager(host);
        manager.registerSpec(recording("arena").completeEnds(false).build());
        manager.handleWandClick(steve.player, "arena", true, block(WORLD, 0, 0, 0));
        manager.handleWandClick(steve.player, "arena", false, block(WORLD, 1, 1, 1));
        assertNotNull(manager.current(steve.id));
        assertFalse(host.lastTimer().isCancelled());
    }

    @Test
    void positionsInDifferentWorldsAreReportedAndNeverComplete() {
        SelectionManager manager = manager(host);
        manager.registerSpec(recording("arena").build());
        manager.handleWandClick(steve.player, "arena", true, block(WORLD, 0, 0, 0));
        manager.handleWandClick(steve.player, "arena", false, block(NETHER, 1, 1, 1));
        assertEquals(List.of("pos1-set x=0 y=0 z=0 world=world", "pos2-set x=1 y=1 z=1 world=nether",
                "different-worlds"), host.sent);
        assertEquals(List.of("update", "update"), calls, "onUpdate still runs");
        assertTrue(host.events.isEmpty());
        assertNull(manager.current(steve.id).cuboid());
    }

    @Test
    void aSelectionOverTheMaximumVolumeIsRefused() {
        SelectionManager manager = manager(host);
        manager.registerSpec(recording("arena").maxVolume(26).build());
        manager.handleWandClick(steve.player, "arena", true, block(WORLD, 0, 0, 0));
        manager.handleWandClick(steve.player, "arena", false, block(WORLD, 2, 2, 2));
        assertEquals("too-big volume=27 max=26", host.sent.get(2));
        assertTrue(host.events.isEmpty());
        manager.handleWandClick(steve.player, "arena", false, block(WORLD, 2, 2, 1));
        assertEquals(1, host.events.size(), "18 blocks fit");
    }

    @Test
    void thePermissionIsCheckedBeforeAnythingIsStored() {
        SelectionManager manager = manager(host);
        manager.registerSpec(recording("arena").permission("arena.wand").silent(true).build());
        manager.handleWandClick(steve.player, "arena", true, block(WORLD, 0, 0, 0));
        assertEquals(List.of("no-permission"), host.sent, "shown even for a silent spec");
        assertNull(manager.current(steve.id));
        steve.perms.add("arena.wand");
        manager.handleWandClick(steve.player, "arena", true, block(WORLD, 0, 0, 0));
        manager.handleWandClick(steve.player, "arena", false, block(WORLD, 1, 0, 0));
        assertEquals(List.of("no-permission"), host.sent, "a silent spec sends nothing else");
        assertEquals(1, host.events.size());
    }

    @Test
    void aWandOfAnUnknownSpecDoesNothingAndAnotherSpecReplacesTheSession() {
        SelectionManager manager = manager(host);
        manager.handleWandClick(steve.player, "ghost", true, block(WORLD, 0, 0, 0));
        assertTrue(host.sent.isEmpty());
        assertNull(manager.current(steve.id));
        manager.registerSpec(recording("arena").build());
        manager.registerSpec(recording("spawn").build());
        manager.handleWandClick(steve.player, "arena", true, block(WORLD, 0, 0, 0));
        manager.handleWandClick(steve.player, "spawn", true, block(WORLD, 0, 0, 0));
        assertEquals("spawn", manager.current(steve.id).spec().id());
        assertTrue(calls.contains("cancel steve"), "the arena session ended without completing");
    }

    // ------------------------------------------------------------------ sessions

    @Test
    void beginCancelAndReplace() {
        SelectionManager manager = manager(host);
        SelectionSession first = manager.begin(steve.player, recording("arena").build());
        assertEquals(steve.id, first.playerId());
        assertEquals(host.now, first.createdAtMillis());
        assertTrue(host.timers.isEmpty(), "nothing to draw and no timeout: no task");
        SelectionSession second = manager.begin(steve.player, recording("arena").build());
        assertEquals(List.of("cancel steve"), calls);
        assertSame(second, manager.current(steve.player));
        assertTrue(manager.cancel(steve.player));
        assertFalse(manager.cancel(steve.id));
        assertEquals(List.of("cancel steve", "cancel steve"), calls);
        assertTrue(host.sent.isEmpty(), "cancelling sends nothing");
    }

    @Test
    void sessionsTimeOutWithAMessageAndEndWhenThePlayerIsGone() {
        SelectionManager manager = manager(host);
        host.online.put(steve.id, steve.player);
        manager.begin(steve.player, recording("arena").timeoutTicks(20).build());
        Fakes.Timer timer = host.lastTimer();
        assertEquals(5, timer.period);
        host.now += 999;
        timer.fire();
        assertNotNull(manager.current(steve.id));
        host.now += 1;
        timer.fire();
        assertNull(manager.current(steve.id));
        assertEquals(List.of("timeout"), host.sent);
        assertEquals(List.of("cancel steve"), calls);
        assertTrue(timer.isCancelled());

        manager.handleWandClick(steve.player, "arena", true, block(WORLD, 0, 0, 0));
        steve.online = false;
        host.lastTimer().fire();
        assertNull(manager.current(steve.id));
        assertEquals(List.of("timeout", "pos1-set x=0 y=0 z=0 world=world"), host.sent, "leaving sends nothing");
        assertEquals(2, calls.stream().filter(c -> c.startsWith("cancel")).count());
    }

    @Test
    void shuttingDownEndsSessionsWithoutCallbacks() {
        SelectionManager manager = manager(host);
        manager.begin(steve.player, recording("arena").build());
        manager.shutdown();
        assertNull(manager.current(steve.id));
        assertTrue(calls.isEmpty());
        manager.handleWandClick(steve.player, "arena", true, block(WORLD, 0, 0, 0));
        assertTrue(host.sent.isEmpty());
        assertThrows(IllegalStateException.class, () -> manager.begin(steve.player, recording("arena").build()));
    }

    @Test
    void sessionsKeepCopiesOfTheirPositions() {
        SelectionSession session = new SelectionSession(steve.id, SelectionSpec.builder("a").build(), 0);
        Location at = new Location(WORLD, 1, 2, 3);
        session.setPos1(at);
        at.setX(50);
        assertEquals(1.0, session.pos1().getX());
        session.pos1().setX(70);
        assertEquals(1.0, session.pos1().getX());
        assertFalse(session.hasBothPositions());
        session.setPos2(new Location(WORLD, 4, 5, 6));
        assertEquals(Cuboid.of("world", 1, 2, 3, 4, 5, 6), session.cuboid());
        session.clearPositions();
        assertNull(session.pos1());
        assertNull(session.cuboid());
    }

    // ------------------------------------------------------------------ drawing

    @Test
    void theOwnerSeesTheOutlineWithinTheRenderDistance() {
        SelectionManager manager = manager(host);
        manager.registerSpec(recording("arena").completeEnds(false).build());
        manager.handleWandClick(steve.player, "arena", true, block(WORLD, 0, 64, 0));
        assertEquals(20, host.particlesFor(steve.player), "one block marked at once");
        assertEquals(Particle.DUST, host.lastParticle);
        Particle.DustOptions dust = (Particle.DustOptions) host.lastData;
        assertEquals(255, dust.getColor().getRed());
        assertEquals(140, dust.getColor().getGreen());

        manager.handleWandClick(steve.player, "arena", false, block(WORLD, 2, 64, 0));
        host.particles.clear();
        host.lastTimer().fire();
        assertEquals(8 + 4 * 5 + 4 + 4, host.particlesFor(steve.player));

        steve.at = new Location(WORLD, 100, 64, 0);
        host.particles.clear();
        host.lastTimer().fire();
        assertEquals(0, host.particlesFor(steve.player), "97 blocks from the box: out of range");
        steve.at = new Location(NETHER, 0, 64, 0);
        host.lastTimer().fire();
        assertEquals(0, host.particlesFor(steve.player));
    }

    @Test
    void worldVisibilityShowsNearbyPlayersOnThisThreadOnly() {
        SelectionManager manager = manager(host);
        FakePlayer near = new FakePlayer("Alex", new Location(WORLD, 3, 64, 3));
        FakePlayer otherRegion = new FakePlayer("Remote", new Location(WORLD, 3, 64, 3));
        host.worldPlayers.addAll(List.of(steve.player, near.player, otherRegion.player));
        host.elsewhere.add(otherRegion.player);
        manager.registerSpec(recording("arena").visibility(SelectionSpec.Visibility.WORLD).build());
        manager.handleWandClick(steve.player, "arena", true, block(WORLD, 0, 64, 0));
        assertEquals(20, host.particlesFor(steve.player));
        assertEquals(20, host.particlesFor(near.player));
        assertEquals(0, host.particlesFor(otherRegion.player));

        Fakes.Host ownHost = new Fakes.Host("rsowner");
        SelectionManager owners = manager(ownHost);
        ownHost.worldPlayers.addAll(List.of(steve.player, near.player));
        owners.registerSpec(recording("arena").build());
        owners.handleWandClick(steve.player, "arena", true, block(WORLD, 0, 64, 0));
        assertEquals(20, ownHost.particlesFor(steve.player));
        assertEquals(0, ownHost.particlesFor(near.player), "OWNER_ONLY");
    }

    @Test
    void aBoxOverTheRenderVolumeOnlyMarksItsCorners() {
        SelectionManager manager = manager(host);
        manager.registerSpec(recording("arena").completeEnds(false).maxRenderVolume(100).build());
        manager.handleWandClick(steve.player, "arena", true, block(WORLD, 0, 64, 0));
        manager.handleWandClick(steve.player, "arena", false, block(WORLD, 9, 73, 9));
        host.particles.clear();
        host.lastTimer().fire();
        assertEquals(SelectionRenderer.CROSS_POINTS, host.particlesFor(steve.player));
    }

    // ------------------------------------------------------------------ the wand mark and the listener

    @Test
    void theWandMarkIsTheSpecIdUnderThePluginNamespace() {
        assertEquals("rslib_selection_wand", SelectionManager.WAND_TAG);
        SelectionManager manager = manager(host);
        assertEquals("arena", manager.wandSpecId(wand(Map.of("rsarena:rslib_selection_wand", "arena"))));
        assertTrue(manager.isWand(wand(Map.of("rsarena:rslib_selection_wand", "arena"))));
        assertNull(manager.wandSpecId(wand(Map.of("rsother:rslib_selection_wand", "arena"))), "another plugin's wand");
        assertNull(manager.wandSpecId(wand(Map.of("rsarena:snlib_selection_wand", "arena"))), "RsLib's own mark only");
        assertNull(manager.wandSpecId(wand(Map.of())));
        assertNull(manager.wandSpecId(null));
    }

    @Test
    void theListenerCancelsWandClicksFirstEvenWhenAlreadyCancelled() throws Exception {
        SelectionManager manager = manager(host);
        manager.registerSpec(recording("arena").permission("arena.wand").build());
        ItemStack wand = wand(Map.of("rsarena:rslib_selection_wand", "arena"));
        SelectionWandListener listener = (SelectionWandListener) SelectionManager.wandListener();

        PlayerInteractEvent click = click(Action.LEFT_CLICK_BLOCK, wand, EquipmentSlot.HAND);
        click.setUseInteractedBlock(Event.Result.ALLOW);
        listener.onInteract(click);
        assertEquals(Event.Result.DENY, click.useInteractedBlock(), "cancelled before the permission check");
        assertEquals(List.of("no-permission"), host.sent);

        steve.perms.add("arena.wand");
        PlayerInteractEvent protectedBlock = click(Action.RIGHT_CLICK_BLOCK, wand, EquipmentSlot.HAND);
        protectedBlock.setCancelled(true);
        listener.onInteract(protectedBlock);
        assertEquals("pos2-set x=7 y=64 z=-3 world=world", host.sent.get(1), "a protection plugin cannot stop a wand");

        PlayerInteractEvent offHand = click(Action.LEFT_CLICK_BLOCK, wand, EquipmentSlot.OFF_HAND);
        listener.onInteract(offHand);
        PlayerInteractEvent air = click(Action.LEFT_CLICK_AIR, wand, EquipmentSlot.HAND);
        listener.onInteract(air);
        PlayerInteractEvent stick = click(Action.LEFT_CLICK_BLOCK, wand(Map.of()), EquipmentSlot.HAND);
        listener.onInteract(stick);
        assertEquals(2, host.sent.size());
        assertEquals(Event.Result.DEFAULT, stick.useItemInHand(), "other items are left alone");

        EventHandler handler = SelectionWandListener.class.getMethod("onInteract", PlayerInteractEvent.class)
                .getAnnotation(EventHandler.class);
        assertEquals(EventPriority.LOWEST, handler.priority());
        assertFalse(handler.ignoreCancelled());
    }

    // ------------------------------------------------------------------ helpers

    private PlayerInteractEvent click(Action action, ItemStack item, EquipmentSlot hand) {
        Block block = (Block) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Block.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getLocation" -> new Location(WORLD, 7, 64, -3);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return new PlayerInteractEvent(steve.player, action, item, block, BlockFace.UP, hand);
    }

    /** A stack whose persistent data holds {@code data} (namespaced key text to value). */
    static ItemStack wand(Map<String, String> data) {
        PersistentDataContainer pdc = (PersistentDataContainer) Proxy.newProxyInstance(
                SelectionManagerTest.class.getClassLoader(), new Class<?>[] {PersistentDataContainer.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "get" -> data.get(((NamespacedKey) args[0]).toString());
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        ItemMeta meta = (ItemMeta) Proxy.newProxyInstance(SelectionManagerTest.class.getClassLoader(),
                new Class<?>[] {ItemMeta.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getPersistentDataContainer" -> pdc;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return new MetaStack(meta);
    }

    static final class MetaStack extends ItemStack {
        private final ItemMeta meta;

        @SuppressWarnings("deprecation")
        MetaStack(ItemMeta meta) {
            super();
            this.meta = meta;
        }

        @Override
        public Material getType() {
            return Material.BLAZE_ROD;
        }

        @Override
        public boolean hasItemMeta() {
            return true;
        }

        @Override
        public ItemMeta getItemMeta() {
            return meta;
        }

        @Override
        public boolean equals(Object other) {
            return other == this;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }
}
