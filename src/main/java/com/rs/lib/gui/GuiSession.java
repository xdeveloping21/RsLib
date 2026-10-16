package com.rs.lib.gui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import com.rs.lib.Ph;
import com.rs.lib.action.ActionContext;
import com.rs.lib.action.ActionList;
import com.rs.lib.action.PageTarget;
import com.rs.lib.action.Requirement;
import com.rs.lib.item.ItemAppearance;
import com.rs.lib.scheduler.TaskHandle;

import net.kyori.adventure.text.Component;

/**
 * One player's open menu: its own inventory, page and binds. Get it with
 * {@link Gui#session(Player)} after {@link Gui#open}; it is also the {@link PageTarget} of the
 * page actions run from its clicks ({@code ctx.pageTarget()}).
 *
 * <h2>What a cell shows</h2>
 * <p>A manual bind ({@link #bind(int, GuiTemplate, Ph...)}) wins over the paged bind, which wins
 * over a region ({@link #bindEach}), which wins over the items the file declares. The paged
 * bind owns its cells even where the page is short; a region only owns the cells it really
 * fills (an entry, a template, view requirements passed), the others show the declared item
 * under them. Declared items sharing a cell are tried in file order and the first whose
 * {@code view-requirements} pass for this player wins. Regions are painted in file order, so
 * a later region wins where two overlap. A click asks the same question again at that moment
 * (requirements re-evaluated): a cell that shows nothing runs nothing, not even
 * {@code deny-actions}.</p>
 *
 * <h2>Rendering</h2>
 * <p>Only cells whose stack changed are written to the inventory. Items that look the same to
 * everyone are built once per menu file and reused by every session, and a refresh with
 * nothing changed builds and writes nothing for them. A plugin that took the inventory through
 * {@link RsGuiHolder#getInventory()} gets every cell rewritten on the next render.</p>
 *
 * <h2>Threads</h2>
 * <p>A session belongs to its player's thread (the main thread on Paper, the player's region on
 * Folia); calls made elsewhere are moved there. Mappers, fillers and the offer handler run on
 * that thread, inside renders and inventory events: they must be cheap and never block.</p>
 */
public final class GuiSession implements PageTarget {

    private static final Ph[] NO_PHS = new Ph[0];
    private static final byte UNKNOWN = 0;
    private static final byte SHOWN = 1;
    private static final byte SHOWN_DISABLED = 2;
    private static final byte HIDDEN = 3;

    private final Gui gui;
    private final GuiDef def;
    private final GuiHost host;
    private final Player viewer;
    private final UUID viewerId;
    private final RsGuiHolder holder;
    private final int size;
    private final ManualBind[] manual;
    private final RegionBind<?>[] regionBinds;
    private final ItemStack[] shownProto;
    private final GuiItemDef[] shownDef;
    private final boolean[] regionInput;
    private final byte[] verdict;
    private final ItemStack[] passProto;
    private final Cell cell = new Cell();
    private final List<TaskHandle> timers = new ArrayList<>(2);

    private Inventory inventory;
    private int page;
    private int declaredTotal;
    private Ph[] titlePhs;
    private Component lastTitle;
    private PagedBind<?> paged;
    private Consumer<ItemOffer> offerHandler;
    private volatile Runnable endHook;
    private boolean protoStatic;
    private volatile boolean closed;
    private volatile boolean transitioning;
    private volatile boolean external;

    GuiSession(Gui gui, GuiDef def, GuiHost host, Player viewer, int page, Ph[] titlePhs) {
        this.gui = gui;
        this.def = def;
        this.host = host;
        this.viewer = viewer;
        this.viewerId = viewer.getUniqueId();
        this.holder = new RsGuiHolder(host.plugin(), this);
        this.size = def.size();
        this.manual = new ManualBind[size];
        this.regionBinds = new RegionBind<?>[def.regionCount()];
        this.shownProto = new ItemStack[size];
        this.shownDef = new GuiItemDef[size];
        this.regionInput = new boolean[size];
        this.verdict = new byte[def.items().size()];
        this.passProto = new ItemStack[def.items().size()];
        this.page = def.pagination() ? Math.max(1, page) : 1;
        this.titlePhs = copy(titlePhs);
    }

