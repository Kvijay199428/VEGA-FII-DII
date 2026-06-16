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
                configService.getDefaultInterval(), null, null
        );
    }

    public List<InstitutionalFlowRecord> fetchDii() {
        return fetchFiiDiiData(
                configService.getConfig().getDii().getEndpoint(),
                configService.getDiiDataTypes(),
                "DII",
                configService.getDefaultInterval(), null, null
        );
    }

    public List<InstitutionalFlowRecord> fetchAdHoc(String category, String dataType, String interval, String fromDate, String toDate) {
        String endpoint = "FII".equalsIgnoreCase(category) ? 
                configService.getConfig().getFii().getEndpoint() : 
                configService.getConfig().getDii().getEndpoint();
        
        List<String> dataTypes = (dataType != null && !dataType.isEmpty()) ? 
                List.of(dataType) : 
                ("FII".equalsIgnoreCase(category) ? configService.getFiiDataTypes() : configService.getDiiDataTypes());
                
        return fetchFiiDiiData(endpoint, dataTypes, category.toUpperCase(), interval, fromDate, toDate);
    }

    public List<InstitutionalFlowRecord> fetchFiiRange(String fromDate, String toDate) {
        return fetchFiiDiiData(
                configService.getConfig().getFii().getEndpoint(),
                configService.getFiiDataTypes(),
                "FII",
                configService.getDefaultInterval(), fromDate, toDate
        );
    }

    public List<InstitutionalFlowRecord> fetchDiiRange(String fromDate, String toDate) {
        return fetchFiiDiiData(
                configService.getConfig().getDii().getEndpoint(),
                configService.getDiiDataTypes(),
                "DII",
                configService.getDefaultInterval(), fromDate, toDate
        );
    }

    private List<InstitutionalFlowRecord> fetchFiiDiiData(String endpoint, List<String> dataTypes, String category, String interval, String fromDate, String toDate) {
        String queryString = dataTypes.stream()
                .map(dt -> "data_type=" + URLEncoder.encode(dt, StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
                
        if (interval != null && !interval.isEmpty()) {
            queryString += "&interval=" + URLEncoder.encode(interval, StandardCharsets.UTF_8);
        }
        if (fromDate != null && !fromDate.isEmpty()) {
            queryString += "&from=" + URLEncoder.encode(fromDate, StandardCharsets.UTF_8);
        }
        if (toDate != null && !toDate.isEmpty()) {
            queryString += "&to=" + URLEncoder.encode(toDate, StandardCharsets.UTF_8);
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
        int maxRetries = 2; // Retry once -> initial try + 1 retry
        for (int i = 1; i <= maxRetries; i++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                logger.info("Content-Encoding={}", response.headers().firstValue("Content-Encoding").orElse("none"));
                if (response.statusCode() == 200) {
                    return parseResponse(response.body(), category);
                } else {
                    logger.warn("Attempt {}: Failed to fetch {}. Status code: {}", i, category, response.statusCode());
                    logger.error("Response Body: {}", response.body());
                }
            } catch (Exception e) {
                logger.warn("Attempt {}: Exception while fetching {}: {}", i, category, e.getMessage());
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

    public FiiDiiController(FiiDiiArchiveService archiveService, UpstoxFiiDiiClient upstoxClient) {
        this.archiveService = archiveService;
        this.upstoxClient = upstoxClient;
    }

    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("totalRecords", archiveService.getAllRecords().size());
        
        LocalDate latestFii = archiveService.getLatestFiiDate();
        LocalDate latestDii = archiveService.getLatestDiiDate();
        
        status.put("latestFiiDate", latestFii != null ? latestFii.toString() : null);
        status.put("latestDiiDate", latestDii != null ? latestDii.toString() : null);
        
        LocalDate yesterday = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(1);
        boolean bootstrapRequired = (latestFii == null || latestFii.isBefore(yesterday)) ||
                                    (latestDii == null || latestDii.isBefore(yesterday));
        
        status.put("bootstrapRequired", bootstrapRequired);
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
            return upstoxClient.fetchAdHoc(category, dataType, interval, from, to);
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

    @Scheduled(fixedRateString = "#{@fiiDiiConfigService.getRefreshIntervalMinutes() * 60000}", initialDelay = 10000)
    public void refreshFiiDii() {
        if (!bootstrapService.isBootstrapCompleted()) {
            logger.info("Bootstrap is not completed yet, skipping scheduled FII/DII refresh.");
            return;
        }

        logger.info("Starting scheduled FII and DII refresh...");

        logger.info("Syncing FII activity...");
        bootstrapService.syncData("FII", archiveService.getLatestFiiDate());

        logger.info("Syncing DII activity...");
        bootstrapService.syncData("DII", archiveService.getLatestDiiDate());

        logger.info("Completed scheduled FII and DII refresh.");
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

    public FiiDiiArchiveService(FiiDiiConfigService configService, ObjectMapper objectMapper) {
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        this.archivePath = configService.getArchivePath();
        this.metadataPath = configService.getMetadataPath();
        loadMetadata();
        loadArchive();
    }

    private void loadMetadata() {
        if (!Files.exists(metadataPath)) return;
        try {
            String content = Files.readString(metadataPath);
            ArchiveMetadata metadata = objectMapper.readValue(content, ArchiveMetadata.class);
            if (metadata.getLatestFiiDate() != null) {
                latestFiiDate = LocalDate.parse(metadata.getLatestFiiDate());
            }
            if (metadata.getLatestDiiDate() != null) {
                latestDiiDate = LocalDate.parse(metadata.getLatestDiiDate());
            }
            logger.info("Loaded metadata: latestFiiDate={}, latestDiiDate={}", latestFiiDate, latestDiiDate);
        } catch (Exception e) {
            logger.warn("Failed to load metadata, will recreate from archive: {}", e.getMessage());
        }
    }

    private void loadArchive() {
        if (!Files.exists(archivePath)) {
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
            lines.forEach(line -> {
                if(line.trim().isEmpty()) return;
                try {
                    InstitutionalFlowRecord record = objectMapper.readValue(line, InstitutionalFlowRecord.class);
                    existingHashes.add(record.getSourceHash());
                    inMemoryArchive.add(record);
                    updateLatestDates(record);
                } catch (JsonProcessingException e) {
                    logger.warn("Failed to parse line: {}", line);
                }
            });
            logger.info("Loaded {} records from archive.", inMemoryArchive.size());
            logger.info("Latest FII date: {}, Latest DII date: {}", latestFiiDate, latestDiiDate);
        } catch (IOException e) {
            logger.error("Failed to load archive", e);
        }
    }

    private synchronized void updateLatestDates(InstitutionalFlowRecord record) {
        LocalDate recordDate = Instant.ofEpochMilli(record.getTimeStamp())
                .atZone(ZoneId.of("Asia/Kolkata"))
                .toLocalDate();
                
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

    public synchronized void appendRecords(List<InstitutionalFlowRecord> records) {
        List<InstitutionalFlowRecord> newRecords = records.stream()
                .filter(r -> !existingHashes.contains(r.getSourceHash()))
                .collect(Collectors.toList());

        if (newRecords.isEmpty()) {
            return;
        }

        try (BufferedWriter writer = Files.newBufferedWriter(archivePath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (InstitutionalFlowRecord record : newRecords) {
                writer.write(objectMapper.writeValueAsString(record));
                writer.newLine();
                existingHashes.add(record.getSourceHash());
                inMemoryArchive.add(record);
                updateLatestDates(record);
            }
            logger.info("Appended {} new records.", newRecords.size());
            logger.info("After append -> latestFiiDate={}, latestDiiDate={}", latestFiiDate, latestDiiDate);
            updateMetadata();
        } catch (IOException e) {
            logger.error("Failed to append records to archive", e);
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

        try {
            if(metadataPath.getParent() != null) {
                Files.createDirectories(metadataPath.getParent());
            }
            Files.writeString(metadataPath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metadata));
        } catch (IOException e) {
            logger.error("Failed to write metadata", e);
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
    private volatile boolean bootstrapCompleted = false;

    public FiiDiiBootstrapService(FiiDiiArchiveService archiveService, UpstoxFiiDiiClient upstoxClient, FiiDiiConfigService configService) {
        this.archiveService = archiveService;
        this.upstoxClient = upstoxClient;
        this.configService = configService;
    }

    public boolean isBootstrapCompleted() {
        return bootstrapCompleted;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        logger.info("Starting FII/DII Data Bootstrap...");
        try {
            syncData("FII", archiveService.getLatestFiiDate());
            syncData("DII", archiveService.getLatestDiiDate());
            logger.info("FII/DII Data Bootstrap completed.");
        } catch (Exception e) {
            logger.error("Error during bootstrap sync", e);
        } finally {
            bootstrapCompleted = true;
        }
    }

    public void syncData(String category, LocalDate latestStoredDate) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDate startDate;

        if (latestStoredDate == null) {
            startDate = configService.getDefaultStartDate();
        } else {
            startDate = latestStoredDate.plusDays(1);
        }

        if (startDate.isAfter(today)) {
            logger.info("[{}] No bootstrap sync required. Latest stored date {} is up to date.", category, latestStoredDate);
            return;
        }
        
        logger.info("[{}] Missing data from {} to {}", category, startDate, today);
        
        int maxDays = configService.getMaxDaysPerRequest();
        long intervalMs = configService.getRequestIntervalMs();

        LocalDate currentDate = startDate;
        while (!currentDate.isAfter(today)) {
            LocalDate chunkEnd = currentDate.plusDays(maxDays - 1);
            if (chunkEnd.isAfter(today)) {
                chunkEnd = today;
            }

            String fromStr = currentDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
            String toStr = chunkEnd.format(DateTimeFormatter.ISO_LOCAL_DATE);
            
            logger.info("[{}] Fetching chunk: {} to {}", category, fromStr, toStr);

            List<InstitutionalFlowRecord> records;
            if ("FII".equalsIgnoreCase(category)) {
                records = upstoxClient.fetchFiiRange(fromStr, toStr);
            } else {
                records = upstoxClient.fetchDiiRange(fromStr, toStr);
            }

            if (!records.isEmpty()) {
                logger.info("[{}] Fetched {} records. Appending...", category, records.size());
                archiveService.appendRecords(records);
            }
            
            currentDate = chunkEnd.plusDays(1);
            
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

import java.nio.file.Path;
import java.nio.file.Paths;

public final class PathResolver {

    private static final Path ROOT = Paths.get("").toAbsolutePath().getParent();

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
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1777487400000,"buyAmount":23109.75,"sellAmount":24642.52,"buyContracts":353981,"sellContracts":384079,"oiContracts":7245154,"oiAmount":452650.0,"totalLongContracts":4021980,"totalShortContracts":3223174,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"fe6b322d439fc520a93151c858a75f5fb486b6840c4b919007b763a84490c153"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1777401000000,"buyAmount":21593.35,"sellAmount":21252.58,"buyContracts":327164,"sellContracts":318686,"oiContracts":7237618,"oiAmount":456065.1,"totalLongContracts":4033261,"totalShortContracts":3204357,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"4757ae5b858d5384520839a9d0faaefcc614706090405a04fd3abc167d4da1a7"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1777314600000,"buyAmount":39923.8,"sellAmount":42987.41,"buyContracts":611176,"sellContracts":653578,"oiContracts":7132580,"oiAmount":452808.03,"totalLongContracts":3963869,"totalShortContracts":3168711,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"08a915bb3401c236c4785e6c9bbcd212931898c3ddb419a0e5b9d7265b7dc4b0"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1777228200000,"buyAmount":121677.19,"sellAmount":120184.08,"buyContracts":1902647,"sellContracts":1869946,"oiContracts":7414760,"oiAmount":472720.42,"totalLongContracts":4157509,"totalShortContracts":3257251,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"b0b5945dfb16e0e1b12d93549d103bfdff5d62750b912681064ba375fef28561"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1776969000000,"buyAmount":167437.51,"sellAmount":168481.94,"buyContracts":2668316,"sellContracts":2697622,"oiContracts":7344582,"oiAmount":463187.55,"totalLongContracts":4106561,"totalShortContracts":3238021,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"c99aca6c83eba7ccfd4bed52a9171b3fbe0b0042c859b7aa2651f163a2bd4587"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1776882600000,"buyAmount":157584.44,"sellAmount":161340.26,"buyContracts":2467736,"sellContracts":2541173,"oiContracts":7293636,"oiAmount":465499.49,"totalLongContracts":4095741,"totalShortContracts":3197895,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"d54e9845f9d0e3bb9992c6e812cc0ac1f4a65eb842e0082b6ddbdc32d991d6c2"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1776796200000,"buyAmount":35707.95,"sellAmount":39376.98,"buyContracts":552144,"sellContracts":618292,"oiContracts":7307995,"oiAmount":470344.99,"totalLongContracts":4138764,"totalShortContracts":3169231,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"55fb5765be5ca4b67c1f67bc6ea115b3bfc5a594dafb18b8352581d0c1a69ccc"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1776709800000,"buyAmount":24372.67,"sellAmount":22815.96,"buyContracts":374673,"sellContracts":347672,"oiContracts":7268887,"oiAmount":469698.63,"totalLongContracts":4153809,"totalShortContracts":3115078,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"0f2502078d15934551060367f13da8c01b28b5dc992d2b92161f97303cf5bf11"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1776623400000,"buyAmount":22726.65,"sellAmount":23409.21,"buyContracts":347270,"sellContracts":367511,"oiContracts":7272022,"oiAmount":466258.21,"totalLongContracts":4141876,"totalShortContracts":3130146,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"57113e0ec453bfb7f3c5b03c43a6c9e76de2f16fb2a6ce574af888f784ae6a70"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1776364200000,"buyAmount":25658.28,"sellAmount":24657.85,"buyContracts":391077,"sellContracts":380918,"oiContracts":7270105,"oiAmount":468159.04,"totalLongContracts":4151038,"totalShortContracts":3119067,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"e3246045ec8530347677170f728aba9b261529556116d9fb8ba9addcf13f0b51"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1776277800000,"buyAmount":24499.14,"sellAmount":26142.27,"buyContracts":383635,"sellContracts":404150,"oiContracts":7270548,"oiAmount":464417.84,"totalLongContracts":4146180,"totalShortContracts":3124368,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"61f92eba4be6b3419f3a4701c00cc4829507a50d87bd5b667a2cd2fdcd377d64"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1776191400000,"buyAmount":25350.18,"sellAmount":25443.68,"buyContracts":389020,"sellContracts":378361,"oiContracts":7285413,"oiAmount":464163.16,"totalLongContracts":4163870,"totalShortContracts":3121543,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"78b0b304f13dc8a041975762383a6e925cd11dd3230cf879e353f88b863205db"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1776018600000,"buyAmount":19968.25,"sellAmount":23294.28,"buyContracts":310881,"sellContracts":358242,"oiContracts":7338264,"oiAmount":458589.55,"totalLongContracts":4184966,"totalShortContracts":3153298,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"a4cf6d677d1c5f9f06c1166599be5208931902c2c262ce2b1aa5f9c3b472962b"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1775759400000,"buyAmount":22881.01,"sellAmount":22478.22,"buyContracts":358226,"sellContracts":361193,"oiContracts":7345865,"oiAmount":463459.74,"totalLongContracts":4212447,"totalShortContracts":3133418,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"5c417afa2534b72193c18e95de32021f66b4e2f116cf6a40e61ea2eb7e91c3e0"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1775673000000,"buyAmount":21873.97,"sellAmount":21998.25,"buyContracts":346942,"sellContracts":355535,"oiContracts":7354438,"oiAmount":459288.88,"totalLongContracts":4218217,"totalShortContracts":3136221,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"e282db0beb86e44548e5f9476bd5f8d6b772ff9d07fc943010d3f5a19997a006"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1775586600000,"buyAmount":34009.7,"sellAmount":31487.91,"buyContracts":520887,"sellContracts":488822,"oiContracts":7312071,"oiAmount":458306.55,"totalLongContracts":4201330,"totalShortContracts":3110741,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"80f8ee1d762fe28edd0e55b5943ba4045251d7507d5c42a031e529c0cf733fb2"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1775500200000,"buyAmount":19991.33,"sellAmount":20571.63,"buyContracts":322585,"sellContracts":335169,"oiContracts":7298942,"oiAmount":440010.87,"totalLongContracts":4178733,"totalShortContracts":3120209,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"a6aa974bddcaf513f89f7d58094298f28e996cba5446277b7d48bd8b912fbd12"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1775413800000,"buyAmount":20712.58,"sellAmount":19818.76,"buyContracts":336862,"sellContracts":320969,"oiContracts":7302874,"oiAmount":438325.5,"totalLongContracts":4186991,"totalShortContracts":3115883,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"5b24c7e4c9e12e3f129358514071c6aa3fcf9d6cf2cecdddbbf9c0ded13b9cf3"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1775068200000,"buyAmount":22900.13,"sellAmount":21476.58,"buyContracts":381779,"sellContracts":359317,"oiContracts":7290091,"oiAmount":430409.28,"totalLongContracts":4172653,"totalShortContracts":3117438,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"c54ded861af18ac7dc2f5835979b042825ae7a611231c13ef8880f6039227e41"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1777487400000,"buyAmount":797967.36,"sellAmount":794438.53,"buyContracts":5094129,"sellContracts":5072195,"oiContracts":1995796,"oiAmount":313760.14,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":351772,"totalPutLongContracts":715640,"totalCallShortContracts":572110,"totalPutShortContracts":356275,"sourceHash":"6a72a629b223e9b988de9b38cd8f6b2f96ac9182efa67830f942a884d3a7fc4f"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1777401000000,"buyAmount":579943.55,"sellAmount":583822.61,"buyContracts":3659192,"sellContracts":3683793,"oiContracts":1776287,"oiAmount":281482.39,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":293574,"totalPutLongContracts":653116,"totalCallShortContracts":509632,"totalPutShortContracts":319965,"sourceHash":"a7617a7fd52642627b2e1b52c10e22e655df53777039bafbb660ba4cbe65ca8b"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1777314600000,"buyAmount":3945021.89,"sellAmount":3957895.97,"buyContracts":24765354,"sellContracts":24848029,"oiContracts":1574567,"oiAmount":247757.65,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":255953,"totalPutLongContracts":602178,"totalCallShortContracts":457771,"totalPutShortContracts":258666,"sourceHash":"a5f8b8f0e2a1fccde575afbf4132745e318f90598efa78fad91c81e3be505039"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1777228200000,"buyAmount":1440500.67,"sellAmount":1446680.39,"buyContracts":9070203,"sellContracts":9104089,"oiContracts":3007892,"oiAmount":478847.79,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":620796,"totalPutLongContracts":944737,"totalCallShortContracts":825375,"totalPutShortContracts":616983,"sourceHash":"4d86526b0d3096d8c89dfe381e1f87a22a24baad03a9b58d83b7fdeac0968c37"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1776969000000,"buyAmount":775497.41,"sellAmount":770086.03,"buyContracts":4871561,"sellContracts":4843110,"oiContracts":2972797,"oiAmount":469598.08,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":658740,"totalPutLongContracts":906190,"totalCallShortContracts":885490,"totalPutShortContracts":522378,"sourceHash":"7ff2cde5054b99eee6c0f0e4dc902637da9246a459d7bb59151b907de4aac9f4"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1776882600000,"buyAmount":617004.17,"sellAmount":618314.39,"buyContracts":3862828,"sellContracts":3871613,"oiContracts":2574707,"oiAmount":411481.92,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":495010,"totalPutLongContracts":856649,"totalCallShortContracts":741870,"totalPutShortContracts":481179,"sourceHash":"1c0a4cd6a73fa4b6a04580bf3ae6d5cd70e5a3ef6c00ab372162d876bbf20664"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1776796200000,"buyAmount":505629.35,"sellAmount":510950.53,"buyContracts":3149101,"sellContracts":3180724,"oiContracts":2439695,"oiAmount":393815.79,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":469533,"totalPutLongContracts":819012,"totalCallShortContracts":678084,"totalPutShortContracts":473066,"sourceHash":"a06c96c75871e2d110e6ead9c3e7e9f91ed17283120fea25e8837c4a36196a89"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1776709800000,"buyAmount":2575707.03,"sellAmount":2599179.21,"buyContracts":16151238,"sellContracts":16291149,"oiContracts":2174739,"oiAmount":354199.1,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":436467,"totalPutLongContracts":735411,"totalCallShortContracts":567780,"totalPutShortContracts":435080,"sourceHash":"88b9302f26760da9a493abcec8619ea47bfcb6818b5f46df289c9b051405ba7b"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1776623400000,"buyAmount":1317291.11,"sellAmount":1327511.06,"buyContracts":8273755,"sellContracts":8338168,"oiContracts":2688046,"oiAmount":431716.95,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":513539,"totalPutLongContracts":888902,"totalCallShortContracts":693549,"totalPutShortContracts":592057,"sourceHash":"2036ab4ac6b2d79da6cfa7eac41133eb0e174a146e35d89a80b6f89a2db5e853"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1776364200000,"buyAmount":738000.31,"sellAmount":733795.01,"buyContracts":4657335,"sellContracts":4631462,"oiContracts":2516604,"oiAmount":404402.16,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":491399,"totalPutLongContracts":857527,"totalCallShortContracts":632096,"totalPutShortContracts":535582,"sourceHash":"94f39c3110fb6cc5435dacda1236561752995c999626736ce2604e358999d0e4"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1776277800000,"buyAmount":676924.0,"sellAmount":677887.99,"buyContracts":4280843,"sellContracts":4286054,"oiContracts":2343073,"oiAmount":374006.25,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":436038,"totalPutLongContracts":813186,"totalCallShortContracts":651704,"totalPutShortContracts":442145,"sourceHash":"d72e81ef04a676c911a5c260038e55aead892a1726597b4efebcd51a08ae8495"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1776191400000,"buyAmount":495064.29,"sellAmount":499517.74,"buyContracts":3146934,"sellContracts":3176575,"oiContracts":2174933,"oiAmount":347980.06,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":416458,"totalPutLongContracts":751302,"totalCallShortContracts":581517,"totalPutShortContracts":425656,"sourceHash":"25e2be22ef58bb51a4cb28f4865de680213107a67e9ee84297cc493cfd0e06f8"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1776018600000,"buyAmount":3073763.12,"sellAmount":3108488.64,"buyContracts":19851654,"sellContracts":20076255,"oiContracts":2142971,"oiAmount":337835.94,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":417588,"totalPutLongContracts":749011,"totalCallShortContracts":568582,"totalPutShortContracts":407790,"sourceHash":"dbd56e587bda2be6f4f7366a1b7d60579a138dfdeaf1acfbc9766223f53e884d"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1775759400000,"buyAmount":1059181.07,"sellAmount":1052163.06,"buyContracts":6784186,"sellContracts":6736084,"oiContracts":2628502,"oiAmount":416310.65,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":517932,"totalPutLongContracts":873651,"totalCallShortContracts":696530,"totalPutShortContracts":540390,"sourceHash":"5fd45d10ae1b11d6c76db2c2b096dbeefdc68e7c85ce501aad6c4b9d41849f77"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1775673000000,"buyAmount":704946.09,"sellAmount":702626.08,"buyContracts":4528511,"sellContracts":4515636,"oiContracts":2417173,"oiAmount":377868.7,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":461675,"totalPutLongContracts":800192,"totalCallShortContracts":686827,"totalPutShortContracts":468479,"sourceHash":"751efe8b07cd4f06a326a78dc46433b7add01cb336795cd706c375ba09900623"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1775586600000,"buyAmount":569465.71,"sellAmount":572629.04,"buyContracts":3680196,"sellContracts":3700576,"oiContracts":2221377,"oiAmount":351230.41,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":427437,"totalPutLongContracts":730094,"totalCallShortContracts":622244,"totalPutShortContracts":441602,"sourceHash":"2fd1c2c95186e0d56e02fb1b73758dbee0e6c19b79468b22c91573c8cebf86b9"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1775500200000,"buyAmount":3605579.14,"sellAmount":3604547.86,"buyContracts":24121610,"sellContracts":24109503,"oiContracts":1953261,"oiAmount":296307.1,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":335168,"totalPutLongContracts":698495,"totalCallShortContracts":541912,"totalPutShortContracts":377686,"sourceHash":"263a35546d6450383b64a55a00aca9b611d01d6989906f339e5cee67eb2e1bad"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1775413800000,"buyAmount":1349221.35,"sellAmount":1348181.33,"buyContracts":9097571,"sellContracts":9087125,"oiContracts":2532983,"oiAmount":381068.72,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":444173,"totalPutLongContracts":864956,"totalCallShortContracts":676826,"totalPutShortContracts":547028,"sourceHash":"d175ba790468d24f36302f2dd156523f27979a393125c702fe82a2f90b41b863"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1775068200000,"buyAmount":811841.61,"sellAmount":816563.73,"buyContracts":5477103,"sellContracts":5504198,"oiContracts":2317637,"oiAmount":344422.19,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":411595,"totalPutLongContracts":784638,"totalCallShortContracts":661152,"totalPutShortContracts":460252,"sourceHash":"1260f7111888506233bc3a9040d5926c311698a223521024077dfa7ad7e2f000"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1777487400000,"buyAmount":2350.88,"sellAmount":4448.54,"buyContracts":14696,"sellContracts":28114,"oiContracts":239488,"oiAmount":38199.7,"totalLongContracts":27506,"totalShortContracts":211982,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"3ecfe6edca3fd2b7f4bdec79574e5925122f5b8f09f1528dc78095bdc7e89673"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1777401000000,"buyAmount":2606.74,"sellAmount":2675.62,"buyContracts":16188,"sellContracts":16568,"oiContracts":228718,"oiAmount":36735.16,"totalLongContracts":28830,"totalShortContracts":199888,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"1490bd24222f96917b05ad231080a1e99306b141da3ca939d17278feffdbb293"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1777314600000,"buyAmount":6556.1,"sellAmount":9650.49,"buyContracts":40790,"sellContracts":60209,"oiContracts":231124,"oiAmount":36951.69,"totalLongContracts":30223,"totalShortContracts":200901,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"06bf5c78e70355648e3a64ee751da45b3ce6ae51c5ae0a8aa689535604553410"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1777228200000,"buyAmount":12135.35,"sellAmount":12456.25,"buyContracts":75834,"sellContracts":77736,"oiContracts":337311,"oiAmount":54048.56,"totalLongContracts":65665,"totalShortContracts":271646,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"22ba405d45bdff081ae6a2cce7dd9d9e8eca9f963223d6075437cb0503ca665f"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1776969000000,"buyAmount":8508.74,"sellAmount":10612.52,"buyContracts":52806,"sellContracts":65971,"oiContracts":332129,"oiAmount":52804.6,"totalLongContracts":64025,"totalShortContracts":268104,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"fd7f673179a1fafd3c888b79b8f7b4558cf374d59dc19fa8d70ef817e5deed12"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1776882600000,"buyAmount":6467.41,"sellAmount":7735.71,"buyContracts":40350,"sellContracts":47950,"oiContracts":326914,"oiAmount":52387.54,"totalLongContracts":68000,"totalShortContracts":258914,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"e35142d4222c13c56e9ac14cdfda23e9856c213753271e2b2a74ea21b2d12c96"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1776796200000,"buyAmount":1978.95,"sellAmount":3499.27,"buyContracts":12179,"sellContracts":21545,"oiContracts":324126,"oiAmount":52457.05,"totalLongContracts":70406,"totalShortContracts":253720,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"dfcf1b1f2f1cf8be76e963f3be84efaa24f6ea6e2000ca8884fb8f1f329603e0"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1776709800000,"buyAmount":3914.39,"sellAmount":1349.64,"buyContracts":24114,"sellContracts":8301,"oiContracts":318164,"oiAmount":51859.35,"totalLongContracts":72108,"totalShortContracts":246056,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"ac45d57bb55b9b4ac1855b6fc35ee49383ccd93fae7d2a775bf1a9280a90bb4c"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1776623400000,"buyAmount":2563.16,"sellAmount":2403.97,"buyContracts":15823,"sellContracts":14631,"oiContracts":323971,"oiAmount":52198.48,"totalLongContracts":67105,"totalShortContracts":256866,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"1581a6eec5ba1090e3c5c448d5e09e8d3571c2c1cd1b554bc82d807f092e33ee"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1776364200000,"buyAmount":2476.92,"sellAmount":1198.81,"buyContracts":15415,"sellContracts":7379,"oiContracts":326991,"oiAmount":52772.3,"totalLongContracts":68019,"totalShortContracts":258972,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"6c364aeed17cac74a169998ede00cd6ffee23f5456268f2526e4209ef76767b0"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1776277800000,"buyAmount":2477.45,"sellAmount":2211.29,"buyContracts":15456,"sellContracts":13727,"oiContracts":335771,"oiAmount":53745.44,"totalLongContracts":68391,"totalShortContracts":267380,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"665548ace733652cd8b8c25c0fc2ddf6876a481192f31a80274b2ecd93fba5b4"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1776191400000,"buyAmount":4029.41,"sellAmount":2786.56,"buyContracts":25056,"sellContracts":17420,"oiContracts":346896,"oiAmount":55582.71,"totalLongContracts":73089,"totalShortContracts":273807,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"61f0af18b002dae41e4c327e225b5d7a8f49a9d06b17fddab08b409f32e088cf"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1776018600000,"buyAmount":2227.4,"sellAmount":2573.7,"buyContracts":14132,"sellContracts":16259,"oiContracts":369658,"oiAmount":58274.21,"totalLongContracts":80652,"totalShortContracts":289006,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"c712d8dd552f09d2ce634f593d3ad917e084241661b36cb447142c567979da4c"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1775759400000,"buyAmount":5691.39,"sellAmount":2253.04,"buyContracts":35710,"sellContracts":14219,"oiContracts":369091,"oiAmount":58720.95,"totalLongContracts":81432,"totalShortContracts":287659,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"c64a204fa8354b9611d482ee234a7f1f32687e2beadae96b1e9b2138771b0732"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1775673000000,"buyAmount":2433.82,"sellAmount":2518.08,"buyContracts":15442,"sellContracts":15760,"oiContracts":385730,"oiAmount":60603.14,"totalLongContracts":79006,"totalShortContracts":306724,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"97f7ea20148f8b2ee2a1b3577d9accaf8f0dd5f1e72438f3ac525e9d6c084df7"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1775586600000,"buyAmount":7872.12,"sellAmount":2799.17,"buyContracts":49723,"sellContracts":17688,"oiContracts":392210,"oiAmount":62172.77,"totalLongContracts":82405,"totalShortContracts":309805,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"a2624aed849f2f419b8866899d0e0b2bbccc41de2be6a5130b2916c0e7040f4d"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1775500200000,"buyAmount":4982.76,"sellAmount":3634.91,"buyContracts":33058,"sellContracts":24031,"oiContracts":410971,"oiAmount":62391.49,"totalLongContracts":75768,"totalShortContracts":335203,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"33b9b3dc53fea557191c753546f7c448dac09b990ff741c0f51f9c80e0261131"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1775413800000,"buyAmount":3613.07,"sellAmount":3657.99,"buyContracts":23944,"sellContracts":24386,"oiContracts":413598,"oiAmount":62589.19,"totalLongContracts":72568,"totalShortContracts":341030,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"a2ebd0c1b6eeb461651f45c3f627df2c41fd2c936a1deebddf01422b4009869e"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1775068200000,"buyAmount":5258.84,"sellAmount":5724.0,"buyContracts":35554,"sellContracts":38753,"oiContracts":404136,"oiAmount":60308.12,"totalLongContracts":68058,"totalShortContracts":336078,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"fbf43f77ee9a7aba40ea945e0876e47ce3396205cb24c5eaf67c6a633e688f62"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1777487400000,"buyAmount":18797.22,"sellAmount":18893.3,"buyContracts":284705,"sellContracts":288393,"oiContracts":395209,"oiAmount":25101.19,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":79478,"totalPutLongContracts":109365,"totalCallShortContracts":129784,"totalPutShortContracts":76582,"sourceHash":"249a256f8cc69ef3853ccfbe6453b02e0fe8586237b5b82fd46d552c75bd9e30"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1777401000000,"buyAmount":19909.29,"sellAmount":20216.29,"buyContracts":296944,"sellContracts":299301,"oiContracts":301283,"oiAmount":19205.65,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":56574,"totalPutLongContracts":87150,"totalCallShortContracts":103340,"totalPutShortContracts":54219,"sourceHash":"21847e78ecbc65d8b11104af7c95b774aa04092c7fa9b45cf801c1314f146dbf"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1777314600000,"buyAmount":14703.71,"sellAmount":15487.72,"buyContracts":212471,"sellContracts":226171,"oiContracts":191470,"oiAmount":12175.12,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":31521,"totalPutLongContracts":57760,"totalCallShortContracts":70199,"totalPutShortContracts":31990,"sourceHash":"32c895402c1b8c2cf2b6874fdd84ed40463963915e6957abeee488dd69656bc3"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1777228200000,"buyAmount":36235.68,"sellAmount":37308.19,"buyContracts":552014,"sellContracts":568143,"oiContracts":1125643,"oiAmount":74517.68,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":208156,"totalPutLongContracts":295725,"totalCallShortContracts":380905,"totalPutShortContracts":240857,"sourceHash":"2b8eb76dcdac48343061bcaf157accf0a27fceb69bfe123a18cbf5bf8c11e79c"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1776969000000,"buyAmount":37500.93,"sellAmount":41329.02,"buyContracts":572729,"sellContracts":635781,"oiContracts":1161548,"oiAmount":76060.25,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":226960,"totalPutLongContracts":302938,"totalCallShortContracts":383613,"totalPutShortContracts":248037,"sourceHash":"a49f94128a3e47de380afcc9ceeda16ed741f63713e547251e8bb36a7541c6ff"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1776882600000,"buyAmount":36560.58,"sellAmount":35122.76,"buyContracts":561926,"sellContracts":543834,"oiContracts":1206676,"oiAmount":80156.59,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":245952,"totalPutLongContracts":338036,"totalCallShortContracts":376872,"totalPutShortContracts":245816,"sourceHash":"8715a21c1b5f583e40bb9d3171d56d7111f2df173bf6a8d88df69e2f91a201b9"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1776796200000,"buyAmount":34948.68,"sellAmount":35781.8,"buyContracts":544268,"sellContracts":560305,"oiContracts":1221984,"oiAmount":81920.29,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":247963,"totalPutLongContracts":334633,"totalCallShortContracts":385025,"totalPutShortContracts":254363,"sourceHash":"1e22afa4e0d64450420dbb491b0efcffaf2c991ca3cae40320181d8bf420025b"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1776709800000,"buyAmount":27348.37,"sellAmount":26813.52,"buyContracts":418819,"sellContracts":412567,"oiContracts":1135445,"oiAmount":76435.3,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":242173,"totalPutLongContracts":305172,"totalCallShortContracts":348223,"totalPutShortContracts":239877,"sourceHash":"c5bca037e5f6b240ccc33823b1d0ff97e34c50a66f4c6d8b9c442f12084e65f7"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1776623400000,"buyAmount":26044.71,"sellAmount":26675.43,"buyContracts":406267,"sellContracts":413557,"oiContracts":1091197,"oiAmount":73160.44,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":232448,"totalPutLongContracts":289647,"totalCallShortContracts":338604,"totalPutShortContracts":230498,"sourceHash":"457938ae3df167c0d4ecd23cc24b20546976ffc6d9d09b8570d162cf08d50221"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1776364200000,"buyAmount":25960.18,"sellAmount":27145.34,"buyContracts":394107,"sellContracts":410442,"oiContracts":1045489,"oiAmount":70097.26,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":226467,"totalPutLongContracts":276419,"totalCallShortContracts":323621,"totalPutShortContracts":218982,"sourceHash":"d6fcdfeebcd83c745d14bf39ff92afa95ea170cc32b08cd36a92231698e65309"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1776277800000,"buyAmount":23485.43,"sellAmount":24003.41,"buyContracts":364282,"sellContracts":367140,"oiContracts":961958,"oiAmount":64245.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":203720,"totalPutLongContracts":265568,"totalCallShortContracts":293228,"totalPutShortContracts":199442,"sourceHash":"f94fd7a9b7086528ecf774a64ec78ed5d570518d73f6ca0e4591d1d41ff0476a"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1776191400000,"buyAmount":18786.92,"sellAmount":19694.44,"buyContracts":294940,"sellContracts":301443,"oiContracts":895518,"oiAmount":59720.84,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":190643,"totalPutLongContracts":246854,"totalCallShortContracts":269553,"totalPutShortContracts":188468,"sourceHash":"b7f0589064ea3ad8e972f5d0ecd8d29bc11a11c4ec47e60f1add5a07246b380e"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1776018600000,"buyAmount":16421.42,"sellAmount":16880.92,"buyContracts":246665,"sellContracts":253219,"oiContracts":834673,"oiAmount":54599.9,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":177666,"totalPutLongContracts":232660,"totalCallShortContracts":252498,"totalPutShortContracts":171849,"sourceHash":"5e97d839dece684b9e5959d4e3254090428747e6083fd0b91fb63909543c4424"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1775759400000,"buyAmount":18581.81,"sellAmount":19468.12,"buyContracts":292082,"sellContracts":307758,"oiContracts":784995,"oiAmount":51725.96,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":171636,"totalPutLongContracts":217128,"totalCallShortContracts":232694,"totalPutShortContracts":163537,"sourceHash":"68f548c760d8c4e23e8b2f0a24bb23493855bdb0b92f004006562e96c351038e"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1775673000000,"buyAmount":17872.87,"sellAmount":18331.29,"buyContracts":272176,"sellContracts":278308,"oiContracts":703463,"oiAmount":45881.59,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":157057,"totalPutLongContracts":198779,"totalCallShortContracts":204632,"totalPutShortContracts":142995,"sourceHash":"c5100d9cb998efde0ab8cdc9fb9002b329c887ff5c5437283a9c3904374401bf"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1775586600000,"buyAmount":17583.7,"sellAmount":17563.92,"buyContracts":269085,"sellContracts":271448,"oiContracts":620407,"oiAmount":40660.75,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":144589,"totalPutLongContracts":172785,"totalCallShortContracts":176735,"totalPutShortContracts":126298,"sourceHash":"49f123070ecd45917e630426515e9ab75abc3ba13ef1a09eb9d47bdd914c1265"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1775500200000,"buyAmount":11454.01,"sellAmount":11241.81,"buyContracts":176770,"sellContracts":174068,"oiContracts":529408,"oiAmount":33158.45,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":124272,"totalPutLongContracts":148784,"totalCallShortContracts":149270,"totalPutShortContracts":107082,"sourceHash":"ecf06ff112a525c6ba348a83d6612488c71eae7875057b3840bd290d55e65dd5"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1775413800000,"buyAmount":15164.33,"sellAmount":14124.71,"buyContracts":236808,"sellContracts":220458,"oiContracts":467110,"oiAmount":29076.56,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":108113,"totalPutLongContracts":132443,"totalCallShortContracts":136438,"totalPutShortContracts":90116,"sourceHash":"f9fdc6ba2cc85718fbdb2c4701f6c5b93d43fac3b8a2b41be2f2d455a4dcff56"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1775068200000,"buyAmount":11179.8,"sellAmount":10270.17,"buyContracts":175919,"sellContracts":163579,"oiContracts":384538,"oiAmount":23531.76,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":86649,"totalPutLongContracts":104446,"totalCallShortContracts":116341,"totalPutShortContracts":77102,"sourceHash":"2f667aea12966ecc65e3ecbfe49c15377895c2f8b65a833e0eaf3911538a028c"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1777487400000,"buyAmount":15049.55,"sellAmount":23097.41,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"bbc70b2156e43d233c6e2752955407fea99785b524ebac17a441867d05b92bf2"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1777401000000,"buyAmount":14271.22,"sellAmount":16739.64,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"aeb3c7a77557f7f33de5a870e2306e0f2cc5e36979261e3779377649f9e64e32"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1777314600000,"buyAmount":17231.5,"sellAmount":19335.24,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"44af1ef5e0616fec65616abf88646ff6aafe74dbef540ac6c0a5129d08ea2a01"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1777228200000,"buyAmount":30263.32,"sellAmount":31414.8,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"aa58ad79e20c1d8e71df34c827894386fe5f3e634450713cb0ebb8b73f268596"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1776969000000,"buyAmount":9837.2,"sellAmount":18665.07,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"d45b1e675f7c479a80d48eb1a80d3d1f3c73ce94cf35a92fbab45e25d1754e8c"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1776882600000,"buyAmount":12829.12,"sellAmount":16083.83,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"28d3124ad2e823724864b1e44679ab9b5f81e759f31f018e4717de234a5f5386"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1776796200000,"buyAmount":13895.07,"sellAmount":15973.43,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"876f505fc06c06f5cbcc9bf0aeb57db1441b335590b38f02dc5e1f45c39bcbd8"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1776709800000,"buyAmount":13033.17,"sellAmount":14952.16,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"da67ddd7fabb020807ce4e084c5724245e26d80918cf8f07935956daf54ad0f0"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1776623400000,"buyAmount":12756.88,"sellAmount":13816.81,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"b80c9bd1e4809bbf384705dff66bcfc89132d26a11f4ad4deea7b2c82fac715d"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1776364200000,"buyAmount":16034.88,"sellAmount":15351.68,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"26ade220f1d69cb872213c72021b01654e249fa5f6b77bae14c4d818f665608c"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1776277800000,"buyAmount":16209.44,"sellAmount":15827.08,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"e929c17b6098cea325fd3996e092969307750667bca6745cef07a20560861303"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1776191400000,"buyAmount":18075.63,"sellAmount":17409.48,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"f280eee8890163a26711f6cc29eab829236f1e92c055b8025e891f896475a739"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1776018600000,"buyAmount":15382.09,"sellAmount":17365.27,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"a94c8897165610d50e985e02ca95abd4cbffa5a3a6766ea58d24fe7180488cda"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1775759400000,"buyAmount":18303.96,"sellAmount":17631.87,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"924f38d524fafa00ed9beefb9251edf289c1f2f847bef3a8c36fdd3f4eb5b26d"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1775673000000,"buyAmount":15746.32,"sellAmount":17457.51,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"c15b963e945c44eeaace16ac3f6b3c435be931d1fab6b983904bd6cb8c0ad82d"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1775586600000,"buyAmount":19092.05,"sellAmount":21904.02,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"6e80aac2a2e9549882e1d8ae036f3451b46fdd2241d5ab1dc6083fe338b59772"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1775500200000,"buyAmount":7953.46,"sellAmount":16645.57,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"2882ab05886be7f7b65a575f84190a1228fdc798d6e7e25747c0cfb986cadbad"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1775413800000,"buyAmount":8837.64,"sellAmount":17004.81,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"4afdb6b20db6f95d9842b428d35580e3698df94d443e009f4facade686cb0392"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1775068200000,"buyAmount":10626.52,"sellAmount":20557.65,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"d71dc6d08f3d29c6e84cc3e5831e50ee0209a7d1b16d6a3c26e0e3e5699875a0"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1779993000000,"buyAmount":45943.66,"sellAmount":47073.79,"buyContracts":741325,"sellContracts":733581,"oiContracts":7365604,"oiAmount":460332.04,"totalLongContracts":4005908,"totalShortContracts":3359696,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"3fc97b9b9b97d06ce951bcfc1a49b0dc85583326016addd88aa96f950f0d160c"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1779820200000,"buyAmount":18955.42,"sellAmount":19659.22,"buyContracts":290470,"sellContracts":301089,"oiContracts":7452284,"oiAmount":469032.79,"totalLongContracts":4045376,"totalShortContracts":3406908,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"25b2f97abf847fea2cf87e5eefd880d4d3759bc91b43a31225312c154a01bb3d"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1779733800000,"buyAmount":38003.09,"sellAmount":36622.88,"buyContracts":595489,"sellContracts":570933,"oiContracts":7411359,"oiAmount":464329.99,"totalLongContracts":4030223,"totalShortContracts":3381136,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"03bc4e641916ff57093c39a3dc4b9cea8776dcb941c97db3e027e30bcd3a613a"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1779647400000,"buyAmount":118081.48,"sellAmount":115597.36,"buyContracts":1879403,"sellContracts":1844609,"oiContracts":7727712,"oiAmount":484959.81,"totalLongContracts":4199409,"totalShortContracts":3528303,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"39ca93b2b84104977abffc0bf3dc379b31918e32ef7fd9e60ff0494abf130210"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1779388200000,"buyAmount":169457.57,"sellAmount":164983.84,"buyContracts":2728932,"sellContracts":2670076,"oiContracts":7729034,"oiAmount":479369.65,"totalLongContracts":4182673,"totalShortContracts":3546361,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"dcd74760623b0e4e1c69e0d32dac279fe3ca44278a450d6717b6f5acbf686116"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1779301800000,"buyAmount":159884.28,"sellAmount":159741.77,"buyContracts":2594725,"sellContracts":2598525,"oiContracts":7736634,"oiAmount":476278.32,"totalLongContracts":4157045,"totalShortContracts":3579589,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"4f6fc5f9194b24e0012a82faf1438c9ad9c042723ac3a0dad73c0e346a614316"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1779215400000,"buyAmount":34990.1,"sellAmount":35920.16,"buyContracts":559444,"sellContracts":585110,"oiContracts":7785508,"oiAmount":478173.87,"totalLongContracts":4183382,"totalShortContracts":3602126,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"abdbcf46fb7ea0e6479f0f626bbc816320c2902d17d4c3fa65c2e14e9ec4d049"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1779129000000,"buyAmount":24093.53,"sellAmount":25972.17,"buyContracts":381303,"sellContracts":407653,"oiContracts":7713152,"oiAmount":472447.0,"totalLongContracts":4160037,"totalShortContracts":3553115,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"1098a2f9b648cca1a74509db1fb175d50015aca8677829cade2488256df66a98"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1779042600000,"buyAmount":21917.31,"sellAmount":22496.35,"buyContracts":350226,"sellContracts":359085,"oiContracts":7684266,"oiAmount":468055.53,"totalLongContracts":4158769,"totalShortContracts":3525497,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"2e98b5605a62cc379a8fb80ed4090737935cc5b20a2872b2e6d61235898db440"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1778783400000,"buyAmount":20741.67,"sellAmount":21940.1,"buyContracts":333139,"sellContracts":344349,"oiContracts":7656067,"oiAmount":467793.07,"totalLongContracts":4149099,"totalShortContracts":3506968,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"3099734da117985fe63b2288a7866b12cd86c23e49dd8ceb577aafd476fe3d4e"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1778697000000,"buyAmount":23470.81,"sellAmount":23083.97,"buyContracts":370362,"sellContracts":388497,"oiContracts":7617543,"oiAmount":467464.42,"totalLongContracts":4135442,"totalShortContracts":3482101,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"6940ccf88030f0e0523065005b123d4d29d2d035827680e0b97bf10f372baa5e"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1778610600000,"buyAmount":24810.45,"sellAmount":22731.28,"buyContracts":392165,"sellContracts":365045,"oiContracts":7535946,"oiAmount":460387.75,"totalLongContracts":4103711,"totalShortContracts":3432235,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"f60a45ccd4e30c89769d6e3e592bd584f394519417ff42f8fe2a28e3e598057a"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1778524200000,"buyAmount":21355.91,"sellAmount":22006.94,"buyContracts":334981,"sellContracts":349280,"oiContracts":7490868,"oiAmount":456049.31,"totalLongContracts":4067612,"totalShortContracts":3423256,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"0cbcb8af60fff9c08195b2a0741d89ae7ec06542f7d5303a93c7f3638e4a700b"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1778437800000,"buyAmount":22023.48,"sellAmount":23240.59,"buyContracts":325672,"sellContracts":349900,"oiContracts":7424899,"oiAmount":464218.57,"totalLongContracts":4041777,"totalShortContracts":3383122,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"9808cb897eb134f216bf76e2d920340e8a743802570bef5ebbdef6daf1c1dab0"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1778178600000,"buyAmount":21162.65,"sellAmount":25353.81,"buyContracts":314611,"sellContracts":373638,"oiContracts":7401559,"oiAmount":469673.56,"totalLongContracts":4042221,"totalShortContracts":3359338,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"5d08de786729c417fd9067bcedad46c1b4531a318dc720ee87b8f57d640505c2"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1778092200000,"buyAmount":21822.61,"sellAmount":24554.66,"buyContracts":319770,"sellContracts":376900,"oiContracts":7406864,"oiAmount":471902.44,"totalLongContracts":4074387,"totalShortContracts":3332477,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"fee3e79d7bf15ed0379d6c6923cc560cd3f327bcd160156ba94df77c6749ba4b"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1778005800000,"buyAmount":26478.69,"sellAmount":25749.01,"buyContracts":399145,"sellContracts":391377,"oiContracts":7373080,"oiAmount":469871.32,"totalLongContracts":4086060,"totalShortContracts":3287020,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"027b30d49f110b2227af809549311d7b7f8547dd02bbfb0d9b63b57bb045aa9b"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1777919400000,"buyAmount":20391.55,"sellAmount":21066.11,"buyContracts":305088,"sellContracts":319654,"oiContracts":7327780,"oiAmount":460039.85,"totalLongContracts":4059526,"totalShortContracts":3268254,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"2e2a18dede4a2a7aded2182f005503e3509c588bbbc62eca78ad757e865547d2"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1777833000000,"buyAmount":24351.6,"sellAmount":23525.25,"buyContracts":356937,"sellContracts":349905,"oiContracts":7283052,"oiAmount":457284.13,"totalLongContracts":4044445,"totalShortContracts":3238607,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"a4f7d94e5cf189f605f370fbbff9e63019d7ba8c88278cf016922b266837b6d4"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1779993000000,"buyAmount":1094401.38,"sellAmount":1099772.59,"buyContracts":7017508,"sellContracts":7056687,"oiContracts":2600970,"oiAmount":402408.27,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":553247,"totalPutLongContracts":842769,"totalCallShortContracts":823871,"totalPutShortContracts":381083,"sourceHash":"33c93c32543c65f3b0de37512ffae232b14536f36abfa8f7492ae514fa712327"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1779820200000,"buyAmount":554682.58,"sellAmount":555013.3,"buyContracts":3543149,"sellContracts":3547295,"oiContracts":2159019,"oiAmount":339005.3,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":426192,"totalPutLongContracts":768438,"totalCallShortContracts":633361,"totalPutShortContracts":331028,"sourceHash":"43834408a264e5c6ee19b24ba5952ccf8e5baa610b33d89e53add7b754eb9c44"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1779733800000,"buyAmount":3440544.8,"sellAmount":3478562.51,"buyContracts":21670897,"sellContracts":21910333,"oiContracts":1769228,"oiAmount":278180.98,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":320160,"totalPutLongContracts":681647,"totalCallShortContracts":520515,"totalPutShortContracts":246905,"sourceHash":"e2d575bbab23d31c6f8476a7616dc99ec888cf9ee35cac204523fbbaab836b57"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1779647400000,"buyAmount":1297756.48,"sellAmount":1285074.46,"buyContracts":8208191,"sellContracts":8128579,"oiContracts":3337091,"oiAmount":529400.5,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":799957,"totalPutLongContracts":1024401,"totalCallShortContracts":843791,"totalPutShortContracts":668943,"sourceHash":"4c74e16d30e88ccf12186ad78289105247e6955fbb1083ebac462cb480f8f4ff"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1779388200000,"buyAmount":891073.54,"sellAmount":879018.42,"buyContracts":5690787,"sellContracts":5617160,"oiContracts":2928710,"oiAmount":458670.19,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":653857,"totalPutLongContracts":926504,"totalCallShortContracts":815806,"totalPutShortContracts":532543,"sourceHash":"e1e57f395bc004c04438d6299686b8258d90c06082a988b9b8c25bee50644a6b"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1779301800000,"buyAmount":804018.96,"sellAmount":803332.82,"buyContracts":5174288,"sellContracts":5172219,"oiContracts":2809846,"oiAmount":437199.86,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":564691,"totalPutLongContracts":919425,"totalCallShortContracts":820753,"totalPutShortContracts":504978,"sourceHash":"0603415b9c7cd154ac615c26a0f6554526d10cdec7ec6ffa499af7c6837a99b0"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1779215400000,"buyAmount":706114.09,"sellAmount":716249.46,"buyContracts":4542269,"sellContracts":4608422,"oiContracts":2435035,"oiAmount":379169.82,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":441464,"totalPutLongContracts":854211,"totalCallShortContracts":663620,"totalPutShortContracts":475740,"sourceHash":"262f7387fc09213631c6a69140318f3979fccf2e767efd91ecf385a655e9e13c"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1779129000000,"buyAmount":3184215.11,"sellAmount":3211466.15,"buyContracts":20635541,"sellContracts":20816453,"oiContracts":2217183,"oiAmount":344582.58,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":406028,"totalPutLongContracts":813798,"totalCallShortContracts":611362,"totalPutShortContracts":385995,"sourceHash":"43e503f60e01621dee79debc4c8c39b0c0df4b8a93c338b5405abe7b08a8e23b"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1779042600000,"buyAmount":1608003.87,"sellAmount":1600256.49,"buyContracts":10461032,"sellContracts":10406956,"oiContracts":3024351,"oiAmount":468808.3,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":517083,"totalPutLongContracts":1089492,"totalCallShortContracts":731449,"totalPutShortContracts":686328,"sourceHash":"da683d617ee5f91863ff93fe27432590fa1bee11f9b022b55e2da7685456295f"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1778783400000,"buyAmount":1124260.11,"sellAmount":1126393.59,"buyContracts":7248342,"sellContracts":7261201,"oiContracts":2739544,"oiAmount":425175.17,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":532822,"totalPutLongContracts":904311,"totalCallShortContracts":789341,"totalPutShortContracts":513070,"sourceHash":"74d706a8d9e748905f3993b28ad46a1286963e0078f021e357b2c22e44aa285b"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1778697000000,"buyAmount":727977.9,"sellAmount":734634.84,"buyContracts":4705278,"sellContracts":4747733,"oiContracts":2430007,"oiAmount":378564.02,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":424050,"totalPutLongContracts":864744,"totalCallShortContracts":656098,"totalPutShortContracts":485115,"sourceHash":"f2dcea38fb6fbbf9c95afa5532cd8771eb25ee77bca619e9bd25a55ae22a28bf"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1778610600000,"buyAmount":651824.36,"sellAmount":657576.58,"buyContracts":4230690,"sellContracts":4266600,"oiContracts":2331683,"oiAmount":359072.26,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":428267,"totalPutLongContracts":832593,"totalCallShortContracts":682279,"totalPutShortContracts":388545,"sourceHash":"aace842c4803498b5f9efd6714d1603d2f00efed89368cdfe7294f17dc7ee26f"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1778524200000,"buyAmount":2842619.23,"sellAmount":2848379.49,"buyContracts":18436209,"sellContracts":18482668,"oiContracts":2144896,"oiAmount":330161.16,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":373291,"totalPutLongContracts":812130,"totalCallShortContracts":623294,"totalPutShortContracts":336181,"sourceHash":"33244a824acd70c7d9d7c69f03a553197474bf31d9e69a27ad26bc438c125b80"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1778437800000,"buyAmount":1351538.38,"sellAmount":1354718.0,"buyContracts":8636103,"sellContracts":8658082,"oiContracts":2652125,"oiAmount":414522.7,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":523725,"totalPutLongContracts":859256,"totalCallShortContracts":822482,"totalPutShortContracts":446661,"sourceHash":"c2e646e571abd63020abbe6e094b687f4bd74b5c8e7d1aa141c708dab822c41c"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1778178600000,"buyAmount":905591.95,"sellAmount":902623.61,"buyContracts":5711420,"sellContracts":5696888,"oiContracts":2443340,"oiAmount":388025.89,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":472541,"totalPutLongContracts":817039,"totalCallShortContracts":722675,"totalPutShortContracts":431086,"sourceHash":"d0f90dc5ac8247c3326d88fa8b6e3dd4318af7637c94738c3c82443ae4c0cd4b"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1778092200000,"buyAmount":739954.96,"sellAmount":747238.64,"buyContracts":4639963,"sellContracts":4684661,"oiContracts":2153916,"oiAmount":344787.71,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":356458,"totalPutLongContracts":781143,"totalCallShortContracts":613358,"totalPutShortContracts":402957,"sourceHash":"936746e8aac58ef81dcea24d8ed81e5af786ecc2726367c10fae6eb3690981ea"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1778005800000,"buyAmount":852162.62,"sellAmount":853472.77,"buyContracts":5394369,"sellContracts":5403192,"oiContracts":2075173,"oiAmount":331933.91,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":368811,"totalPutLongContracts":751767,"totalCallShortContracts":559231,"totalPutShortContracts":395364,"sourceHash":"5eba5b62c5cd6991fc7bd25a41fa73f09bcf823e762825f335369bc27f57b8da"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1777919400000,"buyAmount":3676612.75,"sellAmount":3703779.04,"buyContracts":23492302,"sellContracts":23661914,"oiContracts":1785307,"oiAmount":281394.16,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":289256,"totalPutLongContracts":690801,"totalCallShortContracts":496740,"totalPutShortContracts":308510,"sourceHash":"462a0a04def52939567252b5095b8f4b5bf5be38a26eede041b9c80e958452d2"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1777833000000,"buyAmount":1939940.28,"sellAmount":1935304.7,"buyContracts":12312942,"sellContracts":12287303,"oiContracts":2568707,"oiAmount":405114.15,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":563705,"totalPutLongContracts":802982,"totalCallShortContracts":750271,"totalPutShortContracts":451750,"sourceHash":"0dd2b11831dcba13feb8b8e1928275464672a72e46ce6da5e18ab3c5221310e6"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1779993000000,"buyAmount":1656.52,"sellAmount":7655.91,"buyContracts":10275,"sellContracts":48572,"oiContracts":264757,"oiAmount":41886.85,"totalLongContracts":31724,"totalShortContracts":233033,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"119ae9b44999ffb9981cca2084497261dc0845650b17016d59df9789fcbb2b9a"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1779820200000,"buyAmount":1259.85,"sellAmount":2851.57,"buyContracts":7792,"sellContracts":17939,"oiContracts":240740,"oiAmount":38610.9,"totalLongContracts":38864,"totalShortContracts":201876,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"6877714933b64ee8b829ba3c4caad64a63c20b14f1b317a5eba19c474969afe5"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1779733800000,"buyAmount":5098.7,"sellAmount":8362.25,"buyContracts":31918,"sellContracts":52644,"oiContracts":229387,"oiAmount":36850.67,"totalLongContracts":38261,"totalShortContracts":191126,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"6dba6f74447b574de95a103c049deb0bcbd31937333a107f4aa59d36e7f00d10"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1779647400000,"buyAmount":9720.81,"sellAmount":8125.43,"buyContracts":60499,"sellContracts":50484,"oiContracts":317357,"oiAmount":50947.99,"totalLongContracts":51190,"totalShortContracts":266167,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"6d080c80dd221be6bdebf9b4ce3e8f288f55761922dbe27fd48562a459241c1b"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1779388200000,"buyAmount":11534.62,"sellAmount":10545.47,"buyContracts":72238,"sellContracts":66040,"oiContracts":312052,"oiAmount":49253.88,"totalLongContracts":43530,"totalShortContracts":268522,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"7da2ae7a267c49df6465c3e7bc11223ab3aeac7eb17b38243d3dfa2faec99462"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1779301800000,"buyAmount":5600.9,"sellAmount":6950.05,"buyContracts":34835,"sellContracts":43505,"oiContracts":302570,"oiAmount":47395.9,"totalLongContracts":35690,"totalShortContracts":266880,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"a7669bc1317f1530bc25806afc1e4678f660d0d2dde9f2b89f2cbffaf35eb1f1"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1779215400000,"buyAmount":1955.92,"sellAmount":2920.88,"buyContracts":12474,"sellContracts":18714,"oiContracts":292706,"oiAmount":45938.38,"totalLongContracts":35093,"totalShortContracts":257613,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"8e4d9b1b5e2d38ecb7d27ca049363288257eccf69c6a09bb6e9d96cc361c6005"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1779129000000,"buyAmount":2049.19,"sellAmount":3047.82,"buyContracts":12958,"sellContracts":19372,"oiContracts":286756,"oiAmount":44865.67,"totalLongContracts":35238,"totalShortContracts":251518,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"ba6fa726c351161d56b6b193812f30c9ebcbd49d3d1516b8a069aa9640a12df3"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1779042600000,"buyAmount":4364.63,"sellAmount":3362.67,"buyContracts":28182,"sellContracts":21541,"oiContracts":279710,"oiAmount":43823.27,"totalLongContracts":34922,"totalShortContracts":244788,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"b169f48aa41d7e460c844dd4237ff1d7fb0ec132591a9b644514623fa0db8ead"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1778783400000,"buyAmount":4188.02,"sellAmount":2794.84,"buyContracts":26853,"sellContracts":17749,"oiContracts":288159,"oiAmount":45123.02,"totalLongContracts":35826,"totalShortContracts":252333,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"05fff9c7336246a8e157a751b44848274f1b1386d0bbf888cb30b62acdcb3c31"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1778697000000,"buyAmount":4564.03,"sellAmount":2651.51,"buyContracts":29109,"sellContracts":16846,"oiContracts":295759,"oiAmount":46451.86,"totalLongContracts":35074,"totalShortContracts":260685,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"5c58190434ee5fa8d9963da080ba91f1d5637a314812c0272d05d5e93f992d0a"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1778610600000,"buyAmount":2767.16,"sellAmount":2894.74,"buyContracts":17713,"sellContracts":18443,"oiContracts":303616,"oiAmount":47144.32,"totalLongContracts":32871,"totalShortContracts":270745,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"fc3e8ee3f47e36e515e8be61d10e4e5ddb936d104475cfb7fb8ff9be073c5db3"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1778524200000,"buyAmount":2623.34,"sellAmount":5131.16,"buyContracts":16848,"sellContracts":33023,"oiContracts":304670,"oiAmount":47236.3,"totalLongContracts":33763,"totalShortContracts":270907,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"966408ef9bea456c840d91298cf8beca37aaa4c47023c7037e220313b2fe6d41"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1778437800000,"buyAmount":2252.23,"sellAmount":3937.64,"buyContracts":14113,"sellContracts":24970,"oiContracts":287717,"oiAmount":45486.61,"totalLongContracts":33374,"totalShortContracts":254343,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"ce87c59fc034d943b5824a1c064a2b3a869a320cb47693279df92859cf495f8c"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1778178600000,"buyAmount":1379.87,"sellAmount":3657.63,"buyContracts":8501,"sellContracts":22665,"oiContracts":279344,"oiAmount":44880.9,"totalLongContracts":34616,"totalShortContracts":244728,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"b8b039c097cb5845d1a3f6da1b8eab63b43508f78f87772518ba3a83f6aa07e9"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1778092200000,"buyAmount":1986.18,"sellAmount":2163.58,"buyContracts":11914,"sellContracts":13267,"oiContracts":270302,"oiAmount":43783.64,"totalLongContracts":37177,"totalShortContracts":233125,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"1cb14b3060435c77221ea260ec5eba9269f92a474f5bd87520079b9cf594b247"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1778005800000,"buyAmount":3826.79,"sellAmount":3192.0,"buyContracts":23401,"sellContracts":19863,"oiContracts":266095,"oiAmount":43163.83,"totalLongContracts":35750,"totalShortContracts":230345,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"3ee6b8fd4a79a52700560ef83e3eceaf5270a8b898e9844ac06e2fa913fe0f13"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1777919400000,"buyAmount":1233.83,"sellAmount":2105.35,"buyContracts":7723,"sellContracts":13284,"oiContracts":257331,"oiAmount":40983.83,"totalLongContracts":29599,"totalShortContracts":227732,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"f3cc33ad158cda847d4cbccc7d58bfa7182bde06fa4c232c7710bb8bb42e7fc5"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1777833000000,"buyAmount":1886.22,"sellAmount":3157.34,"buyContracts":11645,"sellContracts":19741,"oiContracts":251188,"oiAmount":40171.74,"totalLongContracts":29308,"totalShortContracts":221880,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"61211299830366a7d10949f186cb03b10942e2dc5e54c7488baff107234b10e1"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1779993000000,"buyAmount":20421.79,"sellAmount":21683.15,"buyContracts":322513,"sellContracts":340637,"oiContracts":449650,"oiAmount":28525.2,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":77080,"totalPutLongContracts":125449,"totalCallShortContracts":151398,"totalPutShortContracts":95723,"sourceHash":"eb762ff3955090144a2aaa28cb21f217d966ab8da2b2e7fd42005616859d184c"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1779820200000,"buyAmount":16071.2,"sellAmount":16577.34,"buyContracts":252380,"sellContracts":259785,"oiContracts":347048,"oiAmount":22290.28,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":60047,"totalPutLongContracts":100243,"totalCallShortContracts":115467,"totalPutShortContracts":71291,"sourceHash":"fc5e0c58dde86bfe29af088468d86a082c6f9d6b8cafec00f70ad824ce135cd8"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1779733800000,"buyAmount":15305.37,"sellAmount":17326.88,"buyContracts":237892,"sellContracts":266971,"oiContracts":253265,"oiAmount":16299.68,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":43016,"totalPutLongContracts":74085,"totalCallShortContracts":85655,"totalPutShortContracts":50509,"sourceHash":"a4b887dad63d3d20f62dd1de104ef5eabb35fbb0e577647f58064691b8e079c9"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1779647400000,"buyAmount":39364.13,"sellAmount":39174.2,"buyContracts":578967,"sellContracts":576120,"oiContracts":1100988,"oiAmount":72687.37,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":231621,"totalPutLongContracts":294910,"totalCallShortContracts":348010,"totalPutShortContracts":226447,"sourceHash":"9d2681e3d01a5b1e9eb343637ecae805fcb3cdb360c91d0138314714a508a1e7"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1779388200000,"buyAmount":52766.61,"sellAmount":52402.64,"buyContracts":829981,"sellContracts":827415,"oiContracts":1155731,"oiAmount":75143.46,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":244303,"totalPutLongContracts":308176,"totalCallShortContracts":366368,"totalPutShortContracts":236884,"sourceHash":"ec282c891fbe45e98167c604937dcaed40d1b84c8450566a4661e8efa7d823fd"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1779301800000,"buyAmount":52114.08,"sellAmount":51254.26,"buyContracts":808626,"sellContracts":795871,"oiContracts":1170523,"oiAmount":75482.05,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":246627,"totalPutLongContracts":311965,"totalCallShortContracts":374615,"totalPutShortContracts":237316,"sourceHash":"8581ad88a42104655b14f0e8185d41e7953fc61222feb179319c3bfb27a91525"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1779215400000,"buyAmount":48315.94,"sellAmount":48634.87,"buyContracts":746688,"sellContracts":754127,"oiContracts":1168434,"oiAmount":75290.96,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":241966,"totalPutLongContracts":309204,"totalCallShortContracts":376650,"totalPutShortContracts":240614,"sourceHash":"476f2af369f4bec4d93202d35179c03dfd4e09a975cd60580938300940d2cf8b"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1779129000000,"buyAmount":44532.0,"sellAmount":44446.06,"buyContracts":693571,"sellContracts":689122,"oiContracts":1192209,"oiAmount":76623.75,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":252804,"totalPutLongContracts":313973,"totalCallShortContracts":381233,"totalPutShortContracts":244199,"sourceHash":"9491be6c17215f7506b48e531958a039c72e03b4104229c85568e9a810f0bcce"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1779042600000,"buyAmount":40301.81,"sellAmount":40500.82,"buyContracts":634355,"sellContracts":630762,"oiContracts":1139870,"oiAmount":72679.27,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":236516,"totalPutLongContracts":301867,"totalCallShortContracts":370828,"totalPutShortContracts":230659,"sourceHash":"2ca58c1709a1d7e07f8fac3d6e2164443023474ae74b191d8e2f9e021bc39343"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1778783400000,"buyAmount":34494.04,"sellAmount":34525.75,"buyContracts":547953,"sellContracts":538625,"oiContracts":1066067,"oiAmount":68180.4,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":218916,"totalPutLongContracts":280769,"totalCallShortContracts":348988,"totalPutShortContracts":217394,"sourceHash":"2faaaff08b5f25d1d42df6a376bd3be86dfce233836d7e517ed3057e32041305"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1778697000000,"buyAmount":36203.86,"sellAmount":37956.83,"buyContracts":563978,"sellContracts":592470,"oiContracts":993829,"oiAmount":63813.54,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":198046,"totalPutLongContracts":260856,"totalCallShortContracts":335171,"totalPutShortContracts":199756,"sourceHash":"2e911acfef45c64ae1fcd490134c17edad24b0303e7148b6e7a11318e369ee57"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1778610600000,"buyAmount":31544.44,"sellAmount":31121.73,"buyContracts":485948,"sellContracts":481337,"oiContracts":922559,"oiAmount":59018.17,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":183325,"totalPutLongContracts":254188,"totalCallShortContracts":306611,"totalPutShortContracts":178435,"sourceHash":"3cd7d9ce5a2d3fff2b78da05dda0f386699cab8b9139c0ee9a904864aeeab921"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1778524200000,"buyAmount":29242.74,"sellAmount":29127.44,"buyContracts":451011,"sellContracts":450442,"oiContracts":856236,"oiAmount":54638.53,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":166814,"totalPutLongContracts":235232,"totalCallShortContracts":286866,"totalPutShortContracts":167324,"sourceHash":"ec53905c583176a13a52a78ff719b656b8e4105556a8745f6ba41ef07d64b21d"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1778437800000,"buyAmount":28620.89,"sellAmount":28939.2,"buyContracts":404802,"sellContracts":409058,"oiContracts":792841,"oiAmount":51734.97,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":163799,"totalPutLongContracts":206265,"totalCallShortContracts":260004,"totalPutShortContracts":162773,"sourceHash":"6e83ad1d84499cf66acafd140bb715f5efaffe26c6a2d8b60f0314b8e6700b37"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1778178600000,"buyAmount":25143.96,"sellAmount":25871.21,"buyContracts":360454,"sellContracts":368691,"oiContracts":726967,"oiAmount":47966.75,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":157287,"totalPutLongContracts":181968,"totalCallShortContracts":233957,"totalPutShortContracts":153755,"sourceHash":"1dcc08852633aecd448a8768edd361f44fb4975107730d7eb69f7c645226ea22"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1778092200000,"buyAmount":23247.95,"sellAmount":23253.77,"buyContracts":343196,"sellContracts":343105,"oiContracts":670936,"oiAmount":44145.75,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":145968,"totalPutLongContracts":169390,"totalCallShortContracts":214376,"totalPutShortContracts":141202,"sourceHash":"c2d8c4a04412d2c746967a0ad350e3e037712fb64d756dd70f6531d9c4c32619"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1778005800000,"buyAmount":27451.32,"sellAmount":27472.76,"buyContracts":423913,"sellContracts":425579,"oiContracts":613427,"oiAmount":40186.02,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":129453,"totalPutLongContracts":157105,"totalCallShortContracts":199718,"totalPutShortContracts":127151,"sourceHash":"7ef48e013c2e899b663dfe797f4063983b0ccf22bb6db6c16c4e042fd5546167"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1777919400000,"buyAmount":17873.07,"sellAmount":18255.17,"buyContracts":269946,"sellContracts":275693,"oiContracts":515227,"oiAmount":33320.57,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":103483,"totalPutLongContracts":134808,"totalCallShortContracts":175780,"totalPutShortContracts":101156,"sourceHash":"59f7fd90b37d6821cb6a9076562d48ef5a7437a8fcd979d9765eff8612dd7e52"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1777833000000,"buyAmount":18978.19,"sellAmount":19953.76,"buyContracts":284885,"sellContracts":300260,"oiContracts":469718,"oiAmount":30351.47,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":94204,"totalPutLongContracts":124206,"totalCallShortContracts":158038,"totalPutShortContracts":93270,"sourceHash":"f4bd992b1d984f54e6caea21c9ae181e08ab46887ae40cfd517698f3fa261dc7"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1779993000000,"buyAmount":89733.64,"sellAmount":110839.5,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"ffce5683acd58933b1acd9b8498e3d776766355651279c0937305a41879cbdc3"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1779820200000,"buyAmount":11418.65,"sellAmount":12461.35,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"75f1913dec248c29ecf16ec8f6c518a5c56a5b6810302a561f1f326bdca326e1"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1779733800000,"buyAmount":13127.02,"sellAmount":15534.89,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"7fbfa8467fbb38ce9bb0a443488f6251ca2ea186b8de1fe7d7d77a35098f788b"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1779647400000,"buyAmount":12083.12,"sellAmount":11261.37,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"dd1ea5a94b8efe8ac7ee771fa559dd0eb4039d78c7c40aadc21ef1adcc4f5b9f"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1779388200000,"buyAmount":10972.76,"sellAmount":15413.23,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"e57f1c11ce40449bd97731702071b9980b81f5f9b00e6be510cba6aba8a5fb84"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1779301800000,"buyAmount":12322.16,"sellAmount":14213.37,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"f82e0228f5822f50330ad5883e7eeb8521097285cfca2b09f630b06b48f87b49"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1779215400000,"buyAmount":14139.56,"sellAmount":15736.91,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"deec5b110fcc901c3e440b1603f95bfe3a58bd87db13af6eac566bcc860ce55c"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1779129000000,"buyAmount":17907.55,"sellAmount":20365.04,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"8990763b804bed255f13e018695ed9ed103503ab0042103821c67fd80a1fdaf7"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1779042600000,"buyAmount":17222.18,"sellAmount":14408.49,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"8aba1f55618c5350237db2b14eed3e40c0999cfabf3d5d0733615387740b45a1"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1778783400000,"buyAmount":16299.6,"sellAmount":14970.43,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"9cf0bf5c4a506655955147454bf48a04352d9ad398da02961e9e99429567894d"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1778697000000,"buyAmount":17350.56,"sellAmount":17163.1,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"0e9e260540b18506f87e00be3fe15b53bd2cfd24546cf3398fbc667a7a8af692"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1778610600000,"buyAmount":14373.99,"sellAmount":19077.14,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"b6441f96f1852bf23b6c04374effb56625b00ab178156bd57c8044368c94eb72"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1778524200000,"buyAmount":16555.27,"sellAmount":18514.66,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"2fe94b588a0f2ee645094e6247617dd5bbac457eb88eafe8cd30545ae6d3c2d5"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1778437800000,"buyAmount":12813.74,"sellAmount":21251.3,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"fc7f5551c3222b9fd1020195b0c6ee6008dd31387ea675e82aa752e56f82a1b0"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1778178600000,"buyAmount":15083.49,"sellAmount":19194.09,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"aa289f056a6352900b4192556711a33f6a56dd705f8fe33d9d1705b87c5c379f"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1778092200000,"buyAmount":17997.95,"sellAmount":18338.84,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"7f80c228a7b14eb9c4064786ab8e0136cbda247a55c5328418234f2c0a5b7f39"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1778005800000,"buyAmount":14459.21,"sellAmount":20294.11,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"46651166510441255ec2d3ddacd6976f6aa75871b6670e585628117c6f707a79"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1777919400000,"buyAmount":10392.91,"sellAmount":14014.49,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"b4e46e80827ffa994b12d88c77ea84266ac2a0c0d5100d1f5298478a750b6e10"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1777833000000,"buyAmount":19660.47,"sellAmount":16824.85,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"d116a1597da6415e4fd7c131dbe98fa4d334bca7f7196051ecbd85e790599508"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1774981800000,"buyAmount":18536.73,"sellAmount":11364.93,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"44b2f7fe53bcb719b74c1eba0bfb698d693ae1110dfec32c27a61584dc3e7376"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1777487400000,"buyAmount":18252.89,"sellAmount":14765.79,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"ecddcb7152d898ef68fdae962f5d0226cfc75f6297f46d9bb72cb36907a11cac"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1777401000000,"buyAmount":17232.28,"sellAmount":14970.11,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"4b9fc983bb23e388a06a313bdbf08430c3931f3981e3fbc272ec10ce31c6425f"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1777314600000,"buyAmount":18044.05,"sellAmount":16332.04,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"d625e22e86442912eb616e24c700dacca330904440fb2895a19ea9069ea62076"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1777228200000,"buyAmount":19978.34,"sellAmount":15854.42,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"c3479d394b383f5d2ccafabf00ec0382f6c5bf016db9fe0275964f161ee896f9"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1776969000000,"buyAmount":21560.16,"sellAmount":16859.45,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"62e7b5d730e4b8a254f32a44e2551e1b40e9b68a55aa1ef1311c8fc856ab4d90"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1776882600000,"buyAmount":18498.19,"sellAmount":17556.84,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"1b3fb24b220835629d8600b37a2bd05729ab9c99bacc5727e9febb975ae3600c"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1776796200000,"buyAmount":18704.25,"sellAmount":19752.42,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"31a61e7de959186597356319285b790304e58346c9ba3a288dfecfa5f85b3932"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1776709800000,"buyAmount":18366.67,"sellAmount":16145.4,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"98d60d27b3c750a8502b049c85f349071ec37a29c5ee9ebf0dc82179cacbc92b"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1776623400000,"buyAmount":18753.06,"sellAmount":15786.17,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"ab2b6da91c4584a3440140d9e9188df14e097c30fd064f9fff24bbdfdbd5e3db"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1776364200000,"buyAmount":17513.99,"sellAmount":22235.47,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"73226d2c49831338e492af099dbbe5efe25e3e60617477eecad8885801771e91"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1776277800000,"buyAmount":16538.08,"sellAmount":19965.83,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"0502e6e01240d8d01e1f855d190b24fc84aa3b75142b40824767bff85d635fdc"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1776191400000,"buyAmount":18499.57,"sellAmount":19068.55,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"7165712a08fa2cbca5a9e6368529f4396772f81a5e3989f1186f3005bbe49c19"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1776018600000,"buyAmount":16612.03,"sellAmount":14179.73,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"3ae78ac87289ff6fdd77960677a3cb57fe0684f92e2884c0b9c053c8fa4e69df"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1775759400000,"buyAmount":15982.46,"sellAmount":15572.41,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"e8332b45437fa00878a1e10d031d51d5960bb9c5ec7207aa8b829cb3355903d8"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1775673000000,"buyAmount":15968.21,"sellAmount":15012.31,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"1730e675025add9a61a966376191296afc3a9d23ed95597a7d06527582ef4a30"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1775586600000,"buyAmount":29003.39,"sellAmount":24835.22,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"d6ba28fe41286b487758698d37889ff4278cf894aa8a0006f9fef929f61552b8"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1775500200000,"buyAmount":20860.09,"sellAmount":12880.59,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"0aed97dffe926919862eeb867617ad5e76c11bc0848347ad3c61fad901cd0ca1"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1775413800000,"buyAmount":20445.57,"sellAmount":12356.87,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"280da5966af9dbb01ccf05848299f4c101f834ce38b7cf49f2b5f5339c4c5230"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1775068200000,"buyAmount":18421.27,"sellAmount":11212.86,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"ae0345b16bc17c1320b4f9880c73f359c05969f7d36f2632958b7edd95825e03"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1779993000000,"buyAmount":36999.7,"sellAmount":20235.56,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"f282d454f76f9d53dfd9aae659a3ee95d4da5f04e1617f91cf76c293f3134e90"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1779820200000,"buyAmount":16893.1,"sellAmount":13072.1,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"3f7bb0040a36651c9f0206880d2b7f4ac1bed6572d78bf790470640721c6df1b"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1779733800000,"buyAmount":15536.74,"sellAmount":14175.31,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"fdee48b73b869978d1e20a8159fb658950f1a058bb47b81f17b0c420fcc96c1b"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1779647400000,"buyAmount":16434.96,"sellAmount":12578.08,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"f90ce134b514a46607f7a675d82de750d8e80e251230a67fd0334e1a547df542"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1779388200000,"buyAmount":18436.72,"sellAmount":12433.19,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"b852ef171fccdfb9cfaa507812f9bdf4980f82b739ac69c3b596a0c7578dfa06"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1779301800000,"buyAmount":15857.03,"sellAmount":13364.61,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"2b2175bac6775dbd12825f68e3bbdd3a81e1c5a6961ea462230d8149f1909720"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1779215400000,"buyAmount":16000.79,"sellAmount":14032.44,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"0f09ff40405feb6294db2cdf3ab36d711fa8331fea4dc9cc332e3a5d6648599e"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1779129000000,"buyAmount":16951.95,"sellAmount":13150.27,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"009530155e08d6e0802a0ca0dc64bfefae5a39407cd544063bfe39c9d9d1d0fe"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1779042600000,"buyAmount":16844.94,"sellAmount":14162.82,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"96adf7f3e86b89d5e0051fffdf745045bc444439743213e65ed370e20781719e"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1778783400000,"buyAmount":14961.88,"sellAmount":16920.7,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"525595eacbb8cd59ccb1e61afaf28ad370a030fae505d14d8eec27ea9f04857e"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1778697000000,"buyAmount":18255.96,"sellAmount":17571.63,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"5a430648b1441a0f5d8e0a49ada936f5d642eee779ec4fc08f0ecc66cab1f75d"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1778610600000,"buyAmount":18872.65,"sellAmount":13003.6,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"74dbf9855fc7fdbb82161cd0e44f261ecc95c5bbe042a72e112110c9a40e0a83"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1778524200000,"buyAmount":20684.82,"sellAmount":12694.5,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"04bd12bf656d926d2c6fa02347a8fd997e9a69f782140ad217ebd9833d2cbe8c"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1778437800000,"buyAmount":21626.43,"sellAmount":15686.78,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"ac2c84132cd2c468223c934f0b4b7855273c5a677c336303768faa0204b990ba"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1778178600000,"buyAmount":21296.87,"sellAmount":14548.74,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"e94e8abf401a4bb21aaf52c578dfc3e7752bf1736540a2b4db6f05987ac38668"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1778092200000,"buyAmount":17032.05,"sellAmount":16590.98,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"b8143c2553e30ab745c4a4002ba190a9e2f3128c8b96a890e9351ffab0dbfef7"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1778005800000,"buyAmount":22888.16,"sellAmount":16051.29,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"8123cfdff9c928e798c878f89d0a9351026e571931752753a6e5cfb58d73dfd7"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1777919400000,"buyAmount":16234.31,"sellAmount":13631.69,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"d343cec95955671a8b684ddbd6b42e302e0f1b3474941b8aeb93acbfb31807f2"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1777833000000,"buyAmount":19516.11,"sellAmount":14751.95,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"17ace6819fab6bd6c187a378c3d72336da52708d0a369c5b0427c14c821af58d"}
```

```json
// File: fiidii/FII-DII.metadata.json
{
  "totalRecords" : 234,
  "fiiRecords" : 195,
  "diiRecords" : 39,
  "lastUpdated" : 1781586425753,
  "latestTimestamp" : 1779993000000,
  "latestFiiDate" : "2026-05-29",
  "latestDiiDate" : "2026-05-29"
}
```
