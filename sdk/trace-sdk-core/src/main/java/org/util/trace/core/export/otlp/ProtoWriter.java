package org.util.trace.core.export.otlp;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Minimal protobuf writer. Nested messages are written into a reusable child writer and copied
 * into the parent with an exact length prefix: {@code ProtoWriter c = w.begin(); ...; w.end(field, c);}.
 */
final class ProtoWriter {

    private static final int VARINT = 0;
    private static final int FIXED64 = 1;
    private static final int LEN = 2;

    private byte[] buf;
    private int pos;
    private ProtoWriter child;

    ProtoWriter(int initialCapacity) {
        this.buf = new byte[initialCapacity];
    }

    void reset() {
        pos = 0;
    }

    int size() {
        return pos;
    }

    byte[] toByteArray() {
        return Arrays.copyOf(buf, pos);
    }

    /** Starts a nested message. Only one child may be open at a time. */
    ProtoWriter begin() {
        if (child == null) {
            child = new ProtoWriter(256);
        }
        child.reset();
        return child;
    }

    void end(int field, ProtoWriter nested) {
        tag(field, LEN);
        varint(nested.pos);
        write(nested.buf, 0, nested.pos);
    }

    /** Appends already-encoded fields. */
    void rawBytes(byte[] encoded) {
        write(encoded, 0, encoded.length);
    }

    void string(int field, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        tag(field, LEN);
        varint(bytes.length);
        write(bytes, 0, bytes.length);
    }

    void uint64(int field, long value) {
        tag(field, VARINT);
        varint(value);
    }

    void bool(int field, boolean value) {
        tag(field, VARINT);
        varint(value ? 1 : 0);
    }

    void fixed64(int field, long value) {
        tag(field, FIXED64);
        fixed64(value);
    }

    void doubleValue(int field, double value) {
        fixed64(field, Double.doubleToRawLongBits(value));
    }

    /** {@code bytes} field holding big-endian longs, e.g. a 16-byte trace id. */
    void idBytes(int field, long... parts) {
        tag(field, LEN);
        varint(parts.length * 8L);
        for (long part : parts) {
            ensure(8);
            for (int shift = 56; shift >= 0; shift -= 8) {
                buf[pos++] = (byte) (part >>> shift);
            }
        }
    }

    private void tag(int field, int wireType) {
        varint(((long) field << 3) | wireType);
    }

    private void varint(long value) {
        ensure(10);
        while ((value & ~0x7FL) != 0) {
            buf[pos++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf[pos++] = (byte) value;
    }

    private void fixed64(long value) {
        ensure(8);
        for (int shift = 0; shift < 64; shift += 8) {
            buf[pos++] = (byte) (value >>> shift);
        }
    }

    private void write(byte[] src, int offset, int length) {
        ensure(length);
        System.arraycopy(src, offset, buf, pos, length);
        pos += length;
    }

    private void ensure(int extra) {
        if (pos + extra > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, pos + extra));
        }
    }
}
