package com.rs.lib.gui;

import java.util.Arrays;
import java.util.function.Consumer;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import com.rs.lib.Ph;
import com.rs.lib.action.ActionContext;
import com.rs.lib.action.Requirement;
import com.rs.lib.internal.PluginLanguage;
import com.rs.lib.item.ItemAppearance;

/**
 * The rendering of one {@link GuiSession}: what each cell shows for the viewer (binds, regions,
 * declared items and their view requirements) and writing it into the inventory when it
 * changed. Lives on the viewer's thread with its session; reads the session's binds, page and
 * inventory.
 */
final class SessionRenderer {

    private static final Ph[] NO_PHS = new Ph[0];
    private static final byte UNKNOWN = 0;
    private static final byte SHOWN = 1;
    private static final byte SHOWN_DISABLED = 2;
    private static final byte HIDDEN = 3;

    private final GuiSession session;
    private final GuiDef def;
    private final GuiHost host;
    private final Player viewer;
    private final ItemStack[] shownProto;
    private final GuiItemDef[] shownDef;
    private final boolean[] regionInput;
    private final byte[] verdict;
    private final ItemStack[] passProto;
    private final Cell cell = new Cell();
    private boolean protoStatic;

    SessionRenderer(GuiSession session, GuiDef def, GuiHost host, Player viewer) {
        this.session = session;
        this.def = def;
        this.host = host;
        this.viewer = viewer;
        int size = def.size();
        this.shownProto = new ItemStack[size];
        this.shownDef = new GuiItemDef[size];
        this.regionInput = new boolean[size];
        this.verdict = new byte[def.items().size()];
        this.passProto = new ItemStack[def.items().size()];
    }

    /** True when a region painted a template with {@code input: true} in {@code slot}. */
    boolean regionInput(int slot) {
        return regionInput[slot];
    }

    /** Forgets what the cells show, for a new inventory. */
    void forgetShown() {
        Arrays.fill(shownProto, null);
        Arrays.fill(shownDef, null);
    }

    int cellCount() {
        Inventory shown = session.inventory;
        return shown == null ? def.size() : Math.min(def.size(), shown.getSize());
    }

    /** What {@code slot} shows for the viewer right now (requirements checked again), or null. */
    GuiItemDef itemAt(int slot) {
        beginPass();
        resolve(slot);
        return cell.def;
    }

    /**
     * Works out what {@code slot} shows for a click and paints it when that changed; the cell
     * is only valid until the next render.
     */
    Cell clicked(int slot) {
        beginPass();
        resolve(slot);
        GuiItemDef item = cell.def;
        if (item == null) {
            if (shownProto[slot] != null) {
                paint(slot, false);
            }
        } else if (shownDef[slot] != item) {
            paint(slot, false);
        }
        return cell;
    }

    void renderAll(boolean force) {
        if (session.closed || session.inventory == null) {
            return;
        }
        beginPass();
        boolean all = force | session.takeExternal();
        int count = cellCount();
        for (int slot = 0; slot < count; slot++) {
            resolve(slot);
            paint(slot, all);
        }
    }

    void renderSlots(int[] slots) {
        if (session.closed || session.inventory == null) {
            return;
        }
        beginPass();
        boolean all = session.takeExternal();
        if (all) {
            int count = cellCount();
            for (int slot = 0; slot < count; slot++) {
                resolve(slot);
                paint(slot, true);
            }
            return;
        }
        int count = cellCount();
        for (int slot : slots) {
            if (slot >= 0 && slot < count) {
                resolve(slot);
                paint(slot, false);
            }
        }
    }

    void renderSlot(int slot) {
        renderSlots(new int[] {slot});
    }

    /**
     * Renders again the cells the declared {@code item} paints or may paint. A template's own
     * {@code update-interval} is never scheduled: only the menu's timer refreshes the cells
     * templates paint.
     */
    void renderItem(GuiItemDef item) {
        int index = def.items().indexOf(item);
        beginPass();
        boolean force = session.takeExternal();
        int count = cellCount();
        for (int slot = 0; slot < count; slot++) {
            if (shownDef[slot] == item || (index >= 0 && contains(def.candidates(slot), index))) {
                resolve(slot);
                paint(slot, force);
            }
        }
    }

    private static boolean contains(int[] values, int value) {
        for (int v : values) {
            if (v == value) {
                return true;
            }
        }
        return false;
    }

