package com.rs.lib.action;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import com.rs.lib.Ph;
import com.rs.lib.Rs;

/**
 * Everything one run of an action list knows about where it came from.
 *
 * @param player       who the actions run for; null only for lists run without a player
 * @param ctx          the plugin's RsLib context
 * @param pageTarget   the menu session behind a click, or null outside menus
 * @param clickType    the click, or null (close actions, rewards, commands): every click
 *                     guard then skips its line
 * @param clickSurface what a physical item was used on, or null (always null in menus)
 * @param phs          local placeholders for the arguments, never null
 */
public record ActionContext(Player player, Rs ctx, PageTarget pageTarget, ClickType clickType,
                            ClickSurface clickSurface, Ph[] phs) {

    private static final Ph[] NONE = new Ph[0];

    public ActionContext {
        phs = phs == null ? NONE : phs;
    }

    /** A context without a click surface (menus). */
    public ActionContext(Player player, Rs ctx, PageTarget pageTarget, ClickType clickType, Ph[] phs) {
        this(player, ctx, pageTarget, clickType, null, phs);
    }

    /** A context with only a player and placeholders: no menu, no click. */
    public static ActionContext of(Player player, Rs ctx, Ph... phs) {
        return new ActionContext(player, ctx, null, null, null, phs);
    }
}
