package org.util.trace.core.buffer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SpanBufferTest {

    @Test
    void capacityIsRoundedUpToAPowerOfTwo() {
        assertThat(new SpanBuffer<>(1).capacity()).isEqualTo(2);
        assertThat(new SpanBuffer<>(3).capacity()).isEqualTo(4);
        assertThat(new SpanBuffer<>(32768).capacity()).isEqualTo(32768);
        assertThat(new SpanBuffer<>(32769).capacity()).isEqualTo(65536);
    }

    @Test
    void fifoAndFull() {
        SpanBuffer<Integer> buffer = new SpanBuffer<>(4);
        for (int i = 0; i < 4; i++) {
            assertThat(buffer.offer(i)).isTrue();
        }
        assertThat(buffer.offer(99)).isFalse();
        assertThat(buffer.size()).isEqualTo(4);
        assertThat(buffer.poll()).isEqualTo(0);
        assertThat(buffer.offer(4)).isTrue();
        List<Integer> rest = new ArrayList<>();
        for (Integer v; (v = buffer.poll()) != null; ) {
            rest.add(v);
        }
        assertThat(rest).containsExactly(1, 2, 3, 4);
        assertThat(buffer.poll()).isNull();
    }

    @Test
    void concurrentProducersLoseNothingThatWasAccepted() throws Exception {
        SpanBuffer<Integer> buffer = new SpanBuffer<>(1024);
        int producers = 4;
        int perProducer = 50_000;
        AtomicInteger accepted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int p = 0; p < producers; p++) {
            int base = p * perProducer;
            Thread t = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < perProducer; i++) {
                    if (buffer.offer(base + i)) {
                        accepted.incrementAndGet();
                    }
                }
            });
            t.start();
            threads.add(t);
        }
        Set<Integer> seen = new HashSet<>();
        start.countDown();
        while (threads.stream().anyMatch(Thread::isAlive) || buffer.size() > 0) {
            Integer v = buffer.poll();
            if (v != null) {
                assertThat(seen.add(v)).as("duplicate %s", v).isTrue();
            }
        }
        for (Integer v; (v = buffer.poll()) != null; ) {
            assertThat(seen.add(v)).isTrue();
        }
        assertThat(seen).hasSize(accepted.get());
    }
}
