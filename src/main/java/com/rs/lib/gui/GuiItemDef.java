package com.rs.lib.gui;

import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import com.rs.lib.Ph;
import com.rs.lib.action.ActionList;
import com.rs.lib.action.Requirement;
import com.rs.lib.item.ItemAppearance;

/**
 * One entry of {@code items:} or {@code templates:} of a menu file, parsed once and shared by
 * every session: where it goes, how it looks ({@link ItemAppearance}, rendered per viewer) and
 * what a click does.
 *
 * <h2>Clicks</h2>
 * <p>Besides {@code click-actions}, {@code click-requirements} and {@code deny-actions}, each
 * of the prefixes {@code right-click}, {@code left-click}, {@code shift-right-click},
 * {@code shift-left-click}, {@code middle-click} and {@code drop-click} has its own
 * {@code -actions}, {@code -requirements} and {@code -deny-actions}. Each of the three is
 * chosen on its own, the first one declared (not empty) in this order: the exact shift list
 * (SHIFT_RIGHT, SHIFT_LEFT); the side list ({@code right-click} covers RIGHT and SHIFT_RIGHT,
 * {@code left-click} covers LEFT, SHIFT_LEFT, DOUBLE_CLICK and CREATIVE, {@code middle-click}
 * covers MIDDLE); {@code drop-click} (DROP and CONTROL_DROP); the generic list. So an item can
 * declare {@code right-click-actions} and still use the generic {@code click-requirements}.
 * NUMBER_KEY, SWAP_OFFHAND and UNKNOWN always use the generic lists.</p>
 *
 * <p>Immutable once loaded and safe to share between threads.</p>
 */
public final class GuiItemDef {

    /** The click prefixes of the per-click lists, in key order. */
    enum Prefix {
        RIGHT("right-click"),
        LEFT("left-click"),
        SHIFT_RIGHT("shift-right-click"),
        SHIFT_LEFT("shift-left-click"),
        MIDDLE("middle-click"),
        DROP("drop-click");

        private final String key;

        Prefix(String key) {
            this.key = key;
        }

        String key() {
            return key;
        }
    }

    /**
     * The three lists of one prefix (or the generic ones).
     *
     * @param actions     {@code <prefix>-actions}
     * @param requirement {@code <prefix>-requirements}; {@link Requirement#ALWAYS} when not written
     * @param deny        {@code <prefix>-deny-actions}
     */
    record Clicks(ActionList actions, Requirement requirement, ActionList deny) {

        boolean hasActions() {
            return !actions.lines().isEmpty();
        }

        boolean hasRequirement() {
            return requirement != Requirement.ALWAYS;
        }

        boolean hasDeny() {
            return !deny.lines().isEmpty();
        }
    }

    /** Most cells a cached stamped stack is kept for (a double chest). */
    static final int MAX_CACHED_SLOT = 54;

    private static final int FIELD_ACTIONS = 0;
    private static final int FIELD_REQUIREMENT = 1;
    private static final int FIELD_DENY = 2;

    private final String id;
    private final String path;
    private final boolean template;
    private final int[] slots;
    private final boolean located;
    private final ItemAppearance appearance;
    private final ItemAppearance navDisabled;
    private final int updateInterval;
    private final boolean input;
    private final Requirement view;
    private final Clicks generic;
    private final Clicks[] byPrefix;
    private final boolean navPrevious;
    private final boolean navNext;
    private volatile String guiId = "";
    private final AtomicReferenceArray<ItemStack> protos = new AtomicReferenceArray<>(2);
    private final AtomicReferenceArray<ItemStack> stamped = new AtomicReferenceArray<>(2 * MAX_CACHED_SLOT);

    GuiItemDef(String id, String path, boolean template, int[] slots, boolean located, ItemAppearance appearance,
               ItemAppearance navDisabled, int updateInterval, boolean input, Requirement view, Clicks generic,
               Clicks[] byPrefix, boolean navPrevious, boolean navNext) {
        this.id = id;
        this.path = path;
        this.template = template;
        this.slots = slots;
        this.located = located;
        this.appearance = appearance;
        this.navDisabled = navDisabled;
        this.updateInterval = updateInterval;
        this.input = input;
        this.view = view;
        this.generic = generic;
        this.byPrefix = byPrefix;
        this.navPrevious = navPrevious;
        this.navNext = navNext;
    }

    // ------------------------------------------------------------------ public reading

    /** The key under {@code items:} or {@code templates:}. */
    public String id() {
        return id;
    }

    /** The cells from {@code slots:} or {@code key:} (empty when it declares neither). */
    public int[] slots() {
        return slots.clone();
    }

    /** True when {@code slots:} or a {@code key:} found in the layout places it. */
    public boolean hasSlots() {
        return located && slots.length > 0;
    }

    /** {@code update-interval} in ticks; 0 when it is not refreshed on its own. */
    public int updateInterval() {
        return updateInterval;
    }

    /** {@code input: true}: its cells receive items offered by the player. */
    public boolean input() {
        return input;
    }

    /** {@code view-requirements}: hidden (and not clickable) for whoever fails them. */
    public Requirement viewRequirement() {
        return view;
    }

    /** The generic {@code click-requirements}. */
    public Requirement clickRequirement() {
        return generic.requirement();
    }

    /** The generic {@code click-actions}, as written. */
    public List<String> clickActions() {
        return generic.actions().lines();
    }

    /** The generic {@code deny-actions}, as written. */
    public List<String> denyActions() {
        return generic.deny().lines();
    }

    /** The actions a click of {@code click} (null: generic) runs, as written. */
    public List<String> clickActionsFor(ClickType click) {
        return actionsFor(click).lines();
    }

