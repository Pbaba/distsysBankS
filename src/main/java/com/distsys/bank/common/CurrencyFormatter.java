package com.distsys.bank.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Utility for parsing and formatting monetary values.
 * All monetary amounts are strictly maintained internally as long integers in paise.
 * (1 Rupee = 100 paise). No double or float primitives are ever used for balances.
 */
public final class CurrencyFormatter {
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private CurrencyFormatter() {}

    /**
     * Parses a string input (e.g. "20000", "5000.50") into long paise.
     */
    public static long parsePaise(String amountStr) {
        if (amountStr == null || amountStr.trim().isEmpty()) {
            throw new IllegalArgumentException("Amount cannot be empty");
        }
        String cleaned = amountStr.trim().replace("₹", "").replace(",", "");
        BigDecimal decimal = new BigDecimal(cleaned);
        if (decimal.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive: " + amountStr);
        }
        BigDecimal inPaise = decimal.multiply(HUNDRED).setScale(0, RoundingMode.UNNECESSARY);
        return inPaise.longValueExact();
    }

    /**
     * Formats internal paise into currency string with two decimal places (e.g. 5000.00).
     */
    public static String formatPaise(long paise) {
        BigDecimal decimal = BigDecimal.valueOf(paise).divide(HUNDRED, 2, RoundingMode.UNNECESSARY);
        return decimal.toPlainString();
    }

    /**
     * Formats internal paise for human-readable display with Rupee symbol (e.g. ₹5,000.00).
     */
    public static String formatDisplay(long paise) {
        return "₹" + formatPaise(paise);
    }
}
