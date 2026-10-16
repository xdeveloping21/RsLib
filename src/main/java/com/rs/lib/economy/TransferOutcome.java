package com.rs.lib.economy;

/**
 * What a {@link EconomyBridge#transfer transfer} between two players did. Only {@link #DONE}
 * moved money from one to the other; {@link #REFUND_FAILED} is the one outcome where money left
 * the payer and reached nobody, and needs a manual refund.
 */
public enum TransferOutcome {

    /** The payer paid and the receiver got the amount. */
    DONE,
    /** Refused before touching any balance: a missing player, the same player twice, or an amount that is not a finite number above 0. */
    INVALID,
    /** No economy is available, or the plugin shut down before the transfer could start; nothing moved. */
    NO_ECONOMY,
    /** A player is offline and the economy in use only serves online players; nothing moved. */
    OFFLINE,
    /** The payer did not have the amount, or the economy refused the take; nothing moved. */
    NOT_TAKEN,
    /** The amount was taken, giving it to the receiver failed and it went back to the payer; nothing moved in the end. */
    REFUNDED,
    /**
     * The amount was taken, giving it to the receiver failed and so did the refund: the payer is
     * short the amount and nobody has it. Logged at SEVERE with both players and the amount.
     */
    REFUND_FAILED;

    /** True only for {@link #DONE}. */
    public boolean moved() {
        return this == DONE;
    }

    /** True when both balances are as they were before the transfer (everything except {@link #DONE} and {@link #REFUND_FAILED}). */
    public boolean nothingMoved() {
        return this != DONE && this != REFUND_FAILED;
    }
}
