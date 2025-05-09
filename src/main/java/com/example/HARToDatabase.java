package com.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import okhttp3.RequestBody;
import org.json.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.bind.annotation.*;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;

@SpringBootApplication
@EnableScheduling
@RestController
@RequestMapping("/api")
public class HARToDatabase implements ApplicationListener<ContextRefreshedEvent> {

    // Injected properties
    @Value("${spring.datasource.url}")
    private String jdbcUrl;
    
    @Value("${spring.datasource.username}")
    private String dbUsername;
    
    @Value("${spring.datasource.password}")
    private String dbPassword;
    
    @Value("${site24x7.client.id}")
    private String clientId;
    
    @Value("${site24x7.client.secret}")
    private String clientSecret;
    
    @Value("${site24x7.refresh.token.file}")
    private String refreshTokenFile;

    private final OkHttpClient HTTP_CLIENT = new OkHttpClient();
    private static final String HAR_FILE_PATH = "example.har";
    private String accessToken = null;
    private String refreshToken = null;
    private long tokenExpirationTime = 0;
    private static final String[] REQUIRED_SCOPES = {
        "Site24x7.Admin.Create",
        "Site24x7.Admin.Delete",
        "Site24x7.Admin.Update",
        "Site24x7.Admin.Read",
        "Site24x7.Reports.Read",
        "Site24x7.Reports.Update",
        "Site24x7.Reports.Delete"
    };
    
    private static final String MONITOR_CREATION_URL = "https://www.site24x7.in/api/monitors";
    private static final String LOCATION_PROFILE_NAME = "API_PRIMARY_LOCATION";
    private static final String NOTIFICATION_PROFILE_NAME = "API_DEFAULT_NOTIFY";
    private static final String THRESHOLD_PROFILE_NAME = "DEFAULT_THRESHOLD";
    
    private Map<String, String> profileIds = new HashMap<>();
    private ThreadPoolTaskScheduler taskScheduler;
    private final Object tokenLock = new Object();

    public static void main(String[] args) {
        SpringApplication.run(HARToDatabase.class, args);
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        try {
            System.out.println("Spring Boot application started...");
            this.refreshToken = loadRefreshToken();
            
            if (this.refreshToken == null) {
                System.err.println("Refresh token not found in the file. Please ensure the file '" + refreshTokenFile + "' exists and contains a valid refresh token.");
                return;
            }

            System.out.println("Using existing refresh token from: " + refreshTokenFile);
            System.out.println("Refreshing access token...");
            refreshAccessToken();

            System.out.println("Fetching required profile IDs...");
            fetchProfileIds();

            System.out.println("Processing HAR file for alarms data...");
            System.out.println("Using monitor creation URL: " + MONITOR_CREATION_URL);
            boolean success = createWebsiteMonitor(
                "Test Monitor - " + System.currentTimeMillis(),
                "https://www.nationalgeographic.com", 
                5
            );
            System.out.println("TEST MONITOR CREATION: " + (success ? "SUCCESS" : "FAILED"));

            initializeScheduler();
        } catch (Exception e) {
            System.err.println("Initialization error:");
            e.printStackTrace();
        }
    }

    private void initializeScheduler() {
        taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(1);
        taskScheduler.initialize();
        taskScheduler.scheduleAtFixedRate(this::scheduledTask, Duration.ofMinutes(5));
    }

    private void scheduledTask() {
        try {
            System.out.println("Scheduled task running...");
            if (System.currentTimeMillis() - tokenExpirationTime < 30000) {
                System.out.println("Skipping refresh - recently initialized");
            }

            if (isAccessTokenExpired()) {
                System.out.println("Access token expired. Refreshing...");
                refreshAccessToken();
            } else {
                System.out.println("Access token is still valid.");
            }
            
            System.out.println("Updating website metrics...");
            updateWebsiteMetrics(accessToken);
            System.out.println("Fetching and storing alarms...");
            fetchAndStoreLiveAlarms(accessToken);
        } catch (Exception e) {
            System.err.println("Error in scheduled task:");
            e.printStackTrace();
        }
    }

    @GetMapping("/test")
    public String testEndpoint() {
        System.out.println("Test endpoint hit!");
        return "Server is working";
    }

