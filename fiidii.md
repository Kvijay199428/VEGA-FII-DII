```xml
// File: pom.xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.4</version>
        <relativePath/> <!-- lookup parent from repository -->
    </parent>
    <groupId>com.vega</groupId>
    <artifactId>vega.fiidii</artifactId>
    <version>0.0.1</version>
    <name>vega.fiidii</name>
    <description>Vega FII/DII Architecture</description>
    <properties>
        <java.version>17</java.version>
    </properties>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.datatype</groupId>
            <artifactId>jackson-datatype-jsr310</artifactId>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
        <finalName>${project.artifactId}.${project.version}</finalName>
    </build>
</project>
```

```java
// File: src/main/java/com/vega/fiidii/VegaFiiDiiApplication.java
package com.vega.fiidii;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VegaFiiDiiApplication {
    public static void main(String[] args) {
        SpringApplication.run(VegaFiiDiiApplication.class, args);
    }
}
```

```java
// File: src/main/java/com/vega/fiidii/client/UpstoxCredentialManager.java
package com.vega.fiidii.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;

@Service
public class UpstoxCredentialManager {
    private static final Logger logger = LoggerFactory.getLogger(UpstoxCredentialManager.class);
    private static final String AUTH_FILE_PATH = "auth/upstox/auth.upstox.json";
    
    private String accessToken;
    private final ObjectMapper objectMapper;

    public UpstoxCredentialManager(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() throws IOException {
        logger.info("Loading Upstox token...");
        java.nio.file.Path authPath = com.vega.fiidii.util.PathResolver.resolve(AUTH_FILE_PATH);
        
        if (!java.nio.file.Files.exists(authPath)) {
            throw new IllegalStateException("Missing auth file: " + authPath);
        }
        
        File authFile = authPath.toFile();
        JsonNode root = objectMapper.readTree(authFile);
        JsonNode analyticAccount = root.path("accounts").path("analytic");
        if (!analyticAccount.isMissingNode() && analyticAccount.has("accessToken")) {
            this.accessToken = analyticAccount.get("accessToken").asText();
            logger.info("Using account identifier: analytic");
        } else {
            throw new IllegalStateException("Analytic account or access token missing in auth.upstox.json");
        }
    }

    public String getAccessToken() {
        return accessToken;
    }
}
```

```java
// File: src/main/java/com/vega/fiidii/client/UpstoxFiiDiiClient.java
package com.vega.fiidii.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vega.fiidii.config.FiiDiiConfigService;
import com.vega.fiidii.model.InstitutionalFlowRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class UpstoxFiiDiiClient {
    private static final Logger logger = LoggerFactory.getLogger(UpstoxFiiDiiClient.class);
    private static final String BASE_URL = "https://api.upstox.com";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final UpstoxCredentialManager credentialManager;
    private final FiiDiiConfigService configService;

    public UpstoxFiiDiiClient(HttpClient httpClient, ObjectMapper objectMapper,
                              UpstoxCredentialManager credentialManager,
                              FiiDiiConfigService configService) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.credentialManager = credentialManager;
        this.configService = configService;
    }

    public List<InstitutionalFlowRecord> fetchFii() {
        return fetchFiiDiiData(
                configService.getConfig().getFii().getEndpoint(),
                configService.getFiiDataTypes(),
                "FII",
                configService.getDefaultInterval(), null
        );
    }

    public List<InstitutionalFlowRecord> fetchDii() {
        return fetchFiiDiiData(
                configService.getConfig().getDii().getEndpoint(),
                configService.getDiiDataTypes(),
                "DII",
                configService.getDefaultInterval(), null
        );
    }

    public List<InstitutionalFlowRecord> fetchLatestFii() {
        return fetchFiiDiiData(
                configService.getConfig().getFii().getEndpoint(),
                configService.getFiiDataTypes(),
                "FII",
                "1D", null
        );
    }

    public List<InstitutionalFlowRecord> fetchLatestDii() {
        return fetchFiiDiiData(
                configService.getConfig().getDii().getEndpoint(),
                configService.getDiiDataTypes(),
                "DII",
                "1D", null
        );
    }

    public List<InstitutionalFlowRecord> fetchAdHoc(String category, String dataType, String interval, String fromDate) {
        String endpoint = "FII".equalsIgnoreCase(category) ? 
                configService.getConfig().getFii().getEndpoint() : 
                configService.getConfig().getDii().getEndpoint();
        
        List<String> dataTypes = (dataType != null && !dataType.isEmpty()) ? 
                List.of(dataType) : 
                ("FII".equalsIgnoreCase(category) ? configService.getFiiDataTypes() : configService.getDiiDataTypes());
                
        return fetchFiiDiiData(endpoint, dataTypes, category.toUpperCase(), interval, fromDate);
    }

    public List<InstitutionalFlowRecord> fetchFiiHistorical(String fromDate) {
        return fetchFiiDiiData(
                configService.getConfig().getFii().getEndpoint(),
                configService.getFiiDataTypes(),
                "FII",
                configService.getDefaultInterval(), fromDate
        );
    }

    public List<InstitutionalFlowRecord> fetchDiiHistorical(String fromDate) {
        return fetchFiiDiiData(
                configService.getConfig().getDii().getEndpoint(),
                configService.getDiiDataTypes(),
                "DII",
                configService.getDefaultInterval(), fromDate
        );
    }

    private List<InstitutionalFlowRecord> fetchFiiDiiData(String endpoint, List<String> dataTypes, String category, String interval, String fromDate) {
        String queryString = dataTypes.stream()
                .map(dt -> "data_type=" + URLEncoder.encode(dt, StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
                
        if (interval != null && !interval.isEmpty()) {
            queryString += "&interval=" + URLEncoder.encode(interval, StandardCharsets.UTF_8);
        }
        if (fromDate != null && !fromDate.isEmpty()) {
            queryString += "&from=" + URLEncoder.encode(fromDate, StandardCharsets.UTF_8);
        }
                
        String url = BASE_URL + endpoint + "?" + queryString;
        logger.info("Upstox Request URL: {}", url);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + credentialManager.getAccessToken())
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        return executeWithRetry(request, category);
    }

    private List<InstitutionalFlowRecord> executeWithRetry(HttpRequest request, String category) {
        int maxRetries = 3;
        for (int i = 1; i <= maxRetries; i++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int statusCode = response.statusCode();
                
                if (statusCode == 200) {
                    return parseResponse(response.body(), category);
                }

                logger.warn("Attempt {}/{}: Failed to fetch {}. Status code: {}", i, maxRetries, category, statusCode);
                logger.error("Response Body: {}", response.body());

                switch (statusCode) {
                    case 401:
                        throw new IllegalStateException("Token expired");
                    case 403:
                        throw new IllegalStateException("Access denied");
                    case 429:
                        if (i < maxRetries) {
                            long waitSeconds = response.headers()
                                    .firstValue("Retry-After")
                                    .map(val -> {
                                        try {
                                            return Long.parseLong(val);
                                        } catch (NumberFormatException e) {
                                            return 60L;
                                        }
                                    })
                                    .orElse(60L);
                            logger.warn("Rate limited (429)! Waiting {} seconds before retry...", waitSeconds);
                            Thread.sleep(waitSeconds * 1000);
                            continue;
                        }
                        break;
                    case 500:
                    case 502:
                    case 503:
                        if (i < maxRetries) {
                            logger.warn("Server error ({})! Retrying in 2s...", statusCode);
                            Thread.sleep(2000);
                            continue;
                        }
                        break;
                    default:
                        if (i < maxRetries) {
                            Thread.sleep(1000);
                            continue;
                        }
                        break;
                }
            } catch (IllegalStateException e) {
                logger.error("Fatal error fetching {}: {}", category, e.getMessage());
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Attempt {}: Interrupted while fetching {}: {}", i, category, e.getMessage());
                break;
            } catch (Exception e) {
                logger.warn("Attempt {}/{}: Exception while fetching {}: {}", i, maxRetries, category, e.getMessage());
                if (i < maxRetries) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (i == maxRetries) {
                logger.error("Failed to fetch {} after {} attempts", category, maxRetries);
            }
        }
        return new ArrayList<>();
    }

    private List<InstitutionalFlowRecord> parseResponse(String responseBody, String category) throws Exception {
        JsonNode rootNode = objectMapper.readTree(responseBody);
        JsonNode dataNode = rootNode.path("data");
        List<InstitutionalFlowRecord> records = new ArrayList<>();
        
        if (dataNode.isObject()) {
            Iterator<String> fields = dataNode.fieldNames();
            while (fields.hasNext()) {
                String dataType = fields.next();
                JsonNode recordsNode = dataNode.get(dataType);
                
                if (recordsNode.isArray()) {
                    for (JsonNode node : recordsNode) {
                        InstitutionalFlowRecord record = new InstitutionalFlowRecord();
                        record.setProvider("UPSTOX");
                        record.setCategory(category);
                        record.setDataType(dataType);
                        
                        record.setTimeStamp(node.path("time_stamp").asLong(0));
                        record.setBuyAmount(node.path("buy_amount").asDouble(0.0));
                        record.setSellAmount(node.path("sell_amount").asDouble(0.0));
                        record.setBuyContracts(node.path("buy_contracts").asLong(0));
                        record.setSellContracts(node.path("sell_contracts").asLong(0));
                        record.setOiContracts(node.path("oi_contracts").asLong(0));
                        record.setOiAmount(node.path("oi_amount").asDouble(0.0));
                        record.setTotalLongContracts(node.path("total_long_contracts").asLong(0));
                        record.setTotalShortContracts(node.path("total_short_contracts").asLong(0));
                        record.setTotalCallLongContracts(node.path("total_call_long_contracts").asLong(0));
                        record.setTotalPutLongContracts(node.path("total_put_long_contracts").asLong(0));
                        record.setTotalCallShortContracts(node.path("total_call_short_contracts").asLong(0));
                        record.setTotalPutShortContracts(node.path("total_put_short_contracts").asLong(0));
                        
                        record.setSourceHash(com.vega.fiidii.util.HashUtil.generateSourceHash(record));
                        
                        records.add(record);
                    }
                }
            }
        } else {
            logger.warn("Upstox response 'data' field is not an object. Found: {}", dataNode.getNodeType());
        }
        logger.info("Parsed {} records from {}", records.size(), category);
        return records;
    }
}
```

