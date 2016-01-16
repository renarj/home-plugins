package com.oberasoftware.home.youless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.Uninterruptibles;
import com.oberasoftware.home.api.AutomationBus;
import com.oberasoftware.home.api.exceptions.HomeAutomationException;
import com.oberasoftware.home.api.impl.events.devices.DeviceValueEventImpl;
import com.oberasoftware.home.api.impl.types.ValueImpl;
import com.oberasoftware.home.api.types.VALUE_TYPE;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * @author renarj
 */
@Component
public class YoulessConnector implements Runnable {
    private static final Logger LOG = getLogger(YoulessConnector.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Value("${youless.ip:}")
    private String youlessIp;

    @Value("${youless.checkinterval:30000}")
    private long interval;

    @Autowired
    private AutomationBus automationBus;


    public void connect() {
        LOG.debug("Scheduling retrieval of wattage from Youless");

        if(!StringUtils.isEmpty(youlessIp)) {
            Thread thread = new Thread(this);
            thread.setDaemon(true);
            thread.start();
        } else {
            LOG.warn("Youless connector is present but not configured, ignoring");
        }
    }

    public String getYoulessIp() {
        return youlessIp;
    }

    @Override
    public void run() {
        while(!Thread.currentThread().isInterrupted()) {
            try {
                LOG.debug("Retrieving wattage usage from youless: {}", youlessIp);
                retrieveUsage();
            } catch (HomeAutomationException e) {
                LOG.error("", e);
            }

            Uninterruptibles.sleepUninterruptibly(interval, TimeUnit.MILLISECONDS);
        }
    }

    private void retrieveUsage() throws HomeAutomationException {
        String url = String.format("http://%s/a?f=j", youlessIp);
        LOG.debug("Connecting to youless at URL: {}", url);

        try {
            HttpURLConnection urlConnection = (HttpURLConnection) new URL(url).openConnection();
            urlConnection.setRequestMethod("GET");
            urlConnection.connect();

            if(urlConnection.getResponseCode() < 400) {
                String response = getResponseAsString(urlConnection.getInputStream());
                long wattage = getWattage(response);
                long kwh = getKWH(response);
                LOG.debug("Retrieved wattage from youless: {}", wattage);

                automationBus.publish(new DeviceValueEventImpl(automationBus.getControllerId(), "youless", youlessIp, new ValueImpl(VALUE_TYPE.NUMBER, wattage), "power"));
                automationBus.publish(new DeviceValueEventImpl(automationBus.getControllerId(), "youless", youlessIp, new ValueImpl(VALUE_TYPE.NUMBER, kwh), "energy"));
            } else {
                String errorResponse = getResponseAsString(urlConnection.getErrorStream());
                LOG.error("Error: {} retrieving data from youless: {}", urlConnection.getResponseCode(), errorResponse);
            }

        } catch (IOException e) {
            LOG.error("", e);
        }

    }

    private Long getKWH(String response) throws HomeAutomationException {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(response);
            String kwhText = root.findValue("cnt").textValue();
            int seperatorIndex = kwhText.indexOf(",");
            if(seperatorIndex > 0) {
                kwhText = kwhText.substring(0, seperatorIndex);
            }

            return Long.parseLong(kwhText);
        } catch (IOException e) {
            throw new HomeAutomationException("Unable to read wattage", e);
        }
    }

    private Long getWattage(String response) throws HomeAutomationException {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(response);
            return root.findValue("pwr").asLong();
        } catch (IOException e) {
            throw new HomeAutomationException("Unable to read wattage", e);
        }
    }

    public String getResponseAsString(InputStream inputStream) throws HomeAutomationException {
        try {
            BufferedReader read = new BufferedReader(new InputStreamReader(inputStream));
            String r = read.readLine();
            read.close();

            return r;
        } catch(IOException e) {
            throw new HomeAutomationException("Unable to load monitoring data from youless device", e);
        }
    }
}
