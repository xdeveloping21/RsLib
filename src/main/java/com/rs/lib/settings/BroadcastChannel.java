package com.rs.lib.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.bukkit.entity.Player;

import com.rs.lib.Ph;

import net.kyori.adventure.text.Component;

/**
 * Announcements players can mute, from {@code rs().settings().channel(toggle)}: a message sent
 * here reaches the online players who have the toggle on (players without the toggle's
 * permission read its default) and the console. Safe from any thread.
 */
public final class BroadcastChannel {

    private final SettingsManager settings;
    private final SettingsHost host;
    private final String key;

    BroadcastChannel(SettingsManager settings, SettingsHost host, String key) {
        this.settings = settings;
        this.host = host;
        this.key = key;
    }

    /** The key of the toggle behind this channel. */
    public String key() {
        return key;
    }

    /** The online players who have this channel on. */
    public List<Player> recipients() {
        List<Player> players = new ArrayList<>();
        for (Player player : List.copyOf(host.online())) {
            if (settings.option(key) != null && settings.enabled(player, key)) {
                players.add(player);
            }
        }
        return players;
    }

    /** Sends {@code message} to every player who has the channel on, and to the console; returns how many players. */
    public int send(Component message) {
        Objects.requireNonNull(message, "message");
        List<Player> players = recipients();
        for (Player player : players) {
            player.sendMessage(message);
        }
        host.console().sendMessage(message);
        return players.size();
    }

    /**
     * Sends the message {@code messageKey} of the plugin's language files (with its prefix and
     * placeholders, resolved for each player) to every player who has the channel on, and to
     * the console; returns how many players.
     */
    public int send(String messageKey, Ph... phs) {
        Objects.requireNonNull(messageKey, "messageKey");
        List<Player> players = recipients();
        for (Player player : players) {
            host.send(player, messageKey, phs);
        }
        host.send(host.console(), messageKey, phs);
        return players.size();
    }
}
