package com.rs.lib.velocity;

import org.slf4j.Logger;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.ProxyServer;

/**
 * RsLib as a Velocity plugin (id {@code rslib}, declared in {@code velocity-plugin.json}). The
 * same jar is the Paper plugin; on the proxy only this package, the text pipeline and the
 * config merge are loaded, never a Bukkit class. Proxy plugins depend on {@code "rslib"} and
 * use {@link Rsv}; RsLib itself only logs that it is ready.
 */
public final class RsLibVelocity {

    private final ProxyServer proxy;
    private final Logger logger;

    @Inject
    public RsLibVelocity(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        String version = proxy.getPluginManager().fromInstance(this)
                .flatMap(container -> container.getDescription().getVersion())
                .orElse("?");
        logger.info("RsLib {} enabled on Velocity.", version);
    }
}
