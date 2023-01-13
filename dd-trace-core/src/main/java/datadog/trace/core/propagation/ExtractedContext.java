package datadog.trace.core.propagation;

import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import java.util.Map;

/**
 * Propagated data resulting from calling tracer.extract with header data from an incoming request.
 */
public class ExtractedContext extends TagContext {
  private final DDTraceId traceId;
  private final long spanId;
  private final long endToEndStartTime;
  private final Map<String, String> baggage;
  private final DatadogTags datadogTags;
  private final Map<String, String> propagatedHeaders;

  public ExtractedContext(
      final DDTraceId traceId,
      final long spanId,
      final int samplingPriority,
      final String origin,
      final long endToEndStartTime,
      final Map<String, String> baggage,
      final Map<String, String> tags,
      final HttpHeaders httpHeaders,
      final DatadogTags datadogTags,
      final Map<String, String> propagatedHeaders) {
    super(origin, tags, httpHeaders, samplingPriority);
    this.traceId = traceId;
    this.spanId = spanId;
    this.endToEndStartTime = endToEndStartTime;
    this.baggage = baggage;
    this.datadogTags = datadogTags;
    this.propagatedHeaders = propagatedHeaders;
  }

  @Override
  public final Iterable<Map.Entry<String, String>> baggageItems() {
    return baggage.entrySet();
  }

  @Override
  public final Iterable<Map.Entry<String, String>> propagatedHeaders() {
    return propagatedHeaders.entrySet();
  }

  @Override
  public final DDTraceId getTraceId() {
    return traceId;
  }

  @Override
  public final long getSpanId() {
    return spanId;
  }

  public final long getEndToEndStartTime() {
    return endToEndStartTime;
  }

  public final Map<String, String> getBaggage() {
    return baggage;
  }

  public DatadogTags getDatadogTags() {
    return datadogTags;
  }

  public final Map<String, String> getPropagatedHeaders() {
    return propagatedHeaders;
  }
}
