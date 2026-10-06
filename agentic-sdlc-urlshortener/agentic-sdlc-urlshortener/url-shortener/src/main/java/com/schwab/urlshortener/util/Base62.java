package com.schwab.urlshortener.util;

/**
 * Base62 ({@code 0-9a-zA-Z}) encoding of non-negative longs.
 *
 * <p>Base62 is URL-safe and dense: 62^7 is about 3.5 trillion codes in seven characters.
 * Encoding a unique, monotonically increasing id yields collision-free short codes by
 * construction, which is why the service never needs a generate-and-check loop.
 */
public final class Base62 {

    private static final String ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int BASE = ALPHABET.length(); // 62

    /** Non-instantiable utility class. */
    private Base62() {
    }

    /**
     * Encodes a non-negative long into its Base62 string representation.
     *
     * @param value the value to encode; must be {@code >= 0}
     * @return the Base62 string (never empty; {@code "0"} for zero)
     * @throws IllegalArgumentException if {@code value} is negative
     */
    public static String encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative: " + value);
        }
        if (value == 0) {
            return String.valueOf(ALPHABET.charAt(0));
        }
        StringBuilder sb = new StringBuilder();
        long v = value;
        while (v > 0) {
            sb.append(ALPHABET.charAt((int) (v % BASE)));
            v /= BASE;
        }
        return sb.reverse().toString();
    }

    /**
     * Decodes a Base62 string back into its long value.
     *
     * @param code the Base62 string to decode
     * @return the decoded non-negative long
     * @throws IllegalArgumentException if {@code code} contains a non-Base62 character
     */
    public static long decode(String code) {
        long value = 0;
        for (int i = 0; i < code.length(); i++) {
            int digit = ALPHABET.indexOf(code.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("illegal base62 character: " + code.charAt(i));
            }
            value = value * BASE + digit;
        }
        return value;
    }
}