```java
// File: src/main/java/com/vega/fiidii/config/FiiDiiConfig.java
package com.vega.fiidii.config;

import java.util.List;

public class FiiDiiConfig {
    private String provider;
    private String defaultInterval;
    private String defaultStartDate;
    private int refreshIntervalMinutes;
    private int requestIntervalMs = 1000;
    private int maxDaysPerRequest = 30;
    private int retentionDays;
    private StorageConfig storage;
    private DataConfig fii;
    private DataConfig dii;

    public FiiDiiConfig() {
    }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getDefaultInterval() { return defaultInterval; }
    public void setDefaultInterval(String defaultInterval) { this.defaultInterval = defaultInterval; }

    public String getDefaultStartDate() { return defaultStartDate; }
    public void setDefaultStartDate(String defaultStartDate) { this.defaultStartDate = defaultStartDate; }

    public int getRefreshIntervalMinutes() { return refreshIntervalMinutes; }
    public void setRefreshIntervalMinutes(int refreshIntervalMinutes) { this.refreshIntervalMinutes = refreshIntervalMinutes; }

    public int getRequestIntervalMs() { return requestIntervalMs; }
    public void setRequestIntervalMs(int requestIntervalMs) { this.requestIntervalMs = requestIntervalMs; }

    public int getMaxDaysPerRequest() { return maxDaysPerRequest; }
    public void setMaxDaysPerRequest(int maxDaysPerRequest) { this.maxDaysPerRequest = maxDaysPerRequest; }

    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }

    public StorageConfig getStorage() { return storage; }
    public void setStorage(StorageConfig storage) { this.storage = storage; }

    public DataConfig getFii() { return fii; }
    public void setFii(DataConfig fii) { this.fii = fii; }

    public DataConfig getDii() { return dii; }
    public void setDii(DataConfig dii) { this.dii = dii; }

    public static class StorageConfig {
        private String archive;
        private String metadata;

        public StorageConfig() {}

        public String getArchive() { return archive; }
        public void setArchive(String archive) { this.archive = archive; }

        public String getMetadata() { return metadata; }
        public void setMetadata(String metadata) { this.metadata = metadata; }
    }

    public static class DataConfig {
        private String endpoint;
        private List<String> dataTypes;

        public DataConfig() {}

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

        public List<String> getDataTypes() { return dataTypes; }
        public void setDataTypes(List<String> dataTypes) { this.dataTypes = dataTypes; }
    }
}
```