    // ------------------------------------------------------------------ reading

    public Player viewer() {
        return viewer;
    }

    public UUID viewerId() {
        return viewerId;
    }

    public String guiId() {
        return def.id();
    }

    /** The menu as it was loaded when this session opened. */
    public GuiDef def() {
        return def;
    }

    public Gui gui() {
        return gui;
    }

    /** The current page, from 1. */
    public int page() {
        return page;
    }

    /** True while the session swaps its inventory for a new one (a title change); its close event is ignored. */
    public boolean transitioningPage() {
        return transitioning;
    }

    public boolean closed() {
        return closed;
    }

    public PlayerInventoryPolicy playerInventory() {
        return def.playerInventory();
    }

    @Override
    public boolean paginationEnabled() {
        return def.pagination();
    }

    /** The holder of this session's inventory. */
    public RsGuiHolder holder() {
        return holder;
    }

    /**
     * What cell {@code slot} shows for the viewer right now (view requirements checked again),
     * or null when it shows nothing.
     */
    public GuiItemDef itemAt(int slot) {
        if (slot < 0 || slot >= cellCount()) {
            return null;
        }
        beginPass();
        resolve(slot);
        return cell.def;
    }

    /**
     * True when {@code slot} receives offered items: a declared item there has
     * {@code input: true}, or the template bound there or painted there by a region has it.
     * Structural: no requirement is checked.
     */
    public boolean isInputSlot(int slot) {
        if (slot < 0 || slot >= size) {
            return false;
        }
        ManualBind bound = manual[slot];
        return def.declaredInput(slot) || (bound != null && bound.template().item().input()) || regionInput[slot];
    }

    // ------------------------------------------------------------------ binds

    /** Binds template {@code templateId} in the cells it declares itself ({@code slots:} or {@code key:}). */
    public void bind(String templateId, Ph... phs) {
        if (hop(() -> bind(templateId, phs))) {
            return;
        }
        GuiTemplate template = def.template(templateId);
        if (template == null) {
            gui.warn("bind(\"" + templateId + "\"): the menu has no template with that id");
            return;
        }
        if (!template.hasSlots()) {
            gui.warn("bind(\"" + templateId + "\"): the template declares no slots or key; bind it to a slot instead");
            return;
        }
        Ph[] values = copy(phs);
        for (int slot : template.item().slotsInternal()) {
            if (slot < size) {
                manual[slot] = new ManualBind(template, null, values);
            }
        }
        renderSlots(template.item().slotsInternal());
    }

    /** Binds {@code template} in cell {@code slot} with {@code phs}. */
    public void bind(int slot, GuiTemplate template, Ph... phs) {
        bind(slot, template, null, phs);
    }

    /**
     * Binds {@code template} in cell {@code slot}. With a {@code stack} (copied), the stack gives
     * the look and the template only adds its {@code display-name} (replacing the name when not
     * empty) and its {@code lore} lines (after the stack's own) on top; requirements and actions
     * still come from the template.
     */
    public void bind(int slot, GuiTemplate template, ItemStack stack, Ph... phs) {
        if (hop(() -> bind(slot, template, stack, phs))) {
            return;
        }
        if (template == null) {
            gui.warn("bind(" + slot + ", null): no template given");
            return;
        }
        if (slot < 0 || slot >= size) {
            gui.warn("bind(" + slot + ", " + template.id() + "): the menu has no cell " + slot);
            return;
        }
        manual[slot] = new ManualBind(current(template), stack == null ? null : stack.clone(), copy(phs));
        renderSlot(slot);
    }

