package com.rs.lib.price;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * What a stack sells for: {@code each} is the price of one item, {@code amount} how many items
 * the stack had and {@code source} who answered ({@link Prices#OWN} or {@link Prices#ESSENTIALS}).
 */
public record SellPrice(BigDecimal each, int amount, String source) {

    public SellPrice {
        Objects.requireNonNull(each, "each");
        Objects.requireNonNull(source, "source");
    }

    /** {@code each × amount}, exact. */
    public BigDecimal total() {
        return each.multiply(BigDecimal.valueOf(amount));
    }
}