    /** The requirement a click of {@code click} (null: generic) must pass. */
    public Requirement clickRequirementFor(ClickType click) {
        return pick(click, FIELD_REQUIREMENT).requirement();
    }

    /** The actions a click of {@code click} (null: generic) runs when its requirement fails, as written. */
    public List<String> denyActionsFor(ClickType click) {
        return denyFor(click).lines();
    }

    /** Its look, parsed once. */
    public ItemAppearance appearance() {
        return appearance;
    }

    /**
     * Renders its look for {@code viewer} (may be null) with {@code phs}, without the menu stamp:
     * for a plugin that wants the stack outside the menu.
     */
    public ItemStack render(Player viewer, Ph... phs) {
        return appearance.render(viewer, phs);
    }

    @Override
    public String toString() {
        return "GuiItemDef[" + guiId + " " + path + "]";
    }

    // ------------------------------------------------------------------ package

    String path() {
        return path;
    }

    /** {@link #slots()} without the copy; never changed. */
    int[] slotsInternal() {
        return slots;
    }

    boolean isTemplate() {
        return template;
    }

    /** The {@code nav-disabled} look, or null. */
    ItemAppearance navDisabled() {
        return navDisabled;
    }

    /** Its actions mention {@code [previous-page]}. */
    boolean navPrevious() {
        return navPrevious;
    }

    /** Its actions mention {@code [next-page]}. */
    boolean navNext() {
        return navNext;
    }

    ActionList actionsFor(ClickType click) {
        return pick(click, FIELD_ACTIONS).actions();
    }

    ActionList denyFor(ClickType click) {
        return pick(click, FIELD_DENY).deny();
    }

    Requirement requirementFor(ClickType click) {
        return pick(click, FIELD_REQUIREMENT).requirement();
    }

    /**
     * Whether {@code strict-clicks: true} lets {@code click} through: LEFT, RIGHT, SHIFT_LEFT
     * and SHIFT_RIGHT always; MIDDLE when {@code middle-click-actions} is declared; DOUBLE_CLICK
     * and CREATIVE when {@code left-click-actions} is; DROP and CONTROL_DROP when
     * {@code drop-click-actions} is; nothing else. A requirement or deny list alone enables nothing.
     */
    boolean strictAllows(ClickType click) {
        if (click == null) {
            return false;
        }
        return switch (click) {
            case LEFT, RIGHT, SHIFT_LEFT, SHIFT_RIGHT -> true;
            case MIDDLE -> declares(Prefix.MIDDLE);
            case DOUBLE_CLICK, CREATIVE -> declares(Prefix.LEFT);
            case DROP, CONTROL_DROP -> declares(Prefix.DROP);
            default -> false;
        };
    }

    private boolean declares(Prefix prefix) {
        Clicks clicks = byPrefix[prefix.ordinal()];
        return clicks != null && clicks.hasActions();
    }

    /** The lists that field {@code field} of {@code click} comes from. */
    private Clicks pick(ClickType click, int field) {
        if (click == null) {
            return generic;
        }
        Clicks found = switch (click) {
            case SHIFT_RIGHT -> declared(Prefix.SHIFT_RIGHT, field);
            case SHIFT_LEFT -> declared(Prefix.SHIFT_LEFT, field);
            default -> null;
        };
        if (found == null) {
            found = switch (click) {
                case RIGHT, SHIFT_RIGHT -> declared(Prefix.RIGHT, field);
                case LEFT, SHIFT_LEFT, DOUBLE_CLICK, CREATIVE -> declared(Prefix.LEFT, field);
                case MIDDLE -> declared(Prefix.MIDDLE, field);
                case DROP, CONTROL_DROP -> declared(Prefix.DROP, field);
                default -> null;
            };
        }
        return found == null ? generic : found;
    }

    private Clicks declared(Prefix prefix, int field) {
        Clicks clicks = byPrefix[prefix.ordinal()];
        if (clicks == null) {
            return null;
        }
        boolean has = switch (field) {
            case FIELD_ACTIONS -> clicks.hasActions();
            case FIELD_REQUIREMENT -> clicks.hasRequirement();
            default -> clicks.hasDeny();
        };
        return has ? clicks : null;
    }

    // ------------------------------------------------------------------ render cache

    void guiId(String guiId) {
        this.guiId = guiId;
    }

    String guiId() {
        return guiId;
    }

    /** The look used for {@code disabled} (the nav-disabled one when there is one). */
    ItemAppearance look(boolean disabled) {
        return disabled && navDisabled != null ? navDisabled : appearance;
    }

    /** True when the look for {@code disabled} is the same for everyone and can be built once. */
    boolean cacheable(boolean disabled) {
        return look(disabled).isStatic();
    }

    ItemStack cachedProto(boolean disabled) {
        return protos.get(disabled ? 1 : 0);
    }

    ItemStack cacheProto(boolean disabled, ItemStack proto) {
        int index = disabled ? 1 : 0;
        return protos.compareAndSet(index, null, proto) ? proto : protos.get(index);
    }

    ItemStack cachedStamped(boolean disabled, int slot) {
        return slot >= 0 && slot < MAX_CACHED_SLOT ? stamped.get((disabled ? MAX_CACHED_SLOT : 0) + slot) : null;
    }

    void cacheStamped(boolean disabled, int slot, ItemStack stack) {
        if (slot >= 0 && slot < MAX_CACHED_SLOT) {
            stamped.set((disabled ? MAX_CACHED_SLOT : 0) + slot, stack);
        }
    }
}
