package com.rs.lib.reward;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import com.rs.lib.Ph;
import com.rs.lib.Rs;
import com.rs.lib.action.Requirement;
import com.rs.lib.db.RsDb;
import com.rs.lib.db.internal.MainHops;
import com.rs.lib.item.ItemAppearance;
import com.rs.lib.util.InvUtil;
import com.rs.lib.yml.RsYml;

/**
 * Rewards with guaranteed delivery, reached with {@code rs().rewards()}.
 *
 * <pre>
 * RewardList list = rs().rewards().read(yml, "rewards");      // once, on load
 * rs().rewards().give(player, list, OverflowPolicy.QUEUE)
 *         .thenAccept(report -&gt; ...);                          // what happened to each one
 * </pre>
 *
 * <h2>Deciding</h2>
 * <p>{@link #resolve} decides every reward of a list; {@link #pick} decides a number of them
 * drawn by weight. For each reward the order is: <b>permission, then requirement, then the
 * roll</b>, so a misspelt permission is reported as {@link RewardOutcome#NO_PERMISSION} and
 * never as a lost roll. A won reward is fixed right there: its item is rendered for the player,
 * its command and message have their placeholders resolved ({@code {player}}, {@code {uuid}},
 * the caller's, then PlaceholderAPI). The result, {@link ResolvedRewards}, can be shown (an
 * animation) and is then delivered exactly, without another check or roll.</p>
 *
 * <h2>Delivering</h2>
 * <p>{@link #deliver(Player, ResolvedRewards, OverflowPolicy)} hands the won rewards over on
 * the player's thread. Items that do not fit follow the {@link OverflowPolicy}: dropped at the
 * feet (the default), kept in the database, or not given at all; a reward never loses part of
 * its items. Money goes through {@code rs().economy()}, so the report is a future: it
 * completes, never exceptionally, once every outcome is known, on the player's thread.</p>
 *
 * <h2>Kept for later</h2>
 * <p>With {@code RsSpec.builder().db().rewards()} the plugin keeps rewards in
 * {@code rslib_pending_rewards}: what {@link OverflowPolicy#QUEUE} could not fit, and every
 * reward {@link #deliver(UUID, ResolvedRewards) delivered} to a player who is not online. They
 * are handed over when the player joins, or with {@link #claim}, each row exactly once (see
 * {@link PendingStore}); what still does not fit goes back to the table.</p>
 *
 * <p>Nothing here is silent: a reward that cannot be handed over nor kept is reported as
 * {@link RewardOutcome#FAILED} and written to the log with what it was.</p>
 */
public final class Rewards {

    static final String CLAIMED = "rslib.rewards.claimed";
    static final String WAITING = "rslib.rewards.waiting";

    /** Live modules of every plugin, for the shared join listener. */
    private static final CopyOnWriteArrayList<Rewards> LIVE = new CopyOnWriteArrayList<>();
    private static final Listener LISTENER = new JoinListener();

    private final RewardsHost host;
    private final PendingStore store;
    private final String name;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private volatile boolean stopped;

    /** Created by the context; keeps rewards for later when the spec declares {@code rewards()} and {@code db()}. */
    public Rewards(Rs rs) {
        this(RewardsHost.of(Objects.requireNonNull(rs, "rs")), storeFor(rs), rs.plugin().getName());
    }

    Rewards(RewardsHost host, PendingStore store, String name) {
        this.host = host;
        this.store = store;
        this.name = name;
        LIVE.add(this);
    }

    private static PendingStore storeFor(Rs rs) {
        if (!rs.spec().rewards()) {
            return null;
        }
        RsDb db = rs.dbOrNull();
        if (db == null) {
            rs.plugin().getLogger().warning("RsSpec rewards() needs db(); rewards are not kept for later.");
            return null;
        }
        PendingStore created = new PendingStore(db, rs.plugin().getName(), System::currentTimeMillis);
        created.ready().whenComplete((ignored, error) -> {
            if (error != null) {
                rs.plugin().getLogger().log(Level.WARNING, "Could not create the table " + PendingStore.TABLE
                        + "; it is tried again on the next use.", error);
            }
        });
        return created;
    }

    /** The listener every plugin shares; RsLib registers it. */
    public static Listener sharedListener() {
        return LISTENER;
    }

    // ------------------------------------------------------------------ reading

