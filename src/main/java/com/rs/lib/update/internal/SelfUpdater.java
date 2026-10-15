package com.rs.lib.update.internal;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import com.rs.lib.scheduler.RsScheduler;
import com.rs.lib.scheduler.TaskHandle;
import com.rs.lib.text.RsText;
import com.rs.lib.yml.YamlUpdater;

/**
 * Keeps RsLib itself up to date from {@link UpdatePass#RELEASES_REPO}, as {@code auto-update}
 * in RsLib's config.yml says.
 *
 * <p>The first pass runs two minutes after RsLib is enabled, then every
 * {@code interval-hours} (1 to 168), off the main thread and never two at once. A new jar is
 * only placed on disk: it applies on the next restart, which the console and every online player
 * with {@value #PERMISSION} are told (players also when they join). {@code /rslib update} forces
 * a pass and reports each step to whoever ran it.</p>
 */
public final class SelfUpdater {

    public static final String PERMISSION = "rslib.admin.update";
    static final String CONFIG = "config.yml";
    static final long FIRST_PASS_TICKS = 2L * 60L * 20L;
    static final long TICKS_PER_HOUR = 60L * 60L * 20L;
    static final int MIN_HOURS = 1;
    static final int MAX_HOURS = 168;
    private static final String INSTALLED_MESSAGE = "&eRsLib &a{version} &7was installed; it applies on the next restart.";
    private static final String TAG = "[RsLib update] ";

    private final JavaPlugin plugin;
    private final Logger logger;
    private final RsScheduler scheduler;
    private final JdkTransport transport = new JdkTransport();
    private final GitHubClient github = new GitHubClient(transport);
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile TaskHandle timer;
    private volatile String pending;
    private volatile boolean stopped;

    /**
     * The {@code auto-update} section.
     *
     * @param enabled       whether passes run on their own
     * @param intervalHours hours between passes, already clamped to 1..168
     * @param sameMajorOnly never install another major version
     */
    record Settings(boolean enabled, int intervalHours, boolean sameMajorOnly) {

        static Settings read(ConfigurationSection root) {
            ConfigurationSection s = root == null ? null : root.getConfigurationSection("auto-update");
            if (s == null) {
                return new Settings(true, 12, true);
            }
            int hours = Math.max(MIN_HOURS, Math.min(MAX_HOURS, s.getInt("interval-hours", 12)));
            return new Settings(s.getBoolean("enabled", true), hours, s.getBoolean("same-major-only", true));
        }
    }

    private SelfUpdater(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.scheduler = new RsScheduler(plugin);
    }

    /** Seeds RsLib's config, cleans leftovers of an earlier pass and arms the timer. */
    public static SelfUpdater start(JavaPlugin plugin) {
        SelfUpdater updater = new SelfUpdater(plugin);
        updater.arm();
        return updater;
    }

    private void arm() {
        Settings settings = settings();
        RsLibJars.cleanStaging(UpdatePass.staging(pluginsDir()));
        if (!settings.enabled()) {
            logger.info("Automatic updates are off (auto-update.enabled in config.yml).");
        }
        timer = scheduler.timerAsync(FIRST_PASS_TICKS, settings.intervalHours() * TICKS_PER_HOUR, () -> run(null));
    }

    /** Forces a pass now, off the main thread, reporting every step to {@code sender}. */
    public void runNow(CommandSender sender) {
        sender.sendMessage("Checking for RsLib updates...");
        try {
            scheduler.async(() -> run(sender));
        } catch (RuntimeException disabled) {
            sender.sendMessage("RsLib is being disabled; try again after the restart.");
        }
    }

    /** Cancels the timer and closes the HTTP client; a running pass stops at its next request. */
    public void shutdown() {
        stopped = true;
        TaskHandle current = timer;
        if (current != null) {
            current.cancel();
        }
        transport.close();
        UpdateHub.removeNotice(this);
    }

    private void run(CommandSender sender) {
        if (stopped) {
            return;
        }
        Settings settings = settings();
        if (sender == null && !settings.enabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            tell(sender, "An update pass is already running.");
            return;
        }
        try {
            if (pending != null) {
                RsLibJars.cleanStaging(UpdatePass.staging(pluginsDir()));
                tell(sender, "RsLib " + pending + " is already installed; restart the server to use it.");
                return;
            }
            UpdatePass.Env env = new UpdatePass.Env(plugin.getPluginMeta().getVersion(), pluginsDir(), codeSource(),
                    Bukkit.getUpdateFolderFile().toPath(), settings.sameMajorOnly(), github, UpdateHub.feed(),
                    RsLibJars.DISK);
            UpdatePass.Outcome outcome = UpdatePass.run(env, reporter(sender));
            if (outcome.status() == UpdatePass.Status.INSTALLED || outcome.status() == UpdatePass.Status.DEFERRED) {
                installed(outcome.version());
            }
        } finally {
            running.set(false);
        }
    }

    private UpdatePass.Reporter reporter(CommandSender sender) {
        return new UpdatePass.Reporter() {
            @Override
            public void step(String message) {
                tell(sender, message);
            }

            @Override
            public void warn(String message) {
                if (stopped) {
                    return;
                }
                logger.warning(TAG + message);
                if (!(sender instanceof ConsoleCommandSender)) {
                    tell(sender, message);
                }
            }
        };
    }

    /** The new version is on disk: console, online admins and, from now on, admins who join. */
    private void installed(String version) {
        pending = version;
        logger.info(TAG + "RsLib " + version + " was installed; it applies on the next restart.");
        UpdateHub.putNotice(this, this::notifyIfAllowed);
        try {
            scheduler.sync(() -> Bukkit.getOnlinePlayers().forEach(this::notifyIfAllowed));
        } catch (RuntimeException disabled) {
            // RsLib is being disabled: the console line is enough.
        }
    }

    private void notifyIfAllowed(Player player) {
        String version = pending;
        if (version != null && !stopped && player.hasPermission(PERMISSION)) {
            player.sendMessage(RsText.color(INSTALLED_MESSAGE.replace("{version}", RsText.literal(version))));
        }
    }

    private static void tell(CommandSender sender, String message) {
        if (sender != null) {
            sender.sendMessage(TAG + message);
        }
    }

    /** Seeds or merges RsLib's config.yml and reads {@code auto-update}. */
    private Settings settings() {
        File file = new File(plugin.getDataFolder(), CONFIG);
        YamlUpdater.update(plugin, CONFIG, file, false);
        return Settings.read(YamlConfiguration.loadConfiguration(file));
    }

    private Path pluginsDir() {
        return plugin.getDataFolder().getAbsoluteFile().getParentFile().toPath();
    }

    /** The file RsLib's classes were loaded from, or null when the JVM does not say. */
    private static Path codeSource() {
        try {
            CodeSource source = SelfUpdater.class.getProtectionDomain().getCodeSource();
            return source == null || source.getLocation() == null ? null : Path.of(source.getLocation().toURI());
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }
}
