package datadog.trace.api;

public class RemoteSettingsValues {
  private boolean TraceEnabled = true;
  private boolean StatsdEnabled = true;

  public boolean isTraceEnabled() {
    return TraceEnabled;
  }

  public void setTraceEnabled(boolean traceEnabled) {
    TraceEnabled = traceEnabled;
  }

  public boolean isStatsdEnabled() {
    return StatsdEnabled;
  }

  public void setStatsdEnabled(boolean statsdEnabled) {
    StatsdEnabled = statsdEnabled;
  }
}
