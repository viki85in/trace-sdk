package org.util.trace.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TraceparentTest {

    @Test
    void parsesAValidHeader() {
        Traceparent tp = Traceparent.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        assertThat(tp).isNotNull();
        assertThat(Ids.hex(tp.traceIdHigh)).isEqualTo("4bf92f3577b34da6");
        assertThat(Ids.hex(tp.traceIdLow)).isEqualTo("a3ce929d0e0e4736");
        assertThat(Ids.hex(tp.parentSpanId)).isEqualTo("00f067aa0ba902b7");
    }

    @Test
    void acceptsFutureVersionsWithExtraFields() {
        assertThat(Traceparent.parse("01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-extra")).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7",
            "00-00000000000000000000000000000000-00f067aa0ba902b7-01",
            "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01",
            "ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
            "00-4bf92f3577b34da6a3ce929d0e0e473z-00f067aa0ba902b7-01",
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-extra",
            "00_4bf92f3577b34da6a3ce929d0e0e4736_00f067aa0ba902b7_01"
    })
    void rejectsInvalidHeaders(String header) {
        assertThat(Traceparent.parse(header)).isNull();
    }

    @Test
    void rejectsNonStrings() {
        assertThat(Traceparent.parse(null)).isNull();
        assertThat(Traceparent.parse(42)).isNull();
    }
}