    /**
     * Reads the reward list at {@code path} of {@code yml} (see {@link RewardList}); problems
     * are logged by the plugin with the file and the key. Parses on every call: keep the result.
     */
    public RewardList read(RsYml yml, String path) {
        Objects.requireNonNull(yml, "yml");
        ConfigurationSection section = yml.getSection(path);
        String source = path == null || path.isEmpty() ? yml.name() : yml.name() + " -> " + path;
        return RewardList.read(section, source, ItemAppearance.TextResolver.of(yml),
                message -> warnOnce("read:" + message, message));
    }

    // ------------------------------------------------------------------ deciding

    /** Decides every reward of {@code list} for {@code player}; see {@link #resolve(Player, RewardList, Random, Ph...)}. */
    public ResolvedRewards resolve(Player player, RewardList list, Ph... phs) {
        return resolve(player, list, ThreadLocalRandom.current(), phs);
    }

    /**
     * Decides every reward of {@code list} for {@code player} on the player's thread: each one
     * is checked (permission, requirement) and rolled on its own, in order; {@code random}
     * draws once per reward whose chance is above 0 and below 100. Without a player (null) a
     * reward that asks for a permission or a requirement is not given.
     */
    public ResolvedRewards resolve(Player player, RewardList list, Random random, Ph... phs) {
        Objects.requireNonNull(list, "list");
        Objects.requireNonNull(random, "random");
        Ph[] locals = locals(player, phs);
        List<ResolvedReward> out = new ArrayList<>(list.size());
        for (Reward reward : list) {
            RewardOutcome refused = check(player, reward, locals);
            if (refused != null) {
                out.add(ResolvedReward.skipped(reward.id(), reward.type(), refused));
            } else if (!roll(reward.chance(), random)) {
                out.add(ResolvedReward.skipped(reward.id(), reward.type(), RewardOutcome.LOST_ROLL));
            } else {
                out.add(fix(player, reward, locals));
            }
        }
        return new ResolvedRewards(player == null ? null : player.getUniqueId(), out);
    }

    /** Draws {@code count} rewards by weight; see {@link #pick(Player, RewardList, int, Random, Ph...)}. */
    public ResolvedRewards pick(Player player, RewardList list, int count, Ph... phs) {
        return pick(player, list, count, ThreadLocalRandom.current(), phs);
    }