```java
// File: src/main/java/com/vega/fiidii/config/FiiDiiConfigService.java
package com.vega.fiidii.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;

@Service
public class FiiDiiConfigService {
    private static final Logger logger = LoggerFactory.getLogger(FiiDiiConfigService.class);
    private static final String CONFIG_PATH = "data/config/fiidii/FIIDII.json";

    private FiiDiiConfig config;
    private final ObjectMapper objectMapper;

    public FiiDiiConfigService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() throws IOException {
        logger.info("Loading FIIDII configuration...");
        java.nio.file.Path configPath = com.vega.fiidii.util.PathResolver.resolve(CONFIG_PATH);
        File configFile = configPath.toFile();
        if (configFile.exists()) {
            config = objectMapper.readValue(configFile, FiiDiiConfig.class);
            logger.info("Loaded {} FII data types.", config.getFii().getDataTypes().size());
        } else {
            throw new IllegalStateException("FIIDII.json not found at " + configPath);
        }
    }

    public FiiDiiConfig getConfig() {
        return config;
    }

    public String getProvider() { return config.getProvider(); }
    public String getDefaultInterval() { return config.getDefaultInterval(); }
    public LocalDate getDefaultStartDate() { return LocalDate.parse(config.getDefaultStartDate()); }
    public List<String> getFiiDataTypes() { return config.getFii().getDataTypes(); }
    public List<String> getDiiDataTypes() { return config.getDii().getDataTypes(); }
    public int getRefreshIntervalMinutes() { return config.getRefreshIntervalMinutes(); }
    public int getRequestIntervalMs() { return config.getRequestIntervalMs(); }
    public int getMaxDaysPerRequest() { return config.getMaxDaysPerRequest(); }
    public Path getArchivePath() { return com.vega.fiidii.util.PathResolver.resolve(config.getStorage().getArchive()); }
    public Path getMetadataPath() { return com.vega.fiidii.util.PathResolver.resolve(config.getStorage().getMetadata()); }
}
```

```java
// File: src/main/java/com/vega/fiidii/config/HttpClientConfig.java
package com.vega.fiidii.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class HttpClientConfig {

    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }
}
```

```java
// File: src/main/java/com/vega/fiidii/controller/FiiDiiController.java
package com.vega.fiidii.controller;

import com.vega.fiidii.client.UpstoxFiiDiiClient;
import com.vega.fiidii.model.InstitutionalFlowRecord;
import com.vega.fiidii.service.FiiDiiArchiveService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/api/fiidii")
public class FiiDiiController {

    private final FiiDiiArchiveService archiveService;
    private final UpstoxFiiDiiClient upstoxClient;
    private final com.vega.fiidii.service.FiiDiiBootstrapService bootstrapService;

    public FiiDiiController(FiiDiiArchiveService archiveService, UpstoxFiiDiiClient upstoxClient, com.vega.fiidii.service.FiiDiiBootstrapService bootstrapService) {
        this.archiveService = archiveService;
        this.upstoxClient = upstoxClient;
        this.bootstrapService = bootstrapService;
    }

    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("archiveRecords", archiveService.getAllRecords().size());
        
        LocalDate latestFii = archiveService.getLatestFiiDate();
        LocalDate latestDii = archiveService.getLatestDiiDate();
        
        status.put("latestFiiDate", latestFii != null ? latestFii.toString() : null);
        status.put("latestDiiDate", latestDii != null ? latestDii.toString() : null);
        status.put("bootstrapState", bootstrapService.getBootstrapState().name());
        
        return status;
    }

    @GetMapping
    public List<InstitutionalFlowRecord> getFiiDiiData(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String dataType,
            @RequestParam(required = false) String interval,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        
        if (category != null && !category.equalsIgnoreCase("FII") && !category.equalsIgnoreCase("DII")) {
            throw new IllegalArgumentException("Invalid category. Must be FII or DII.");
        }

        if (interval != null || from != null || to != null) {
            // Ad-hoc user query -> fetch directly without storing
            return upstoxClient.fetchAdHoc(category, dataType, interval, from);
        } else {
            return archiveService.filterRecords(category, dataType);
        }
    }
}
```

```java
// File: src/main/java/com/vega/fiidii/model/ArchiveMetadata.java
package com.vega.fiidii.model;

public class ArchiveMetadata {
    private long totalRecords;
    private long fiiRecords;
    private long diiRecords;
    private long lastUpdated;
    private long latestTimestamp;
    private String latestFiiDate;
    private String latestDiiDate;
    private String providerLatestFiiDate;
    private String providerLatestDiiDate;

    public ArchiveMetadata() {
    }

    public long getTotalRecords() { return totalRecords; }
    public void setTotalRecords(long totalRecords) { this.totalRecords = totalRecords; }

    public long getFiiRecords() { return fiiRecords; }
    public void setFiiRecords(long fiiRecords) { this.fiiRecords = fiiRecords; }

    public long getDiiRecords() { return diiRecords; }
    public void setDiiRecords(long diiRecords) { this.diiRecords = diiRecords; }

    public long getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(long lastUpdated) { this.lastUpdated = lastUpdated; }

    public long getLatestTimestamp() { return latestTimestamp; }
    public void setLatestTimestamp(long latestTimestamp) { this.latestTimestamp = latestTimestamp; }

    public String getLatestFiiDate() { return latestFiiDate; }
    public void setLatestFiiDate(String latestFiiDate) { this.latestFiiDate = latestFiiDate; }

    public String getLatestDiiDate() { return latestDiiDate; }
    public void setLatestDiiDate(String latestDiiDate) { this.latestDiiDate = latestDiiDate; }

    public String getProviderLatestFiiDate() { return providerLatestFiiDate; }
    public void setProviderLatestFiiDate(String providerLatestFiiDate) { this.providerLatestFiiDate = providerLatestFiiDate; }

    public String getProviderLatestDiiDate() { return providerLatestDiiDate; }
    public void setProviderLatestDiiDate(String providerLatestDiiDate) { this.providerLatestDiiDate = providerLatestDiiDate; }
}
```

```java
// File: src/main/java/com/vega/fiidii/model/BootstrapState.java
package com.vega.fiidii.model;

public enum BootstrapState {
    NOT_STARTED,
    RUNNING,
    SUCCESS,
    FAILED,
    PARTIAL
}
```