    @PostMapping("/create-monitor")
    public Map<String, Object> createMonitor(@org.springframework.web.bind.annotation.RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            boolean success = createWebsiteMonitor(
                request.get("display_name").toString(),
                request.get("website").toString(),
                Integer.parseInt(request.get("check_frequency").toString())
            );

            response.put("success", success);
            response.put("message", success ? "Monitor created successfully" : "Failed to create monitor");
            if (success) {
                response.put("monitorId", "mon_" + System.currentTimeMillis());
            }
            return response;
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Error: " + e.getMessage());
            return response;
        }
    }

    @GetMapping("/get-metrics")
    public List<Map<String, Object>> getMetrics() {
        try {
            return fetchFromDatabase("SELECT * FROM website_metrics LIMIT 50");
        } catch (Exception e) {
            throw new RuntimeException("Error fetching metrics: " + e.getMessage());
        }
    }

    @GetMapping("/get-alarms")
    public List<Map<String, Object>> getAlarms() {
        try {
            return fetchFromDatabase("SELECT * FROM monitor_alarms ORDER BY created_at DESC LIMIT 50");
        } catch (Exception e) {
            throw new RuntimeException("Error fetching alarms: " + e.getMessage());
        }
    }

    @PostMapping("/create-group")
    public Map<String, Object> createMonitorGroup(@org.springframework.web.bind.annotation.RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (!request.containsKey("display_name") || !request.containsKey("monitors")) {
                response.put("error", "Missing display_name or monitors");
                return response;
            }

            JSONObject payload = new JSONObject()
                .put("health_threshold_count", request.get("health_threshold_count"))
                .put("enable_incident_management", request.getOrDefault("enable_incident_management", false))
                .put("group_type", request.get("group_type"))
                .put("alert_periodically", request.get("alert_periodically"))
                .put("alert_frequency", request.get("alert_frequency"))
                .put("healing_period", request.get("healing_period"))
                .put("healthcheck_profile_id", "49084000000154001") 
                .put("monitors", request.get("monitors"))
                .put("notification_profile_id", profileIds.get("NOTIFICATION"))
                .put("selection_type", request.get("selection_type"))
                .put("suppress_alert", request.get("suppress_alert"))
                .put("tags", new JSONArray())
                .put("user_group_ids", new JSONArray().put("49084000000002009")) 
                .put("display_name", request.get("display_name"));

            okhttp3.RequestBody body = okhttp3.RequestBody.create(
                payload.toString(),
                okhttp3.MediaType.get("application/json")
            );
            
            Request apiRequest = new Request.Builder()
                .url("https://www.site24x7.in/api/monitor_groups")
                .header("Accept", "application/json; version=2.1")
                .header("Authorization", "Zoho-oauthtoken " + accessToken)
                .post(body)
                .build();

            try (Response apiResponse = HTTP_CLIENT.newCall(apiRequest).execute()) {
                String responseBody = apiResponse.body().string();
                JSONObject apiResponseJson = new JSONObject(responseBody);

                if (!apiResponseJson.has("code")) {
                    response.put("success", false);
                    response.put("error", "Site24x7 API Error: " + responseBody);
                    return response;
                }

                if (apiResponseJson.getInt("code") != 0) {
                    response.put("success", false);
                    response.put("error", apiResponseJson.getString("message"));
                    return response;
                }

                JSONObject data = apiResponseJson.getJSONObject("data");
                response.put("success", true);
                response.put("group_id", data.getString("group_id"));
                response.put("message", "Monitor group created successfully");
                return response;
            }
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", "Server error: " + e.getMessage());
            return response;
        }
    }

    @GetMapping("/get-monitor-details")
    public Map<String, Object> getMonitorDetails(@RequestParam String monitorId) {
        Map<String, Object> response = new HashMap<>();
        try {
            String apiUrl = String.format(
                "https://www.site24x7.in/app/api/monitors/details_page/base/%speriod-1",
                monitorId
            );

            Request request = new Request.Builder()
                .url(apiUrl)
                .header("Authorization", "Zoho-oauthtoken " + accessToken)
                .build();

            try (Response apiResponse = HTTP_CLIENT.newCall(request).execute()) {
                String responseBody = apiResponse.body().string();
                JSONObject harData = new JSONObject(responseBody);
                
                response.put("monitor_id", harData.getString("monitor_id"));
                response.put("display_name", harData.getString("display_name"));
                response.put("status", harData.getJSONObject("availability").getString("current_status"));
                return response;
            }
        } catch (Exception e) {
            response.put("error", "Failed to get monitor details: " + e.getMessage());
            return response;
        }
    }

    @GetMapping("/get-monitors")
    public List<Map<String, Object>> getMonitors() {
        try {
            return fetchFromDatabase("SELECT monitor_id, monitor_name FROM website_metrics");
        } catch (Exception e) {
            throw new RuntimeException("Error fetching monitors: " + e.getMessage());
        }
    }

    private List<Map<String, Object>> fetchFromDatabase(String query) throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();
        try (java.sql.Connection conn = DriverManager.getConnection(jdbcUrl, dbUsername, dbPassword);
             PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {
            
            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();
            
            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.put(metaData.getColumnName(i), rs.getObject(i));
                }
                result.add(row);
            }
        }
        return result;
    }

    private void fetchAndStoreLiveAlarms(String accessToken) throws IOException, JSONException {
        String alarmUrl = "https://www.site24x7.in/api/current_status"
            + "?group_required=false"
            + "&apm_required=true"
            + "&suspended_required=true"
            + "&locations_required=false"
            + "&exclude_kube_workload=true";

        Request request = new Request.Builder()
            .url(alarmUrl)
            .header("Authorization", "Zoho-oauthtoken " + accessToken)
            .header("Accept", "application/json")
            .build();

        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            String responseBody = response.body().string();
            JSONObject json = new JSONObject(responseBody);

            JSONObject data = json.optJSONObject("data");
            if (data != null && data.has("monitors")) {
                JSONArray monitors = data.getJSONArray("monitors");
                System.out.println("Fetched " + monitors.length() + " alarms entries.");
                storeAlarmsData(monitors);
            } else {
                System.err.println("API Response Missing 'data.monitors' Array:");
                System.err.println(responseBody);
            }
        }
    }

    private void storeAlarmsData(JSONArray monitors) {
        String sql = "INSERT INTO monitor_alarms ("
            + "outage_id, monitor_id, name, monitor_type, status, "
            + "down_reason, duration, downtime_millis, last_polled_time, "
            + "technician, is_monitor_muted, user_id) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE "
            + "status=VALUES(status), down_reason=VALUES(down_reason), "
            + "duration=VALUES(duration), downtime_millis=VALUES(downtime_millis), "
            + "last_polled_time=VALUES(last_polled_time), technician=VALUES(technician), "
            + "is_monitor_muted=VALUES(is_monitor_muted)";

        try (java.sql.Connection conn = DriverManager.getConnection(jdbcUrl, dbUsername, dbPassword);
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            int batchCount = 0;
            for (int i = 0; i < monitors.length(); i++) {
                JSONObject alarm = monitors.getJSONObject(i);

                String outageId = alarm.optString("outage_id", null);
                if (outageId == null) {
                    continue;
                }

                stmt.setString(1, outageId);
                stmt.setString(2, alarm.optString("monitor_id", null));
                stmt.setString(3, alarm.optString("name", null));
                stmt.setString(4, alarm.optString("monitor_type", null));
                stmt.setInt(5, alarm.optInt("status", 0));
                stmt.setString(6, alarm.optString("down_reason", null));
                stmt.setString(7, alarm.optString("duration", null));

                Object dm = alarm.opt("downtime_millis");
                if (dm instanceof String) {
                    stmt.setLong(8, Long.parseLong((String) dm));
                } else if (dm instanceof Number) {
                    stmt.setLong(8, ((Number) dm).longValue());
                } else {
                    stmt.setNull(8, Types.BIGINT);
                }

                String ts = alarm.optString("last_polled_time", null);
                stmt.setString(9, convertToMysqlDateTime(ts));

                stmt.setString(10, alarm.optString("technician", null));
                stmt.setBoolean(11, alarm.optBoolean("is_monitor_muted", false));
                stmt.setString(12, alarm.optString("user_id", null));

                stmt.addBatch();
                batchCount++;
            }

            if (batchCount > 0) {
                int[] counts = stmt.executeBatch();
                System.out.println("Upserted " + counts.length + " alarm rows.");
            } else {
                System.out.println("No outage entries to upsert.");
            }
        } catch (Exception e) {
            System.err.println("Error storing alarms:");
            e.printStackTrace();
        }
    }

    private String convertToMysqlDateTime(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) {
            return null;
        }
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");
            String normalizedTimestamp = timestamp.replaceAll("([+-]\\d{2})(\\d{2})$", "$1$2");
            LocalDateTime dateTime = LocalDateTime.parse(normalizedTimestamp, formatter);
            return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (DateTimeParseException e) {
            System.err.println("Error parsing timestamp: " + timestamp);
            e.printStackTrace();
            return null;
        }
    }
    
    private void fetchProfileIds() throws IOException {
        System.out.println("Fetching profile IDs...");
        
        String locationId = fetchProfileId(
            "https://www.site24x7.in/api/location_profiles",
            LOCATION_PROFILE_NAME
        );
        profileIds.put("LOCATION", locationId);
        
        String notificationId = fetchProfileId(
            "https://www.site24x7.in/api/notification_profiles",
            NOTIFICATION_PROFILE_NAME
        );
        profileIds.put("NOTIFICATION", notificationId);
        
        String thresholdId = fetchProfileId(
            "https://www.site24x7.in/api/threshold_profiles",
            THRESHOLD_PROFILE_NAME
        );
        profileIds.put("THRESHOLD", thresholdId);
        
        System.out.println("Profile IDs fetched successfully:");
        System.out.println("- Location: " + locationId);
        System.out.println("- Notification: " + notificationId);
        System.out.println("- Threshold: " + thresholdId);
    }

    private String fetchProfileId(String endpointUrl, String profileName) throws IOException {
        Request request = new Request.Builder()
            .url(endpointUrl)
            .addHeader("Authorization", "Zoho-oauthtoken " + accessToken)
            .addHeader("Accept", "application/json; version=2.1")
            .get()
            .build();

        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            String responseBody = response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("Failed to fetch profiles from " + endpointUrl + 
                                   ". Response: " + responseBody);
            }

            JSONObject json = new JSONObject(responseBody);
            JSONArray profiles = json.getJSONArray("data");
            
            for (int i = 0; i < profiles.length(); i++) {
                JSONObject profile = profiles.getJSONObject(i);
                if (profileName.equalsIgnoreCase(profile.optString("profile_name"))) {
                    return profile.getString("profile_id");
                }
            }
            throw new IOException("Profile '" + profileName + "' not found at: " + endpointUrl);
        }
    }

    public boolean createWebsiteMonitor(String displayName, String websiteUrl, int checkFrequency) {
        try {
            JSONObject monitorObj = new JSONObject();
            
            monitorObj.put("display_name", displayName);
            monitorObj.put("type", "URL");
            monitorObj.put("website", websiteUrl);
            monitorObj.put("check_frequency", String.valueOf(checkFrequency));
            monitorObj.put("timeout", 15);
            monitorObj.put("http_protocol", "H1.1");
            monitorObj.put("use_name_server", false);
            monitorObj.put("match_case", false);
            monitorObj.put("ignore_cert_err", true);
            monitorObj.put("follow_redirect", true);
            monitorObj.put("ssl_protocol", "Auto");
            monitorObj.put("http_method", "G");
    
            monitorObj.put("location_profile_id", profileIds.get("LOCATION"));
            monitorObj.put("notification_profile_id", profileIds.get("NOTIFICATION"));
            monitorObj.put("threshold_profile_id", profileIds.get("THRESHOLD"));
    
            List<String> userGroupIds = new ArrayList<>();
            userGroupIds.add("49084000000002009");
            monitorObj.put("user_group_ids", userGroupIds);
    
            okhttp3.RequestBody body = okhttp3.RequestBody.create(
                monitorObj.toString(), 
                okhttp3.MediaType.parse("application/json; charset=utf-8")
            );
    
            Request request = new Request.Builder()
                    .url(MONITOR_CREATION_URL)
                    .addHeader("Authorization", "Zoho-oauthtoken " + accessToken)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json; version=2.1")
                    .post(body)
                    .build();
    
            try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                String responseBody = response.body().string();
                System.out.println("API Response (" + response.code() + "):\n" + responseBody);
    
                if (!response.isSuccessful()) {
                    System.err.println("Monitor creation failed. Response: " + responseBody);
                    return false;
                }
    
                JSONObject responseJson = new JSONObject(responseBody);
                if (responseJson.getInt("code") != 0) {
                    System.err.println("API Error: " + responseJson.getString("message"));
                    return false;
                }
    
                String monitorId = responseJson.getJSONObject("data").getString("monitor_id");
                System.out.println("Successfully created monitor with ID: " + monitorId);
                
                return true;
            }
        } catch (Exception e) {
            System.err.println("Exception during monitor creation:");
            e.printStackTrace();
            return false;
        }
    }

    private String loadRefreshToken() {
        try {
            if (Files.exists(Paths.get(refreshTokenFile))) {
                String token = new String(Files.readAllBytes(Paths.get(refreshTokenFile)));
                System.out.println("Refresh token loaded from file: " + refreshTokenFile);
                return token;
            }
        } catch (IOException e) {
            System.err.println("Error loading refresh token from file:");
            e.printStackTrace();
        }
        return null;
    }

    private void updateWebsiteMetrics(String accessToken) {
        try (java.sql.Connection connection = DriverManager.getConnection(jdbcUrl, dbUsername, dbPassword)) {
            System.out.println("Connected to the database.");
    
            File harFile = new File(HAR_FILE_PATH);
            if (!harFile.exists()) {
                System.err.println("HAR file not found: " + HAR_FILE_PATH);
                return;
            }
            System.out.println("HAR file found: " + HAR_FILE_PATH);
    
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode root = objectMapper.readTree(harFile);
            JsonNode entries = root.path("log").path("entries");
    
            String apiUrl = null;
    
            for (JsonNode entry : entries) {
                String url = entry.path("request").path("url").asText();
                System.out.println("Checking URL: " + url);
                if (url.contains("current_status")) {
                    apiUrl = url;
                    System.out.println("Found current_status API URL: " + apiUrl);
                    break;
                }
            }
    
            if (apiUrl == null) {
                System.out.println("current_status API URL not found in the HAR file.");
                return;
            }
    
            String apiResponse = fetchFreshApiData(apiUrl, accessToken);
            if (apiResponse == null) {
                System.err.println("Failed to fetch fresh data from API.");
                return;
            }
    
            System.out.println("Extracted API URL: " + apiUrl);
            System.out.println("API Response: " + apiResponse.substring(0, Math.min(apiResponse.length(), 1000)));
    
            JsonNode responseData = objectMapper.readTree(apiResponse);
            JsonNode monitors = responseData.path("data").path("monitors");
    
            String upsertQuery = "INSERT INTO website_metrics (" +
            "monitor_id, monitor_name, monitor_status, monitor_type, " + 
            "attribute_value, unit, last_polled_time, downtime_millis, " +
            "duration, down_reason) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON DUPLICATE KEY UPDATE " +
            "monitor_name = VALUES(monitor_name), " +
            "monitor_status = VALUES(monitor_status), " +
            "monitor_type = VALUES(monitor_type), " +
            "attribute_value = VALUES(attribute_value), " +
            "unit = VALUES(unit), " +
            "last_polled_time = VALUES(last_polled_time), " +
            "downtime_millis = VALUES(downtime_millis), " +
            "duration = VALUES(duration), " +
            "down_reason = VALUES(down_reason)";

            try (PreparedStatement statement = connection.prepareStatement(upsertQuery)) {
                for (JsonNode monitor : monitors) {
                    String monitorId = monitor.path("monitor_id").asText();
                    String monitorName = monitor.path("name").asText();
                    int monitorStatus = monitor.path("status").asInt();
                    String monitorType = monitor.path("monitor_type").asText();
    
                    String attributeValue = monitor.path("attribute_value").asText();
                    String unit = monitor.path("unit").asText();
                    String lastPolledTime = monitor.path("last_polled_time").asText();
                    long downtimeMillis = monitor.path("downtime_millis").asLong();
                    String duration = monitor.path("duration").asText();
                    String downReason = monitor.path("down_reason").asText();
    
                    String mysqlDateTime = null;
                    if (lastPolledTime != null && !lastPolledTime.isEmpty()) {
                        try {
                            DateTimeFormatter inputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");
                            LocalDateTime dateTime = LocalDateTime.parse(lastPolledTime, inputFormatter);
                            mysqlDateTime = dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                        } catch (DateTimeParseException e) {
                            System.err.println("Error parsing last_polled_time: " + lastPolledTime);
                            e.printStackTrace();
                        }
                    }
    
                    if (attributeValue == null || attributeValue.isEmpty()) {
                        attributeValue = "N/A";
                    }
                    if (unit == null || unit.isEmpty()) {
                        unit = "N/A";
                    }
                    if (duration == null || duration.isEmpty()) {
                        duration = "N/A";
                    }
                    if (downReason == null || downReason.isEmpty()) {
                        downReason = "N/A";
                    }
                    if (downtimeMillis == 0) {
                        downtimeMillis = 0;
                    }
                    
                    statement.setString(1, monitorId);
                    statement.setString(2, monitorName);
                    statement.setInt(3, monitorStatus);
                    statement.setString(4, monitorType);
                    statement.setString(5, attributeValue);
                    statement.setString(6, unit);
                    statement.setString(7, mysqlDateTime);
                    statement.setLong(8, downtimeMillis);
                    statement.setString(9, duration);
                    statement.setString(10, downReason);
    
                    int rowsAffected = statement.executeUpdate();
                    if (rowsAffected > 0) {
                        System.out.println("Upserted monitor: " + monitorName);
                    } else {
                        System.out.println("No rows affected for monitor: " + monitorName);
                    }
                }
            } catch (SQLException e) {
                System.err.println("Error executing SQL statement:");
                e.printStackTrace();
            }
    
            System.out.println("Monitor data updated in website_metrics successfully!");
    
        } catch (Exception e) {
            System.err.println("Error updating website metrics:");
            e.printStackTrace();
        }
    }

    private String fetchFreshApiData(String apiUrl, String accessToken) {
        try {
            System.out.println("Fetching fresh data from API...");
            System.out.println("API URL: " + apiUrl);
            apiUrl = "https://www.site24x7.in/api/current_status?group_required=false&apm_required=true&suspended_required=true&locations_required=false&exclude_kube_workload=true";
            Request request = new Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Zoho-oauthtoken " + accessToken)
                    .get()
                    .build();
    
            try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    System.err.println("API request failed: " + response.body().string());
                    return null;
                }
                System.out.println("API request successful.");
                return response.body() != null ? response.body().string() : null;
            }
        } catch (IOException e) {
            System.err.println("Error making API request:");
            e.printStackTrace();
            return null;
        }
    }

    private void refreshAccessToken() throws IOException {
        synchronized(tokenLock) {
            System.out.println("Refreshing access token...");
            OkHttpClient client = new OkHttpClient();

            RequestBody body = new FormBody.Builder()
                    .add("client_id", clientId)
                    .add("client_secret", clientSecret)
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refreshToken)
                    .add("scope", String.join(" ", REQUIRED_SCOPES))
                    .build();

            Request request = new Request.Builder()
                    .url("https://accounts.zoho.in/oauth/v2/token")
                    .post(body)
                    .build();

            Response response = client.newCall(request).execute();

            if (!response.isSuccessful()) {
                System.err.println("Refresh token request failed: " + response.body().string());
                throw new IOException("Refresh token request failed: " + response);
            }

            String responseBody = response.body().string();
            System.out.println("Refresh token response: " + responseBody);

            JSONObject tokenResponse = new JSONObject(responseBody);

            if (!tokenResponse.has("access_token")) {
                throw new IOException("Access token not found in the response.");
            }

            accessToken = tokenResponse.getString("access_token");

            if (tokenResponse.has("refresh_token")) {
                refreshToken = tokenResponse.getString("refresh_token");
                System.out.println("New refresh token received: " + refreshToken);
            } else {
                System.out.println("No new refresh token received. Using the existing one.");
            }

            tokenExpirationTime = System.currentTimeMillis() + (tokenResponse.getInt("expires_in") * 1000);
            System.out.println("Access token refreshed successfully.");
            System.out.println("New token expiration time: " + tokenExpirationTime);
        }
    }

    private boolean isAccessTokenExpired() {
        boolean expired = System.currentTimeMillis() > tokenExpirationTime;
        System.out.println("Access token expired: " + expired);
        return expired;
    }
}