    /**
     * Draws up to {@code count} different rewards of {@code list} by weight, among those the
     * player may get (permission and requirement first), and then rolls each winner's own
     * chance. Rewards that were not drawn are {@link RewardOutcome#LOST_ROLL}. {@code random}
     * draws once per winner, then once per winner whose chance is above 0 and below 100, in
     * list order.
     */
    public ResolvedRewards pick(Player player, RewardList list, int count, Random random, Ph... phs) {
        Objects.requireNonNull(list, "list");
        Objects.requireNonNull(random, "random");
        Ph[] locals = locals(player, phs);
        List<Reward> rewards = list.rewards();
        RewardOutcome[] refused = new RewardOutcome[rewards.size()];
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < rewards.size(); i++) {
            refused[i] = check(player, rewards.get(i), locals);
            if (refused[i] == null && rewards.get(i).weight() > 0) {
                candidates.add(i);
            }
        }
        boolean[] drawn = new boolean[rewards.size()];
        for (int n = 0; n < count && !candidates.isEmpty(); n++) {
            double total = 0;
            for (int index : candidates) {
                total += rewards.get(index).weight();
            }
            double at = random.nextDouble() * total;
            int chosen = candidates.size() - 1;
            for (int c = 0; c < candidates.size(); c++) {
                at -= rewards.get(candidates.get(c)).weight();
                if (at < 0) {
                    chosen = c;
                    break;
                }
            }
            drawn[candidates.remove(chosen)] = true;
        }
        List<ResolvedReward> out = new ArrayList<>(rewards.size());
        for (int i = 0; i < rewards.size(); i++) {
            Reward reward = rewards.get(i);
            if (refused[i] != null) {
                out.add(ResolvedReward.skipped(reward.id(), reward.type(), refused[i]));
            } else if (!drawn[i] || !roll(reward.chance(), random)) {
                out.add(ResolvedReward.skipped(reward.id(), reward.type(), RewardOutcome.LOST_ROLL));
            } else {
                out.add(fix(player, reward, locals));
            }
        }
        return new ResolvedRewards(player == null ? null : player.getUniqueId(), out);
    }

    /** Permission, then requirement: the outcome that refuses the reward, or null. */
    private RewardOutcome check(Player player, Reward reward, Ph[] locals) {
        if (reward.permission() != null && (player == null || !player.hasPermission(reward.permission()))) {
            return RewardOutcome.NO_PERMISSION;
        }
        Requirement requirement = reward.requirement();
        if (requirement != Requirement.ALWAYS) {
            if (player == null) {
                return RewardOutcome.REQUIREMENT_FAILED;
            }
            try {
                if (!host.test(requirement, player, locals)) {
                    return RewardOutcome.REQUIREMENT_FAILED;
                }
            } catch (RuntimeException e) {
                host.logger().log(Level.WARNING, "The requirement of reward " + reward.id() + " failed; not given.", e);
                return RewardOutcome.REQUIREMENT_FAILED;
            }
        }
        return null;
    }

    /** True when a reward of {@code chance} percent happens; 100 and 0 never draw. */
    static boolean roll(double chance, Random random) {
        if (chance >= Reward.ALWAYS) {
            return true;
        }
        if (!(chance > 0)) {
            return false;
        }
        return random.nextDouble() * Reward.ALWAYS < chance;
    }

    /** A won reward with everything it hands over fixed now. */
    private ResolvedReward fix(Player player, Reward reward, Ph[] locals) {
        String id = reward.id();
        try {
            return switch (reward.type()) {
                case ITEM -> {
                    ItemStack stack = reward.item() != null ? reward.item()
                            : reward.serializedItem() != null ? host.decode(reward.serializedItem())
                            : host.render(reward.appearance(), player, locals);
                    if (Reward.empty(stack)) {
                        warnOnce("item:" + id, "Reward " + id + " has no item to give; reported as failed.");
                        yield ResolvedReward.skipped(id, RewardType.ITEM, RewardOutcome.FAILED);
                    }
                    yield ResolvedReward.items(id, List.of(stack));
                }
                case COMMAND -> ResolvedReward.text(id, RewardType.COMMAND, host.resolve(player, reward.text(), locals, false));
                case MESSAGE -> ResolvedReward.text(id, RewardType.MESSAGE, host.resolve(player, reward.text(), locals, true));
                case MONEY, EXPERIENCE -> ResolvedReward.amount(id, reward.type(), reward.amount());
                case POTION -> ResolvedReward.potion(id, reward.effect(), reward.ticks(), reward.amplifier());
            };
        } catch (RuntimeException e) {
            warnOnce("fix:" + id, "Reward " + id + " could not be prepared (" + e + "); reported as failed.");
            return ResolvedReward.skipped(id, reward.type(), RewardOutcome.FAILED);
        }
    }

    /** The caller's placeholders, then {@code {player}} and {@code {uuid}} (as plain text). */
    private static Ph[] locals(Player player, Ph[] phs) {
        Ph[] given = phs == null ? new Ph[0] : phs;
        if (player == null) {
            return given;
        }
        Ph[] out = Arrays.copyOf(given, given.length + 2);
        out[given.length] = Ph.literal("player", player.getName());
        out[given.length + 1] = Ph.literal("uuid", player.getUniqueId());
        return out;
    }

    // ------------------------------------------------------------------ delivering

    /** Decides and delivers {@code list} for {@code player}, dropping what does not fit. */
    public CompletableFuture<RewardReport> give(Player player, RewardList list, Ph... phs) {
        return give(player, list, OverflowPolicy.DROP, phs);
    }

    /** {@link #resolve(Player, RewardList, Ph...)} and then {@link #deliver(Player, ResolvedRewards, OverflowPolicy)}. */
    public CompletableFuture<RewardReport> give(Player player, RewardList list, OverflowPolicy overflow, Ph... phs) {
        Objects.requireNonNull(player, "player");
        return deliver(player, resolve(player, list, phs), overflow);
    }

    /** {@link #deliver(Player, ResolvedRewards, OverflowPolicy)} dropping what does not fit. */
    public CompletableFuture<RewardReport> deliver(Player player, ResolvedRewards resolved) {
        return deliver(player, resolved, OverflowPolicy.DROP);
    }

    /**
     * Hands the won rewards of {@code resolved} to {@code player}, on the player's thread (at
     * once when called there). When the player is gone by then, everything is kept for later
     * like {@link #deliver(UUID, ResolvedRewards)}. {@code resolved} is delivered only once.
     *
     * @throws IllegalArgumentException when {@code resolved} was decided for another player
     */
    public CompletableFuture<RewardReport> deliver(Player player, ResolvedRewards resolved, OverflowPolicy overflow) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(overflow, "overflow");
        UUID id = player.getUniqueId();
        requireOwner(resolved, id);
        if (!resolved.take()) {
            return CompletableFuture.completedFuture(again(resolved));
        }
        List<ResolvedReward> entries = resolved.entries();
        return onPlayerThread(player, () -> deliverNow(player, entries, overflow, false), () -> keep(id, entries));
    }

    /**
     * Hands {@code resolved} to the player with this id: now when online (what does not fit is
     * kept for later), otherwise everything won is kept in the database until the player joins
     * or {@link #claim claims} it ({@link RewardOutcome#QUEUED}). Any thread.
     *
     * @throws IllegalArgumentException when {@code resolved} was decided for another player
     */
    public CompletableFuture<RewardReport> deliver(UUID player, ResolvedRewards resolved) {
        Objects.requireNonNull(player, "player");
        requireOwner(resolved, player);
        Player online = host.online(player);
        if (online != null) {
            return deliver(online, resolved, OverflowPolicy.QUEUE);
        }
        if (!resolved.take()) {
            return CompletableFuture.completedFuture(again(resolved));
        }
        return keep(player, resolved.entries());
    }

    private static void requireOwner(ResolvedRewards resolved, UUID player) {
        Objects.requireNonNull(resolved, "resolved");
        if (resolved.player() != null && !resolved.player().equals(player)) {
            throw new IllegalArgumentException("These rewards were decided for " + resolved.player()
                    + ", not for " + player);
        }
    }

    /** The report of a second delivery: nothing handed over. */
    private RewardReport again(ResolvedRewards resolved) {
        warnOnce("again", "Rewards that were already delivered were delivered again; nothing was given the"
                + " second time. Deliver a ResolvedRewards only once.");
        List<RewardResult> results = new ArrayList<>();
        for (ResolvedReward entry : resolved.entries()) {
            results.add(new RewardResult(entry.id(), entry.type(), entry.won() ? RewardOutcome.FAILED : entry.skipped()));
        }
        return new RewardReport(results);
    }

    /** Runs {@code work} on the player's thread, or {@code gone} when it cannot run there any more. */
    private CompletableFuture<RewardReport> onPlayerThread(Player player, Supplier<CompletableFuture<RewardReport>> work,
                                                          Supplier<CompletableFuture<RewardReport>> gone) {
        if (host.ownsHere(player)) {
            return player.isOnline() ? work.get() : gone.get();
        }
        CompletableFuture<RewardReport> result = new CompletableFuture<>();
        MainHops.Hop hop = MainHops.add(host.owner(), () -> forward(gone.get(), result));
        Runnable retired = () -> {
            if (hop.claim()) {
                forward(gone.get(), result);
            }
        };
        try {
            host.runFor(player, () -> {
                if (hop.claim()) {
                    forward(player.isOnline() ? work.get() : gone.get(), result);
                }
            }, retired);
        } catch (RuntimeException notScheduled) {
            retired.run();
        }
        return result;
    }

    private static void forward(CompletableFuture<RewardReport> from, CompletableFuture<RewardReport> to) {
        from.whenComplete((report, error) -> to.complete(report == null ? RewardReport.EMPTY : report));
    }

    /**
     * Hands {@code entries} over now, on the player's thread. {@code claim}: they come from the
     * table, so leftovers and failed money go back to it.
     */
    private CompletableFuture<RewardReport> deliverNow(Player player, List<ResolvedReward> entries, OverflowPolicy overflow,
                                                       boolean claim) {
        int size = entries.size();
        RewardOutcome[] outcomes = new RewardOutcome[size];
        List<PendingStore.Row> queued = new ArrayList<>();
        List<Integer> queuedEntry = new ArrayList<>();
        List<ItemStack> queuedStacks = new ArrayList<>();
        List<CompletableFuture<Void>> money = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ResolvedReward entry = entries.get(i);
            if (!entry.won()) {
                outcomes[i] = entry.skipped();
                continue;
            }
            int index = i;
            try {
                switch (entry.type()) {
                    case ITEM -> outcomes[i] = items(player, entry, overflow, index, queued, queuedEntry, queuedStacks);
                    case MONEY -> money.add(host.money(player, entry.amount()).handle((ok, error) -> {
                        if (Boolean.TRUE.equals(ok)) {
                            outcomes[index] = RewardOutcome.GIVEN;
                        } else {
                            outcomes[index] = RewardOutcome.FAILED;
                            host.logger().warning("Could not give " + entry.amount() + " money to " + player.getName()
                                    + " for reward " + entry.id() + (claim ? "; it stays kept." : "."));
                        }
                        return null;
                    }));
                    case COMMAND -> outcomes[i] = command(entry);
                    case EXPERIENCE -> {
                        player.giveExp((int) entry.amount());
                        outcomes[i] = RewardOutcome.GIVEN;
                    }
                    case POTION -> {
                        if (host.potion(player, entry.effect(), entry.ticks(), entry.amplifier())) {
                            outcomes[i] = RewardOutcome.GIVEN;
                        } else {
                            warnOnce("potion:" + entry.effect(), "Reward " + entry.id() + ": there is no potion effect '"
                                    + entry.effect() + "'; reported as failed.");
                            outcomes[i] = RewardOutcome.FAILED;
                        }
                    }
                    case MESSAGE -> {
                        host.message(player, entry.text());
                        outcomes[i] = RewardOutcome.GIVEN;
                    }
                }
            } catch (RuntimeException e) {
                host.logger().log(Level.WARNING, "Reward " + entry.id() + " failed for " + player.getName(), e);
                outcomes[i] = RewardOutcome.FAILED;
            }
        }
        CompletableFuture<RewardReport> result = new CompletableFuture<>();
        CompletableFuture.allOf(money.toArray(new CompletableFuture<?>[0])).whenComplete((ignored, error) -> {
            if (claim) {
                for (int i = 0; i < size; i++) {
                    ResolvedReward entry = entries.get(i);
                    if (!entry.won() || outcomes[i] != RewardOutcome.FAILED) {
                        continue;
                    }
                    if (entry.type() == RewardType.MONEY) {
                        // The economy said no: the money is still owed.
                        queued.add(new PendingStore.Row(entry.id(), RewardType.MONEY, null, entry.amount()));
                        queuedEntry.add(i);
                        queuedStacks.add(null);
                    } else {
                        // Its row is gone and running it again could repeat half of it: written out whole.
                        lostEntry(player.getUniqueId(), entry);
                    }
                }
            }
            if (queued.isEmpty()) {
                finish(player, entries, outcomes, claim, result);
                return;
            }
            store.put(player.getUniqueId(), queued).whenComplete((done, failed) -> {
                if (failed == null) {
                    for (int index : queuedEntry) {
                        outcomes[index] = RewardOutcome.QUEUED;
                    }
                    afterwards(player, () -> finish(player, entries, outcomes, claim, result), result, entries, outcomes);
                    return;
                }
                host.logger().log(Level.WARNING, "Could not keep the rewards of " + player.getName()
                        + " that did not fit; dropping them at their feet.", failed);
                afterwards(player, () -> {
                    for (int r = 0; r < queued.size(); r++) {
                        int index = queuedEntry.get(r);
                        ItemStack stack = queuedStacks.get(r);
                        if (stack != null && player.isOnline()) {
                            InvUtil.giveItems(player, InvUtil.Overflow.DROP, stack);
                            outcomes[index] = RewardOutcome.GIVEN;
                        } else {
                            lost(player.getUniqueId(), queued.get(r));
                            outcomes[index] = RewardOutcome.FAILED;
                        }
                    }
                    finish(player, entries, outcomes, claim, result);
                }, result, entries, outcomes);
            });
        });
        return result;
    }

    /** Hands an item reward over under {@code overflow}; leftovers to keep are added to {@code queued}. */
    private RewardOutcome items(Player player, ResolvedReward entry, OverflowPolicy overflow, int index,
                                List<PendingStore.Row> queued, List<Integer> queuedEntry, List<ItemStack> queuedStacks) {
        ItemStack[] stacks = entry.stacks().toArray(new ItemStack[0]);
        switch (overflow) {
            case FAIL -> {
                return InvUtil.giveItems(player, InvUtil.Overflow.FAIL, stacks).isEmpty()
                        ? RewardOutcome.GIVEN : RewardOutcome.NO_SPACE;
            }
            case QUEUE -> {
                if (store == null) {
                    warnOnce("queue", "Overflow QUEUE needs RsSpec.builder().db().rewards(); items that do not fit"
                            + " are dropped at the player's feet instead.");
                    InvUtil.giveItems(player, InvUtil.Overflow.DROP, stacks);
                    return RewardOutcome.GIVEN;
                }
                List<ItemStack> rest = InvUtil.giveItems(player, InvUtil.Overflow.RETURN, stacks);
                for (ItemStack stack : rest) {
                    String data;
                    try {
                        data = host.encode(stack);
                    } catch (RuntimeException unwritable) {
                        host.logger().log(Level.WARNING, "Reward " + entry.id() + ": an item that did not fit cannot be"
                                + " stored; dropping it at the feet of " + player.getName(), unwritable);
                        InvUtil.giveItems(player, InvUtil.Overflow.DROP, stack);
                        continue;
                    }
                    queued.add(new PendingStore.Row(entry.id(), RewardType.ITEM, data, stack.getAmount()));
                    queuedEntry.add(index);
                    queuedStacks.add(stack);
                }
                // What fit is given; once the rest is written the reward becomes QUEUED.
                return RewardOutcome.GIVEN;
            }
            default -> {
                InvUtil.giveItems(player, InvUtil.Overflow.DROP, stacks);
                return RewardOutcome.GIVEN;
            }
        }
    }

    private RewardOutcome command(ResolvedReward entry) {
        boolean ran;
        try {
            ran = host.console(entry.text());
        } catch (RuntimeException e) {
            host.logger().log(Level.WARNING, "Reward " + entry.id() + ": the command '" + entry.text() + "' failed", e);
            return RewardOutcome.FAILED;
        }
        if (!ran) {
            warnOnce("command:" + entry.id(), "Reward " + entry.id() + ": the server did not run the command '"
                    + entry.text() + "'; reported as failed.");
            return RewardOutcome.FAILED;
        }
        return RewardOutcome.GIVEN;
    }

    /** Runs {@code then} back on the player's thread; when that cannot happen, finishes the report here. */
    private void afterwards(Player player, Runnable then, CompletableFuture<RewardReport> result,
                            List<ResolvedReward> entries, RewardOutcome[] outcomes) {
        if (host.ownsHere(player)) {
            then.run();
            return;
        }
        Runnable here = () -> result.complete(report(entries, outcomes));
        try {
            host.runFor(player, then, here);
        } catch (RuntimeException notScheduled) {
            here.run();
        }
    }

    /** Builds the report and tells the player what was kept or claimed. */
    private void finish(Player player, List<ResolvedReward> entries, RewardOutcome[] outcomes, boolean claim,
                        CompletableFuture<RewardReport> result) {
        RewardReport report = report(entries, outcomes);
        try {
            if (player.isOnline()) {
                int given = report.count(RewardOutcome.GIVEN);
                int waiting = report.count(RewardOutcome.QUEUED);
                if (claim && given > 0) {
                    host.send(player, CLAIMED, Ph.of("count", given));
                }
                if (waiting > 0) {
                    host.send(player, WAITING, Ph.of("count", waiting));
                }
            }
        } catch (RuntimeException e) {
            host.logger().log(Level.WARNING, "Could not tell " + player.getName() + " about their rewards", e);
        }
        result.complete(report);
    }

    private static RewardReport report(List<ResolvedReward> entries, RewardOutcome[] outcomes) {
        List<RewardResult> results = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            ResolvedReward entry = entries.get(i);
            RewardOutcome outcome = outcomes[i] == null ? RewardOutcome.FAILED : outcomes[i];
            results.add(new RewardResult(entry.id(), entry.type(), outcome));
        }
        return new RewardReport(results);
    }

    // ------------------------------------------------------------------ kept for later

    /** True when this plugin keeps rewards for later ({@code RsSpec.builder().db().rewards()}). */
    public boolean keepsPending() {
        return store != null;
    }

    /**
     * Hands over everything kept for {@code player}, each row exactly once; what does not fit
     * (or money the economy refused) goes back to the table. Runs by itself when the player
     * joins. The report lists one result per row.
     *
     * @throws UnsupportedOperationException when the plugin does not keep rewards
     */
    public CompletableFuture<RewardReport> claim(Player player) {
        Objects.requireNonNull(player, "player");
        PendingStore pending = requireStore("claim");
        UUID id = player.getUniqueId();
        CompletableFuture<RewardReport> result = new CompletableFuture<>();
        pending.take(id, host::shuttingDown).whenComplete((rows, error) -> {
            if (error != null) {
                if (!host.shuttingDown()) {
                    host.logger().log(Level.WARNING, "Could not read the rewards kept for " + player.getName()
                            + "; they stay kept.", error);
                }
                result.complete(RewardReport.EMPTY);
                return;
            }
            if (rows.isEmpty()) {
                result.complete(RewardReport.EMPTY);
                return;
            }
            MainHops.Hop hop = MainHops.add(host.owner(), () -> putBack(id, rows, result));
            Runnable back = () -> {
                if (hop.claim()) {
                    putBack(id, rows, result);
                }
            };
            try {
                host.runFor(player, () -> {
                    if (!hop.claim()) {
                        return;
                    }
                    if (!player.isOnline()) {
                        putBack(id, rows, result);
                        return;
                    }
                    forward(handOver(player, rows), result);
                }, back);
            } catch (RuntimeException notScheduled) {
                back.run();
            }
        });
        return result;
    }

    /** Turns taken rows back into rewards and hands them over; unreadable rows go back as they were. */
    private CompletableFuture<RewardReport> handOver(Player player, List<PendingStore.Row> rows) {
        List<ResolvedReward> entries = new ArrayList<>(rows.size());
        List<PendingStore.Row> unreadable = new ArrayList<>();
        for (PendingStore.Row row : rows) {
            ResolvedReward entry = entry(row);
            if (entry == null) {
                unreadable.add(row);
                entries.add(ResolvedReward.skipped(row.entry(), row.type(), RewardOutcome.FAILED));
            } else {
                entries.add(entry);
            }
        }
        if (!unreadable.isEmpty()) {
            UUID id = player.getUniqueId();
            store.put(id, unreadable).whenComplete((ignored, error) -> {
                if (error != null) {
                    for (PendingStore.Row row : unreadable) {
                        lost(id, row);
                    }
                }
            });
        }
        return deliverNow(player, entries, OverflowPolicy.QUEUE, true);
    }

    /** The reward a row stands for, or null (reported) when it cannot be read. */
    private ResolvedReward entry(PendingStore.Row row) {
        try {
            return switch (row.type()) {
                case ITEM -> {
                    ItemStack stack = host.decode(row.data());
                    if (Reward.empty(stack)) {
                        throw new IllegalArgumentException("no item");
                    }
                    yield ResolvedReward.items(row.entry(), List.of(stack));
                }
                case COMMAND, MESSAGE -> ResolvedReward.text(row.entry(), row.type(), Objects.requireNonNull(row.data()));
                case MONEY, EXPERIENCE -> ResolvedReward.amount(row.entry(), row.type(), row.amount());
                case POTION -> {
                    String[] words = Objects.requireNonNull(row.data()).split(" ");
                    yield ResolvedReward.potion(row.entry(), words[0], Integer.parseInt(words[1]), Integer.parseInt(words[2]));
                }
            };
        } catch (RuntimeException unreadable) {
            host.logger().log(Level.SEVERE, "A kept reward (" + row.entry() + ", " + row.type()
                    + ") cannot be read; it stays kept: " + unreadable);
            return null;
        }
    }

    /** Puts taken rows back because they were not given; completes {@code result} as all queued. */
    private void putBack(UUID player, List<PendingStore.Row> rows, CompletableFuture<RewardReport> result) {
        store.put(player, rows).whenComplete((ignored, error) -> {
            RewardOutcome outcome = RewardOutcome.QUEUED;
            if (error != null) {
                outcome = RewardOutcome.FAILED;
                host.logger().log(Level.SEVERE, "Could not put back rewards that were not given to " + player, error);
                for (PendingStore.Row row : rows) {
                    lost(player, row);
                }
            }
            List<RewardResult> results = new ArrayList<>(rows.size());
            for (PendingStore.Row row : rows) {
                results.add(new RewardResult(row.entry(), row.type(), outcome));
            }
            result.complete(new RewardReport(results));
        });
    }

    /** Keeps every won reward of {@code entries} for later. */
    private CompletableFuture<RewardReport> keep(UUID player, List<ResolvedReward> entries) {
        RewardOutcome[] outcomes = new RewardOutcome[entries.size()];
        List<PendingStore.Row> rows = new ArrayList<>();
        List<Integer> rowEntry = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            ResolvedReward entry = entries.get(i);
            if (!entry.won()) {
                outcomes[i] = entry.skipped();
                continue;
            }
            try {
                for (PendingStore.Row row : rows(entry)) {
                    rows.add(row);
                    rowEntry.add(i);
                }
            } catch (RuntimeException unwritable) {
                outcomes[i] = RewardOutcome.FAILED;
                host.logger().log(Level.SEVERE, "Reward " + entry.id() + " for " + player + " cannot be stored and"
                        + " was not given: " + entry.stacks(), unwritable);
            }
        }
        if (rows.isEmpty()) {
            return CompletableFuture.completedFuture(report(entries, outcomes));
        }
        if (store == null) {
            warnOnce("keep", "A reward was owed to a player who is not online, but this plugin does not keep rewards"
                    + " for later: add .db().rewards() to buildSpec().");
            for (int r = 0; r < rows.size(); r++) {
                outcomes[rowEntry.get(r)] = RewardOutcome.FAILED;
                lost(player, rows.get(r));
            }
            return CompletableFuture.completedFuture(report(entries, outcomes));
        }
        CompletableFuture<RewardReport> result = new CompletableFuture<>();
        store.put(player, rows).whenComplete((ignored, error) -> {
            RewardOutcome outcome = error == null ? RewardOutcome.QUEUED : RewardOutcome.FAILED;
            if (error != null) {
                host.logger().log(Level.SEVERE, "Could not keep the rewards owed to " + player, error);
            }
            for (int r = 0; r < rows.size(); r++) {
                outcomes[rowEntry.get(r)] = outcome;
                if (error != null) {
                    lost(player, rows.get(r));
                }
            }
            result.complete(report(entries, outcomes));
            Player online = error == null ? host.online(player) : null;
            if (online != null && !stopped) {
                // They joined while the rows were being written: the join claim may have missed them.
                claim(online);
            }
        });
        return result;
    }

    /** The rows a won reward is kept as: one per stack, or one. */
    private List<PendingStore.Row> rows(ResolvedReward entry) {
        return switch (entry.type()) {
            case ITEM -> {
                List<PendingStore.Row> out = new ArrayList<>();
                for (ItemStack stack : entry.stacks()) {
                    out.add(new PendingStore.Row(entry.id(), RewardType.ITEM, host.encode(stack), stack.getAmount()));
                }
                yield out;
            }
            case COMMAND, MESSAGE -> List.of(new PendingStore.Row(entry.id(), entry.type(), entry.text(), 0));
            case MONEY, EXPERIENCE -> List.of(new PendingStore.Row(entry.id(), entry.type(), null, entry.amount()));
            case POTION -> List.of(new PendingStore.Row(entry.id(), RewardType.POTION,
                    entry.effect() + " " + entry.ticks() + " " + entry.amplifier(), 0));
        };
    }

    private void lostEntry(UUID player, ResolvedReward entry) {
        try {
            for (PendingStore.Row row : rows(entry)) {
                lost(player, row);
            }
        } catch (RuntimeException unwritable) {
            host.logger().severe("A reward for " + player + " could not be given nor kept: " + entry + " "
                    + entry.stacks());
        }
    }

    /** Writes a reward that could be neither given nor kept to the log, whole, so it can be given by hand. */
    private void lost(UUID player, PendingStore.Row row) {
        host.logger().severe("A reward for " + player + " could not be given nor kept: reward "
                + row.entry() + ", " + row.type() + (row.data() == null ? "" : ", data " + row.data())
                + (row.amount() == 0 ? "" : ", amount " + row.amount()));
    }

    /**
     * How many rows are kept for {@code player}, without taking them (for a "you have rewards
     * to claim" line). Any thread; the query runs on the database threads.
     *
     * @throws UnsupportedOperationException when the plugin does not keep rewards
     */
    public CompletableFuture<Integer> pendingCount(UUID player) {
        Objects.requireNonNull(player, "player");
        return requireStore("pendingCount").count(player).exceptionally(error -> {
            host.logger().log(Level.WARNING, "Could not count the rewards kept for " + player, error);
            return 0;
        });
    }

    private PendingStore requireStore(String method) {
        if (store == null) {
            throw new UnsupportedOperationException(name + " called rs().rewards()." + method
                    + " without keeping rewards: add .db().rewards() to buildSpec().");
        }
        return store;
    }

    // ------------------------------------------------------------------ lifecycle

    /** A player joined: hands over what is kept for them. */
    void joined(Player player) {
        if (store == null || stopped) {
            return;
        }
        try {
            claim(player);
        } catch (RuntimeException e) {
            host.logger().log(Level.WARNING, "Could not hand over the rewards kept for " + player.getName(), e);
        }
    }

    /** Stops the join claims; called by the context on shutdown, before the database closes. */
    public void shutdown() {
        stopped = true;
        LIVE.remove(this);
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) {
            host.logger().warning(message);
        }
    }

    /** Claims kept rewards on join for every plugin that keeps them. */
    static final class JoinListener implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onJoin(PlayerJoinEvent event) {
            for (Rewards rewards : LIVE) {
                rewards.joined(event.getPlayer());
            }
        }
    }
}