    /**
     * Fills region {@code regionId} with one entry per cell: entry i goes to cell i of the region
     * and {@code filler} chooses its template, placeholders and stack on every render. Binding
     * again replaces the whole region; extra entries are not shown, cells without an entry show
     * the declared item under them.
     */
    public <T> void bindEach(String regionId, List<T> data, BiConsumer<T, GuiEntry> filler) {
        if (hop(() -> bindEach(regionId, data, filler))) {
            return;
        }
        int number = regionId == null ? -1 : def.regionNumber(regionId);
        if (number < 0) {
            gui.warn("bindEach(\"" + regionId + "\"): the menu declares no region with that id");
            return;
        }
        regionBinds[number] = filler == null ? null : new RegionBind<>(data == null ? List.of() : new ArrayList<>(data),
                filler);
        renderSlots(def.regionSlots(regionId));
    }

    /**
     * Shows {@code data} a page at a time in {@code slots} (in that order) with template
     * {@code templateId}; {@code mapper} fills each entry's placeholders on every render. Needs
     * {@code pagination: true}. The cells belong to this bind even where the page is short.
     */
    public <T> void bindPaged(String templateId, List<T> data, int[] slots, BiConsumer<T, PhCollector> mapper) {
        if (hop(() -> bindPaged(templateId, data, slots, mapper))) {
            return;
        }
        if (!def.pagination()) {
            gui.warn("bindPaged(\"" + templateId + "\") is ignored: the menu does not declare 'pagination: true'");
            return;
        }
        GuiTemplate template = def.template(templateId);
        if (template == null) {
            gui.warn("bindPaged(\"" + templateId + "\"): the menu has no template with that id");
            return;
        }
        int[] cells = cells(slots);
        if (cells.length == 0) {
            gui.warn("bindPaged(\"" + templateId + "\"): no cells to page into (paged-key or slots)");
            return;
        }
        paged = new PagedBind<>(template, Pagination.of(data, cells.length), cells, positions(cells), mapper);
        renderAll(false);
    }

    /** {@link #bindPaged(String, List, int[], BiConsumer)} into the cells of {@code paged-key}, in {@code paged-flow} order. */
    public <T> void bindPaged(String templateId, List<T> data, BiConsumer<T, PhCollector> mapper) {
        bindPaged(templateId, data, def.pagedSlots(), mapper);
    }

    /** The cells of {@code slots} inside the menu, each once. */
    private int[] cells(int[] slots) {
        if (slots == null) {
            return new int[0];
        }
        boolean[] seen = new boolean[size];
        int[] out = new int[slots.length];
        int n = 0;
        for (int slot : slots) {
            if (slot >= 0 && slot < size && !seen[slot]) {
                seen[slot] = true;
                out[n++] = slot;
            }
        }
        return Arrays.copyOf(out, n);
    }

    private int[] positions(int[] cells) {
        int[] index = new int[size];
        Arrays.fill(index, -1);
        for (int i = 0; i < cells.length; i++) {
            index[cells[i]] = i;
        }
        return index;
    }

    /** {@code template} as this session's menu has it now (a template kept by the plugin across a reload). */
    private GuiTemplate current(GuiTemplate template) {
        GuiTemplate mine = def.template(template.id());
        return mine != null && template.item().guiId().equals(def.id()) ? mine : template;
    }

    // ------------------------------------------------------------------ offers

    /** Sets (null removes) what receives the items the player offers to this menu. */
    public void onOffer(Consumer<ItemOffer> handler) {
        this.offerHandler = handler;
    }

    /** Hands {@code offer} to the offer handler; without one it is dropped with a debug note. */
    public void handleOffer(ItemOffer offer) {
        Consumer<ItemOffer> handler = offerHandler;
        if (closed || offer == null) {
            return;
        }
        if (handler == null) {
            host.debug(() -> "An item offered to menu " + def.id() + " was dropped: no offer handler is set.");
            return;
        }
        try {
            handler.accept(offer);
        } catch (RuntimeException | LinkageError e) {
            host.logger().log(Level.WARNING, "The offer handler of menu " + def.id() + " failed", e);
        }
    }

    // ------------------------------------------------------------------ clicks

