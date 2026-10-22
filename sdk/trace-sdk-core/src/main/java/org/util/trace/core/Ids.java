package org.util.trace.core;

import java.util.concurrent.ThreadLocalRandom;

/** Random non-zero IDs and their W3C hex form. */
public final class Ids {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Ids() {
    }

    public static long randomNonZero() {
        long id;
        do {
            id = ThreadLocalRandom.current().nextLong();
        } while (id == 0L);
        return id;
    }

    public static void appendHex(StringBuilder out, long value) {
        for (int shift = 60; shift >= 0; shift -= 4) {
            out.append(HEX[(int) (value >>> shift) & 0xF]);
        }
    }

    public static String hex(long value) {
        StringBuilder sb = new StringBuilder(16);
        appendHex(sb, value);
        return sb.toString();
    }

    /** Parses exactly 16 lower- or upper-case hex characters at {@code from}; throws if invalid. */
    static long parseHex16(CharSequence s, int from) {
        long value = 0;
        for (int i = from; i < from + 16; i++) {
            int digit = Character.digit(s.charAt(i), 16);
            if (digit < 0) {
                throw new IllegalArgumentException("not hex");
            }
            value = (value << 4) | digit;
        }
        return value;
    }
}
