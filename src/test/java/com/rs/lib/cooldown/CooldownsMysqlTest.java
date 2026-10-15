package com.rs.lib.cooldown;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.rs.lib.db.FakeDbHost;
import com.rs.lib.db.RsDb;
import com.rs.lib.db.TestDbs;
import com.rs.lib.scheduler.TaskHandle;

/** Persistent cooldowns on a real MySQL/MariaDB server; skipped unless {@code RSLIB_TEST_MYSQL} is set. */
@EnabledIfEnvironmentVariable(named = TestDbs.MYSQL_ENV, matches = ".+")
class CooldownsMysqlTest {

    private final AtomicLong now = new AtomicLong(5_000_000L);

    private Cooldowns cooldowns(RsDb db) {
        return new Cooldowns(Logger.getAnonymousLogger(), now::get, (period, task) -> new TaskHandle() {
            @Override
            public void cancel() {
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        }, () -> db, "MysqlTest");
    }

    @Test
    void persistentCategorySurvivesARestart() {
        RsDb db = TestDbs.mysql(new FakeDbHost(), 2);
        db.update("DROP TABLE IF EXISTS rslib_cooldowns", null).join();
        Cooldowns before = cooldowns(db);
        before.registerPersistentCategory("kit").join();
        UUID player = UUID.randomUUID();
        UUID cleared = UUID.randomUUID();
        before.tryUse(player, "kit", Duration.ofMinutes(10));
        before.tryUse(cleared, "kit", Duration.ofMinutes(10));
        before.clearAll();
        before.tryUse(player, "kit", Duration.ofMinutes(5));
        before.shutdown();
        db.shutdown();

        RsDb reopened = TestDbs.mysql(new FakeDbHost(), 2);
        try {
            Cooldowns after = cooldowns(reopened);
            after.registerPersistentCategory("kit").join();
            assertEquals(300_000L, after.remainingMillis(player, "kit"));
            assertEquals(0L, after.remainingMillis(cleared, "kit"));
        } finally {
            reopened.shutdown();
        }
    }
}