    /**
     * Runs cell {@code slot} as a button for {@code click}: what it shows is worked out again now;
     * nothing runs for an empty cell or a disabled navigation arrow, nor for a click
     * {@code strict-clicks} discards; otherwise the click actions run when the click
     * requirements pass, the deny actions when they do not.
     */
    public void handleClick(int slot, ClickType click) {
        if (closed || slot < 0 || slot >= cellCount()) {
            return;
        }
        beginPass();
        resolve(slot);
        GuiItemDef item = cell.def;
        boolean disabled = cell.navDisabled;
        Ph[] phs = cell.phs;
        if (item == null) {
            if (shownProto[slot] != null) {
                paint(slot, false);
            }
            return;
        }
        if (shownDef[slot] != item) {
            paint(slot, false);
        }
        if (disabled) {
            return;
        }
        if (def.strictClicks() && !item.strictAllows(click)) {
            return;
        }
        ActionContext context = new ActionContext(viewer, host.rs(), this, click, phs);
        Requirement requirement = item.requirementFor(click);
        if (host.actions().test(requirement, context)) {
            item.actionsFor(click).run(context);
        } else {
            item.denyFor(click).run(context);
        }
    }

    // ------------------------------------------------------------------ pages

    @Override
    public void nextPage() {
        if (!pageable()) {
            return;
        }
        int total = totalPages();
        if (total > 0 && page >= total) {
            return;
        }
        changePage(page + 1);
    }

    @Override
    public void previousPage() {
        if (!pageable() || page <= 1) {
            return;
        }
        changePage(page - 1);
    }

    @Override
    public void setPage(int page) {
        if (!pageable()) {
            return;
        }
        int target = Math.max(1, page);
        int total = totalPages();
        if (total > 0) {
            target = Math.min(target, total);
        }
        changePage(target);
    }

    /** Renders the content again (only with {@code pagination: true}). */
    @Override
    public void refreshPage() {
        if (hop(this::refreshPage)) {
            return;
        }
        if (closed || !def.pagination()) {
            return;
        }
        renderAll(false);
    }

    /** Renders everything again: a changed title brings a new inventory, otherwise only the content. */
    @Override
    public void refreshMenu() {
        if (hop(this::refreshMenu)) {
            return;
        }
        if (closed || inventory == null) {
            return;
        }
        Component title = host.title(def, viewer, titlePhs);
        if (!title.equals(lastTitle)) {
            recreate(title);
        } else {
            renderAll(false);
        }
    }

    /**
     * Sets how many pages there are when no paged bind says it (a plugin paging a region itself);
     * 0 means unknown, and then "next" is never disabled. Like the page changes, it needs
     * {@code pagination: true}.
     */
    public void setTotalPages(int total) {
        if (hop(() -> setTotalPages(total))) {
            return;
        }
        if (!pageable()) {
            return;
        }
        declaredTotal = Math.max(0, total);
        if (!closed && inventory != null) {
            renderAll(false);
        }
    }

    /** Sets the title placeholders and shows the new title (a new inventory if it changed). */
    public void titlePlaceholders(Ph... phs) {
        if (hop(() -> titlePlaceholders(phs))) {
            return;
        }
        titlePhs = copy(phs);
        refreshMenu();
    }

    /** Pages known: those of the paged bind, else those set with {@link #setTotalPages}; 0 is unknown. */
    int totalPages() {
        PagedBind<?> bound = paged;
        return bound != null ? bound.data().totalPages() : declaredTotal;
    }

    private boolean pageable() {
        if (!def.pagination()) {
            host.debug(() -> "Page change ignored: menu " + def.id() + " does not declare 'pagination: true'.");
            return false;
        }
        return !closed;
    }

    private void changePage(int target) {
        if (hop(() -> changePage(target))) {
            return;
        }
        page = target;
        refreshMenu();
    }

    // ------------------------------------------------------------------ lifecycle