    /** Starts a render or click pass: declared items are judged once per pass. */
    private void beginPass() {
        Arrays.fill(verdict, UNKNOWN);
        Arrays.fill(passProto, null);
        SessionBinds.Paged<?> bound = session.paged;
        if (bound != null) {
            session.page = bound.data().clamp(session.page);
        }
    }

    /** Works out what {@code slot} shows into {@link #cell}. */
    private void resolve(int slot) {
        cell.clear();
        SessionBinds.Manual bound = session.manual[slot];
        if (bound != null) {
            GuiItemDef item = bound.template().item();
            if (visible(item, bound.phs())) {
                cell.set(Cell.MANUAL, item, false, bound.phs(), bound.stack(), bound, -1);
            }
            return;
        }
        SessionBinds.Paged<?> pages = session.paged;
        if (pages != null && pages.positions()[slot] >= 0) {
            resolvePaged(pages, slot);
            return;
        }
        SessionBinds.Region<?>[] regions = session.regionBinds;
        for (int r = regions.length - 1; r >= 0; r--) {
            SessionBinds.Region<?> region = regions[r];
            int position = region == null ? -1 : def.regionPosition(r, slot);
            if (position >= 0 && position < region.data().size() && resolveRegion(region, r, position)) {
                return;
            }
        }
        int[] candidates = def.candidates(slot);
        for (int index : candidates) {
            byte v = verdict[index];
            if (v == UNKNOWN) {
                v = judge(def.items().get(index));
                verdict[index] = v;
            }
            if (v == SHOWN || v == SHOWN_DISABLED) {
                cell.set(Cell.DECLARED, def.items().get(index), v == SHOWN_DISABLED, NO_PHS, null, null, index);
                return;
            }
        }
    }

    private <T> void resolvePaged(SessionBinds.Paged<T> pages, int slot) {
        int index = (session.page - 1) * pages.slots().length + pages.positions()[slot];
        if (!pages.data().has(index)) {
            return;
        }
        PhCollector collector = new PhCollector();
        try {
            pages.mapper().accept(pages.data().at(index), collector);
        } catch (RuntimeException | LinkageError e) {
            session.gui().warn("The bindPaged mapper of template " + pages.template().id() + " failed: " + e);
            return;
        }
        GuiItemDef item = pages.template().item();
        Ph[] phs = collector.toArray();
        if (visible(item, phs)) {
            cell.set(Cell.PAGED, item, false, phs, collector.stackOrNull(), pages, -1);
        }
    }

    private <T> boolean resolveRegion(SessionBinds.Region<T> region, int number, int position) {
        GuiEntry entry = new GuiEntry();
        try {
            region.filler().accept(region.data().get(position), entry);
        } catch (RuntimeException | LinkageError e) {
            session.gui().warn("The bindEach filler of region " + def.regionId(number) + " failed: " + e);
            return false;
        }
        if (entry.templateId() == null) {
            return false;
        }
        GuiTemplate template = def.template(entry.templateId());
        if (template == null) {
            session.gui().warn("bindEach(\"" + def.regionId(number) + "\"): the menu has no template '"
                    + entry.templateId() + "'");
            return false;
        }
        Ph[] phs = entry.phs();
        if (!visible(template.item(), phs)) {
            return false;
        }
        cell.set(Cell.REGION, template.item(), false, phs, entry.stackOrNull(), region, -1);
        return true;
    }

    /** Whether a declared item shows this pass, and in its disabled look. */
    private byte judge(GuiItemDef item) {
        if (!visible(item, NO_PHS)) {
            return HIDDEN;
        }
        return navDisabled(item) ? SHOWN_DISABLED : SHOWN;
    }

    /** A navigation arrow with no page to go to: previous on page 1, next on the last known page. */
    private boolean navDisabled(GuiItemDef item) {
        int page = session.page;
        if (item.navPrevious() && page <= 1) {
            return true;
        }
        int total = session.totalPages();
        return item.navNext() && total > 0 && page >= total;
    }

    private boolean visible(GuiItemDef item, Ph[] phs) {
        Requirement view = item.viewRequirement();
        if (view == Requirement.ALWAYS) {
            return true;
        }
        return host.actions().test(view, new ActionContext(viewer, host.rs(), session, null, phs));
    }

