package com.rs.lib.update;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import com.rs.lib.Ph;
import com.rs.lib.Rs;
import com.rs.lib.lang.RsLang;
import com.rs.lib.scheduler.TaskHandle;
import com.rs.lib.text.RsText;
import com.rs.lib.update.internal.GitHubClient;
import com.rs.lib.update.internal.Release;
import com.rs.lib.update.internal.ReleaseFeed;
import com.rs.lib.update.internal.UpdateHub;
import com.rs.lib.yml.YmlManager;

/**
 * Tells the server owner when a newer release of the plugin is on GitHub, reached with
 * {@code rs().updates()}.
 *
 * <p>Opt-in: {@code RsSpec.builder().updates("owner/repo")} arms a watch when the plugin is
 * enabled; without it nothing is sent anywhere. {@link #watch} checks after one minute and then
 * every six hours; {@link #checkNow} checks once. Both run off the main thread.</p>
 *
 * <ul>
 *   <li>Without a tag prefix the repository's latest release is read
 *   ({@code /releases/latest}).</li>
 *   <li>With a tag prefix (one repository shared by several plugins, such as
 *   {@code rskills-1.4.0}) the release list is scanned, the tags starting with the prefix are
 *   kept, the prefix and one leading {@code v} are removed and the highest version wins.</li>
 * </ul>
 *
 * <p>The version found is compared with the installed one (the plugin meta). When it is newer
 * and was not reported yet, the console gets {@code Version X available, installed Y.} and every
 * online player with {@code <plugin>.admin.update} gets {@code rslib.update-available} (or a
 * built-in text when the plugin has no messages); the same players are told again when they
 * join. <b>The plugin must declare {@code <plugin>.admin.update} (default op) in its own
 * plugin.yml.</b></p>
 *
 * <p>A token in {@code update-check.token} of the main config, read on every check, is sent as
 * {@code Authorization: Bearer} (raises GitHub's rate limit; never logged). A failed check
 * logs one warning per repository until the plugin is enabled again.</p>
 */
public final class UpdateChecker {

    /** Ticks before the first check of a watch (one minute). */
    static final long FIRST_CHECK_TICKS = 1200L;
    /** Ticks between checks of a watch (six hours). */
    static final long PERIOD_TICKS = 432_000L;
    static final String MESSAGE_KEY = "rslib.update-available";
    static final String DEFAULT_MESSAGE = "&e{plugin} &7has a new version: &a{new} &7(installed &c{current}&7)";
    static final String TOKEN_SECTION = "update-check";
    static final String TOKEN_KEY = "token";
    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");

    private final Rs rs;
    private final String pluginName;
    private final Supplier<String> installed;
    private final Supplier<String> token;
    private final Logger logger;
    private final GitHubClient github;
    private final ReleaseFeed feed;
    private final Map<String, TaskHandle> timers = new ConcurrentHashMap<>();
    private final Map<String, Finding> findings = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private volatile boolean stopped;

    /**
     * A newer release found for one repository.
     *
     * @param repo    {@code owner/repo}
     * @param version the newer version, without prefix
     * @param url     the release page, or null
     */
    public record Finding(String repo, String version, String url) {
    }

    public UpdateChecker(Rs rs) {
        this(rs, rs.plugin().getName(), () -> rs.plugin().getPluginMeta().getVersion(), () -> configToken(rs),
                rs.plugin().getLogger(), new GitHubClient(UpdateHub.transport()), UpdateHub.feed());
    }

    /** Without a context ({@code rs} null) nothing is scheduled and no player is told: for tests. */
    UpdateChecker(Rs rs, String pluginName, Supplier<String> installed, Supplier<String> token, Logger logger,
                  GitHubClient github, ReleaseFeed feed) {
        this.rs = rs;
        this.pluginName = pluginName;
        this.installed = installed;
        this.token = token;
        this.logger = logger;
        this.github = github;
        this.feed = feed;
    }

    /** Checks the latest release of {@code ownerRepo} after one minute and then every six hours. */
    public void watch(String ownerRepo) {
        watch(ownerRepo, null);
    }

