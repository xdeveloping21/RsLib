package com.rs.lib.price;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * EssentialsX's {@code worth.yml}, reached only through reflection: {@code getWorth()} on the
 * Essentials plugin and {@code getPrice(...)} on what it returns, which takes the item (and,
 * in the versions that have it, Essentials itself). The worth object is asked for on every
 * lookup, so {@code /essentials reload} and {@code /setworth} are seen at once. Created by
 * {@link Prices}' soft dependency only after Essentials was found enabled; when the methods
 * are not there it throws and the hook stays off (every item it would price is not sellable).
 */
final class EssentialsWorth implements Prices.Lookup {

    private final Object essentials;
    private final Method getWorth;
    private final Method getPrice;
    /** True when {@link #getPrice} takes Essentials before the item. */
    private final boolean takesEssentials;

    private EssentialsWorth(Object essentials, Method getWorth, Method getPrice, boolean takesEssentials) {
        this.essentials = essentials;
        this.getWorth = getWorth;
        this.getPrice = getPrice;
        this.takesEssentials = takesEssentials;
    }

    static Prices.Lookup create() {
        Plugin essentials = Bukkit.getPluginManager().getPlugin("Essentials");
        if (essentials == null) {
            throw new IllegalStateException("Essentials is not installed");
        }
        return of(essentials);
    }

    /** The lookup of {@code essentials}; throws when it has no worth list RsLib can read. */
    static EssentialsWorth of(Object essentials) {
        Method getWorth;
        try {
            getWorth = essentials.getClass().getMethod("getWorth");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Essentials has no getWorth()", e);
        }
        Method twoArgs = null;
        Method oneArg = null;
        for (Method method : getWorth.getReturnType().getMethods()) {
            if (!method.getName().equals("getPrice") || !Number.class.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 2 && params[0].isInstance(essentials) && params[1] == ItemStack.class) {
                twoArgs = method;
            } else if (params.length == 1 && params[0] == ItemStack.class) {
                oneArg = method;
            }
        }
        Method chosen = twoArgs != null ? twoArgs : oneArg;
        if (chosen == null) {
            throw new IllegalStateException("Essentials' worth list has no getPrice for an item");
        }
        return new EssentialsWorth(essentials, getWorth, chosen, chosen == twoArgs);
    }

    @Override
    public BigDecimal unitPrice(ItemStack stack) {
        try {
            Object worth = getWorth.invoke(essentials);
            if (worth == null) {
                return null;
            }
            Object price = takesEssentials ? getPrice.invoke(worth, essentials, stack) : getPrice.invoke(worth, stack);
            if (price == null) {
                return null;
            }
            return price instanceof BigDecimal exact ? exact : new BigDecimal(price.toString());
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("Essentials failed to price " + stack.getType(), e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Essentials' getPrice cannot be called", e);
        }
    }
}
