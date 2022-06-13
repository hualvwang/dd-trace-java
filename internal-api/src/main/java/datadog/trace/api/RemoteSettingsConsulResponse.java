package datadog.trace.api;

public class RemoteSettingsConsulResponse {
  private Integer createIndex;
  private Integer modifyIndex;
  private Integer lockIndex;
  private Integer flags;
  private String key;
  private String value;
  private String session;

  public Integer getCreateIndex() {
    return createIndex;
  }

  public void setCreateIndex(Integer createIndex) {
    this.createIndex = createIndex;
  }

  public Integer getModifyIndex() {
    return modifyIndex;
  }

  public void setModifyIndex(Integer modifyIndex) {
    this.modifyIndex = modifyIndex;
  }

  public Integer getLockIndex() {
    return lockIndex;
  }

  public void setLockIndex(Integer lockIndex) {
    this.lockIndex = lockIndex;
  }

  public Integer getFlags() {
    return flags;
  }

  public void setFlags(Integer flags) {
    this.flags = flags;
  }

  public String getKey() {
    return key;
  }

  public void setKey(String key) {
    this.key = key;
  }

  public String getValue() {
    return value;
  }

  public void setValue(String value) {
    this.value = value;
  }

  public String getSession() {
    return session;
  }

  public void setSession(String session) {
    this.session = session;
  }
}