```java
// File: src/main/java/com/vega/fiidii/model/InstitutionalFlowRecord.java
package com.vega.fiidii.model;

import java.util.Objects;

public class InstitutionalFlowRecord {

    private String provider;
    private String category;
    private String dataType;
    private long timeStamp;
    private double buyAmount;
    private double sellAmount;
    private long buyContracts;
    private long sellContracts;
    private long oiContracts;
    private double oiAmount;
    private long totalLongContracts;
    private long totalShortContracts;
    private long totalCallLongContracts;
    private long totalPutLongContracts;
    private long totalCallShortContracts;
    private long totalPutShortContracts;
    private String sourceHash;

    public InstitutionalFlowRecord() {
    }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }

    public long getTimeStamp() { return timeStamp; }
    public void setTimeStamp(long timeStamp) { this.timeStamp = timeStamp; }

    public double getBuyAmount() { return buyAmount; }
    public void setBuyAmount(double buyAmount) { this.buyAmount = buyAmount; }

    public double getSellAmount() { return sellAmount; }
    public void setSellAmount(double sellAmount) { this.sellAmount = sellAmount; }

    public long getBuyContracts() { return buyContracts; }
    public void setBuyContracts(long buyContracts) { this.buyContracts = buyContracts; }

    public long getSellContracts() { return sellContracts; }
    public void setSellContracts(long sellContracts) { this.sellContracts = sellContracts; }

    public long getOiContracts() { return oiContracts; }
    public void setOiContracts(long oiContracts) { this.oiContracts = oiContracts; }

    public double getOiAmount() { return oiAmount; }
    public void setOiAmount(double oiAmount) { this.oiAmount = oiAmount; }

    public long getTotalLongContracts() { return totalLongContracts; }
    public void setTotalLongContracts(long totalLongContracts) { this.totalLongContracts = totalLongContracts; }

    public long getTotalShortContracts() { return totalShortContracts; }
    public void setTotalShortContracts(long totalShortContracts) { this.totalShortContracts = totalShortContracts; }

    public long getTotalCallLongContracts() { return totalCallLongContracts; }
    public void setTotalCallLongContracts(long totalCallLongContracts) { this.totalCallLongContracts = totalCallLongContracts; }

    public long getTotalPutLongContracts() { return totalPutLongContracts; }
    public void setTotalPutLongContracts(long totalPutLongContracts) { this.totalPutLongContracts = totalPutLongContracts; }

    public long getTotalCallShortContracts() { return totalCallShortContracts; }
    public void setTotalCallShortContracts(long totalCallShortContracts) { this.totalCallShortContracts = totalCallShortContracts; }

    public long getTotalPutShortContracts() { return totalPutShortContracts; }
    public void setTotalPutShortContracts(long totalPutShortContracts) { this.totalPutShortContracts = totalPutShortContracts; }

    public String getSourceHash() { return sourceHash; }
    public void setSourceHash(String sourceHash) { this.sourceHash = sourceHash; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        InstitutionalFlowRecord that = (InstitutionalFlowRecord) o;
        return Objects.equals(sourceHash, that.sourceHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceHash);
    }
}
```

```java
// File: src/main/java/com/vega/fiidii/model/SyncDecision.java
package com.vega.fiidii.model;

import java.time.LocalDate;

public class SyncDecision {
    private SyncMode mode;
    private LocalDate fromDate;
    private LocalDate latestStoredDate;
    private LocalDate providerLatestDate;

    public SyncDecision(SyncMode mode, LocalDate fromDate, LocalDate latestStoredDate, LocalDate providerLatestDate) {
        this.mode = mode;
        this.fromDate = fromDate;
        this.latestStoredDate = latestStoredDate;
        this.providerLatestDate = providerLatestDate;
    }

    public SyncMode getMode() {
        return mode;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public LocalDate getLatestStoredDate() {
        return latestStoredDate;
    }

    public LocalDate getProviderLatestDate() {
        return providerLatestDate;
    }

    @Override
    public String toString() {
        return "SyncDecision{" +
                "mode=" + mode +
                ", fromDate=" + fromDate +
                ", latestStoredDate=" + latestStoredDate +
                ", providerLatestDate=" + providerLatestDate +
                '}';
    }
}
```

```java
// File: src/main/java/com/vega/fiidii/model/SyncMode.java
package com.vega.fiidii.model;

public enum SyncMode {
    INITIAL_LOAD,
    BACKFILL,
    LATEST_REFRESH,
    NO_SYNC_REQUIRED
}
```

```java
// File: src/main/java/com/vega/fiidii/scheduler/FiiDiiRefreshScheduler.java
package com.vega.fiidii.scheduler;

import com.vega.fiidii.service.FiiDiiArchiveService;
import com.vega.fiidii.service.FiiDiiBootstrapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FiiDiiRefreshScheduler {
    private static final Logger logger = LoggerFactory.getLogger(FiiDiiRefreshScheduler.class);

    private final FiiDiiArchiveService archiveService;
    private final FiiDiiBootstrapService bootstrapService;

    public FiiDiiRefreshScheduler(FiiDiiArchiveService archiveService, FiiDiiBootstrapService bootstrapService) {
        this.archiveService = archiveService;
        this.bootstrapService = bootstrapService;
    }

    @Scheduled(cron = "0 0 16 * * MON-FRI", zone = "Asia/Kolkata")
    public void startDailySync() {
        if (bootstrapService.getBootstrapState() != com.vega.fiidii.model.BootstrapState.SUCCESS) {
            logger.info("Bootstrap is not completed yet, skipping daily sync.");
            return;
        }

        logger.info("Starting daily market close FII and DII refresh...");
        bootstrapService.syncLatestSnapshot("FII");
        bootstrapService.syncLatestSnapshot("DII");
        logger.info("Completed daily FII and DII refresh.");
    }

    private boolean shouldRetry(java.time.LocalDate latestStored, java.time.LocalDate providerLatest, java.time.LocalDate today) {
        if (today.equals(latestStored)) {
            return false;
        }

        if (providerLatest != null && !providerLatest.isAfter(latestStored) && !providerLatest.equals(today)) {
            return false;
        }

        return true;
    }

    @Scheduled(cron = "0 0 17-23 * * MON-FRI", zone = "Asia/Kolkata")
    public void retryIfMissing() {
        if (bootstrapService.getBootstrapState() != com.vega.fiidii.model.BootstrapState.SUCCESS) {
            return;
        }

        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));

        if (shouldRetry(archiveService.getLatestFiiDate(), archiveService.getProviderLatestFiiDate(), today)) {
            logger.info("Hourly retry: Syncing FII activity...");
            bootstrapService.syncLatestSnapshot("FII");
        } else {
            logger.info("Hourly retry: Skipping FII activity, provider hasn't published newer data or already up to date.");
        }

        if (shouldRetry(archiveService.getLatestDiiDate(), archiveService.getProviderLatestDiiDate(), today)) {
            logger.info("Hourly retry: Syncing DII activity...");
            bootstrapService.syncLatestSnapshot("DII");
        } else {
            logger.info("Hourly retry: Skipping DII activity, provider hasn't published newer data or already up to date.");
        }
    }
}
```

