package com.rs.lib.action;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.entity.Player;

import com.rs.lib.Ph;

/**
 * An action list parsed once by {@link ActionEngine#compile(List, String)}: keep it with the
 * button, item or reward it belongs to and {@link #run(ActionContext)} it on every use; only
 * the placeholders are filled in then. The tag of each line is looked up when it runs, so
 * actions a plugin registers after the list was compiled still apply. Immutable.
 */
public final class ActionList {

    private final ActionEngine engine;
    private final Step[] steps;
    private final List<String> lines;

    ActionList(ActionEngine engine, Step[] steps, List<String> lines) {
        this.engine = engine;
        this.steps = steps;
        this.lines = lines;
    }

    /** Runs the list for {@code context.player()}; see {@link ActionEngine#run(Player, List, ActionContext)}. */
    public void run(ActionContext context) {
        engine.dispatch(this, context.player(), context);
    }

    /** Runs the list for {@code player} with only local placeholders (no menu, no click). */
    public void run(Player player, Ph... phs) {
        engine.dispatch(this, player, ActionContext.of(player, engine.context(), phs));
    }

    /** True when no line does anything (an empty list, blank lines). */
    public boolean isEmpty() {
        return steps.length == 0;
    }

    /** The lines as given to {@code compile}, blank ones included. */
    public List<String> lines() {
        return lines;
    }

    /** The action tag of every line that does something, in order ({@code message} for untagged lines). */
    public List<String> tags() {
        List<String> tags = new ArrayList<>(steps.length);
        for (Step step : steps) {
            tags.add(step.tag());
        }
        return tags;
    }

    Step[] steps() {
        return steps;
    }

    @Override
    public String toString() {
        return "ActionList" + lines;
    }
}
