package com.datadog.debugger.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.MetricProbe;
import com.datadog.debugger.probe.SnapshotProbe;
import datadog.remoteconfig.ConfigurationChangesListener;
import datadog.remoteconfig.state.ParsedConfigKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import org.junit.Assert;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class DebuggerProductChangesListenerTest {

  final ConfigurationChangesListener.PollingHinterNoop pollingHinter =
      new ConfigurationChangesListener.PollingHinterNoop();

  class SimpleAcceptor implements DebuggerProductChangesListener.ConfigurationAcceptor {
    private Configuration configuration;

    @Override
    public void accept(Configuration configuration) {
      this.configuration = configuration;
    }

    public Configuration getConfiguration() {
      return configuration;
    }
  }

  @Test
  public void testNoConfiguration() {
    Configuration emptyConfig = Configuration.builder().build();
    SimpleAcceptor acceptor = new SimpleAcceptor();

    DebuggerProductChangesListener listener = new DebuggerProductChangesListener(acceptor);
    listener.commit(pollingHinter);

    Assert.assertEquals(emptyConfig, acceptor.getConfiguration());
  }

  @Test
  public void testSingleConfiguration() {
    Configuration config =
        Configuration.builder()
            .add(createSnapshotProbe(UUID.randomUUID().toString()))
            .addDenyList(createFilteredList())
            .build();
    SimpleAcceptor acceptor = new SimpleAcceptor();

    DebuggerProductChangesListener listener = new DebuggerProductChangesListener(acceptor);

    acceptConfig(listener, config, UUID.randomUUID().toString());
    listener.commit(pollingHinter);

    Assert.assertEquals(config, acceptor.getConfiguration());
  }

  @Test
  public void testMultipleSingleProbesConfigurations() {
    SimpleAcceptor acceptor = new SimpleAcceptor();

    DebuggerProductChangesListener listener = new DebuggerProductChangesListener(acceptor);

    SnapshotProbe snapshotProbe = createSnapshotProbe(UUID.randomUUID().toString());
    MetricProbe metricProbe = createMetricProbe(UUID.randomUUID().toString());
    LogProbe logProbe = createLogProbe(UUID.randomUUID().toString());

    acceptSnapshotProbe(listener, snapshotProbe);
    listener.commit(pollingHinter);
    Assert.assertEquals(
        Configuration.builder().add(snapshotProbe).build(), acceptor.getConfiguration());

    acceptMetricProbe(listener, metricProbe);
    listener.commit(pollingHinter);
    Assert.assertEquals(
        Configuration.builder().add(snapshotProbe).add(metricProbe).build(),
        acceptor.getConfiguration());

    acceptLogProbe(listener, logProbe);
    listener.commit(pollingHinter);
    Assert.assertEquals(
        Configuration.builder().add(snapshotProbe).add(metricProbe).add(logProbe).build(),
        acceptor.getConfiguration());

    removeSnapshotProbe(listener, snapshotProbe);
    listener.commit(pollingHinter);
    Assert.assertEquals(
        Configuration.builder().add(metricProbe).add(logProbe).build(),
        acceptor.getConfiguration());

    removeMetricProbe(listener, metricProbe);
    listener.commit(pollingHinter);
    Assert.assertEquals(Configuration.builder().add(logProbe).build(), acceptor.getConfiguration());

    removeLogProbe(listener, logProbe);
    listener.commit(pollingHinter);
    Assert.assertEquals(Configuration.builder().build(), acceptor.getConfiguration());
  }

  @Test
  public void testMergeConfigWithSingleProbe() {
    SimpleAcceptor acceptor = new SimpleAcceptor();

    DebuggerProductChangesListener listener = new DebuggerProductChangesListener(acceptor);

    SnapshotProbe snapshotProbe = createSnapshotProbe("123");
    MetricProbe metricProbe = createMetricProbe("345");
    LogProbe logProbe = createLogProbe("567");

    Configuration config =
        Configuration.builder()
            .add(metricProbe)
            .add(logProbe)
            .add(new SnapshotProbe.Sampling(3.0))
            .addDenyList(createFilteredList())
            .build();

    acceptSnapshotProbe(listener, snapshotProbe);
    acceptConfig(listener, config, UUID.randomUUID().toString());
    listener.commit(pollingHinter);
    Assert.assertEquals(
        Configuration.builder().add(config).add(snapshotProbe).build(),
        acceptor.getConfiguration());
  }

  @Test
  public void badConfigIDFailsToAccept() {
    SimpleAcceptor acceptor = new SimpleAcceptor();

    DebuggerProductChangesListener listener = new DebuggerProductChangesListener(acceptor);

    Assertions.assertThrows(
        IOException.class,
        () -> listener.accept(createConfigKey("bad-config-id"), null, pollingHinter));
  }

  byte[] toContent(Configuration configuration) {
    return DebuggerProductChangesListener.Adapter.CONFIGURATION_JSON_ADAPTER
        .toJson(configuration)
        .getBytes(StandardCharsets.UTF_8);
  }

  byte[] toContent(SnapshotProbe probe) {
    return DebuggerProductChangesListener.Adapter.SNAPSHOT_PROBE_JSON_ADAPTER
        .toJson(probe)
        .getBytes(StandardCharsets.UTF_8);
  }

  byte[] toContent(MetricProbe probe) {
    return DebuggerProductChangesListener.Adapter.METRIC_PROBE_JSON_ADAPTER
        .toJson(probe)
        .getBytes(StandardCharsets.UTF_8);
  }

  byte[] toContent(LogProbe probe) {
    return DebuggerProductChangesListener.Adapter.LOG_PROBE_JSON_ADAPTER
        .toJson(probe)
        .getBytes(StandardCharsets.UTF_8);
  }

  void acceptConfig(
      DebuggerProductChangesListener listener, Configuration config, String configId) {
    assertDoesNotThrow(
        () -> listener.accept(createConfigKey(configId), toContent(config), pollingHinter));
  }

  void acceptSnapshotProbe(DebuggerProductChangesListener listener, SnapshotProbe probe) {
    assertDoesNotThrow(
        () ->
            listener.accept(
                createConfigKey("snapshotProbe_" + probe.getId()),
                toContent(probe),
                pollingHinter));
  }

  void removeSnapshotProbe(DebuggerProductChangesListener listener, SnapshotProbe probe) {
    assertDoesNotThrow(
        () -> listener.remove(createConfigKey("snapshotProbe_" + probe.getId()), pollingHinter));
  }

  void acceptMetricProbe(DebuggerProductChangesListener listener, MetricProbe probe) {
    assertDoesNotThrow(
        () ->
            listener.accept(
                createConfigKey("metricProbe_" + probe.getId()), toContent(probe), pollingHinter));
  }

  void removeMetricProbe(DebuggerProductChangesListener listener, MetricProbe probe) {
    assertDoesNotThrow(
        () -> listener.remove(createConfigKey("metricProbe_" + probe.getId()), pollingHinter));
  }

  void acceptLogProbe(DebuggerProductChangesListener listener, LogProbe probe) {
    assertDoesNotThrow(
        () ->
            listener.accept(
                createConfigKey("logProbe_" + probe.getId()), toContent(probe), pollingHinter));
  }

  void removeLogProbe(DebuggerProductChangesListener listener, LogProbe probe) {
    assertDoesNotThrow(
        () -> listener.remove(createConfigKey("logProbe_" + probe.getId()), pollingHinter));
  }

  SnapshotProbe createSnapshotProbe(String id) {
    return SnapshotProbe.builder()
        .probeId(id)
        .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
        .build();
  }

  MetricProbe createMetricProbe(String id) {
    return MetricProbe.builder()
        .metricId(id)
        .kind(MetricProbe.MetricKind.COUNT)
        .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
        .build();
  }

  LogProbe createLogProbe(String id) {
    return LogProbe.builder()
        .logId(id)
        .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
        .template("hello {^world}")
        .build();
  }

  Configuration.FilterList createFilteredList() {
    return new Configuration.FilterList(
        Collections.singletonList("datadog"), Collections.singletonList("class1"));
  }

  ParsedConfigKey createConfigKey(String configId) {
    return ParsedConfigKey.parse("datadog/2/LIVE_DEBUGGING/" + configId + "/config");
  }
}