```java
// File: src/main/java/com/vega/fiidii/service/FiiDiiArchiveService.java
package com.vega.fiidii.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vega.fiidii.config.FiiDiiConfigService;
import com.vega.fiidii.model.ArchiveMetadata;
import com.vega.fiidii.model.InstitutionalFlowRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class FiiDiiArchiveService {
    private static final Logger logger = LoggerFactory.getLogger(FiiDiiArchiveService.class);

    private final FiiDiiConfigService configService;
    private final ObjectMapper objectMapper;
    private final Set<String> existingHashes = ConcurrentHashMap.newKeySet();
    private final List<InstitutionalFlowRecord> inMemoryArchive = Collections.synchronizedList(new ArrayList<>());
    
    private Path archivePath;
    private Path metadataPath;

    private volatile LocalDate latestFiiDate;
    private volatile LocalDate latestDiiDate;
    
    private volatile LocalDate providerLatestFiiDate;
    private volatile LocalDate providerLatestDiiDate;

    public FiiDiiArchiveService(FiiDiiConfigService configService, ObjectMapper objectMapper) {
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        this.archivePath = configService.getArchivePath();
        this.metadataPath = configService.getMetadataPath();
        logger.info("Archive path = {}", archivePath.toAbsolutePath());
        logger.info("Metadata path = {}", metadataPath.toAbsolutePath());
        loadArchive();
        rebuildLatestDatesFromArchive();
        loadMetadata();
    }

    private void loadMetadata() {
        if (!Files.exists(metadataPath)) return;
        try {
            String content = Files.readString(metadataPath);
            ArchiveMetadata metadata = objectMapper.readValue(content, ArchiveMetadata.class);
            
            // Re-sync metadata with archive reality if they differ significantly
            if (latestFiiDate == null && metadata.getLatestFiiDate() != null) {
                latestFiiDate = LocalDate.parse(metadata.getLatestFiiDate());
            }
            if (latestDiiDate == null && metadata.getLatestDiiDate() != null) {
                latestDiiDate = LocalDate.parse(metadata.getLatestDiiDate());
            }
            if (metadata.getProviderLatestFiiDate() != null) {
                providerLatestFiiDate = LocalDate.parse(metadata.getProviderLatestFiiDate());
            }
            if (metadata.getProviderLatestDiiDate() != null) {
                providerLatestDiiDate = LocalDate.parse(metadata.getProviderLatestDiiDate());
            }
            
            logger.info("Loaded metadata: latestFiiDate={}, latestDiiDate={}, providerFii={}, providerDii={}", latestFiiDate, latestDiiDate, providerLatestFiiDate, providerLatestDiiDate);
        } catch (Exception e) {
            logger.warn("Failed to load metadata: {}", e.getMessage());
        }
    }

    private void loadArchive() {
        logger.info("Starting to load archive from: {}", archivePath.toAbsolutePath());
        if (!Files.exists(archivePath)) {
            logger.info("Archive file does not exist, creating new one.");
            try {
                if(archivePath.getParent() != null) {
                    Files.createDirectories(archivePath.getParent());
                }
                Files.createFile(archivePath);
            } catch (IOException e) {
                logger.error("Failed to create archive file", e);
            }
            return;
        }

        try (Stream<String> lines = Files.lines(archivePath)) {
            logger.info("Successfully opened archive file for reading.");
            lines.forEach(line -> {
                if(line.trim().isEmpty()) return;
                try {
                    InstitutionalFlowRecord record = objectMapper.readValue(line, InstitutionalFlowRecord.class);
                    existingHashes.add(record.getSourceHash());
                    inMemoryArchive.add(record);
                } catch (Exception e) {
                    logger.warn("Failed to parse line: {} - Error: {}", line, e.getMessage());
                }
            });
            logger.info("Archive loading completed. Loaded {} records.", inMemoryArchive.size());
        } catch (Throwable t) {
            logger.error("CRITICAL: Failed to load archive due to unexpected error", t);
        }
    }

    private synchronized void rebuildLatestDatesFromArchive() {
        logger.info("Rebuilding latest dates from {} in-memory records...", inMemoryArchive.size());
        latestFiiDate = inMemoryArchive.stream()
                .filter(r -> "FII".equalsIgnoreCase(r.getCategory()))
                .map(this::extractDate)
                .max(LocalDate::compareTo)
                .orElse(null);

        latestDiiDate = inMemoryArchive.stream()
                .filter(r -> "DII".equalsIgnoreCase(r.getCategory()))
                .map(this::extractDate)
                .max(LocalDate::compareTo)
                .orElse(null);

        logger.info("Rebuilt latest dates from archive: FII={}, DII={}", latestFiiDate, latestDiiDate);
    }

    private LocalDate extractDate(InstitutionalFlowRecord record) {
        return Instant.ofEpochMilli(record.getTimeStamp())
                .atZone(ZoneId.of("Asia/Kolkata"))
                .toLocalDate();
    }

    private synchronized void updateLatestDates(InstitutionalFlowRecord record) {
        LocalDate recordDate = extractDate(record);
                
        if ("FII".equalsIgnoreCase(record.getCategory())) {
            if (latestFiiDate == null || recordDate.isAfter(latestFiiDate)) {
                latestFiiDate = recordDate;
            }
        } else if ("DII".equalsIgnoreCase(record.getCategory())) {
            if (latestDiiDate == null || recordDate.isAfter(latestDiiDate)) {
                latestDiiDate = recordDate;
            }
        }
    }

    public LocalDate getLatestFiiDate() {
        return latestFiiDate;
    }

    public LocalDate getLatestDiiDate() {
        return latestDiiDate;
    }

    public LocalDate getProviderLatestFiiDate() {
        return providerLatestFiiDate;
    }

    public LocalDate getProviderLatestDiiDate() {
        return providerLatestDiiDate;
    }

    public synchronized void setProviderLatestDate(String category, LocalDate date) {
        if ("FII".equalsIgnoreCase(category)) {
            providerLatestFiiDate = date;
        } else if ("DII".equalsIgnoreCase(category)) {
            providerLatestDiiDate = date;
        }
        updateMetadata();
    }

    public synchronized void appendRecords(List<InstitutionalFlowRecord> records) {
        if (records == null || records.isEmpty()) return;
        
        logger.info("Processing batch of {} records...", records.size());

        // Update latest dates from the ENTIRE batch to ensure we don't lag, 
        // even if some records are already in our archive.
        updateLatestDatesFromBatch(records);

        List<InstitutionalFlowRecord> newRecords = records.stream()
                .filter(r -> !existingHashes.contains(r.getSourceHash()))
                .collect(Collectors.toList());

        if (newRecords.isEmpty()) {
            logger.info("No new records to append (all {} were duplicates).", records.size());
            updateMetadata(); // Still update metadata as dates might have changed
            return;
        }

        logger.info("Filtered to {} new records. Writing to {}...", newRecords.size(), archivePath.toAbsolutePath());

        try (BufferedWriter writer = Files.newBufferedWriter(archivePath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            int writtenCount = 0;
            for (InstitutionalFlowRecord record : newRecords) {
                writer.write(objectMapper.writeValueAsString(record));
                writer.newLine();
                existingHashes.add(record.getSourceHash());
                inMemoryArchive.add(record);
                writtenCount++;
            }
            writer.flush();
            logger.info("Successfully flushed {} records to disk.", writtenCount);
            logger.info("Archive size now={}, latestFiiDate={}, latestDiiDate={}", inMemoryArchive.size(), latestFiiDate, latestDiiDate);
            updateMetadata();
        } catch (Throwable t) {
            logger.error("CRITICAL: Failed to append records to archive", t);
        }
    }

    private void updateLatestDatesFromBatch(List<InstitutionalFlowRecord> records) {
        LocalDate maxFii = records.stream()
                .filter(r -> "FII".equalsIgnoreCase(r.getCategory()))
                .map(this::extractDate)
                .max(LocalDate::compareTo)
                .orElse(null);
        
        LocalDate maxDii = records.stream()
                .filter(r -> "DII".equalsIgnoreCase(r.getCategory()))
                .map(this::extractDate)
                .max(LocalDate::compareTo)
                .orElse(null);

        if (maxFii != null && (latestFiiDate == null || maxFii.isAfter(latestFiiDate))) {
            logger.info("Advancing latestFiiDate: {} -> {}", latestFiiDate, maxFii);
            latestFiiDate = maxFii;
        }
        if (maxDii != null && (latestDiiDate == null || maxDii.isAfter(latestDiiDate))) {
            logger.info("Advancing latestDiiDate: {} -> {}", latestDiiDate, maxDii);
            latestDiiDate = maxDii;
        }
    }

    private void updateMetadata() {
        ArchiveMetadata metadata = new ArchiveMetadata();
        metadata.setTotalRecords(inMemoryArchive.size());
        long fiiCount = inMemoryArchive.stream().filter(r -> "FII".equalsIgnoreCase(r.getCategory())).count();
        long diiCount = inMemoryArchive.size() - fiiCount;
        metadata.setFiiRecords(fiiCount);
        metadata.setDiiRecords(diiCount);
        metadata.setLastUpdated(System.currentTimeMillis());
        
        long latestTimestamp = inMemoryArchive.stream()
                .mapToLong(InstitutionalFlowRecord::getTimeStamp)
                .max()
                .orElse(0L);
        metadata.setLatestTimestamp(latestTimestamp);
        
        if (latestFiiDate != null) metadata.setLatestFiiDate(latestFiiDate.toString());
        if (latestDiiDate != null) metadata.setLatestDiiDate(latestDiiDate.toString());
        if (providerLatestFiiDate != null) metadata.setProviderLatestFiiDate(providerLatestFiiDate.toString());
        if (providerLatestDiiDate != null) metadata.setProviderLatestDiiDate(providerLatestDiiDate.toString());

        try {
            if(metadataPath.getParent() != null) {
                Files.createDirectories(metadataPath.getParent());
            }
            String content = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metadata);
            Path tempPath = metadataPath.resolveSibling(metadataPath.getFileName() + ".tmp");
            Files.writeString(tempPath, content);
            Files.move(tempPath, metadataPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger.error("Failed to write metadata atomically", e);
        }
    }

    public List<InstitutionalFlowRecord> getAllRecords() {
        return new ArrayList<>(inMemoryArchive);
    }

    public List<InstitutionalFlowRecord> getRecordsByCategory(String category) {
        return inMemoryArchive.stream()
                .filter(r -> category.equalsIgnoreCase(r.getCategory()))
                .collect(Collectors.toList());
    }

    public List<InstitutionalFlowRecord> getRecordsByDataType(String dataType) {
        return inMemoryArchive.stream()
                .filter(r -> dataType.equalsIgnoreCase(r.getDataType()))
                .collect(Collectors.toList());
    }

    public List<InstitutionalFlowRecord> filterRecords(String category, String dataType) {
        Stream<InstitutionalFlowRecord> stream = inMemoryArchive.stream();
        if (category != null && !category.isEmpty()) {
            stream = stream.filter(r -> category.equalsIgnoreCase(r.getCategory()));
        }
        if (dataType != null && !dataType.isEmpty()) {
            stream = stream.filter(r -> dataType.equalsIgnoreCase(r.getDataType()));
        }
        // sort by timestamp desc
        return stream
                .sorted((a, b) -> Long.compare(b.getTimeStamp(), a.getTimeStamp()))
                .collect(Collectors.toList());
    }
}
```

