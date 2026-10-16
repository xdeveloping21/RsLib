package com.rs.lib.yml;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import com.rs.lib.util.Formats;

/** Test helpers: a host without a server and a logger that remembers what it printed. */
public final class YmlTestSupport {

    private YmlTestSupport() {
    }

    /** A logger that keeps every record. */
    public static final class Captured {
        public final Logger logger;
        public final List<LogRecord> records = new ArrayList<>();

        public Captured() {
            logger = Logger.getAnonymousLogger();
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.ALL);
            logger.addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    synchronized (records) {
                        records.add(record);
                    }
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            });
        }

        public List<String> messages(Level level) {
            List<String> out = new ArrayList<>();
            synchronized (records) {
                for (LogRecord record : records) {
                    if (record.getLevel().equals(level)) {
                        out.add(record.getMessage());
                    }
                }
            }
            return out;
        }

        public List<String> warnings() {
            return messages(Level.WARNING);
        }

        public void clear() {
            synchronized (records) {
                records.clear();
            }
        }
    }

    /** A host whose async tasks wait in a queue until {@link #runTasks()}. */
    public static final class FakeHost implements YmlHost {
        public final Captured log = new Captured();
        public final List<Runnable> tasks = new ArrayList<>();
        public final List<String> traces = new ArrayList<>();
        public volatile boolean primary;
        public volatile boolean shuttingDown;
        public volatile boolean refuseAsync;
        /** The plugin's words of {@code :relative}; null for RsLib's. */
        public volatile Supplier<Formats.RelativeTexts> relative;

        @Override
        public Supplier<Formats.RelativeTexts> relativeTexts() {
            return relative;
        }

        @Override
        public Logger logger() {
            return log.logger;
        }

        @Override
        public boolean isPrimaryThread() {
            return primary;
        }

        @Override
        public String papi(Player viewer, String text) {
            return text.replace("%server_name%", "Lobby");
        }

        @Override
        public void async(Runnable task) {
            if (refuseAsync) {
                throw new IllegalStateException("plugin disabled");
            }
            tasks.add(task);
        }

        @Override
        public boolean shuttingDown() {
            return shuttingDown;
        }

        @Override
        public void trace(Supplier<String> message) {
            traces.add(message.get());
        }

        public int runTasks() {
            List<Runnable> now = new ArrayList<>(tasks);
            tasks.clear();
            now.forEach(Runnable::run);
            return now.size();
        }
    }

    /** Opens {@code file} as a data file with a fake host, already read. */
    public static RsYml open(File file, FakeHost host) {
        RsYml yml = new RsYml(host, file, file.getName());
        yml.read();
        return yml;
    }
}
