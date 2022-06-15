package datadog.trace.api;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
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

  private RemoteSettings() {
    ConfigProvider configProvider = ConfigProvider.createDefault();
    String consulBaseUrl = configProvider.getString(CONSUL_URL);
    String consulResourcePath = configProvider.getString(CONSUL_RESOURCE_PATH);
    if (consulResourcePath == null){
      consulResourcePath = "/v1/kv/datadog/";
    }
    Integer consulUpateInterval = configProvider.getInteger(CONSUL_UPDATE_INTERVAL);
    if (consulUpateInterval == null) {
      consulUpateInterval = 60;
    }
    log.info("Consul base url: {}", consulBaseUrl);
    if (consulBaseUrl == null || consulBaseUrl.isEmpty()) {
      return;
    }
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

      boolean exist = true;
      try {
        URL url = new URL(consulUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        int responseCode = connection.getResponseCode();
        if (responseCode >= 400) {
          exist = false;
        }
      } catch (IOException e) {
        exist = false;
      }

      if (!exist) {
        StringWriter writer = new StringWriter();
        mapper.writeValue(writer, new RemoteSettingsValues());

        try {
          URL url = new URL(consulUrl);
          HttpURLConnection connection = (HttpURLConnection) url.openConnection();
          connection.setRequestMethod("PUT");
          connection.setRequestProperty("Content-Type", "application/json");
          connection.setDoOutput(true);
          OutputStream outputStream = connection.getOutputStream();
          outputStream.write(writer.toString().getBytes());
          outputStream.flush();
          outputStream.close();
          int responseCode = connection.getResponseCode();
          if (responseCode >= 400) {
            BufferedReader in = new BufferedReader(new InputStreamReader(
                connection.getInputStream()));
            String inputLine;
            StringBuffer response = new StringBuffer();
            while ((inputLine = in.readLine()) != null) {
              response.append(inputLine);
            }
            in.close();
            log.warn("Unable to write settings to consul, {0}", response);
          } else {
            log.debug("Settings written to consul");
          }
        } catch (Exception e) {
          log.warn("Unable to write settings to consul", e);
        }
      }
    } catch (Exception e) {
      log.warn("Unable to write settings to consul", e);
    }

  }

  private void updateSettings() {
    try {
      URL url = new URL(consulUrl);
      HttpURLConnection connection = (HttpURLConnection) url.openConnection();
      connection.setRequestMethod("GET");
      int responseCode = connection.getResponseCode();
      BufferedReader in = new BufferedReader(new InputStreamReader(
          connection.getInputStream()));
      String inputLine;
      StringBuffer response = new StringBuffer();
      while ((inputLine = in.readLine()) != null) {
        response.append(inputLine);
      }
      in.close();
      if (responseCode < 400) {
        StringWriter writer = new StringWriter();
        mapper.writeValue(writer, this);
        RemoteSettingsConsulResponse[] consulResponses = mapper.readValue(response.toString(), RemoteSettingsConsulResponse[].class);
        if (consulResponses.length > 0) {
          String json = new String(Base64.getDecoder().decode(consulResponses[0].getValue()));
          RemoteSettingsValues newValues = mapper.readValue(json, RemoteSettingsValues.class);
          this.remoteSettingsValues.setTraceEnabled(newValues.isTraceEnabled());
          this.remoteSettingsValues.setStatsdEnabled(newValues.isStatsdEnabled());
        }
      } else {
        log.warn("Unable to read settings from consul", response);
      }

    } catch (Exception e) {
      log.warn("Unable to read settings from consul", e);
    }
  }

}