```java
// File: src/main/java/com/vega/fiidii/service/FiiDiiBootstrapService.java
package com.vega.fiidii.service;

import com.vega.fiidii.client.UpstoxFiiDiiClient;
import com.vega.fiidii.config.FiiDiiConfigService;
import com.vega.fiidii.model.InstitutionalFlowRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class FiiDiiBootstrapService {

    private static final Logger logger = LoggerFactory.getLogger(FiiDiiBootstrapService.class);
    private final FiiDiiArchiveService archiveService;
    private final UpstoxFiiDiiClient upstoxClient;
    private final FiiDiiConfigService configService;
    private final FiiDiiSyncDecisionService syncDecisionService;
    private volatile com.vega.fiidii.model.BootstrapState state = com.vega.fiidii.model.BootstrapState.NOT_STARTED;
    private final java.util.concurrent.locks.ReentrantLock fiiLock = new java.util.concurrent.locks.ReentrantLock();
    private final java.util.concurrent.locks.ReentrantLock diiLock = new java.util.concurrent.locks.ReentrantLock();

    private java.util.concurrent.locks.ReentrantLock getLock(String category) {
        return "FII".equalsIgnoreCase(category) ? fiiLock : diiLock;
    }

    public FiiDiiBootstrapService(FiiDiiArchiveService archiveService, UpstoxFiiDiiClient upstoxClient, FiiDiiConfigService configService, FiiDiiSyncDecisionService syncDecisionService) {
        this.archiveService = archiveService;
        this.upstoxClient = upstoxClient;
        this.configService = configService;
        this.syncDecisionService = syncDecisionService;
    }

    public com.vega.fiidii.model.BootstrapState getBootstrapState() {
        return state;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        logger.info("Starting FII/DII Data Bootstrap...");
        state = com.vega.fiidii.model.BootstrapState.RUNNING;
        try {
            syncData("FII", archiveService.getLatestFiiDate(), archiveService.getProviderLatestFiiDate());
            syncData("DII", archiveService.getLatestDiiDate(), archiveService.getProviderLatestDiiDate());
            state = com.vega.fiidii.model.BootstrapState.SUCCESS;
            logger.info("FII/DII Data Bootstrap completed successfully.");
        } catch (Exception e) {
            state = com.vega.fiidii.model.BootstrapState.FAILED;
            logger.error("Error during bootstrap sync", e);
        }
    }

    public void syncData(String category, LocalDate latestStoredDate, LocalDate providerLatestDate) {
        com.vega.fiidii.model.SyncDecision decision = syncDecisionService.determineSyncMode(category, latestStoredDate, providerLatestDate);
        switch(decision.getMode()) {
            case INITIAL_LOAD:
            case BACKFILL:
                syncHistoricalData(category, decision.getFromDate());
                break;
            case LATEST_REFRESH:
                syncLatestSnapshot(category);
                break;
            case NO_SYNC_REQUIRED:
                logger.info("[{}] Archive already synchronized with provider. Skipping bootstrap.", category);
                break;
        }
    }

    public void syncLatestSnapshot(String category) {
        java.util.concurrent.locks.ReentrantLock lock = getLock(category);
        if (!lock.tryLock()) {
            logger.warn("[{}] Sync already running, skipping concurrent execution.", category);
            return;
        }
        try {
            logger.info("[{}] Fetching latest snapshot...", category);
            List<InstitutionalFlowRecord> records;
            if ("FII".equalsIgnoreCase(category)) {
                records = upstoxClient.fetchLatestFii();
            } else {
                records = upstoxClient.fetchLatestDii();
            }

            LocalDate maxReturnedDate = records.stream()
                    .map(r -> java.time.Instant.ofEpochMilli(r.getTimeStamp())
                            .atZone(ZoneId.of("Asia/Kolkata"))
                            .toLocalDate())
                    .max(LocalDate::compareTo)
                    .orElse(null);

            if (maxReturnedDate != null) {
                logger.info("[{}] Fetched {} records from snapshot. Latest returned: {}. Appending...", category, records.size(), maxReturnedDate);
                archiveService.appendRecords(records);
                archiveService.setProviderLatestDate(category, maxReturnedDate);
            } else {
                logger.info("[{}] No records returned from snapshot.", category);
            }
        } finally {
            lock.unlock();
        }
    }

    public void syncHistoricalData(String category, LocalDate startDate) {
        java.util.concurrent.locks.ReentrantLock lock = getLock(category);
        if (!lock.tryLock()) {
            logger.warn("[{}] Sync already running, skipping concurrent execution.", category);
            return;
        }
        try {
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
            LocalDate currentDate = startDate;
            long intervalMs = configService.getRequestIntervalMs();

            while (!currentDate.isAfter(today)) {
                String fromStr = currentDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
                logger.info("[{}] Fetching historical data from {}", category, fromStr);

                List<InstitutionalFlowRecord> records;
                if ("FII".equalsIgnoreCase(category)) {
                    records = upstoxClient.fetchFiiHistorical(fromStr);
                } else {
                    records = upstoxClient.fetchDiiHistorical(fromStr);
                }

                LocalDate maxReturnedDate = records.stream()
                        .map(r -> java.time.Instant.ofEpochMilli(r.getTimeStamp())
                                .atZone(ZoneId.of("Asia/Kolkata"))
                                .toLocalDate())
                        .max(LocalDate::compareTo)
                        .orElse(null);

                if (maxReturnedDate != null) {
                    logger.info("[{}] Fetched {} historical records. Latest returned: {}. Appending...", category, records.size(), maxReturnedDate);
                    archiveService.appendRecords(records);
                    archiveService.setProviderLatestDate(category, maxReturnedDate);
                }

                if (maxReturnedDate == null || maxReturnedDate.isBefore(currentDate)) {
                    logger.warn("[{}] No newer data returned (latest: {}). Stopping historical sync for {}.", category, maxReturnedDate, category);
                    break;
                }

                currentDate = maxReturnedDate.plusDays(1);

                if (!currentDate.isAfter(today)) {
                    try {
                        Thread.sleep(intervalMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        logger.warn("Bootstrap sync interrupted");
                        break;
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }
}
```

