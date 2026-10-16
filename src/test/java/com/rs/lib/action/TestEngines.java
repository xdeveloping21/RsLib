package com.rs.lib.action;

import java.util.List;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import com.rs.lib.util.Formats;

/** An action engine without a server, for the tests of other packages (menus). */
public final class TestEngines {

    private TestEngines() {
    }

    /** A fresh engine over a fake host that runs everything on the calling thread. */
    public static Engine create() {
        return new Engine();
    }

    /** The engine and what its host recorded. */
    public static final class Engine {
        private final FakeActionHost host = new FakeActionHost();
        private final ActionEngine engine = new ActionEngine(host);

        public ActionEngine engine() {
            return engine;
        }

        /** Warnings the engine logged (unknown actions, requirement lists written wrong). */
        public List<String> warnings() {
            return host.warnings;
        }

        /** Commands sent to the console with {@code [console]}. */
        public List<String> console() {
            return host.console;
        }

        /** What PlaceholderAPI answers (identity by default). */
        public void papi(UnaryOperator<String> papi) {
            host.papi = papi;
        }

        /** The plugin's words for {@code {key:relative}} (null: none). */
        public void relativeTexts(Supplier<Formats.RelativeTexts> texts) {
            host.relative = texts;
        }

        /** Runs what was scheduled for the next tick. */
        public void tick() {
            host.tick();
        }
    }
}
