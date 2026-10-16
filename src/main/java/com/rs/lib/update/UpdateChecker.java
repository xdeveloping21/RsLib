package com.rs.lib.update;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.rs.lib.Ph;
import com.rs.lib.Rs;
import com.rs.lib.lang.RsLang;
import com.rs.lib.scheduler.TaskHandle;
import com.rs.lib.text.RsText;
import com.rs.lib.update.internal.ReleaseCache;
import com.rs.lib.update.internal.SiteClient;
import com.rs.lib.update.internal.UpdateHub;

/**
 * Tells the server owner when rsdevelopment.dev offers a newer version of the plugin, reached
 * with {@code rs().updates()}.
 *
 * <p>Opt-in: {@code RsSpec.builder().updates()} arms a watch when the plugin is enabled; without
 * it nothing is sent anywhere. {@link #watch} checks after one minute and then every six hours;
 * {@link #checkNow} checks once. Both run off the main thread.</p>
 *
 * <p>A product is named by its id on the site: 1 to 40 characters among {@code a-z},
 * {@code 0-9} and {@code -} (capitals are lowered). {@link #watch()} and {@link #checkNow()}
 * use the plugin's name in lower case ({@link #ownProductId()}). A check reads
 * {@code https://rsdevelopment.dev/v1/releases/<id>}, which names the one current version of the
 * product; the answer is kept five minutes and shared with every plugin (and RsLib's own
 * updater) that asks for the same product.</p>
 *
 * <p>The version found is compared with the installed one (the plugin meta). When it is newer
 * and was not reported yet, the console gets {@code Version X available, installed Y.} and every
 * online player with {@code <plugin>.admin.update} gets {@code rslib.update-available} (or a
 * built-in text when the plugin has no messages); the same players are told again when they
 * join. <b>The plugin must declare {@code <plugin>.admin.update} (default op) in its own
 * plugin.yml.</b></p>
 *
 * <p>A failed check (no answer, a status other than 200 such as the 404 of a product the site
 * does not know, or unexpected JSON) logs one warning per product until the plugin is enabled
 * again.</p>
 */
public final class UpdateChecker {

    /** Ticks before the first check of a watch (one minute). */
    static final long FIRST_CHECK_TICKS = 1200L;
    /** Ticks between checks of a watch (six hours). */
    static final long PERIOD_TICKS = 432_000L;
    static final String MESSAGE_KEY = "rslib.update-available";
    static final String DEFAULT_MESSAGE = "&e{plugin} &7has a new version: &a{new} &7(installed &c{current}&7)";

    private final Rs rs;
    private final String pluginName;
    private final Supplier<String> installed;
    private final Logger logger;
    private final SiteClient site;
    private final ReleaseCache cache;
    private final Map<String, TaskHandle> timers = new ConcurrentHashMap<>();
    private final Map<String, Finding> findings = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private volatile boolean stopped;

    /**
     * A newer version found for one product.
     *
     * @param product the product id
     * @param version the newer version, as the site writes it
     */
    public record Finding(String product, String version) {
    }

    public UpdateChecker(Rs rs) {
        this(rs, rs.plugin().getName(), () -> rs.plugin().getPluginMeta().getVersion(), rs.plugin().getLogger(),
                UpdateHub.site(), UpdateHub.cache());
    }

    /** Without a context ({@code rs} null) nothing is scheduled and no player is told: for tests. */
    UpdateChecker(Rs rs, String pluginName, Supplier<String> installed, Logger logger, SiteClient site,
                  ReleaseCache cache) {
        this.rs = rs;
        this.pluginName = pluginName;
        this.installed = installed;
        this.logger = logger;
        this.site = site;
        this.cache = cache;
    }

    /** The plugin's name in lower case: the product {@link #watch()} and {@link #checkNow()} look at. */
    public String ownProductId() {
        return pluginName.toLowerCase(Locale.ROOT);
    }

    /** Checks the plugin's own product ({@link #ownProductId()}) after one minute and then every six hours. */
    public void watch() {
        watch(ownProductId());
    }

    /**
     * Checks product {@code productId} after one minute and then every six hours. Watching the
     * same product again replaces its timer.
     */
    public void watch(String productId) {
        String id = validId(productId);
        if (id == null || rs == null || stopped) {
            return;
        }
        TaskHandle next;
        try {
            next = rs.scheduler().timerAsync(FIRST_CHECK_TICKS, PERIOD_TICKS, () -> check(id));
        } catch (RuntimeException disabled) {
            return;
        }
        TaskHandle old = timers.put(id, next);
        if (old != null) {
            old.cancel();
        }
    }

