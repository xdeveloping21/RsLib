package com.rs.lib.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

/** The lines the registry logs when names change hands, and who gets a freed name back. */
class RegistryLinesTest {

    private static Command command(String name) {
        return new Command(name) {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                return true;
            }
        };
    }

    @Test
    void lostNameLines() {
        assertEquals("Command '/money' of MyPlugin is taken by Essentials; kept it. This command answers as /myplugin:money",
                CommandRegistry.keptLine("MyPlugin", "money", "money", "Essentials"));
        assertEquals("Alias '/bal' of '/money' in MyPlugin is taken by Essentials; kept it. This alias answers as /myplugin:bal",
                CommandRegistry.keptLine("MyPlugin", "money", "bal", "Essentials"));
        assertEquals("Command '/money' of MyPlugin is taken by another command; kept it. This command answers as "
                + "/myplugin:money", CommandRegistry.keptLine("MyPlugin", "money", "money", "another command"));
    }

    @Test
    void priorityLines() {
        assertEquals("Command '/money' of MyPlugin took the name from Essentials (command priority); Essentials still "
                + "answers as /essentials:money", CommandRegistry.tookLine("MyPlugin", "money", "money", "Essentials", true));
        assertEquals("Alias '/bal' of '/money' in MyPlugin took the name from Essentials (command priority); Essentials "
                + "still answers as /essentials:bal", CommandRegistry.tookLine("MyPlugin", "money", "bal", "Essentials", true));
        assertEquals("Command '/money' of MyPlugin took the name from Essentials (command priority)",
                CommandRegistry.tookLine("MyPlugin", "money", "money", "Essentials", false));
        assertEquals("Alias '/bal' of '/money' in MyPlugin took the name from Essentials (command priority)",
                CommandRegistry.tookLine("MyPlugin", "money", "bal", "Essentials", false));
    }

    @Test
    void freedNameGoesToTheOriginalOrToWhatAnswersNow() {
        Command original = command("money");
        Command now = command("money");
        assertSame(original, CommandRegistry.giveBackTo(original, now, c -> true));
        assertSame(now, CommandRegistry.giveBackTo(original, now, c -> c == now));
        assertSame(now, CommandRegistry.giveBackTo(null, now, c -> true));
        assertNull(CommandRegistry.giveBackTo(original, original, c -> c != original));
        assertNull(CommandRegistry.giveBackTo(original, null, c -> false));
        assertNull(CommandRegistry.giveBackTo(original, now, c -> false));
    }
}
