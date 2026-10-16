package com.rs.lib.db;

import java.sql.SQLException;

/**
 * An export or import that RsLib would not run or could not finish. When it comes from an
 * import, the transaction was rolled back and nothing was written. The message is written for
 * the server owner and says what to do.
 */
public final class TransferRefused extends SQLException {

    private static final long serialVersionUID = 1L;

    public TransferRefused(String message) {
        super(message);
    }

    public TransferRefused(String message, Throwable cause) {
        super(message, cause);
    }
}
