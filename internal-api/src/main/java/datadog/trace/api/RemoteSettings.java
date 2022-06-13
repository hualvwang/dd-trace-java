package datadog.trace.api;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


public class RemoteSettings {

  private static final Logger log = LoggerFactory.getLogger(RemoteSettings.class);

  public static final String CONSUL_URL = "consul.url";
  public static final String CONSUL_UPDATE_INTERVAL = "consul.update.interval";
  public static final String CONSUL_RESOURCE_PATH = "consul.resource.path";
  private final RemoteSettingsValues remoteSettingsValues = new RemoteSettingsValues();

  public boolean isTraceEnabled() {
    return remoteSettingsValues.isTraceEnabled();
  }

  public boolean isStatsdEnabled() {
    return remoteSettingsValues.isStatsdEnabled();
  }

  private static ObjectMapper mapper = new ObjectMapper();
  private static final RemoteSettings instance = new RemoteSettings();

  public static RemoteSettings getInstance() {
    return instance;
  }

  private String consulUrl;

  private OkHttpClient httpClient;

  private RemoteSettings() {
    ConfigProvider configProvider = ConfigProvider.createDefault();
    String consulBaseUrl = configProvider.getString(CONSUL_URL);
    String consulResourcePath = configProvider.getString(CONSUL_RESOURCE_PATH);
    Integer consulUpateInterval = configProvider.getInteger(CONSUL_UPDATE_INTERVAL);
    log.info("Consul base url: {}", consulBaseUrl);
    if (consulBaseUrl.isEmpty()) {
      return;
    }
    httpClient = new OkHttpClient.Builder().build();
    String serviceName = Config.get().getServiceName();
    consulUrl = consulBaseUrl + consulResourcePath + serviceName;
    mapper.configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true);

    initSettings();
    Executors.newScheduledThreadPool(1).scheduleAtFixedRate(
        new Runnable() {
          @Override
          public void run() {
            updateSettings();
          }
        },
        1,
        consulUpateInterval,
        TimeUnit.SECONDS);
  }

  private void initSettings() {
    try {
      final Request request = new Request.Builder()
          .url(consulUrl)
          .method("GET", null)
          .build();
      boolean exist = true;
      try (final Response response = httpClient.newCall(request).execute()) {
        if (response.code() >= 400) {
          exist = false;
        }
      } catch (IOException e) {
        exist = false;
      }
      if (!exist) {
        StringWriter writer = new StringWriter();
        mapper.writeValue(writer, new RemoteSettingsValues());
        final Request request2 = new Request.Builder()
            .url(consulUrl)
            .method("PUT", RequestBody.create(MediaType.parse("application/json"), writer.toString()))
            .build();
        try (final Response response2 = httpClient.newCall(request2).execute()) {
          if (response2.code() >= 400) {
            log.warn("Unable to write settings to consul, {0}", response2.body().string());
          } else {
            log.debug("Settings written to consul");
          }
        }
      }
    } catch (Exception e) {
      log.warn("Unable to write settings to consul", e);
    }

  }

  private void updateSettings() {
    try {
      final Request request = new Request.Builder()
          .url(consulUrl)
          .method("GET", null)
          .build();
      try (final Response response = httpClient.newCall(request).execute()) {
        String body = response.body().string();
        if (response.code() < 400) {
          StringWriter writer = new StringWriter();
          mapper.writeValue(writer, this);
          RemoteSettingsConsulResponse[] consulResponses = mapper.readValue(body, RemoteSettingsConsulResponse[].class);
          if (consulResponses.length > 0) {
            String json = new String(Base64.getDecoder().decode(consulResponses[0].getValue()));
            RemoteSettingsValues newValues = mapper.readValue(json, RemoteSettingsValues.class);
            this.remoteSettingsValues.setTraceEnabled(newValues.isTraceEnabled());
            this.remoteSettingsValues.setStatsdEnabled(newValues.isStatsdEnabled());
          }
        } else {
          log.warn("Unable to read settings from consul", body);
        }
      }
    } catch (Exception e) {
      log.warn("Unable to read settings from consul", e);
    }
  }

}