```java
// File: src/main/java/com/vega/fiidii/service/FiiDiiSyncDecisionService.java
package com.vega.fiidii.service;

import com.vega.fiidii.config.FiiDiiConfigService;
import com.vega.fiidii.model.SyncDecision;
import com.vega.fiidii.model.SyncMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;

@Service
public class FiiDiiSyncDecisionService {
    private static final Logger logger = LoggerFactory.getLogger(FiiDiiSyncDecisionService.class);
    private final FiiDiiConfigService configService;

    public FiiDiiSyncDecisionService(FiiDiiConfigService configService) {
        this.configService = configService;
    }

    public SyncDecision determineSyncMode(String category, LocalDate latestStoredDate, LocalDate providerLatestDate) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        
        // Rule 1: Archive empty
        if (latestStoredDate == null) {
            LocalDate defaultStartDate = configService.getDefaultStartDate();
            logger.info("[{}] Archive empty. Decision: INITIAL_LOAD from {}", category, defaultStartDate);
            return new SyncDecision(SyncMode.INITIAL_LOAD, defaultStartDate, null, providerLatestDate);
        }

        // Rule 2: Archive exists, Provider date unknown
        if (providerLatestDate == null) {
            LocalDate nextDay = latestStoredDate.plusDays(1);
            if (nextDay.isAfter(today)) {
                logger.info("[{}] Provider date unknown, but archive is up to today ({}). Decision: LATEST_REFRESH", category, latestStoredDate);
                return new SyncDecision(SyncMode.LATEST_REFRESH, null, latestStoredDate, null);
            }
            logger.info("[{}] Provider date unknown. Decision: BACKFILL from {}", category, nextDay);
            return new SyncDecision(SyncMode.BACKFILL, nextDay, latestStoredDate, null);
        }

        // Rule 3: Provider newer than archive
        if (providerLatestDate.isAfter(latestStoredDate)) {
            LocalDate nextDay = latestStoredDate.plusDays(1);
            logger.info("[{}] Provider has newer data ({} > {}). Decision: BACKFILL from {}", category, providerLatestDate, latestStoredDate, nextDay);
            return new SyncDecision(SyncMode.BACKFILL, nextDay, latestStoredDate, providerLatestDate);
        }

        // Rule 4: Provider equals archive
        if (providerLatestDate.equals(latestStoredDate)) {
            logger.info("[{}] Archive is synchronized with provider ({}). Decision: NO_SYNC_REQUIRED", category, latestStoredDate);
            return new SyncDecision(SyncMode.NO_SYNC_REQUIRED, null, latestStoredDate, providerLatestDate);
        }

        // Rule 5: Provider older than archive (unexpected)
        if (providerLatestDate.isBefore(latestStoredDate)) {
            logger.warn("[{}] Provider latest date ({}) is older than archive latest date ({}). Decision: NO_SYNC_REQUIRED", category, providerLatestDate, latestStoredDate);
            return new SyncDecision(SyncMode.NO_SYNC_REQUIRED, null, latestStoredDate, providerLatestDate);
        }

        // Fallback (should not happen)
        return new SyncDecision(SyncMode.NO_SYNC_REQUIRED, null, latestStoredDate, providerLatestDate);
    }
}
```