    /** First open: inventory, content, window, sound, timers. */
    void start() {
        lastTitle = host.title(def, viewer, titlePhs);
        inventory = host.createInventory(holder, def, lastTitle);
        renderAll(true);
        host.openWindow(viewer, inventory);
        if (!host.viewing(viewer, inventory)) {
            host.debug(() -> "Menu " + def.id() + " did not open for " + viewer.getName() + " (another plugin refused it).");
            teardown();
            return;
        }
        host.sound(viewer, def.openSound());
        startTimers();
    }

    /** {@link Gui#open} on a live session: binds kept, content refreshed, window and sound only if not shown. */
    void reopen(int page, Ph[] phs) {
        if (phs != null && phs.length > 0) {
            titlePhs = copy(phs);
        }
        this.page = def.pagination() ? Math.max(1, page) : 1;
        boolean shown = host.viewing(viewer, inventory);
        Component title = host.title(def, viewer, titlePhs);
        if (!title.equals(lastTitle)) {
            recreate(title);
        } else {
            renderAll(false);
            if (!shown) {
                host.openWindow(viewer, inventory);
            }
        }
        if (!shown && !closed) {
            host.sound(viewer, def.openSound());
        }
    }

    /** A new inventory with {@code title}: same holder, session and binds; its close event is ignored. */
    private void recreate(Component title) {
        transitioning = true;
        try {
            lastTitle = title;
            inventory = host.createInventory(holder, def, title);
            Arrays.fill(shownProto, null);
            Arrays.fill(shownDef, null);
            renderAll(true);
            host.openWindow(viewer, inventory);
        } finally {
            transitioning = false;
        }
        if (!host.viewing(viewer, inventory)) {
            teardown();
        }
    }

    /** The inventory's close event: ignored during a swap or after a programmatic close. */
    void closedByEvent() {
        if (transitioning || closed) {
            return;
        }
        handleClose();
    }

    /**
     * A natural close (the player closed it, {@code [close]}, another inventory opened): the
     * session ends, {@code close-sound} plays now and {@code close-actions} run on the next tick,
     * without a click type.
     */
    public void handleClose() {
        if (closed) {
            return;
        }
        teardown();
        if (!viewer.isOnline()) {
            return;
        }
        host.sound(viewer, def.closeSound());
        ActionList actions = def.closeActionList();
        if (!actions.isEmpty() && !host.shuttingDown()) {
            ActionContext context = new ActionContext(viewer, host.rs(), this, null, titlePhs);
            try {
                host.later(viewer, 1L, () -> {
                    // The server closes the inventory before the player's quit event: by now the
                    // player may be gone.
                    if (viewer.isOnline() && !host.shuttingDown()) {
                        actions.run(context);
                    }
                });
            } catch (RuntimeException disabled) {
                host.debug(() -> "close-actions of " + def.id() + " not run: the plugin is being disabled.");
            }
        }
    }

    /** Ends the session without close sound or close actions, then closes the window. */
    public void close() {
        if (closed) {
            return;
        }
        teardown();
        Inventory shown = inventory;
        if (shown == null) {
            return;
        }
        Runnable window = () -> {
            if (viewer.isOnline() && host.viewing(viewer, shown)) {
                host.closeWindow(viewer);
            }
        };
        if (host.ownsHere(viewer)) {
            window.run();
        } else {
            try {
                host.runFor(viewer, window);
            } catch (RuntimeException disabled) {
                // The plugin is going away; the server closes the window itself.
            }
        }
    }

    private void teardown() {
        closed = true;
        for (TaskHandle timer : timers) {
            timer.cancel();
        }
        timers.clear();
        offerHandler = null;
        gui.forget(this);
        Runnable hook = endHook;
        endHook = null;
        if (hook != null) {
            try {
                hook.run();
            } catch (RuntimeException | LinkageError e) {
                host.logger().log(Level.WARNING, "The end hook of menu " + def.id() + " failed", e);
            }
        }
    }

