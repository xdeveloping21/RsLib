package com.rs.lib.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.logging.Level;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Edits a list (rewards, kit items, commands) in one of the plugin's menus
 * ({@code rs().guis().listEditor(menuId, list)}): add, remove and reorder, then save or cancel.
 * Every change goes into a copy; {@link #onSave} gets the edited list only when the player
 * saves. Cancelling, closing the window, leaving, a reload of the menus, another editor for the
 * same player and the plugin stopping all keep nothing.
 *
 * <pre>
 * rs().guis().itemEditor("kit-items", kit.items())
 *         .onSave(items -&gt; kits.save(kit.withItems(items)))
 *         .onEnd(end -&gt; kitMenu.open(player))
 *         .open(player);
 * </pre>
 *
 * <h2>The menu</h2>
 * <p>The layout is a normal menu of the plugin's {@code guis/} folder (seeded from the plugin's
 * own jar, like every menu). It needs {@code pagination: true}, a {@code paged-key} and a
 * template ({@value #DEFAULT_TEMPLATE} unless {@link #template} says otherwise), drawn once per
 * element with {@code {index}} (its position, from 1) plus what {@link #placeholders} adds,
 * over the element's {@link #icon} when there is one. Buttons and the template call the action
 * {@code [list-editor]}:</p>
 * <ul>
 *   <li>{@code [list-editor] save} and {@code [list-editor] cancel};</li>
 *   <li>{@code [list-editor] add}: a new element from {@link #factory};</li>
 *   <li>{@code [list-editor] add-hand}: a new element from a copy of the item in the main
 *       hand ({@link #fromItem});</li>
 *   <li>{@code [list-editor] remove {index}}, {@code [list-editor] move-up {index}} and
 *       {@code [list-editor] move-down {index}} on the template.</li>
 * </ul>
 * <p>The page arrows are the usual {@code [next-page]} and {@code [previous-page]}. With
 * {@link #fromItem}, items the player offers to the menu ({@code input: true} cells, or a
 * shift-click with {@code player-inventory: open}) are added too. The player's own stacks
 * never move: the editor keeps copies, so nothing is taken from or given back to anyone. The
 * menu's stacks are stamped like any menu's, so they cannot be taken out of it.</p>
 *
 * <h2>One ending</h2>
 * <p>Every ending goes through one path that runs once: the editor is forgotten, its menu
 * closes, {@link #onSave} runs (only for {@link End#SAVED}, with the edited copy) and then
 * {@link #onEnd}, a tick later on the player's thread (at once for {@link End#LEFT},
 * {@link End#SHUT_DOWN}, or when nothing can be scheduled).</p>
 *
 * @param <T> the element type
 */
public final class ListEditor<T> {

    /** The action tag of the editor's buttons. */
    public static final String ACTION = "list-editor";
    /** The template drawn for each element unless {@link #template} says otherwise. */
    public static final String DEFAULT_TEMPLATE = "entry";

    static final String EMPTY_HAND = "rslib.editor.empty-hand";
    static final String ITEM_REFUSED = "rslib.editor.item-refused";

    /** How an editor ended. Exactly one, once, per editor. */
    public enum End {
        /** The player pressed save: the only ending that hands the list over. */
        SAVED,
        /** The cancel button, the window closed, the menus reloaded, the menu missing or broken, or the plugin cancelled it. */
        CANCELLED,
        /** The player left the server. */
        LEFT,
        /** Another editor was opened for the same player. */
        REPLACED,
        /** The plugin is shutting down. */
        SHUT_DOWN
    }

    private final GuiManager manager;
    private final String menuId;
    private final List<T> source;
    private final AtomicBoolean opened = new AtomicBoolean();
    private final AtomicBoolean ended = new AtomicBoolean();

    private String templateId = DEFAULT_TEMPLATE;
    private Function<? super T, ItemStack> icon;
    private BiConsumer<? super T, PhCollector> placeholders;
    private Function<ItemStack, ? extends T> fromItem;
    private Function<Player, ? extends CompletionStage<? extends T>> factory;
    private UnaryOperator<T> copier = UnaryOperator.identity();
    private Consumer<List<T>> onSave;
    private Consumer<End> onEnd;

    private volatile Player viewer;
    /** The working copy; touched only on the viewer's thread. */
    private final List<T> working = new ArrayList<>();
    private volatile GuiSession session;
    private volatile boolean aside;
    /** The stage the editor stepped aside for. */
    private volatile CompletableFuture<? extends T> pending;
    private int page = 1;
    private volatile End endReason;

    ListEditor(GuiManager manager, String menuId, List<T> source) {
        this.manager = manager;
        this.menuId = Objects.requireNonNull(menuId, "menuId");
        this.source = source == null ? List.of() : source;
    }

    // ------------------------------------------------------------------ settings

    /** The template drawn for each element; {@value #DEFAULT_TEMPLATE} by default. */
    public ListEditor<T> template(String templateId) {
        requireNotOpened();
        this.templateId = Objects.requireNonNull(templateId, "templateId");
        return this;
    }

    /** The stack an element is drawn over (copied); null, or no icon, draws the template alone. */
    public ListEditor<T> icon(Function<? super T, ItemStack> icon) {
        requireNotOpened();
        this.icon = icon;
        return this;
    }

    /** Adds an element's placeholders to its template, besides {@code {index}}. */
    public ListEditor<T> placeholders(BiConsumer<? super T, PhCollector> placeholders) {
        requireNotOpened();
        this.placeholders = placeholders;
        return this;
    }

    /**
     * Turns a copy of an item the player offers or holds into an element (null refuses it).
     * Without it, {@code add-hand} and offered items add nothing.
     */
    public ListEditor<T> fromItem(Function<ItemStack, ? extends T> fromItem) {
        requireNotOpened();
        this.fromItem = fromItem;
        return this;
    }

    /**
     * Makes the element {@code [list-editor] add} adds (a null answer adds nothing). A stage
     * already complete adds at once. One that is not (a chat question, a {@code Wizard}) closes
     * the menu while it waits, keeping the working copy, and opens it again at the same page
     * when it completes. Called on the player's thread.
     */
    public ListEditor<T> factory(Function<Player, ? extends CompletionStage<? extends T>> factory) {
        requireNotOpened();
        this.factory = factory;
        return this;
    }

    /** How each element of the list handed in is copied into the working copy; as it is by default. */
    public ListEditor<T> copier(UnaryOperator<T> copier) {
        requireNotOpened();
        this.copier = Objects.requireNonNull(copier, "copier");
        return this;
    }

    /** Gets the edited list (a new list, the player's order) when the player saves; on the player's thread. */
    public ListEditor<T> onSave(Consumer<List<T>> onSave) {
        requireNotOpened();
        this.onSave = onSave;
        return this;
    }

    /** Runs once on every ending, after {@link #onSave} for a saved one; see the class notes for when. */
    public ListEditor<T> onEnd(Consumer<End> onEnd) {
        requireNotOpened();
        this.onEnd = onEnd;
        return this;
    }

    // ------------------------------------------------------------------ running

    /**
     * Copies the list and opens the menu for {@code player}; another editor of this plugin open
     * for them ends as {@link End#REPLACED}. Call it once. A menu that is missing, lacks
     * pagination, the template or the paged cells is reported and the editor ends as
     * {@link End#CANCELLED}.
     *
     * @throws IllegalStateException when called twice
     */
    public ListEditor<T> open(Player player) {
        Objects.requireNonNull(player, "player");
        if (!opened.compareAndSet(false, true)) {
            throw new IllegalStateException("This editor was already opened");
        }
        viewer = player;
        GuiHost host = manager.host();
        if (host.ownsHere(player)) {
            begin();
        } else {
            try {
                host.runFor(player, this::begin);
            } catch (RuntimeException disabled) {
                end(End.SHUT_DOWN);
            }
        }
        return this;
    }

    /** Ends the editor as {@link End#SAVED}; false when it had already ended. */
    public boolean save() {
        return end(End.SAVED);
    }

    /** Ends the editor as {@link End#CANCELLED}; false when it had already ended. */
    public boolean cancel() {
        return end(End.CANCELLED);
    }

    /** The working copy as it stands; read-only. */
    public List<T> elements() {
        return Collections.unmodifiableList(working);
    }

    public Player viewer() {
        return viewer;
    }

    public String menuId() {
        return menuId;
    }

    public boolean isEnded() {
        return ended.get();
    }

    /** How it ended, or null while it runs. */
    public End endReason() {
        return endReason;
    }

    // ------------------------------------------------------------------ editing

    /** Adds {@code element} at the end; false when ended or null. */
    boolean add(T element) {
        if (ended.get() || element == null) {
            return false;
        }
        working.add(element);
        render();
        return true;
    }

    /** Removes the element at {@code index} (from 0); false when there is none. */
    boolean remove(int index) {
        if (ended.get() || index < 0 || index >= working.size()) {
            return false;
        }
        working.remove(index);
        render();
        return true;
    }

    /** Swaps the element at {@code index} with the one before it; false for the first or none. */
    boolean moveUp(int index) {
        if (ended.get() || index <= 0 || index >= working.size()) {
            return false;
        }
        Collections.swap(working, index, index - 1);
        render();
        return true;
    }

    /** Swaps the element at {@code index} with the one after it; false for the last or none. */
    boolean moveDown(int index) {
        if (ended.get() || index < 0 || index >= working.size() - 1) {
            return false;
        }
        Collections.swap(working, index, index + 1);
        render();
        return true;
    }

    /** One {@code [list-editor]} line clicked in this editor's menu; {@code index} is from 1, or -1. */
    void command(String command, int index) {
        if (ended.get()) {
            return;
        }
        switch (command.toLowerCase(Locale.ROOT)) {
            case "save" -> end(End.SAVED);
            case "cancel" -> end(End.CANCELLED);
            case "add" -> create();
            case "add-hand" -> addHand();
            case "remove" -> remove(index - 1);
            case "move-up" -> moveUp(index - 1);
            case "move-down" -> moveDown(index - 1);
            default -> warn("[" + ACTION + "] " + command + ": unknown; use save, cancel, add, add-hand, remove,"
                    + " move-up or move-down");
        }
    }

    private void addHand() {
        if (fromItem == null) {
            warn("[" + ACTION + "] add-hand needs the editor's fromItem(...)");
            return;
        }
        ItemStack hand = viewer.getInventory().getItemInMainHand();
        if (GuiListener.empty(hand)) {
            manager.editors().send(viewer, EMPTY_HAND);
            return;
        }
        addItem(hand);
    }

    /** An item offered to the menu: a copy may become an element; the player keeps theirs. */
    private void offered(ItemOffer offer) {
        if (ended.get()) {
            return;
        }
        if (fromItem == null) {
            manager.host().debug(() -> "An item offered to list editor " + menuId + " was ignored: no fromItem(...).");
            return;
        }
        addItem(offer.stack());
        try {
            viewer.updateInventory();
        } catch (RuntimeException ignored) {
            // Only the client's drawing of the cancelled move; the next update fixes it.
        }
    }

    private void addItem(ItemStack stack) {
        T element;
        try {
            element = fromItem.apply(stack.clone());
        } catch (RuntimeException e) {
            manager.host().logger().log(Level.WARNING, "fromItem of list editor " + menuId + " failed", e);
            element = null;
        }
        if (element == null) {
            manager.editors().send(viewer, ITEM_REFUSED);
            return;
        }
        add(element);
    }

    private void create() {
        if (factory == null) {
            warn("[" + ACTION + "] add needs the editor's factory(...)");
            return;
        }
        CompletableFuture<? extends T> future;
        try {
            CompletionStage<? extends T> stage = factory.apply(viewer);
            if (stage == null) {
                return;
            }
            future = stage.toCompletableFuture();
        } catch (RuntimeException e) {
            manager.host().logger().log(Level.WARNING, "The factory of list editor " + menuId + " failed", e);
            return;
        }
        if (future.isDone()) {
            add(made(future));
            return;
        }
        stepAside();
        pending = future;
        future.whenComplete((value, failure) -> resume());
    }

    /** The window comes down for a question; the working copy stays. */
    private void stepAside() {
        GuiSession shown = session;
        aside = true;
        session = null;
        if (shown != null) {
            page = shown.page();
            shown.onEnd(null);
            shown.onOffer(null);
            shown.close();
        }
    }

    /** The stage the editor stepped aside for completed: add its element and show the menu again. */
    private void resume() {
        Runnable back = () -> {
            CompletableFuture<? extends T> future = pending;
            if (ended.get() || !aside || future == null || !future.isDone()) {
                return;
            }
            pending = null;
            aside = false;
            T made = made(future);
            if (made != null) {
                working.add(made);
            }
            if (!viewer.isOnline()) {
                end(End.LEFT);
                return;
            }
            show(page);
        };
        GuiHost host = manager.host();
        try {
            host.runFor(viewer, back);
        } catch (RuntimeException disabled) {
            end(End.SHUT_DOWN);
        }
    }

    private T made(CompletableFuture<? extends T> future) {
        try {
            return future.getNow(null);
        } catch (CompletionException | CancellationException e) {
            manager.host().logger().log(Level.WARNING, "The factory of list editor " + menuId + " failed", e);
            return null;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    private void begin() {
        if (ended.get()) {
            return;
        }
        if (!viewer.isOnline()) {
            end(End.LEFT);
            return;
        }
        if (manager.refusesOpening()) {
            end(End.SHUT_DOWN);
            return;
        }
        for (T element : source) {
            working.add(element == null ? null : copier.apply(element));
        }
        working.removeIf(Objects::isNull);
        ListEditor<?> previous = manager.editors().register(this);
        if (previous != null) {
            previous.end(End.REPLACED);
        }
        show(1);
    }

    /** Opens the menu at {@code at} and binds the working copy into it. */
    private void show(int at) {
        if (ended.get()) {
            return;
        }
        Gui gui = manager.get(menuId);
        String problem = gui == null ? "there is no menu '" + menuId + "'" : problem(gui.def());
        if (problem != null) {
            GuiWarnings.once(manager.host().logger(), "List editor: " + problem + "; the editor is cancelled.");
            end(End.CANCELLED);
            return;
        }
        gui.open(viewer, at);
        GuiSession opened = gui.session(viewer);
        if (opened == null) {
            end(End.CANCELLED);
            return;
        }
        session = opened;
        opened.onOffer(this::offered);
        opened.onEnd(() -> sessionEnded(opened));
        render();
    }

    /** Why {@code def} cannot show an editor, or null. */
    private String problem(GuiDef def) {
        if (!def.pagination()) {
            return def.file() + " needs 'pagination: true'";
        }
        if (def.template(templateId) == null) {
            return def.file() + " has no template '" + templateId + "'";
        }
        if (def.pagedSlots().length == 0) {
            return def.file() + " has no paged-key cells";
        }
        return null;
    }

    private void sessionEnded(GuiSession which) {
        if (which == session && !aside) {
            end(End.CANCELLED);
        }
    }

    private void render() {
        GuiSession shown = session;
        if (shown == null || shown.closed() || ended.get()) {
            return;
        }
        List<Indexed<T>> rows = new ArrayList<>(working.size());
        for (int i = 0; i < working.size(); i++) {
            rows.add(new Indexed<>(i, working.get(i)));
        }
        shown.bindPaged(templateId, rows, this::fill);
    }

    private void fill(Indexed<T> row, PhCollector phs) {
        phs.add("index", row.index() + 1);
        if (icon != null) {
            phs.stack(icon.apply(row.value()));
        }
        if (placeholders != null) {
            placeholders.accept(row.value(), phs);
        }
    }

    /**
     * Ends the editor once: forgets it, closes its menu, hands the copy to {@link #onSave} for
     * {@link End#SAVED} and runs {@link #onEnd}. Each part runs even when another fails.
     *
     * @return false when it had already ended
     */
    boolean end(End reason) {
        if (!ended.compareAndSet(false, true)) {
            return false;
        }
        endReason = reason;
        GuiHost host = manager.host();
        GuiSession shown = session;
        session = null;
        pending = null;
        try {
            manager.editors().forget(this);
        } catch (RuntimeException e) {
            host.logger().log(Level.WARNING, "Could not forget list editor " + menuId, e);
        }
        if (shown != null) {
            try {
                shown.onEnd(null);
                shown.onOffer(null);
                shown.close();
            } catch (RuntimeException e) {
                host.logger().log(Level.WARNING, "Could not close list editor " + menuId, e);
            }
        }
        if (reason == End.SAVED && onSave != null) {
            try {
                onSave.accept(new ArrayList<>(working));
            } catch (RuntimeException e) {
                host.logger().log(Level.SEVERE, "The save action of list editor " + menuId + " failed", e);
            }
        }
        if (onEnd != null) {
            Runnable after = () -> {
                try {
                    onEnd.accept(reason);
                } catch (RuntimeException e) {
                    host.logger().log(Level.SEVERE, "The end action of list editor " + menuId + " failed", e);
                }
            };
            Player player = viewer;
            boolean now = reason == End.LEFT || reason == End.SHUT_DOWN || player == null;
            if (!now) {
                try {
                    host.later(player, 1L, after);
                } catch (RuntimeException disabled) {
                    now = true;
                }
            }
            if (now) {
                after.run();
            }
        }
        return true;
    }

    /** Whether {@code shown} is this editor's menu right now. */
    boolean shows(GuiSession shown) {
        return shown != null && shown == session;
    }

    private void warn(String message) {
        Gui gui = manager.get(menuId);
        if (gui != null) {
            gui.warn(message);
        } else {
            GuiWarnings.once(manager.host().logger(), message);
        }
    }

    private void requireNotOpened() {
        if (opened.get()) {
            throw new IllegalStateException("A list editor cannot change after open()");
        }
    }

    @Override
    public String toString() {
        return "ListEditor[" + menuId + " " + working.size() + (endReason == null ? "" : " " + endReason) + "]";
    }

    /** An element and its position. */
    private record Indexed<T>(int index, T value) {
    }
}
