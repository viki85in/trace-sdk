package org.util.trace.core.buffer;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Bounded multi-producer, single-consumer ring (Vyukov's bounded queue). Producers never block or
 * take a lock; when the ring is full {@link #offer} returns {@code false} and the caller drops the
 * record (NFR-5).
 */
public final class SpanBuffer<E> {

    private final Object[] slots;
    private final AtomicLongArray sequences;
    private final int mask;
    private final AtomicLong tail = new AtomicLong();
    /** Consumer-only. */
    private long head;

    public SpanBuffer(int minCapacity) {
        int capacity = Integer.highestOneBit(Math.max(2, minCapacity) - 1) << 1;
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity too large: " + minCapacity);
        }
        this.slots = new Object[capacity];
        this.sequences = new AtomicLongArray(capacity);
        for (int i = 0; i < capacity; i++) {
            sequences.set(i, i);
        }
        this.mask = capacity - 1;
    }

    public int capacity() {
        return slots.length;
    }

    /** Any thread. Returns {@code false} if full. */
    public boolean offer(E element) {
        long pos = tail.get();
        while (true) {
            int index = (int) pos & mask;
            long diff = sequences.get(index) - pos;
            if (diff == 0) {
                if (tail.compareAndSet(pos, pos + 1)) {
                    slots[index] = element;
                    sequences.lazySet(index, pos + 1); // publishes the slot write
                    return true;
                }
                pos = tail.get();
            } else if (diff < 0) {
                return false;
            } else {
                pos = tail.get();
            }
        }
    }

    /** Consumer thread only. Returns {@code null} if empty. */
    @SuppressWarnings("unchecked")
    public E poll() {
        int index = (int) head & mask;
        if (sequences.get(index) != head + 1) {
            return null;
        }
        E element = (E) slots[index];
        slots[index] = null;
        sequences.lazySet(index, head + mask + 1);
        head++;
        return element;
    }

    /** Approximate. */
    public int size() {
        long size = tail.get() - head;
        return (int) Math.max(0, Math.min(size, slots.length));
    }
}
