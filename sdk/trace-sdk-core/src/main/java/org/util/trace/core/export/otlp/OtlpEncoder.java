package org.util.trace.core.export.otlp;

import java.util.List;
import org.util.trace.core.Span;
import org.util.trace.core.config.TraceConfig;

/** Encodes a batch of spans as one OTLP {@code ExportTraceServiceRequest}. Exporter thread only. */
public interface OtlpEncoder {

    String contentType();

    byte[] encode(List<Span> spans);

    static OtlpEncoder forConfig(TraceConfig config) {
        return config.protocol() == TraceConfig.Protocol.HTTP_JSON
                ? new OtlpJsonEncoder(config) : new OtlpProtoEncoder(config);
    }
}