    /** One check of the plugin's own product, now, off the main thread. */
    public void checkNow() {
        checkNow(ownProductId());
    }

    /** One check of product {@code productId}, now, off the main thread. */
    public void checkNow(String productId) {
        String id = validId(productId);
        if (id == null || rs == null || stopped) {
            return;
        }
        try {
            rs.scheduler().async(() -> check(id));
        } catch (RuntimeException disabled) {
            // The plugin is being disabled: no check.
        }
    }

    /** The newer versions found so far, by product id. */
    public Map<String, Finding> findings() {
        return Map.copyOf(findings);
    }

    /** Cancels every watch and forgets what was found; called when the plugin shuts down. */
    public void shutdown() {
        stopped = true;
        timers.values().forEach(TaskHandle::cancel);
        timers.clear();
        findings.clear();
        UpdateHub.removeNotice(this);
    }

    // ------------------------------------------------------------------ one check

    /** One check of a valid product id, on the calling thread. */
    void check(String id) {
        if (stopping()) {
            return;
        }
        SiteClient.Info info;
        try {
            info = cache.get(site, id);
        } catch (IOException e) {
            warnOnce(id, e.getMessage() == null ? e.toString() : e.getMessage());
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        evaluate(id, info.version());
    }

    /** Stores, replaces or clears the finding of {@code product}; reports only what is new. */
    synchronized void evaluate(String product, String version) {
        if (stopping()) {
            return;
        }
        String current = installed.get();
        if (!Versions.isNewer(version, current)) {
            if (findings.remove(product) != null) {
                refreshNotice();
            }
            return;
        }
        Finding finding = new Finding(product, version);
        Finding old = findings.put(product, finding);
        refreshNotice();
        if (old != null && old.version().equals(version)) {
            return;
        }
        logger.info("Version " + version + " available, installed " + current + ".");
        announce(finding, current);
    }

    private void announce(Finding finding, String current) {
        if (rs == null) {
            return;
        }
        try {
            rs.scheduler().sync(() -> {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.hasPermission(permission())) {
                        tell(player, finding, current);
                    }
                }
            });
        } catch (RuntimeException disabled) {
            // The plugin is being disabled: the console line is enough.
        }
    }

    /** The join notice: one line per pending finding, for players with the permission. */
    private void refreshNotice() {
        if (findings.isEmpty()) {
            UpdateHub.removeNotice(this);
            return;
        }
        UpdateHub.putNotice(this, player -> {
            if (stopped || !player.hasPermission(permission())) {
                return;
            }
            String current = installed.get();
            for (Finding finding : List.copyOf(findings.values())) {
                tell(player, finding, current);
            }
        });
    }

    private void tell(Player player, Finding finding, String current) {
        Ph[] phs = placeholders(pluginName, finding.version(), current);
        RsLang lang = rs == null ? null : rs.langOrNull();
        if (lang != null) {
            lang.send(player, MESSAGE_KEY, phs);
        } else {
            player.sendMessage(RsText.color(RsText.applyLocals(DEFAULT_MESSAGE, phs)));
        }
    }

    /** The placeholders of the message; the versions come from outside, so they are literal. */
    static Ph[] placeholders(String plugin, String version, String current) {
        return new Ph[] {Ph.of("plugin", plugin), Ph.literal("new", version), Ph.literal("current", current)};
    }

    /** {@code <plugin>.admin.update}, the permission that sees the notices. */
    public String permission() {
        return pluginName.toLowerCase(Locale.ROOT) + ".admin.update";
    }

    // ------------------------------------------------------------------ helpers

    private boolean stopping() {
        return stopped || (rs != null && rs.isShuttingDown());
    }

    /** The first failure of {@code id} since this checker started; later ones stay silent. */
    private void warnOnce(String id, String reason) {
        if (warned.add(id)) {
            logger.warning("update check of '" + id + "' failed: " + reason);
        }
    }

    /** {@code productId} in lower case, or null after a warning when it is not a product id. */
    private String validId(String productId) {
        String id = SiteClient.normalizeId(productId);
        if (id == null) {
            logger.warning("Update check skipped: '" + productId + "' is not a product id of rsdevelopment.dev"
                    + " (1 to 40 characters among a-z, 0-9 and -).");
        }
        return id;
    }
}
