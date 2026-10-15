package com.rs.lib;

/**
 * API level of this RsLib build.
 *
 * <p>A plugin returns {@code RsApi.LEVEL} from {@link RsPlugin#requiredApiLevel()}. Because it
 * is a compile-time constant, the number of the RsLib the plugin was compiled against ends up
 * inside the plugin's own class file; at startup it is compared with the installed RsLib, and
 * an older RsLib disables the plugin with a clear message instead of failing later with a
 * missing method. The level goes up by one in every release that adds public API, and public
 * API is never removed within a major version.</p>
 *
 * <ul>
 *     <li>1 (1.0.0): core (plugin base, context, placeholders, text, YAML files, messages,
 *     debug, reload, scheduler, soft dependencies, version checks), commands, update checks,
 *     database (SQLite/MySQL, schemas, upserts, migrations, player caches), cooldowns,
 *     PlaceholderAPI expansions, cron expressions and the util classes; items (the item
 *     builder, appearance blocks, registered physical items with recipes and death rules,
 *     serialization), action lists and requirement lists, questions asked in chat, and menus
 *     ({@code rs().guis()}: {@code guis/*.yml}, sessions, templates, regions, pages, item offers
 *     and anti-theft); money ({@code rs().economy()}: Vault, console commands or the plugin's
 *     own backend), cached leaderboards, teleports with a wait, player snapshots on disk, boss
 *     bars, text holograms and Discord webhooks.</li>
 *     <li>2 (1.1.0): cuboids and the selection wand ({@code rs().selections()}), RsLib's own
 *     events (armour equip, chunk move, selection complete), calendar jobs
 *     ({@code rs().cron()}) and, on Velocity, the proxy base ({@code com.rs.lib.velocity}).</li>
 * </ul>
 */
public final class RsApi {

    /** Must stay a literal so the compiler inlines it into every plugin. */
    public static final int LEVEL = 2;

    private RsApi() {
    }
}