    /**
     * Same as {@link #watch(String)} for the tags starting with {@code tagPrefix} (null or empty:
     * the latest release). Watching the same repository again replaces its timer.
     */
    public void watch(String ownerRepo, String tagPrefix) {
        if (!validRepo(ownerRepo) || rs == null || stopped) {
            return;
        }
        String repo = ownerRepo.trim();
        String prefix = prefixOf(tagPrefix);
        TaskHandle next;
        try {
            next = rs.scheduler().timerAsync(FIRST_CHECK_TICKS, PERIOD_TICKS, () -> check(repo, prefix));
        } catch (RuntimeException disabled) {
            return;
        }
        TaskHandle old = timers.put(key(repo), next);
        if (old != null) {
            old.cancel();
        }
    }

    /** One check of the latest release of {@code ownerRepo}, now, off the main thread. */
    public void checkNow(String ownerRepo) {
        checkNow(ownerRepo, null);
    }

    /** One check of the tags starting with {@code tagPrefix}, now, off the main thread. */
    public void checkNow(String ownerRepo, String tagPrefix) {
        if (!validRepo(ownerRepo) || rs == null || stopped) {
            return;
        }
        String repo = ownerRepo.trim();
        String prefix = prefixOf(tagPrefix);
        try {
            rs.scheduler().async(() -> check(repo, prefix));
        } catch (RuntimeException disabled) {
            // The plugin is being disabled: no check.
        }
    }

    /** The newer releases found so far, by repository. */
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

    /** One check, on the calling thread. */
    void check(String repo, String prefix) {
        if (stopping()) {
            return;
        }
        String version;
        String url;
        try {
            if (prefix == null) {
                Release latest = github.latest(repo, token.get());
                version = Versions.normalize(latest.tag());
                url = latest.url();
            } else {
                ReleaseFeed.Scan scan = feed.scan(github, repo, token.get());
                ReleaseFeed.Pick pick = ReleaseFeed.highest(scan.releases(), prefix);
                if (pick == null) {
                    warnOnce(repo, "No release of " + repo + " has a tag starting with '" + prefix + "'"
                            + (scan.capped() ? " (the scan stopped after " + scan.releases().size()
                            + " releases, the page limit)" : "") + ".");
                    return;
                }
                version = pick.version();
                url = pick.release().url();
            }
        } catch (IOException e) {
            warnOnce(repo, "Could not check " + repo + " for updates: " + e.getMessage());
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        evaluate(repo, version, url);
    }

    /** Stores, replaces or clears the finding of {@code repo}; reports only what is new. */
    synchronized void evaluate(String repo, String version, String url) {
        if (stopping()) {
            return;
        }
        String current = installed.get();
        String key = key(repo);
        if (!Versions.isNewer(version, current)) {
            if (findings.remove(key) != null) {
                refreshNotice();
            }
            return;
        }
        Finding finding = new Finding(repo, version, url);
        Finding old = findings.put(key, finding);
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

    private void warnOnce(String repo, String message) {
        if (warned.add(key(repo))) {
            logger.warning(message + " Further failures for " + repo + " are not logged until the next start.");
        }
    }

    private boolean validRepo(String ownerRepo) {
        if (isValidRepo(ownerRepo)) {
            return true;
        }
        logger.warning("Update check skipped: '" + ownerRepo + "' is not a GitHub repository like owner/repo.");
        return false;
    }

    /** True for {@code owner/repo} made of letters, digits, {@code _ . -} (not {@code .} or {@code ..}). */
    static boolean isValidRepo(String ownerRepo) {
        if (ownerRepo == null || !REPO.matcher(ownerRepo.trim()).matches()) {
            return false;
        }
        for (String part : ownerRepo.trim().split("/")) {
            if (part.equals(".") || part.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static String prefixOf(String tagPrefix) {
        return tagPrefix == null || tagPrefix.isBlank() ? null : tagPrefix.trim();
    }

    private static String key(String repo) {
        return repo.toLowerCase(Locale.ROOT);
    }

    /** {@code update-check.token} of the main config, raw (never resolved nor logged); null when blank. */
    private static String configToken(Rs rs) {
        YmlManager yml = rs.ymlOrNull();
        ConfigurationSection section = yml == null ? null : yml.config().getSection(TOKEN_SECTION);
        Object value = section == null ? null : section.get(TOKEN_KEY);
        return value instanceof String text && !text.isBlank() ? text.trim() : null;
    }
}
