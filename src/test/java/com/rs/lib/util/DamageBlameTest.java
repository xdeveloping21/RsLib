package com.rs.lib.util;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.OfflinePlayer;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.junit.jupiter.api.Test;

/** Who is behind a hit: each kind of hop, chains of them, the hop limit and the dead ends. */
class DamageBlameTest {

    /** An entity of {@code type} answering the getters in {@code answers}. */
    private static <T> T entity(Class<T> type, Map<String, Object> answers) {
        return type.cast(Proxy.newProxyInstance(DamageBlameTest.class.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> type.getSimpleName();
                    default -> {
                        if (answers.containsKey(method.getName())) {
                            yield answers.get(method.getName());
                        }
                        throw new UnsupportedOperationException(method.getName());
                    }
                }));
    }

    private static final Player STEVE = entity(Player.class, Map.of());

    private static Arrow arrowFrom(Object shooter) {
        Map<String, Object> answers = new HashMap<>();
        answers.put("getShooter", shooter);
        return entity(Arrow.class, answers);
    }

    @Test
    void aPlayerIsTheirOwnCulprit() {
        assertSame(STEVE, DamageBlame.responsiblePlayer(STEVE));
    }

    @Test
    void projectilesTntCloudsFangsAndPetsLeadToThePlayer() {
        assertSame(STEVE, DamageBlame.responsiblePlayer(arrowFrom(STEVE)));
        assertSame(STEVE, DamageBlame.responsiblePlayer(entity(TNTPrimed.class, Map.of("getSource", STEVE))));
        assertSame(STEVE, DamageBlame.responsiblePlayer(entity(AreaEffectCloud.class, Map.of("getSource", STEVE))));
        assertSame(STEVE, DamageBlame.responsiblePlayer(entity(EvokerFangs.class, Map.of("getOwner", STEVE))));
        assertSame(STEVE, DamageBlame.responsiblePlayer(
                entity(Wolf.class, Map.of("isTamed", true, "getOwner", STEVE))));
    }

    @Test
    void chainsAreFollowedUpToFourHops() {
        // TNT lit by a flaming arrow shot by Steve: three hops.
        TNTPrimed tnt = entity(TNTPrimed.class, Map.of("getSource", arrowFrom(STEVE)));
        assertSame(STEVE, DamageBlame.responsiblePlayer(tnt));
        // Four hops still count.
        Entity four = entity(TNTPrimed.class, Map.of("getSource", entity(TNTPrimed.class,
                Map.of("getSource", entity(TNTPrimed.class, Map.of("getSource", arrowFrom(STEVE)))))));
        assertSame(STEVE, DamageBlame.responsiblePlayer(four));
        // Five do not.
        Entity five = entity(TNTPrimed.class, Map.of("getSource", four));
        assertNull(DamageBlame.responsiblePlayer(five));
    }

    @Test
    void noPlayerBehindItGivesNull() {
        Skeleton skeleton = entity(Skeleton.class, Map.of());
        assertNull(DamageBlame.responsiblePlayer(arrowFrom(skeleton)), "a mob's arrow");
        assertNull(DamageBlame.responsiblePlayer(arrowFrom(null)), "a dispenser's arrow has no entity shooter");
        Map<String, Object> unlit = new HashMap<>();
        unlit.put("getSource", null);
        assertNull(DamageBlame.responsiblePlayer(entity(TNTPrimed.class, unlit)), "TNT nobody lit");
        assertNull(DamageBlame.responsiblePlayer(entity(Zombie.class, Map.of())));
        assertNull(DamageBlame.responsiblePlayer(entity(Wolf.class, Map.of("isTamed", false))), "a wild wolf");
        OfflinePlayer away = entity(OfflinePlayer.class, Map.of());
        assertNull(DamageBlame.responsiblePlayer(entity(Wolf.class, Map.of("isTamed", true, "getOwner", away))),
                "an owner who is offline");
        assertNull(DamageBlame.responsiblePlayer((Entity) null));
    }

    @Test
    @SuppressWarnings("UnstableApiUsage")
    void eventsAreReadThroughTheirDamager() {
        Zombie victim = entity(Zombie.class, Map.of());
        DamageSource source = entity(DamageSource.class, Map.of());
        EntityDamageEvent byArrow = new EntityDamageByEntityEvent(arrowFrom(STEVE), victim, DamageCause.PROJECTILE,
                source, 4.0);
        assertSame(STEVE, DamageBlame.responsiblePlayer(byArrow));
        assertNull(DamageBlame.responsiblePlayer(new EntityDamageEvent(victim, DamageCause.FALL, source, 3.0)));
        assertNull(DamageBlame.responsiblePlayer((EntityDamageEvent) null));
    }
}