```java
// File: src/main/java/com/vega/fiidii/util/HashUtil.java
package com.vega.fiidii.util;

import com.vega.fiidii.model.InstitutionalFlowRecord;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class HashUtil {
    public static String generateSourceHash(InstitutionalFlowRecord record) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String input = record.getCategory() + record.getDataType() + record.getTimeStamp() +
                    record.getBuyAmount() + record.getSellAmount() +
                    record.getBuyContracts() + record.getSellContracts() +
                    record.getOiContracts() + record.getOiAmount() +
                    record.getTotalLongContracts() + record.getTotalShortContracts() +
                    record.getTotalCallLongContracts() + record.getTotalPutLongContracts() +
                    record.getTotalCallShortContracts() + record.getTotalPutShortContracts();
            
            byte[] encodedhash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(2 * encodedhash.length);
            for (byte b : encodedhash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to initialize SHA-256 algorithm", e);
        }
    }
}
```

```java
// File: src/main/java/com/vega/fiidii/util/PathResolver.java
package com.vega.fiidii.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class PathResolver {

    private static final Path ROOT = findRoot();

    private static Path findRoot() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            // fiidii.md is our project-specific root marker
            if (Files.exists(current.resolve("fiidii.md"))) {
                return current;
            }
            current = current.getParent();
        }
        // Fallback to legacy behavior if marker not found
        return Paths.get("").toAbsolutePath().getParent();
    }

    public static Path root() {
        return ROOT;
    }

    public static Path resolve(String path) {
        return ROOT.resolve(path);
    }
}
```

```yaml
// File: src/main/resources/application.yml
server:
  port: 8080

spring:
  application:
    name: vega.fiidii
  jackson:
    serialization:
      write-dates-as-timestamps: false

logging:
  level:
    com.vega.fiidii: INFO
    org.springframework.web: INFO
```

```json
// File: fiidii/FIIDII.json
{
  "provider": "UPSTOX",
  "defaultInterval": "1D",
  "defaultStartDate": "2026-04-01",
  "refreshIntervalMinutes": 15,
  "requestIntervalMs": 1000,
  "maxDaysPerRequest": 30,
  "retentionDays": 3650,
  "storage": {
    "archive": "storage/fiidii/FII-DII.jsonl",
    "metadata": "storage/fiidii/FII-DII.metadata.json"
  },
  "fii": {
    "endpoint": "/v2/market/fii",
    "dataTypes": [
      "NSE_FO|INDEX_FUTURES",
      "NSE_FO|STOCK_FUTURES",
      "NSE_FO|INDEX_OPTIONS",
      "NSE_FO|STOCK_OPTIONS",
      "NSE_EQ|CASH"
    ]
  },
  "dii": {
    "endpoint": "/v2/market/dii",
    "dataTypes": [
      "NSE_EQ|CASH"
    ]
  }
}
```

```jsonl
// File: fiidii/FII-DII.jsonl
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1774981800000,"buyAmount":23167.05,"sellAmount":19967.74,"buyContracts":379347,"sellContracts":329264,"oiContracts":7203663,"oiAmount":425483.5,"totalLongContracts":4118208,"totalShortContracts":3085455,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"268cf19983a17857f36e7620ce9d15d36b26f729fa92944c8efe18130210f8f8"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1774981800000,"buyAmount":611507.21,"sellAmount":615967.2,"buyContracts":4070312,"sellContracts":4099523,"oiContracts":2055697,"oiAmount":305041.81,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":376141,"totalPutLongContracts":702669,"totalCallShortContracts":630531,"totalPutShortContracts":346355,"sourceHash":"1e9c6f9659ff0673c85e150d17fae32d77434a999fddd8700eff248946ec60e4"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1774981800000,"buyAmount":5079.42,"sellAmount":5157.91,"buyContracts":33590,"sellContracts":34365,"oiContracts":391771,"oiAmount":58555.41,"totalLongContracts":63475,"totalShortContracts":328296,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"c7bd776b0a9d84c5134a5f73b18e9b3fb5e8e70846dd847543010ab420392724"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1774981800000,"buyAmount":10277.74,"sellAmount":10368.61,"buyContracts":159495,"sellContracts":163821,"oiContracts":303206,"oiAmount":18534.78,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":59035,"totalPutLongContracts":85224,"totalCallShortContracts":97070,"totalPutShortContracts":61877,"sourceHash":"e5aa1e5d18a4fa0f404514993fda1c6cab801efc0b09009d787d1007a38f114e"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1774981800000,"buyAmount":17958.07,"sellAmount":26289.22,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"b744984b0f50187857bb17c861ba5144aa692bfffc276b795a84418a32b5a291"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1775068200000,"buyAmount":22900.13,"sellAmount":21476.58,"buyContracts":381779,"sellContracts":359317,"oiContracts":7290091,"oiAmount":430409.28,"totalLongContracts":4172653,"totalShortContracts":3117438,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"c54ded861af18ac7dc2f5835979b042825ae7a611231c13ef8880f6039227e41"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1775068200000,"buyAmount":811841.61,"sellAmount":816563.73,"buyContracts":5477103,"sellContracts":5504198,"oiContracts":2317637,"oiAmount":344422.19,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":411595,"totalPutLongContracts":784638,"totalCallShortContracts":661152,"totalPutShortContracts":460252,"sourceHash":"1260f7111888506233bc3a9040d5926c311698a223521024077dfa7ad7e2f000"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1775068200000,"buyAmount":5258.84,"sellAmount":5724.0,"buyContracts":35554,"sellContracts":38753,"oiContracts":404136,"oiAmount":60308.12,"totalLongContracts":68058,"totalShortContracts":336078,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"fbf43f77ee9a7aba40ea945e0876e47ce3396205cb24c5eaf67c6a633e688f62"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1775068200000,"buyAmount":11179.8,"sellAmount":10270.17,"buyContracts":175919,"sellContracts":163579,"oiContracts":384538,"oiAmount":23531.76,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":86649,"totalPutLongContracts":104446,"totalCallShortContracts":116341,"totalPutShortContracts":77102,"sourceHash":"2f667aea12966ecc65e3ecbfe49c15377895c2f8b65a833e0eaf3911538a028c"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1775068200000,"buyAmount":10626.52,"sellAmount":20557.65,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"d71dc6d08f3d29c6e84cc3e5831e50ee0209a7d1b16d6a3c26e0e3e5699875a0"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1774981800000,"buyAmount":18536.73,"sellAmount":11364.93,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"44b2f7fe53bcb719b74c1eba0bfb698d693ae1110dfec32c27a61584dc3e7376"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1775068200000,"buyAmount":18421.27,"sellAmount":11212.86,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"ae0345b16bc17c1320b4f9880c73f359c05969f7d36f2632958b7edd95825e03"}
```

```json
// File: fiidii/FII-DII.metadata.json
{
  "totalRecords" : 12,
  "fiiRecords" : 10,
  "diiRecords" : 2,
  "lastUpdated" : 1781748552873,
  "latestTimestamp" : 1775068200000,
  "latestFiiDate" : "2026-04-02",
  "latestDiiDate" : "2026-04-02",
  "providerLatestFiiDate" : "2026-04-02",
  "providerLatestDiiDate" : "2026-04-02"
}
```