    /** Writes what {@link #cell} says into {@code slot} when it differs from what is there. */
    private void paint(int slot, boolean force) {
        GuiItemDef item = cell.def;
        ItemStack proto = proto(slot);
        ItemStack old = shownProto[slot];
        boolean same = !force && (proto == old
                || (proto != null && old != null && shownDef[slot] == item && proto.equals(old)));
        shownDef[slot] = item;
        regionInput[slot] = cell.kind == Cell.REGION && item.input();
        if (same) {
            return;
        }
        shownProto[slot] = proto;
        ItemStack stack = null;
        if (proto != null) {
            if (protoStatic) {
                boolean disabled = cell.navDisabled;
                stack = item.cachedStamped(disabled, slot);
                if (stack == null) {
                    stack = host.stamp(proto, def.id(), slot);
                    item.cacheStamped(disabled, slot, stack);
                }
            } else {
                stack = host.stamp(proto, def.id(), slot);
            }
        }
        session.inventory.setItem(slot, stack);
    }

    /** The unstamped stack for {@link #cell}; sets {@link #protoStatic}. */
    private ItemStack proto(int slot) {
        protoStatic = false;
        GuiItemDef item = cell.def;
        if (item == null) {
            return null;
        }
        boolean disabled = cell.navDisabled;
        ItemStack overlay = cell.stack;
        if (overlay == null && item.cacheable(disabled)) {
            protoStatic = true;
            ItemStack cached = item.cachedProto(disabled);
            return cached != null ? cached : item.cacheProto(disabled, build(item.look(disabled), NO_PHS, null, null));
        }
        ItemAppearance look = item.look(disabled);
        Consumer<String> skins = look.has("skull-owner") ? skinRefresh(item, slot, cell.bind) : null;
        if (cell.kind == Cell.DECLARED) {
            ItemStack built = passProto[cell.index];
            if (built == null) {
                built = build(look, NO_PHS, null, skins);
                passProto[cell.index] = built;
            }
            return built;
        }
        return build(look, cell.phs, overlay, skins);
    }

    /** {@link GuiHost#build} with {@code {key:relative}} in the plugin's language. */
    private ItemStack build(ItemAppearance look, Ph[] phs, ItemStack overlay, Consumer<String> skins) {
        return PluginLanguage.call(host.relativeTexts(), () -> host.build(look, viewer, phs, overlay, skins));
    }

    /** Called when a skin a cell waited for arrives: that cell is rendered again if it still shows the same thing. */
    private Consumer<String> skinRefresh(GuiItemDef item, int slot, Object bind) {
        int pageThen = session.page;
        return owner -> {
            Runnable again = () -> {
                if (session.closed || session.inventory == null) {
                    return;
                }
                if (bind instanceof SessionBinds.Paged<?> && (session.paged != bind || session.page != pageThen)) {
                    return;
                }
                if (bind instanceof SessionBinds.Manual && session.manual[slot] != bind) {
                    return;
                }
                if (bind instanceof SessionBinds.Region<?> && !Arrays.asList(session.regionBinds).contains(bind)) {
                    return;
                }
                beginPass();
                int count = cellCount();
                for (int s = 0; s < count; s++) {
                    if (shownDef[s] == item) {
                        resolve(s);
                        paint(s, false);
                    }
                }
            };
            if (host.ownsHere(viewer)) {
                again.run();
            } else {
                try {
                    host.runFor(viewer, again);
                } catch (RuntimeException disabled) {
                    // The plugin is going away.
                }
            }
        };
    }

    /** What one cell shows, worked out by {@code resolve}; reused, never kept. */
    static final class Cell {
        static final int EMPTY = 0;
        static final int DECLARED = 1;
        static final int MANUAL = 2;
        static final int PAGED = 3;
        static final int REGION = 4;

        int kind;
        GuiItemDef def;
        boolean navDisabled;
        Ph[] phs = NO_PHS;
        ItemStack stack;
        Object bind;
        int index;

        void clear() {
            kind = EMPTY;
            def = null;
            navDisabled = false;
            phs = NO_PHS;
            stack = null;
            bind = null;
            index = -1;
        }

        void set(int kind, GuiItemDef def, boolean navDisabled, Ph[] phs, ItemStack stack, Object bind, int index) {
            this.kind = kind;
            this.def = def;
            this.navDisabled = navDisabled;
            this.phs = phs;
            this.stack = stack;
            this.bind = bind;
            this.index = index;
        }
    }
}
