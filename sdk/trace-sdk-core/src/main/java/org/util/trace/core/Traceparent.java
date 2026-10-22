package org.util.trace.core;

/** A parsed W3C {@code traceparent} header: {@code 00-<32 hex trace id>-<16 hex parent id>-<2 hex flags>}. */
final class Traceparent {

    final long traceIdHigh;
    final long traceIdLow;
    final long parentSpanId;

    private Traceparent(long traceIdHigh, long traceIdLow, long parentSpanId) {
        this.traceIdHigh = traceIdHigh;
        this.traceIdLow = traceIdLow;
        this.parentSpanId = parentSpanId;
    }

    /** Returns {@code null} for anything that isn't a valid header; an invalid header starts a new trace. */
    static Traceparent parse(Object value) {
        if (!(value instanceof String)) {
            return null;
        }
        String s = ((String) value).trim();
        // Version 00 is exactly 55 chars; later versions may append fields after the flags.
        if (s.length() < 55 || s.charAt(2) != '-' || s.charAt(35) != '-' || s.charAt(52) != '-'
                || (s.length() > 55 && s.charAt(55) != '-')) {
            return null;
        }
        try {
            int version = Integer.parseInt(s.substring(0, 2), 16);
            if (version == 0xff || (version == 0 && s.length() != 55)) {
                return null;
            }
            Integer.parseInt(s.substring(53, 55), 16);
            long high = Ids.parseHex16(s, 3);
            long low = Ids.parseHex16(s, 19);
            long parent = Ids.parseHex16(s, 36);
            if ((high == 0 && low == 0) || parent == 0) {
                return null;
            }
            return new Traceparent(high, low, parent);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
