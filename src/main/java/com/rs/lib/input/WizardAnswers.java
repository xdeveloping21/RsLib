package com.rs.lib.input;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a player answered in a {@link Wizard}, by step key, in the order the steps were asked.
 * A copy: it never changes after it is handed out.
 */
public final class WizardAnswers {

    private final Map<String, Object> values;

    WizardAnswers(Map<String, Object> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** Whether step {@code key} has an answer. */
    public boolean has(String key) {
        return values.containsKey(key);
    }

    /**
     * The answer of step {@code key}: a {@code String} for text and choice steps, a
     * {@code BigDecimal} for amounts, a {@code Long} for whole numbers, whatever the parser made
     * for custom steps.
     *
     * @throws IllegalArgumentException when the step has no answer (the message lists the ones there are)
     */
    public Object get(String key) {
        Object value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("The wizard has no answer for '" + key + "'; it has " + keys());
        }
        return value;
    }

    /**
     * The answer of step {@code key} as {@code type}.
     *
     * @throws IllegalArgumentException when there is no answer or it is of another type
     */
    public <T> T get(String key, Class<T> type) {
        Object value = get(key);
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException("The wizard answer '" + key + "' is a " + value.getClass().getSimpleName()
                    + ", not a " + type.getSimpleName());
        }
        return type.cast(value);
    }

    /** The answer of a text or choice step. */
    public String text(String key) {
        return get(key, String.class);
    }

    /** The answer of an amount step. */
    public BigDecimal amount(String key) {
        return get(key, BigDecimal.class);
    }

    /** The answer of a whole number step. */
    public long whole(String key) {
        return get(key, Long.class);
    }

    /** The keys answered, in the order they were asked. */
    public List<String> keys() {
        return List.copyOf(values.keySet());
    }

    /** Every answer by key, in the order they were asked; read-only. */
    public Map<String, Object> asMap() {
        return values;
    }

    /** How many steps have an answer. */
    public int size() {
        return values.size();
    }

    @Override
    public String toString() {
        return "WizardAnswers" + values;
    }
}