    /**
     * Runs {@code hook} once when this session ends, however it ends (a close by the player, by
     * the plugin, a reload, the player leaving); at once when it already ended. Null removes it.
     * Used by {@link ListEditor}.
     */
    void onEnd(Runnable hook) {
        endHook = hook;
        if (closed && hook != null) {
            endHook = null;
            hook.run();
        }
    }

    private void startTimers() {
        try {
            if (def.updateInterval() > 0) {
                timers.add(host.timer(viewer, def.updateInterval(), this::menuTick));
            }
            for (GuiItemDef item : def.items()) {
                if (item.updateInterval() > 0) {
                    timers.add(host.timer(viewer, item.updateInterval(), () -> itemTick(item)));
                }
            }
        } catch (RuntimeException disabled) {
            host.debug(() -> "Timers of menu " + def.id() + " not started: the plugin is being disabled.");
        }
    }

    private void menuTick() {
        if (closed) {
            return;
        }
        if (!host.viewing(viewer, inventory)) {
            teardown();
            return;
        }
        refreshMenu();
    }

    /**
     * Renders again the cells the declared {@code item} paints or may paint. A template's own
     * {@code update-interval} is never scheduled: only the menu's timer refreshes the cells
     * templates paint.
     */
    private void itemTick(GuiItemDef item) {
        if (closed) {
            return;
        }
        int index = def.items().indexOf(item);
        beginPass();
        boolean force = takeExternal();
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

    /** Moves the call to the viewer's thread when made elsewhere; true when it was moved. */
    private boolean hop(Runnable again) {
        if (host.ownsHere(viewer)) {
            return false;
        }
        try {
            host.runFor(viewer, again);
        } catch (RuntimeException disabled) {
            host.debug(() -> "A menu call for " + viewer.getName() + " was dropped: the plugin is being disabled.");
        }
        return true;
    }

    /** The inventory, for {@link RsGuiHolder#getInventory()}: the next render then rewrites every cell. */
    Inventory inventoryForPlugin() {
        external = true;
        return inventory;
    }

    /** The inventory, for RsLib's own code. */
    Inventory inventory() {
        return inventory;
    }

    // ------------------------------------------------------------------ rendering

    private int cellCount() {
        Inventory shown = inventory;
        return shown == null ? size : Math.min(size, shown.getSize());
    }

    private boolean takeExternal() {
        boolean was = external;
        external = false;
        return was;
    }

    void renderAll(boolean force) {
        if (closed || inventory == null) {
            return;
        }
        beginPass();
        boolean all = force | takeExternal();
        int count = cellCount();
        for (int slot = 0; slot < count; slot++) {
            resolve(slot);
            paint(slot, all);
        }
    }

    private void renderSlots(int[] slots) {
        if (closed || inventory == null) {
            return;
        }
        beginPass();
        boolean all = takeExternal();
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

    private void renderSlot(int slot) {
        renderSlots(new int[] {slot});
    }

    /** Starts a render or click pass: declared items are judged once per pass. */
    private void beginPass() {
        Arrays.fill(verdict, UNKNOWN);
        Arrays.fill(passProto, null);
        PagedBind<?> bound = paged;
        if (bound != null) {
            page = bound.data().clamp(page);
        }
    }

    /** Works out what {@code slot} shows into {@link #cell}. */
    private void resolve(int slot) {
        cell.clear();
        ManualBind bound = manual[slot];
        if (bound != null) {
            GuiItemDef item = bound.template().item();
            if (visible(item, bound.phs())) {
                cell.set(Cell.MANUAL, item, false, bound.phs(), bound.stack(), bound, -1);
            }
            return;
        }
        PagedBind<?> pages = paged;
        if (pages != null && pages.positions()[slot] >= 0) {
            resolvePaged(pages, slot);
            return;
        }
        for (int r = regionBinds.length - 1; r >= 0; r--) {
            RegionBind<?> region = regionBinds[r];
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

    private <T> void resolvePaged(PagedBind<T> pages, int slot) {
        int index = (page - 1) * pages.slots().length + pages.positions()[slot];
        if (!pages.data().has(index)) {
            return;
        }
        PhCollector collector = new PhCollector();
        try {
            pages.mapper().accept(pages.data().at(index), collector);
        } catch (RuntimeException | LinkageError e) {
            gui.warn("The bindPaged mapper of template " + pages.template().id() + " failed: " + e);
            return;
        }
        GuiItemDef item = pages.template().item();
        Ph[] phs = collector.toArray();
        if (visible(item, phs)) {
            cell.set(Cell.PAGED, item, false, phs, collector.stackOrNull(), pages, -1);
        }
    }

    private <T> boolean resolveRegion(RegionBind<T> region, int number, int position) {
        GuiEntry entry = new GuiEntry();
        try {
            region.filler().accept(region.data().get(position), entry);
        } catch (RuntimeException | LinkageError e) {
            gui.warn("The bindEach filler of region " + def.regionId(number) + " failed: " + e);
            return false;
        }
        if (entry.templateId() == null) {
            return false;
        }
        GuiTemplate template = def.template(entry.templateId());
        if (template == null) {
            gui.warn("bindEach(\"" + def.regionId(number) + "\"): the menu has no template '" + entry.templateId() + "'");
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
    boolean navDisabled(GuiItemDef item) {
        if (item.navPrevious() && page <= 1) {
            return true;
        }
        int total = totalPages();
        return item.navNext() && total > 0 && page >= total;
    }

    private boolean visible(GuiItemDef item, Ph[] phs) {
        Requirement view = item.viewRequirement();
        if (view == Requirement.ALWAYS) {
            return true;
        }
        return host.actions().test(view, new ActionContext(viewer, host.rs(), this, null, phs));
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
        inventory.setItem(slot, stack);
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
            return cached != null ? cached : item.cacheProto(disabled, host.build(item.look(disabled), viewer, NO_PHS,
                    null, null));
        }
        ItemAppearance look = item.look(disabled);
        Consumer<String> skins = look.has("skull-owner") ? skinRefresh(item, slot, cell.bind) : null;
        if (cell.kind == Cell.DECLARED) {
            ItemStack built = passProto[cell.index];
            if (built == null) {
                built = host.build(look, viewer, NO_PHS, null, skins);
                passProto[cell.index] = built;
            }
            return built;
        }
        return host.build(look, viewer, cell.phs, overlay, skins);
    }

    /** Called when a skin a cell waited for arrives: that cell is rendered again if it still shows the same thing. */
    private Consumer<String> skinRefresh(GuiItemDef item, int slot, Object bind) {
        int pageThen = page;
        return owner -> {
            Runnable again = () -> {
                if (closed || inventory == null) {
                    return;
                }
                if (bind instanceof PagedBind<?> && (paged != bind || page != pageThen)) {
                    return;
                }
                if (bind instanceof ManualBind && manual[slot] != bind) {
                    return;
                }
                if (bind instanceof RegionBind<?> && !Arrays.asList(regionBinds).contains(bind)) {
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

    private static Ph[] copy(Ph[] phs) {
        return phs == null || phs.length == 0 ? NO_PHS : phs.clone();
    }

    @Override
    public String toString() {
        return "GuiSession[" + def.id() + " " + viewer.getName() + " page " + page + (closed ? " closed" : "") + "]";
    }

    // ------------------------------------------------------------------ binds and cells

    /** A template bound to one cell. */
    private record ManualBind(GuiTemplate template, ItemStack stack, Ph[] phs) {
    }

    /** The paged bind: its data, cells (in fill order) and mapper. */
    private record PagedBind<T>(GuiTemplate template, Pagination<T> data, int[] slots, int[] positions,
                                BiConsumer<T, PhCollector> mapper) {
    }

    /** A bound region: one entry per cell. */
    private record RegionBind<T>(List<T> data, BiConsumer<T, GuiEntry> filler) {
    }

    /** What one cell shows, worked out by {@code resolve}; reused, never kept. */
    private static final class Cell {
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
