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
                        
                        record.setSourceHash(com.vega.fiidii.util.HashUtil.generateSourceHash(
                                record.getCategory(), record.getDataType(), record.getTimeStamp(),
                                record.getBuyAmount(), record.getSellAmount()
                        ));
                        
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
        
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        boolean bootstrapRequired = (latestFii == null || latestFii.isBefore(today)) ||
                                    (latestDii == null || latestDii.isBefore(today));
        
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
        loadArchive();
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
                .atZone(ZoneId.systemDefault())
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

    public FiiDiiBootstrapService(FiiDiiArchiveService archiveService, UpstoxFiiDiiClient upstoxClient, FiiDiiConfigService configService) {
        this.archiveService = archiveService;
        this.upstoxClient = upstoxClient;
        this.configService = configService;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class HashUtil {
    public static String generateSourceHash(String category, String dataType, long timeStamp, double buyAmount, double sellAmount) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String input = category + dataType + timeStamp + buyAmount + sellAmount;
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
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1774981800000,"buyAmount":23167.05,"sellAmount":19967.74,"buyContracts":379347,"sellContracts":329264,"oiContracts":7203663,"oiAmount":425483.5,"totalLongContracts":4118208,"totalShortContracts":3085455,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"c8647c4408fd8b04921422aea75c4ac004a74a57e17755d52775643ab71365f0"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1774981800000,"buyAmount":611507.21,"sellAmount":615967.2,"buyContracts":4070312,"sellContracts":4099523,"oiContracts":2055697,"oiAmount":305041.81,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":376141,"totalPutLongContracts":702669,"totalCallShortContracts":630531,"totalPutShortContracts":346355,"sourceHash":"33f85863a6114e94f46093630f367b23d6be1d322a67ae029790d073292e0139"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1774981800000,"buyAmount":5079.42,"sellAmount":5157.91,"buyContracts":33590,"sellContracts":34365,"oiContracts":391771,"oiAmount":58555.41,"totalLongContracts":63475,"totalShortContracts":328296,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"84a4538e70c60b74c9c343e114a5b67abe5bc68ed21df3a8ce178a1517f9b0b5"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1774981800000,"buyAmount":10277.74,"sellAmount":10368.61,"buyContracts":159495,"sellContracts":163821,"oiContracts":303206,"oiAmount":18534.78,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":59035,"totalPutLongContracts":85224,"totalCallShortContracts":97070,"totalPutShortContracts":61877,"sourceHash":"9cabcb6b113daad6b80f13856c1898f4690784d88a9ea27c690a22798426b48a"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1774981800000,"buyAmount":17958.07,"sellAmount":26289.22,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"6b77c1a41bb68d52a77343b83873369deb1536ca5f91e17d01143380eb22d122"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1777487400000,"buyAmount":23109.75,"sellAmount":24642.52,"buyContracts":353981,"sellContracts":384079,"oiContracts":7245154,"oiAmount":452650.0,"totalLongContracts":4021980,"totalShortContracts":3223174,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"1c5b6cc77777566607ca325d499f9303b9d917a6a4fdf8bde7881856c76f2e0a"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1777401000000,"buyAmount":21593.35,"sellAmount":21252.58,"buyContracts":327164,"sellContracts":318686,"oiContracts":7237618,"oiAmount":456065.1,"totalLongContracts":4033261,"totalShortContracts":3204357,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"a9f3b25e7c8a4d588919a1bc91491761086a266f1d0f6a9688fd779bf932e981"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1777314600000,"buyAmount":39923.8,"sellAmount":42987.41,"buyContracts":611176,"sellContracts":653578,"oiContracts":7132580,"oiAmount":452808.03,"totalLongContracts":3963869,"totalShortContracts":3168711,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"740e096da868aebd0ee9c63df995adfcbb76a4b7fcc9b6c4c63e8d7be9a607db"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1777228200000,"buyAmount":121677.19,"sellAmount":120184.08,"buyContracts":1902647,"sellContracts":1869946,"oiContracts":7414760,"oiAmount":472720.42,"totalLongContracts":4157509,"totalShortContracts":3257251,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"2ba07eff367f99ab4f3f4fceb903f1d1e2ecbeb1d8c88d06a9e53f868a9b47e2"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1776969000000,"buyAmount":167437.51,"sellAmount":168481.94,"buyContracts":2668316,"sellContracts":2697622,"oiContracts":7344582,"oiAmount":463187.55,"totalLongContracts":4106561,"totalShortContracts":3238021,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"f179a5105b0a2bc52a484c36d8d49ce89b79d65cbf68b5c14b19c3d0d2317bdc"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1776882600000,"buyAmount":157584.44,"sellAmount":161340.26,"buyContracts":2467736,"sellContracts":2541173,"oiContracts":7293636,"oiAmount":465499.49,"totalLongContracts":4095741,"totalShortContracts":3197895,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"4cd0cee34e90e3f1c843e578ab3a4134aa3ebf669e4bda31fb680e97f6fd20f7"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1776796200000,"buyAmount":35707.95,"sellAmount":39376.98,"buyContracts":552144,"sellContracts":618292,"oiContracts":7307995,"oiAmount":470344.99,"totalLongContracts":4138764,"totalShortContracts":3169231,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"2492d4b46013de191b22c6706e3f4468c794477ac916a7a9e66f1504f57d62f4"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1776709800000,"buyAmount":24372.67,"sellAmount":22815.96,"buyContracts":374673,"sellContracts":347672,"oiContracts":7268887,"oiAmount":469698.63,"totalLongContracts":4153809,"totalShortContracts":3115078,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"5815f6b951afa8b81a0cf71c305d1c7727b790e49472775a90767f2564ec5498"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1776623400000,"buyAmount":22726.65,"sellAmount":23409.21,"buyContracts":347270,"sellContracts":367511,"oiContracts":7272022,"oiAmount":466258.21,"totalLongContracts":4141876,"totalShortContracts":3130146,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"226134315be15ab5cb44d6f88f3dd0e28e0741332f9b0ee3d012f6c922c5840c"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1776364200000,"buyAmount":25658.28,"sellAmount":24657.85,"buyContracts":391077,"sellContracts":380918,"oiContracts":7270105,"oiAmount":468159.04,"totalLongContracts":4151038,"totalShortContracts":3119067,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"264a5f2adbf9ec5d8218f9f39fb135ab5b73eeda32e33fcd10773c103bc655a8"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1776277800000,"buyAmount":24499.14,"sellAmount":26142.27,"buyContracts":383635,"sellContracts":404150,"oiContracts":7270548,"oiAmount":464417.84,"totalLongContracts":4146180,"totalShortContracts":3124368,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"363d4113b672547b8e86022e26c7fee7d1e39ee05a3ef5b40c3aa8fe6e3eaeed"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1776191400000,"buyAmount":25350.18,"sellAmount":25443.68,"buyContracts":389020,"sellContracts":378361,"oiContracts":7285413,"oiAmount":464163.16,"totalLongContracts":4163870,"totalShortContracts":3121543,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"2bccc28a9826095f0470a6b6e4ef60ffd24fdbab3536359fa1316c24f8f3bc84"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1776018600000,"buyAmount":19968.25,"sellAmount":23294.28,"buyContracts":310881,"sellContracts":358242,"oiContracts":7338264,"oiAmount":458589.55,"totalLongContracts":4184966,"totalShortContracts":3153298,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"61d88d0c7400ff2ab64a1abe05b9cfc585a62958d0f875d34b35587b454b5a41"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1775759400000,"buyAmount":22881.01,"sellAmount":22478.22,"buyContracts":358226,"sellContracts":361193,"oiContracts":7345865,"oiAmount":463459.74,"totalLongContracts":4212447,"totalShortContracts":3133418,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"ea593a63c27807158ceb8076cffcc81d95716a6ba89a05154c4998b2c2ae8906"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1775673000000,"buyAmount":21873.97,"sellAmount":21998.25,"buyContracts":346942,"sellContracts":355535,"oiContracts":7354438,"oiAmount":459288.88,"totalLongContracts":4218217,"totalShortContracts":3136221,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"e94f3a6bc0db648b2161676dc70f2299543445c96152016854a6364382f7b9e0"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1775586600000,"buyAmount":34009.7,"sellAmount":31487.91,"buyContracts":520887,"sellContracts":488822,"oiContracts":7312071,"oiAmount":458306.55,"totalLongContracts":4201330,"totalShortContracts":3110741,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"b460da9dc372838774d08659daca47a38a6885757d3111170148295c90a7abfd"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1775500200000,"buyAmount":19991.33,"sellAmount":20571.63,"buyContracts":322585,"sellContracts":335169,"oiContracts":7298942,"oiAmount":440010.87,"totalLongContracts":4178733,"totalShortContracts":3120209,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"692eddea15a554e1b4c8f04308ed51d72b1a5aa8862104f7428c45a9b134d26f"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1775413800000,"buyAmount":20712.58,"sellAmount":19818.76,"buyContracts":336862,"sellContracts":320969,"oiContracts":7302874,"oiAmount":438325.5,"totalLongContracts":4186991,"totalShortContracts":3115883,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"95ce099051ce26fefaba31c7773f4a92aa574ab2768964117646440d839042bd"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1775068200000,"buyAmount":22900.13,"sellAmount":21476.58,"buyContracts":381779,"sellContracts":359317,"oiContracts":7290091,"oiAmount":430409.28,"totalLongContracts":4172653,"totalShortContracts":3117438,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"08109d70f2e18f996309bfe912a3665e4f6276baf7289f5a7ef5d9c01a89bf25"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1777487400000,"buyAmount":797967.36,"sellAmount":794438.53,"buyContracts":5094129,"sellContracts":5072195,"oiContracts":1995796,"oiAmount":313760.14,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":351772,"totalPutLongContracts":715640,"totalCallShortContracts":572110,"totalPutShortContracts":356275,"sourceHash":"f6f61a0e06b5fc3d0a4e11fee90a9f45b885859951ca05deae5aeecb36d137ad"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1777401000000,"buyAmount":579943.55,"sellAmount":583822.61,"buyContracts":3659192,"sellContracts":3683793,"oiContracts":1776287,"oiAmount":281482.39,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":293574,"totalPutLongContracts":653116,"totalCallShortContracts":509632,"totalPutShortContracts":319965,"sourceHash":"c93cc65c0846b588e1531f112d3c4b90acee8b598779b03e958366a88a658246"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1777314600000,"buyAmount":3945021.89,"sellAmount":3957895.97,"buyContracts":24765354,"sellContracts":24848029,"oiContracts":1574567,"oiAmount":247757.65,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":255953,"totalPutLongContracts":602178,"totalCallShortContracts":457771,"totalPutShortContracts":258666,"sourceHash":"c462b8cb9a638423c9e674db4e70c461e8a7c583f7be625ec6bf603735de5641"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1777228200000,"buyAmount":1440500.67,"sellAmount":1446680.39,"buyContracts":9070203,"sellContracts":9104089,"oiContracts":3007892,"oiAmount":478847.79,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":620796,"totalPutLongContracts":944737,"totalCallShortContracts":825375,"totalPutShortContracts":616983,"sourceHash":"70f93c59bf84d6509140dbd0a706b39f662f7a0da86b7e9dcd2e54df705a40ac"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1776969000000,"buyAmount":775497.41,"sellAmount":770086.03,"buyContracts":4871561,"sellContracts":4843110,"oiContracts":2972797,"oiAmount":469598.08,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":658740,"totalPutLongContracts":906190,"totalCallShortContracts":885490,"totalPutShortContracts":522378,"sourceHash":"616a99fa6e350b6568a2886a03c70216c24e6f47cbabf8e793048263942f6a0a"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1776882600000,"buyAmount":617004.17,"sellAmount":618314.39,"buyContracts":3862828,"sellContracts":3871613,"oiContracts":2574707,"oiAmount":411481.92,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":495010,"totalPutLongContracts":856649,"totalCallShortContracts":741870,"totalPutShortContracts":481179,"sourceHash":"6108de316f214832dbfac24d9392d086457a5dfd9ce98b940eca0cb30104bc6f"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1776796200000,"buyAmount":505629.35,"sellAmount":510950.53,"buyContracts":3149101,"sellContracts":3180724,"oiContracts":2439695,"oiAmount":393815.79,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":469533,"totalPutLongContracts":819012,"totalCallShortContracts":678084,"totalPutShortContracts":473066,"sourceHash":"e5e73277af46e1251a7ea42808c5d08aae5b52f4c0978006d51059e0a920b2e9"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1776709800000,"buyAmount":2575707.03,"sellAmount":2599179.21,"buyContracts":16151238,"sellContracts":16291149,"oiContracts":2174739,"oiAmount":354199.1,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":436467,"totalPutLongContracts":735411,"totalCallShortContracts":567780,"totalPutShortContracts":435080,"sourceHash":"0669d2e247ad7762b317c75d35505a66ca4572d5c8d8132329ac0e73f88124c4"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1776623400000,"buyAmount":1317291.11,"sellAmount":1327511.06,"buyContracts":8273755,"sellContracts":8338168,"oiContracts":2688046,"oiAmount":431716.95,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":513539,"totalPutLongContracts":888902,"totalCallShortContracts":693549,"totalPutShortContracts":592057,"sourceHash":"7325f7989c701f65b73f4dec3f43247be171264a009f2ab88d5f15539ad7de5e"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1776364200000,"buyAmount":738000.31,"sellAmount":733795.01,"buyContracts":4657335,"sellContracts":4631462,"oiContracts":2516604,"oiAmount":404402.16,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":491399,"totalPutLongContracts":857527,"totalCallShortContracts":632096,"totalPutShortContracts":535582,"sourceHash":"0ea116671d0b30bdfc76d719dab4542ee15fa43134e276b60a02844715007359"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1776277800000,"buyAmount":676924.0,"sellAmount":677887.99,"buyContracts":4280843,"sellContracts":4286054,"oiContracts":2343073,"oiAmount":374006.25,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":436038,"totalPutLongContracts":813186,"totalCallShortContracts":651704,"totalPutShortContracts":442145,"sourceHash":"2b41add4451f48750e143cad30c8ca577f5a9bdf4d47952a2c66336aa1169494"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1776191400000,"buyAmount":495064.29,"sellAmount":499517.74,"buyContracts":3146934,"sellContracts":3176575,"oiContracts":2174933,"oiAmount":347980.06,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":416458,"totalPutLongContracts":751302,"totalCallShortContracts":581517,"totalPutShortContracts":425656,"sourceHash":"4668d00ba2f96623cc7ba166e275f1363bb24b4eb02fdc4e7a37406b44e94fb5"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1776018600000,"buyAmount":3073763.12,"sellAmount":3108488.64,"buyContracts":19851654,"sellContracts":20076255,"oiContracts":2142971,"oiAmount":337835.94,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":417588,"totalPutLongContracts":749011,"totalCallShortContracts":568582,"totalPutShortContracts":407790,"sourceHash":"c3f4e43ada9c7529524781d872ccb3f45007612315fbab407c434002c883bfe3"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1775759400000,"buyAmount":1059181.07,"sellAmount":1052163.06,"buyContracts":6784186,"sellContracts":6736084,"oiContracts":2628502,"oiAmount":416310.65,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":517932,"totalPutLongContracts":873651,"totalCallShortContracts":696530,"totalPutShortContracts":540390,"sourceHash":"a67d2255f2baf8126ea5a6c15d06a1ba1ac9ccf6670c886c83b01e759a6e1785"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1775673000000,"buyAmount":704946.09,"sellAmount":702626.08,"buyContracts":4528511,"sellContracts":4515636,"oiContracts":2417173,"oiAmount":377868.7,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":461675,"totalPutLongContracts":800192,"totalCallShortContracts":686827,"totalPutShortContracts":468479,"sourceHash":"136ff590418548083c3844c15ce36c84816a4f98d48f7fb50461addc722672a2"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1775586600000,"buyAmount":569465.71,"sellAmount":572629.04,"buyContracts":3680196,"sellContracts":3700576,"oiContracts":2221377,"oiAmount":351230.41,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":427437,"totalPutLongContracts":730094,"totalCallShortContracts":622244,"totalPutShortContracts":441602,"sourceHash":"808bc735a9bde2713179e14fafbbe1a1b1aa99681290ee65178257b1f1cc39d1"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1775500200000,"buyAmount":3605579.14,"sellAmount":3604547.86,"buyContracts":24121610,"sellContracts":24109503,"oiContracts":1953261,"oiAmount":296307.1,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":335168,"totalPutLongContracts":698495,"totalCallShortContracts":541912,"totalPutShortContracts":377686,"sourceHash":"3894cae751ad1f8a5c7b03edf94b46d25132b1cd1e162be915e6f19b45813487"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1775413800000,"buyAmount":1349221.35,"sellAmount":1348181.33,"buyContracts":9097571,"sellContracts":9087125,"oiContracts":2532983,"oiAmount":381068.72,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":444173,"totalPutLongContracts":864956,"totalCallShortContracts":676826,"totalPutShortContracts":547028,"sourceHash":"1493bc7c09688ad8bb5eed2ba80664661a7d4bc52a5a62885613ab1955cf5827"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1775068200000,"buyAmount":811841.61,"sellAmount":816563.73,"buyContracts":5477103,"sellContracts":5504198,"oiContracts":2317637,"oiAmount":344422.19,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":411595,"totalPutLongContracts":784638,"totalCallShortContracts":661152,"totalPutShortContracts":460252,"sourceHash":"913cb6bf03b6de3abde2bb511b6c22e1ddf256461ec418e2e4fa306bceab1b6d"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1777487400000,"buyAmount":2350.88,"sellAmount":4448.54,"buyContracts":14696,"sellContracts":28114,"oiContracts":239488,"oiAmount":38199.7,"totalLongContracts":27506,"totalShortContracts":211982,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"830cbafce19e3e71aaecd0e56c0460a708f7a3adb055a8f53a2229c7af4d1df2"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1777401000000,"buyAmount":2606.74,"sellAmount":2675.62,"buyContracts":16188,"sellContracts":16568,"oiContracts":228718,"oiAmount":36735.16,"totalLongContracts":28830,"totalShortContracts":199888,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"65954a7b88b4479e01857fb03f32c75b9d06a73bc02aba923274ec3e384e44ba"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1777314600000,"buyAmount":6556.1,"sellAmount":9650.49,"buyContracts":40790,"sellContracts":60209,"oiContracts":231124,"oiAmount":36951.69,"totalLongContracts":30223,"totalShortContracts":200901,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"03442706b0832654dd5ea4d855f918ede4ecec05264da135e75588fd2e6a3d2d"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1777228200000,"buyAmount":12135.35,"sellAmount":12456.25,"buyContracts":75834,"sellContracts":77736,"oiContracts":337311,"oiAmount":54048.56,"totalLongContracts":65665,"totalShortContracts":271646,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"69dc56dd453f09505e7dd314ab7be9e474dba963eb86d17175711d9220afc98a"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1776969000000,"buyAmount":8508.74,"sellAmount":10612.52,"buyContracts":52806,"sellContracts":65971,"oiContracts":332129,"oiAmount":52804.6,"totalLongContracts":64025,"totalShortContracts":268104,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"050965d8ccd5b89c0d1092485240b1a9480521e27c441b702fbfc84622c15582"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1776882600000,"buyAmount":6467.41,"sellAmount":7735.71,"buyContracts":40350,"sellContracts":47950,"oiContracts":326914,"oiAmount":52387.54,"totalLongContracts":68000,"totalShortContracts":258914,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"4b30e5b00316316a6ba816211b0ad9026c99cadc8264b8b749877dfdcc90a9a3"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1776796200000,"buyAmount":1978.95,"sellAmount":3499.27,"buyContracts":12179,"sellContracts":21545,"oiContracts":324126,"oiAmount":52457.05,"totalLongContracts":70406,"totalShortContracts":253720,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"70f60058271cdc7e5b824a9c390f69ba3704e7972b00aaa69026849984177376"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1776709800000,"buyAmount":3914.39,"sellAmount":1349.64,"buyContracts":24114,"sellContracts":8301,"oiContracts":318164,"oiAmount":51859.35,"totalLongContracts":72108,"totalShortContracts":246056,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"d1cd04eed304ca59c67a7c6afb0bc8fc2b9e85475816e859f54ed53d60a1290c"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1776623400000,"buyAmount":2563.16,"sellAmount":2403.97,"buyContracts":15823,"sellContracts":14631,"oiContracts":323971,"oiAmount":52198.48,"totalLongContracts":67105,"totalShortContracts":256866,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"c07821505457d17c71555055735eb96302c4568a1670fbc156677858e049d18c"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1776364200000,"buyAmount":2476.92,"sellAmount":1198.81,"buyContracts":15415,"sellContracts":7379,"oiContracts":326991,"oiAmount":52772.3,"totalLongContracts":68019,"totalShortContracts":258972,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"bdfc06007b0b3feced760db4fc287d88c263ffc9122748944d42a73bb5dddba5"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1776277800000,"buyAmount":2477.45,"sellAmount":2211.29,"buyContracts":15456,"sellContracts":13727,"oiContracts":335771,"oiAmount":53745.44,"totalLongContracts":68391,"totalShortContracts":267380,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"617ddc3c1504bcf2ef3458861476de4679ee945a0c103ea3dcf13883c698657f"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1776191400000,"buyAmount":4029.41,"sellAmount":2786.56,"buyContracts":25056,"sellContracts":17420,"oiContracts":346896,"oiAmount":55582.71,"totalLongContracts":73089,"totalShortContracts":273807,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"21371748aadc365751bfa5e5bc1e35872a29bd31bcc4c80e461b9a3cdf807857"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1776018600000,"buyAmount":2227.4,"sellAmount":2573.7,"buyContracts":14132,"sellContracts":16259,"oiContracts":369658,"oiAmount":58274.21,"totalLongContracts":80652,"totalShortContracts":289006,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"6d4e4d84349774fd30fa5ee20f520d93fc082dbf601adb12118eaa55a42d2652"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1775759400000,"buyAmount":5691.39,"sellAmount":2253.04,"buyContracts":35710,"sellContracts":14219,"oiContracts":369091,"oiAmount":58720.95,"totalLongContracts":81432,"totalShortContracts":287659,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"9883e70ebf2f5b856f8355f399efa0523862495e3ba7a632a7ab95e9363c3902"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1775673000000,"buyAmount":2433.82,"sellAmount":2518.08,"buyContracts":15442,"sellContracts":15760,"oiContracts":385730,"oiAmount":60603.14,"totalLongContracts":79006,"totalShortContracts":306724,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"5ea05e4b7b2b3c2e42a63c0822941073132659bb93aa78abaa0088b54139215a"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1775586600000,"buyAmount":7872.12,"sellAmount":2799.17,"buyContracts":49723,"sellContracts":17688,"oiContracts":392210,"oiAmount":62172.77,"totalLongContracts":82405,"totalShortContracts":309805,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"1c62eaa5fce7fbb1245316e6f8e647c8fdbe0805ef7d3366a211d4ff85d7c126"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1775500200000,"buyAmount":4982.76,"sellAmount":3634.91,"buyContracts":33058,"sellContracts":24031,"oiContracts":410971,"oiAmount":62391.49,"totalLongContracts":75768,"totalShortContracts":335203,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"4808c83fa07f9692c99fe94b3b9503bc5807e2f2378e81c211af248bc00f9070"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1775413800000,"buyAmount":3613.07,"sellAmount":3657.99,"buyContracts":23944,"sellContracts":24386,"oiContracts":413598,"oiAmount":62589.19,"totalLongContracts":72568,"totalShortContracts":341030,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"d4364b0303de810dcb97997409f9f00c7ba75aa52f7cd89c4be612a72ba4e4cf"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1775068200000,"buyAmount":5258.84,"sellAmount":5724.0,"buyContracts":35554,"sellContracts":38753,"oiContracts":404136,"oiAmount":60308.12,"totalLongContracts":68058,"totalShortContracts":336078,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"0bd30264e2242d24f9e3c59f2c1f2400ec61e4fcf14cbb44b80652a9ea4a53d9"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1777487400000,"buyAmount":18797.22,"sellAmount":18893.3,"buyContracts":284705,"sellContracts":288393,"oiContracts":395209,"oiAmount":25101.19,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":79478,"totalPutLongContracts":109365,"totalCallShortContracts":129784,"totalPutShortContracts":76582,"sourceHash":"31b534d6cc73b43114022c571f04298b54ce291ffed2591aebc6fcd6f4fca510"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1777401000000,"buyAmount":19909.29,"sellAmount":20216.29,"buyContracts":296944,"sellContracts":299301,"oiContracts":301283,"oiAmount":19205.65,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":56574,"totalPutLongContracts":87150,"totalCallShortContracts":103340,"totalPutShortContracts":54219,"sourceHash":"09ca0ef65194e2771a6a152d2fcdd6288d0a35d71f8bd1b48bcb860afa009234"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1777314600000,"buyAmount":14703.71,"sellAmount":15487.72,"buyContracts":212471,"sellContracts":226171,"oiContracts":191470,"oiAmount":12175.12,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":31521,"totalPutLongContracts":57760,"totalCallShortContracts":70199,"totalPutShortContracts":31990,"sourceHash":"c43ce8d28d138cf56cadb63105a3e3dbcb4bc11bbddc776dedac698fe91de107"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1777228200000,"buyAmount":36235.68,"sellAmount":37308.19,"buyContracts":552014,"sellContracts":568143,"oiContracts":1125643,"oiAmount":74517.68,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":208156,"totalPutLongContracts":295725,"totalCallShortContracts":380905,"totalPutShortContracts":240857,"sourceHash":"f55a3ce47db7e55ba18cbe52493235d260a072f0431208f0dd6f83758b7021a5"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1776969000000,"buyAmount":37500.93,"sellAmount":41329.02,"buyContracts":572729,"sellContracts":635781,"oiContracts":1161548,"oiAmount":76060.25,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":226960,"totalPutLongContracts":302938,"totalCallShortContracts":383613,"totalPutShortContracts":248037,"sourceHash":"c84ada57842ccc51cbd60a1c1ef326fa7b1f4aec10535032a34cce36f4c3ce29"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1776882600000,"buyAmount":36560.58,"sellAmount":35122.76,"buyContracts":561926,"sellContracts":543834,"oiContracts":1206676,"oiAmount":80156.59,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":245952,"totalPutLongContracts":338036,"totalCallShortContracts":376872,"totalPutShortContracts":245816,"sourceHash":"a483130e9f9d3923742345295d86ee14223b4cc92fd085c3c9d7bc5014bad75c"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1776796200000,"buyAmount":34948.68,"sellAmount":35781.8,"buyContracts":544268,"sellContracts":560305,"oiContracts":1221984,"oiAmount":81920.29,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":247963,"totalPutLongContracts":334633,"totalCallShortContracts":385025,"totalPutShortContracts":254363,"sourceHash":"c687a793394cf5d1b5567ba22a764821a832d2969c3a4864639bb6e04d67267d"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1776709800000,"buyAmount":27348.37,"sellAmount":26813.52,"buyContracts":418819,"sellContracts":412567,"oiContracts":1135445,"oiAmount":76435.3,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":242173,"totalPutLongContracts":305172,"totalCallShortContracts":348223,"totalPutShortContracts":239877,"sourceHash":"68f1c8fefb0320da16b87d8a9f820b99f0d6fc263004fedef83e9bceab27f420"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1776623400000,"buyAmount":26044.71,"sellAmount":26675.43,"buyContracts":406267,"sellContracts":413557,"oiContracts":1091197,"oiAmount":73160.44,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":232448,"totalPutLongContracts":289647,"totalCallShortContracts":338604,"totalPutShortContracts":230498,"sourceHash":"095219207468aa07bedc5b1d267e5073a59c56a3ee7a2eeed883d9fd3134108c"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1776364200000,"buyAmount":25960.18,"sellAmount":27145.34,"buyContracts":394107,"sellContracts":410442,"oiContracts":1045489,"oiAmount":70097.26,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":226467,"totalPutLongContracts":276419,"totalCallShortContracts":323621,"totalPutShortContracts":218982,"sourceHash":"1a892d1a7cb605cc6b8c887a616e7a9a0183aa89bfa5acd8e213ce1c8b75c3cf"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1776277800000,"buyAmount":23485.43,"sellAmount":24003.41,"buyContracts":364282,"sellContracts":367140,"oiContracts":961958,"oiAmount":64245.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":203720,"totalPutLongContracts":265568,"totalCallShortContracts":293228,"totalPutShortContracts":199442,"sourceHash":"1446ebc946b91f02cdc73858503c689d9f4131b0810a4a9a6f68948d96533035"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1776191400000,"buyAmount":18786.92,"sellAmount":19694.44,"buyContracts":294940,"sellContracts":301443,"oiContracts":895518,"oiAmount":59720.84,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":190643,"totalPutLongContracts":246854,"totalCallShortContracts":269553,"totalPutShortContracts":188468,"sourceHash":"af4758e1b5337478f92cd36b784413a8913a42fac283bc79b89d8b48b5eab821"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1776018600000,"buyAmount":16421.42,"sellAmount":16880.92,"buyContracts":246665,"sellContracts":253219,"oiContracts":834673,"oiAmount":54599.9,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":177666,"totalPutLongContracts":232660,"totalCallShortContracts":252498,"totalPutShortContracts":171849,"sourceHash":"aa68e84ac0da18a16cd9214e47b203b297b07fc55508a7616d49149666efabc4"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1775759400000,"buyAmount":18581.81,"sellAmount":19468.12,"buyContracts":292082,"sellContracts":307758,"oiContracts":784995,"oiAmount":51725.96,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":171636,"totalPutLongContracts":217128,"totalCallShortContracts":232694,"totalPutShortContracts":163537,"sourceHash":"a677257d9d6bab87ea20b28fae8354cf98f262bd327dd45cd906a97bd7eb1970"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1775673000000,"buyAmount":17872.87,"sellAmount":18331.29,"buyContracts":272176,"sellContracts":278308,"oiContracts":703463,"oiAmount":45881.59,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":157057,"totalPutLongContracts":198779,"totalCallShortContracts":204632,"totalPutShortContracts":142995,"sourceHash":"56c75b1a78c96def2b2a03881c16e8a5208594daa0ea03cf9dadd5514814c4fc"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1775586600000,"buyAmount":17583.7,"sellAmount":17563.92,"buyContracts":269085,"sellContracts":271448,"oiContracts":620407,"oiAmount":40660.75,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":144589,"totalPutLongContracts":172785,"totalCallShortContracts":176735,"totalPutShortContracts":126298,"sourceHash":"37fb703377adcb073cc37d72e5a557749ffee183cd52ced7e6eb7783457ee920"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1775500200000,"buyAmount":11454.01,"sellAmount":11241.81,"buyContracts":176770,"sellContracts":174068,"oiContracts":529408,"oiAmount":33158.45,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":124272,"totalPutLongContracts":148784,"totalCallShortContracts":149270,"totalPutShortContracts":107082,"sourceHash":"0974f1bd61877d3ac8254aed121bdd33418ebef7fd4dbd400f5659bde51a0dd9"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1775413800000,"buyAmount":15164.33,"sellAmount":14124.71,"buyContracts":236808,"sellContracts":220458,"oiContracts":467110,"oiAmount":29076.56,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":108113,"totalPutLongContracts":132443,"totalCallShortContracts":136438,"totalPutShortContracts":90116,"sourceHash":"efc9b0320a3ba32fa3bf882443e917e292ab5a72bd217ed689c2b350eb006649"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1775068200000,"buyAmount":11179.8,"sellAmount":10270.17,"buyContracts":175919,"sellContracts":163579,"oiContracts":384538,"oiAmount":23531.76,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":86649,"totalPutLongContracts":104446,"totalCallShortContracts":116341,"totalPutShortContracts":77102,"sourceHash":"432457fb48291bf350a3d7e3188110dd94b2f0d23ab31c0b465b39c5915239eb"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1777487400000,"buyAmount":15049.55,"sellAmount":23097.41,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"427a54110753e6d7cc272268ab30605cd836dda62785e9c77582ba5c228862b1"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1777401000000,"buyAmount":14271.22,"sellAmount":16739.64,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"dea9bfdc60191a99b305308e7bdf9ebf13db4610bb0aca7eebcc04ea09f4dbf5"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1777314600000,"buyAmount":17231.5,"sellAmount":19335.24,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"aa8b081fbb90f4d1453fa5ae235ba77f23090a65f7306086c78c3cf0da9af792"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1777228200000,"buyAmount":30263.32,"sellAmount":31414.8,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"7d94df2cc3f53aacb79c1719b9eeb33168be57c885fc18bcbc557d53ec484183"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1776969000000,"buyAmount":9837.2,"sellAmount":18665.07,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"e15cc2c5c9b52d8808f53b85d47f9ebc6306201f6eb9a935fb378ff50c874f55"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1776882600000,"buyAmount":12829.12,"sellAmount":16083.83,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"ccf4c825558477a1bc9449834e4790c518bc0bb132376bf99f0dd186a65b74f8"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1776796200000,"buyAmount":13895.07,"sellAmount":15973.43,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"24ce34d77fe74d824dc7fb78b84d282eb2ff731fae2f4315a535e1f104e673da"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1776709800000,"buyAmount":13033.17,"sellAmount":14952.16,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"cb9cdf2dac49de8c6e8123d383db330bd4b70a530ee9f4f817840a3a6361b64c"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1776623400000,"buyAmount":12756.88,"sellAmount":13816.81,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"fe0150ce29e365917e610aa17219254771c29b373222dde71c02780af87e5334"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1776364200000,"buyAmount":16034.88,"sellAmount":15351.68,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"6bd38927bad3e05517c48e51c4b1fab925765b7610fa72b16329fd7c26731b2e"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1776277800000,"buyAmount":16209.44,"sellAmount":15827.08,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"e85557fee67ae58f4d90b9d2662c1765fa537d1dcf7605d9051aa2b27ee5187a"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1776191400000,"buyAmount":18075.63,"sellAmount":17409.48,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"46039a7e6e0811ca50405ec54aa6eb91aa1b21c0d76dd6f904da506cfb1e2790"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1776018600000,"buyAmount":15382.09,"sellAmount":17365.27,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"0e4c518eda179053aa329fd28c57043e326b285b350f81655f0c1aed8d9b6f8f"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1775759400000,"buyAmount":18303.96,"sellAmount":17631.87,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"8f4f5f5ee94eb1b433d4e5e38dbd734dc8e77aabf3b9046ecbd83658d74e2bca"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1775673000000,"buyAmount":15746.32,"sellAmount":17457.51,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"2836c52099414333ef6ddb8217b0c4d6edd9d8487c7f7ffba269c85ccce8f2b9"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1775586600000,"buyAmount":19092.05,"sellAmount":21904.02,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"a3683c07d66543d30d19942ac167f00f33b3bc7c15301de92e006f6c949dab1f"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1775500200000,"buyAmount":7953.46,"sellAmount":16645.57,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"5a94793e8a282457fb668c949e6aba35b712eac0c6bd684a75c76dda0d879453"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1775413800000,"buyAmount":8837.64,"sellAmount":17004.81,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"5430b8c5c401afe65bd6fcce80cc297188ce80bfa0eba0dfe20017ae59b2b0c7"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1775068200000,"buyAmount":10626.52,"sellAmount":20557.65,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"653665882c7f56c7b1040b85f42ed06ab46bf448e2863922867550c9014f2eb5"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1779993000000,"buyAmount":45943.66,"sellAmount":47073.79,"buyContracts":741325,"sellContracts":733581,"oiContracts":7365604,"oiAmount":460332.04,"totalLongContracts":4005908,"totalShortContracts":3359696,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"8d9898531293bdef3d142ca9a60795a0afb8d73180a91b497734847035c25636"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1779820200000,"buyAmount":18955.42,"sellAmount":19659.22,"buyContracts":290470,"sellContracts":301089,"oiContracts":7452284,"oiAmount":469032.79,"totalLongContracts":4045376,"totalShortContracts":3406908,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"f8e900927899fb2827ae347a76e2e23d1126c0d22840ad0fe46a46bb9d6d0f3f"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1779733800000,"buyAmount":38003.09,"sellAmount":36622.88,"buyContracts":595489,"sellContracts":570933,"oiContracts":7411359,"oiAmount":464329.99,"totalLongContracts":4030223,"totalShortContracts":3381136,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"6f6f17b3ba46a7857b4f8c878f0233548843eb1d0a1ea0a3ba1758b1cbdd8c33"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1779647400000,"buyAmount":118081.48,"sellAmount":115597.36,"buyContracts":1879403,"sellContracts":1844609,"oiContracts":7727712,"oiAmount":484959.81,"totalLongContracts":4199409,"totalShortContracts":3528303,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"4f3056520bb7f924fa523628211bbb4ed9897e015184c8f4918aa341ad552fdc"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1779388200000,"buyAmount":169457.57,"sellAmount":164983.84,"buyContracts":2728932,"sellContracts":2670076,"oiContracts":7729034,"oiAmount":479369.65,"totalLongContracts":4182673,"totalShortContracts":3546361,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"9ca80a09a0ed426687182e6436b5647b02e8cfd6ed9d820861450b3601feefea"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1779301800000,"buyAmount":159884.28,"sellAmount":159741.77,"buyContracts":2594725,"sellContracts":2598525,"oiContracts":7736634,"oiAmount":476278.32,"totalLongContracts":4157045,"totalShortContracts":3579589,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"11179df39caeeca5b1881274f4b0c61076a2fb686c615da6f6ade361e0646fac"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1779215400000,"buyAmount":34990.1,"sellAmount":35920.16,"buyContracts":559444,"sellContracts":585110,"oiContracts":7785508,"oiAmount":478173.87,"totalLongContracts":4183382,"totalShortContracts":3602126,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"13ccd35b2666131813af189aadbafc84838c1de86fd83ae219348866f8178811"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1779129000000,"buyAmount":24093.53,"sellAmount":25972.17,"buyContracts":381303,"sellContracts":407653,"oiContracts":7713152,"oiAmount":472447.0,"totalLongContracts":4160037,"totalShortContracts":3553115,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"56aae67382d574f9fb3d7e3df4d06a198181b5aaab5e3e930e1b3319716d3b49"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1779042600000,"buyAmount":21917.31,"sellAmount":22496.35,"buyContracts":350226,"sellContracts":359085,"oiContracts":7684266,"oiAmount":468055.53,"totalLongContracts":4158769,"totalShortContracts":3525497,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"959d0e50101a3d9e8592c223da050c61f00290cf4add4b98d97354e9960ffd15"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1778783400000,"buyAmount":20741.67,"sellAmount":21940.1,"buyContracts":333139,"sellContracts":344349,"oiContracts":7656067,"oiAmount":467793.07,"totalLongContracts":4149099,"totalShortContracts":3506968,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"ce3f47fa3cfadf4af7a2d0984f41ec62269e107851ab11fd3bed422e29877322"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1778697000000,"buyAmount":23470.81,"sellAmount":23083.97,"buyContracts":370362,"sellContracts":388497,"oiContracts":7617543,"oiAmount":467464.42,"totalLongContracts":4135442,"totalShortContracts":3482101,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"885e264a04c82da68104b44e5621e2a45e3ec8a478a09f34cdc93ec1091eb303"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1778610600000,"buyAmount":24810.45,"sellAmount":22731.28,"buyContracts":392165,"sellContracts":365045,"oiContracts":7535946,"oiAmount":460387.75,"totalLongContracts":4103711,"totalShortContracts":3432235,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"a8f249b7a55ea975b61483f481a4f656b51daecdb6910f4d1b7e3e2078e17627"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1778524200000,"buyAmount":21355.91,"sellAmount":22006.94,"buyContracts":334981,"sellContracts":349280,"oiContracts":7490868,"oiAmount":456049.31,"totalLongContracts":4067612,"totalShortContracts":3423256,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"c91f60775ac854ff70b725405695a6e8458c4eb654f5d0ae40ff372c254711e2"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1778437800000,"buyAmount":22023.48,"sellAmount":23240.59,"buyContracts":325672,"sellContracts":349900,"oiContracts":7424899,"oiAmount":464218.57,"totalLongContracts":4041777,"totalShortContracts":3383122,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"c006f82786a587544265c70b2813152e24ec68616be23963ab6d7533aa3af5d1"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1778178600000,"buyAmount":21162.65,"sellAmount":25353.81,"buyContracts":314611,"sellContracts":373638,"oiContracts":7401559,"oiAmount":469673.56,"totalLongContracts":4042221,"totalShortContracts":3359338,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"7d1761f7c3df5ec66b0efc848574a0c6766bb5b2391a5b113d2d83b09b233834"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1778092200000,"buyAmount":21822.61,"sellAmount":24554.66,"buyContracts":319770,"sellContracts":376900,"oiContracts":7406864,"oiAmount":471902.44,"totalLongContracts":4074387,"totalShortContracts":3332477,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"4490063fa6486af351828455c9517f7ec647671cb63f6ca0750ec100a5ff6424"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1778005800000,"buyAmount":26478.69,"sellAmount":25749.01,"buyContracts":399145,"sellContracts":391377,"oiContracts":7373080,"oiAmount":469871.32,"totalLongContracts":4086060,"totalShortContracts":3287020,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"1b358d55e1e425685183189fa4fbff2b73049e2efa7fed267209b19878b0d92d"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1777919400000,"buyAmount":20391.55,"sellAmount":21066.11,"buyContracts":305088,"sellContracts":319654,"oiContracts":7327780,"oiAmount":460039.85,"totalLongContracts":4059526,"totalShortContracts":3268254,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"722da310dd05144ef43ecc276fb704ce9d18b29349382a56cda8ed35b01dd31f"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1777833000000,"buyAmount":24351.6,"sellAmount":23525.25,"buyContracts":356937,"sellContracts":349905,"oiContracts":7283052,"oiAmount":457284.13,"totalLongContracts":4044445,"totalShortContracts":3238607,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"7f6230815a2ad2312d63da3084987ab45f8c8d18728a1f1cc132be64699f16f7"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1779993000000,"buyAmount":1094401.38,"sellAmount":1099772.59,"buyContracts":7017508,"sellContracts":7056687,"oiContracts":2600970,"oiAmount":402408.27,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":553247,"totalPutLongContracts":842769,"totalCallShortContracts":823871,"totalPutShortContracts":381083,"sourceHash":"3ecba8d21c1177c701eaccae7f3579a35cc9a370ac6f621d15ec5673d0f4f3e1"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1779820200000,"buyAmount":554682.58,"sellAmount":555013.3,"buyContracts":3543149,"sellContracts":3547295,"oiContracts":2159019,"oiAmount":339005.3,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":426192,"totalPutLongContracts":768438,"totalCallShortContracts":633361,"totalPutShortContracts":331028,"sourceHash":"1cbc1487fc63afca0beececa266bf2aa4d4cb586f6c7ab085bc44c1db2b3124f"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1779733800000,"buyAmount":3440544.8,"sellAmount":3478562.51,"buyContracts":21670897,"sellContracts":21910333,"oiContracts":1769228,"oiAmount":278180.98,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":320160,"totalPutLongContracts":681647,"totalCallShortContracts":520515,"totalPutShortContracts":246905,"sourceHash":"85f2a5741e768e74d73b7f2a795b5265fdb367bc74896bf34f9b602411411ffc"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1779647400000,"buyAmount":1297756.48,"sellAmount":1285074.46,"buyContracts":8208191,"sellContracts":8128579,"oiContracts":3337091,"oiAmount":529400.5,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":799957,"totalPutLongContracts":1024401,"totalCallShortContracts":843791,"totalPutShortContracts":668943,"sourceHash":"027da8b2b6912c6146c243911d204c9c01a1e33a7ec289add7df223287eeb004"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1779388200000,"buyAmount":891073.54,"sellAmount":879018.42,"buyContracts":5690787,"sellContracts":5617160,"oiContracts":2928710,"oiAmount":458670.19,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":653857,"totalPutLongContracts":926504,"totalCallShortContracts":815806,"totalPutShortContracts":532543,"sourceHash":"903c1665dd4a2bd92857556f745f75a0664aad04a1c5ee000c2284dffaaca949"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1779301800000,"buyAmount":804018.96,"sellAmount":803332.82,"buyContracts":5174288,"sellContracts":5172219,"oiContracts":2809846,"oiAmount":437199.86,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":564691,"totalPutLongContracts":919425,"totalCallShortContracts":820753,"totalPutShortContracts":504978,"sourceHash":"ff53ea05e39492ffc631ada39a1e7028b5e467bfc3b50ea43f3de2a1acab8a69"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1779215400000,"buyAmount":706114.09,"sellAmount":716249.46,"buyContracts":4542269,"sellContracts":4608422,"oiContracts":2435035,"oiAmount":379169.82,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":441464,"totalPutLongContracts":854211,"totalCallShortContracts":663620,"totalPutShortContracts":475740,"sourceHash":"4efb73e8a734a82ba69e7712ff15f84d983fca77dc89d52709768661713e39e5"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1779129000000,"buyAmount":3184215.11,"sellAmount":3211466.15,"buyContracts":20635541,"sellContracts":20816453,"oiContracts":2217183,"oiAmount":344582.58,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":406028,"totalPutLongContracts":813798,"totalCallShortContracts":611362,"totalPutShortContracts":385995,"sourceHash":"ab14b279b07197eefa403a32b0a3b73af575b7615301d0b71e27f2040b50b7a9"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1779042600000,"buyAmount":1608003.87,"sellAmount":1600256.49,"buyContracts":10461032,"sellContracts":10406956,"oiContracts":3024351,"oiAmount":468808.3,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":517083,"totalPutLongContracts":1089492,"totalCallShortContracts":731449,"totalPutShortContracts":686328,"sourceHash":"01f8dd3fabb9cdf25b79946365a3ed8bb87332d79823a789a4a2a21dd83e239c"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1778783400000,"buyAmount":1124260.11,"sellAmount":1126393.59,"buyContracts":7248342,"sellContracts":7261201,"oiContracts":2739544,"oiAmount":425175.17,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":532822,"totalPutLongContracts":904311,"totalCallShortContracts":789341,"totalPutShortContracts":513070,"sourceHash":"0e91a20d00d34b741a7a17626480fb0f652e8100815e22ae080590c55bb492a9"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1778697000000,"buyAmount":727977.9,"sellAmount":734634.84,"buyContracts":4705278,"sellContracts":4747733,"oiContracts":2430007,"oiAmount":378564.02,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":424050,"totalPutLongContracts":864744,"totalCallShortContracts":656098,"totalPutShortContracts":485115,"sourceHash":"9b766010121f1aea37ade820f35e312347b9a71cd268dfa1d0fc528ccbcb2cf9"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1778610600000,"buyAmount":651824.36,"sellAmount":657576.58,"buyContracts":4230690,"sellContracts":4266600,"oiContracts":2331683,"oiAmount":359072.26,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":428267,"totalPutLongContracts":832593,"totalCallShortContracts":682279,"totalPutShortContracts":388545,"sourceHash":"9ea32bed0fb8f0786fadb77fca7dff94b629a976c390c425ac0e8ea042c4fc6b"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1778524200000,"buyAmount":2842619.23,"sellAmount":2848379.49,"buyContracts":18436209,"sellContracts":18482668,"oiContracts":2144896,"oiAmount":330161.16,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":373291,"totalPutLongContracts":812130,"totalCallShortContracts":623294,"totalPutShortContracts":336181,"sourceHash":"163f1ec8673d77e5d7e254c68bfa794bcc95e813a660a4320e823c912905e786"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1778437800000,"buyAmount":1351538.38,"sellAmount":1354718.0,"buyContracts":8636103,"sellContracts":8658082,"oiContracts":2652125,"oiAmount":414522.7,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":523725,"totalPutLongContracts":859256,"totalCallShortContracts":822482,"totalPutShortContracts":446661,"sourceHash":"9250df934ca7f92b519cc11ac70646b9ba927e769b63ce1d6248c2aa399548b7"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1778178600000,"buyAmount":905591.95,"sellAmount":902623.61,"buyContracts":5711420,"sellContracts":5696888,"oiContracts":2443340,"oiAmount":388025.89,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":472541,"totalPutLongContracts":817039,"totalCallShortContracts":722675,"totalPutShortContracts":431086,"sourceHash":"dd5f51cf6fe3d4841953da6010290470475396a87c19ffa36e6ba640071b3a7e"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1778092200000,"buyAmount":739954.96,"sellAmount":747238.64,"buyContracts":4639963,"sellContracts":4684661,"oiContracts":2153916,"oiAmount":344787.71,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":356458,"totalPutLongContracts":781143,"totalCallShortContracts":613358,"totalPutShortContracts":402957,"sourceHash":"0b66fbbdf180db8c2a8615f58f6ee88f743ab2c51d3b184943bca6ab60bf63d7"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1778005800000,"buyAmount":852162.62,"sellAmount":853472.77,"buyContracts":5394369,"sellContracts":5403192,"oiContracts":2075173,"oiAmount":331933.91,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":368811,"totalPutLongContracts":751767,"totalCallShortContracts":559231,"totalPutShortContracts":395364,"sourceHash":"f70fe84eb67e7b0c6ae98489b652809bb7df4306c087201293cc4113d77ce2c7"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1777919400000,"buyAmount":3676612.75,"sellAmount":3703779.04,"buyContracts":23492302,"sellContracts":23661914,"oiContracts":1785307,"oiAmount":281394.16,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":289256,"totalPutLongContracts":690801,"totalCallShortContracts":496740,"totalPutShortContracts":308510,"sourceHash":"0ebf24a666b17274542ea17b669be83379b12312428e26db4bf73c943a6c2237"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1777833000000,"buyAmount":1939940.28,"sellAmount":1935304.7,"buyContracts":12312942,"sellContracts":12287303,"oiContracts":2568707,"oiAmount":405114.15,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":563705,"totalPutLongContracts":802982,"totalCallShortContracts":750271,"totalPutShortContracts":451750,"sourceHash":"78b8553f86200893373868749c62f7c982cd27da39bde0c531eee8352da03faf"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1779993000000,"buyAmount":1656.52,"sellAmount":7655.91,"buyContracts":10275,"sellContracts":48572,"oiContracts":264757,"oiAmount":41886.85,"totalLongContracts":31724,"totalShortContracts":233033,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"8ea4beb6672a04e013f1aef6418c3d5c613e23595befe998e3f466000c712148"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1779820200000,"buyAmount":1259.85,"sellAmount":2851.57,"buyContracts":7792,"sellContracts":17939,"oiContracts":240740,"oiAmount":38610.9,"totalLongContracts":38864,"totalShortContracts":201876,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"1b3b525707c2ff322684d1f816ef8941e9ba6bd73b21ecd1eec1b41745aaca7b"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1779733800000,"buyAmount":5098.7,"sellAmount":8362.25,"buyContracts":31918,"sellContracts":52644,"oiContracts":229387,"oiAmount":36850.67,"totalLongContracts":38261,"totalShortContracts":191126,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"db33f9a09521536f754481c315a59d6c9a7e326dbfe45de84620b065fb4f2bf6"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1779647400000,"buyAmount":9720.81,"sellAmount":8125.43,"buyContracts":60499,"sellContracts":50484,"oiContracts":317357,"oiAmount":50947.99,"totalLongContracts":51190,"totalShortContracts":266167,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"ccae0cb3b2b42a87e9d727e3b17dc46fffe434815404905bb5397e3a74d81f47"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1779388200000,"buyAmount":11534.62,"sellAmount":10545.47,"buyContracts":72238,"sellContracts":66040,"oiContracts":312052,"oiAmount":49253.88,"totalLongContracts":43530,"totalShortContracts":268522,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"fd6f30816ed51b755c174aac4ac150e87a7ce28517156412b0a5c28f49d210c7"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1779301800000,"buyAmount":5600.9,"sellAmount":6950.05,"buyContracts":34835,"sellContracts":43505,"oiContracts":302570,"oiAmount":47395.9,"totalLongContracts":35690,"totalShortContracts":266880,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"1f52a1e391d37a935ff289667ff5cebc65450666227e197e73e6ab095fec43d0"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1779215400000,"buyAmount":1955.92,"sellAmount":2920.88,"buyContracts":12474,"sellContracts":18714,"oiContracts":292706,"oiAmount":45938.38,"totalLongContracts":35093,"totalShortContracts":257613,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"3128a139b0d86e17d4b560359bc7310aaf26189dce51a5f34c2de263f593b7a9"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1779129000000,"buyAmount":2049.19,"sellAmount":3047.82,"buyContracts":12958,"sellContracts":19372,"oiContracts":286756,"oiAmount":44865.67,"totalLongContracts":35238,"totalShortContracts":251518,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"9ab62e2d81832a57b18e3d09420270e3798ca5a6ca9ddfa0d489b2f94cd49b4e"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1779042600000,"buyAmount":4364.63,"sellAmount":3362.67,"buyContracts":28182,"sellContracts":21541,"oiContracts":279710,"oiAmount":43823.27,"totalLongContracts":34922,"totalShortContracts":244788,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"ed7776f92ddf30c5349c530006ba23146e1471d0909642611533565446ffe616"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1778783400000,"buyAmount":4188.02,"sellAmount":2794.84,"buyContracts":26853,"sellContracts":17749,"oiContracts":288159,"oiAmount":45123.02,"totalLongContracts":35826,"totalShortContracts":252333,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"19dcb84134a14b1cca5741edd953a642a3cc1f027f3644c4b9fa7c848490b89e"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1778697000000,"buyAmount":4564.03,"sellAmount":2651.51,"buyContracts":29109,"sellContracts":16846,"oiContracts":295759,"oiAmount":46451.86,"totalLongContracts":35074,"totalShortContracts":260685,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"e4288e509436698cab028c75b81882004ab4fe307bc69a958f9167d72cabfc19"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1778610600000,"buyAmount":2767.16,"sellAmount":2894.74,"buyContracts":17713,"sellContracts":18443,"oiContracts":303616,"oiAmount":47144.32,"totalLongContracts":32871,"totalShortContracts":270745,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"87e7193c3f2257cf68ee8591ba7a00d712abe09853b7f7db7ee93246a5472ebe"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1778524200000,"buyAmount":2623.34,"sellAmount":5131.16,"buyContracts":16848,"sellContracts":33023,"oiContracts":304670,"oiAmount":47236.3,"totalLongContracts":33763,"totalShortContracts":270907,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"81f3b2464a56e21b500f05cbc1e50a74c39d4a4c880b7376a9b0cdfa430bd0cb"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1778437800000,"buyAmount":2252.23,"sellAmount":3937.64,"buyContracts":14113,"sellContracts":24970,"oiContracts":287717,"oiAmount":45486.61,"totalLongContracts":33374,"totalShortContracts":254343,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"d3ff60d0590822506371142ea16f2d77c20ac68337220adb927fcf3eae77940c"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1778178600000,"buyAmount":1379.87,"sellAmount":3657.63,"buyContracts":8501,"sellContracts":22665,"oiContracts":279344,"oiAmount":44880.9,"totalLongContracts":34616,"totalShortContracts":244728,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"27b04233468333c56876b583eee621c7820b451ce59a8f417e84436273b4e4bd"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1778092200000,"buyAmount":1986.18,"sellAmount":2163.58,"buyContracts":11914,"sellContracts":13267,"oiContracts":270302,"oiAmount":43783.64,"totalLongContracts":37177,"totalShortContracts":233125,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"f2121ce357eee5241eb6507f68048462337e7f369236395c1c16f69a8a4110b7"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1778005800000,"buyAmount":3826.79,"sellAmount":3192.0,"buyContracts":23401,"sellContracts":19863,"oiContracts":266095,"oiAmount":43163.83,"totalLongContracts":35750,"totalShortContracts":230345,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"ab7746b339abad9a0ff4835a169866e543e5cb4d2152bce5642212c8ad44ca8b"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1777919400000,"buyAmount":1233.83,"sellAmount":2105.35,"buyContracts":7723,"sellContracts":13284,"oiContracts":257331,"oiAmount":40983.83,"totalLongContracts":29599,"totalShortContracts":227732,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"2e35363114789461e75ed149a72fb766f50ed80982451bc86d96521b9e01b0fb"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1777833000000,"buyAmount":1886.22,"sellAmount":3157.34,"buyContracts":11645,"sellContracts":19741,"oiContracts":251188,"oiAmount":40171.74,"totalLongContracts":29308,"totalShortContracts":221880,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"c72b35bd837f5fc611aa48f2bd9709241d2d5a6f8f872ad7cbafa738bd6d0e6d"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1779993000000,"buyAmount":20421.79,"sellAmount":21683.15,"buyContracts":322513,"sellContracts":340637,"oiContracts":449650,"oiAmount":28525.2,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":77080,"totalPutLongContracts":125449,"totalCallShortContracts":151398,"totalPutShortContracts":95723,"sourceHash":"7a7a1417c2ac50b4b39e4264b43831662bf356870fd45dabe57e1370ccb3dc61"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1779820200000,"buyAmount":16071.2,"sellAmount":16577.34,"buyContracts":252380,"sellContracts":259785,"oiContracts":347048,"oiAmount":22290.28,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":60047,"totalPutLongContracts":100243,"totalCallShortContracts":115467,"totalPutShortContracts":71291,"sourceHash":"4cc3cd467dab25249f57b383733d5122b641b6d34c82c06313648bb4aca12d7f"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1779733800000,"buyAmount":15305.37,"sellAmount":17326.88,"buyContracts":237892,"sellContracts":266971,"oiContracts":253265,"oiAmount":16299.68,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":43016,"totalPutLongContracts":74085,"totalCallShortContracts":85655,"totalPutShortContracts":50509,"sourceHash":"0f112f340d8c2c63c9bd216fc38642883b0ea8d51d36aaf7e2159e496c4ad3db"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1779647400000,"buyAmount":39364.13,"sellAmount":39174.2,"buyContracts":578967,"sellContracts":576120,"oiContracts":1100988,"oiAmount":72687.37,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":231621,"totalPutLongContracts":294910,"totalCallShortContracts":348010,"totalPutShortContracts":226447,"sourceHash":"613c21d04039cf28ae89d386f098d85ff3a42c0328644a3b8e23c0995812f227"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1779388200000,"buyAmount":52766.61,"sellAmount":52402.64,"buyContracts":829981,"sellContracts":827415,"oiContracts":1155731,"oiAmount":75143.46,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":244303,"totalPutLongContracts":308176,"totalCallShortContracts":366368,"totalPutShortContracts":236884,"sourceHash":"c3080204944e3e82985e064adc0d50e8bab408d7ca53ed02e7897b3fa39b7c1f"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1779301800000,"buyAmount":52114.08,"sellAmount":51254.26,"buyContracts":808626,"sellContracts":795871,"oiContracts":1170523,"oiAmount":75482.05,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":246627,"totalPutLongContracts":311965,"totalCallShortContracts":374615,"totalPutShortContracts":237316,"sourceHash":"99522548536642e8ddebfd04e832e813fe127e2826064c679fde2f7dfeecdfe0"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1779215400000,"buyAmount":48315.94,"sellAmount":48634.87,"buyContracts":746688,"sellContracts":754127,"oiContracts":1168434,"oiAmount":75290.96,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":241966,"totalPutLongContracts":309204,"totalCallShortContracts":376650,"totalPutShortContracts":240614,"sourceHash":"dca1fc75a3009b19a829f5558d9b98feab836785f8a3f58e492caae8bfd5557c"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1779129000000,"buyAmount":44532.0,"sellAmount":44446.06,"buyContracts":693571,"sellContracts":689122,"oiContracts":1192209,"oiAmount":76623.75,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":252804,"totalPutLongContracts":313973,"totalCallShortContracts":381233,"totalPutShortContracts":244199,"sourceHash":"2af9158554468473f355d654b78ab8f9379c622c50258cf411efea6a8bd7789b"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1779042600000,"buyAmount":40301.81,"sellAmount":40500.82,"buyContracts":634355,"sellContracts":630762,"oiContracts":1139870,"oiAmount":72679.27,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":236516,"totalPutLongContracts":301867,"totalCallShortContracts":370828,"totalPutShortContracts":230659,"sourceHash":"69a3c0048e69686f9ea40f21ea2f6b44abf20c584c4b1c029fc87bed3918ed8e"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1778783400000,"buyAmount":34494.04,"sellAmount":34525.75,"buyContracts":547953,"sellContracts":538625,"oiContracts":1066067,"oiAmount":68180.4,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":218916,"totalPutLongContracts":280769,"totalCallShortContracts":348988,"totalPutShortContracts":217394,"sourceHash":"7fbe2646eba1b5388f547297902436a6360de8c70623d25607e0ef6cd988c92f"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1778697000000,"buyAmount":36203.86,"sellAmount":37956.83,"buyContracts":563978,"sellContracts":592470,"oiContracts":993829,"oiAmount":63813.54,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":198046,"totalPutLongContracts":260856,"totalCallShortContracts":335171,"totalPutShortContracts":199756,"sourceHash":"9955651b081dfb62e4a243f0836e3660e87837de1d4874b3efd09f59c5bd8a14"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1778610600000,"buyAmount":31544.44,"sellAmount":31121.73,"buyContracts":485948,"sellContracts":481337,"oiContracts":922559,"oiAmount":59018.17,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":183325,"totalPutLongContracts":254188,"totalCallShortContracts":306611,"totalPutShortContracts":178435,"sourceHash":"5252fc4929922a56ed8a9c90518eb7138dd1a522487b4ad698ac2e059909c49c"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1778524200000,"buyAmount":29242.74,"sellAmount":29127.44,"buyContracts":451011,"sellContracts":450442,"oiContracts":856236,"oiAmount":54638.53,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":166814,"totalPutLongContracts":235232,"totalCallShortContracts":286866,"totalPutShortContracts":167324,"sourceHash":"1f553d3800e83d9d70dcaefaab72be937e23724e87b4a6fe2e7a78c94eb23866"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1778437800000,"buyAmount":28620.89,"sellAmount":28939.2,"buyContracts":404802,"sellContracts":409058,"oiContracts":792841,"oiAmount":51734.97,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":163799,"totalPutLongContracts":206265,"totalCallShortContracts":260004,"totalPutShortContracts":162773,"sourceHash":"ddf7f0c986857fe29ec76252beae10a13aa1841f09115fa0d58371f09054ab76"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1778178600000,"buyAmount":25143.96,"sellAmount":25871.21,"buyContracts":360454,"sellContracts":368691,"oiContracts":726967,"oiAmount":47966.75,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":157287,"totalPutLongContracts":181968,"totalCallShortContracts":233957,"totalPutShortContracts":153755,"sourceHash":"641082125e61370e98f4e29cc8a59d56347e5e7d5fd7db31cebf861091af211f"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1778092200000,"buyAmount":23247.95,"sellAmount":23253.77,"buyContracts":343196,"sellContracts":343105,"oiContracts":670936,"oiAmount":44145.75,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":145968,"totalPutLongContracts":169390,"totalCallShortContracts":214376,"totalPutShortContracts":141202,"sourceHash":"6530cf30b828a0af1d165a6403a9836920aacec77d233bdca43d8a655822c456"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1778005800000,"buyAmount":27451.32,"sellAmount":27472.76,"buyContracts":423913,"sellContracts":425579,"oiContracts":613427,"oiAmount":40186.02,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":129453,"totalPutLongContracts":157105,"totalCallShortContracts":199718,"totalPutShortContracts":127151,"sourceHash":"83e8c88c8387065dd7f012f225e15003f96435881df57132f30c6caba64123fe"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1777919400000,"buyAmount":17873.07,"sellAmount":18255.17,"buyContracts":269946,"sellContracts":275693,"oiContracts":515227,"oiAmount":33320.57,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":103483,"totalPutLongContracts":134808,"totalCallShortContracts":175780,"totalPutShortContracts":101156,"sourceHash":"6f076b6496e5d81981f7ded831ae912b0898bd46e7839460c23613ae5034c8fa"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1777833000000,"buyAmount":18978.19,"sellAmount":19953.76,"buyContracts":284885,"sellContracts":300260,"oiContracts":469718,"oiAmount":30351.47,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":94204,"totalPutLongContracts":124206,"totalCallShortContracts":158038,"totalPutShortContracts":93270,"sourceHash":"cac358250b677289ac5fbfb1f04bbaa5888a65edf169adb4aaadfc0d93e90d2d"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1779993000000,"buyAmount":89733.64,"sellAmount":110839.5,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"4965d2f1b6ca6689d4ad9e82d76af60822d5c59aadedeccdd0b28e0c1ae4ee4d"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1779820200000,"buyAmount":11418.65,"sellAmount":12461.35,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"84a8682d61f83f6fb398564bd08c615b48efa094805ee298e14b2438751cf54f"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1779733800000,"buyAmount":13127.02,"sellAmount":15534.89,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"6f9fedab8b4db4329966ffa3c9c37581bc999f0908801f692f7f7c51495e9712"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1779647400000,"buyAmount":12083.12,"sellAmount":11261.37,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"71f4dc6b8f33fe23ad767f3b29104592fdbb3b149446bc7a439f56a13d0d6f31"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1779388200000,"buyAmount":10972.76,"sellAmount":15413.23,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"be6e51d16eadb1640f28db4c8493aed9a15ae64184400c89728692bfbca61d39"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1779301800000,"buyAmount":12322.16,"sellAmount":14213.37,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"2d9f36a4787efba1392ec2d64a0b189b01d6f8df51e8be23c8b62e67134a7269"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1779215400000,"buyAmount":14139.56,"sellAmount":15736.91,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"972cea50287459e5636bbee2b32e3828aab0258f54db13d43c2bfb994927c791"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1779129000000,"buyAmount":17907.55,"sellAmount":20365.04,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"97efe1f41a2885a30acf50be1e09aa78217cd149885887579beb5b836c65bb0e"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1779042600000,"buyAmount":17222.18,"sellAmount":14408.49,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"52b7a09b1ff4541a9d60b0854c530d73d32bfa61ac29542771614251e29e2e72"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1778783400000,"buyAmount":16299.6,"sellAmount":14970.43,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"7132847c532c9273422fa80dadb9a560ec3b3c3ece48e7731b6c4fa61a856ec1"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1778697000000,"buyAmount":17350.56,"sellAmount":17163.1,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"4902c1cf2d2c24037ebd4acfb5cae73e97e9c2ad421e12007366c2454d4f3caa"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1778610600000,"buyAmount":14373.99,"sellAmount":19077.14,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"9c2c2e3815781978ac99215f1495fc94fa0a33af9a2a3a2962cf2993c9be105a"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1778524200000,"buyAmount":16555.27,"sellAmount":18514.66,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"86aaade605e92b92fbc9ce8e096fc415ea97bafbb8fba52a88bebaf655f8da82"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1778437800000,"buyAmount":12813.74,"sellAmount":21251.3,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"55feb3688770b0e55474b740c515bd671c9a5cdb23bac3561dce1e23ce6a6ee6"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1778178600000,"buyAmount":15083.49,"sellAmount":19194.09,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"c1c8757bd0191874ce1c76d06c325caf98a28ebd0ed66a3414f2aff6a387e1a1"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1778092200000,"buyAmount":17997.95,"sellAmount":18338.84,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"1e1a8ea522b5c8b87127b00d573a41de1313daf7c2574f67f6b747c8c4551bf0"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1778005800000,"buyAmount":14459.21,"sellAmount":20294.11,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"6eb779952fe77ff850f2731b1c6170cf4a27c49fe45a721953d8fe5fd259248c"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1777919400000,"buyAmount":10392.91,"sellAmount":14014.49,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"9851381810fd24e132e2e9ad9074d2f5270e77fd84692eb3fe9f0896239ad321"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1777833000000,"buyAmount":19660.47,"sellAmount":16824.85,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"1dd0a9a2c46b09d3c181b01890fcc4da5d6684aef2fdabfbae9e237fdce4a66a"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1774981800000,"buyAmount":18536.73,"sellAmount":11364.93,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"b326caeef4f116973f8487c4e8ef78419159d55d10a1fadd5948864a65f3d551"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1777487400000,"buyAmount":18252.89,"sellAmount":14765.79,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"ec3279ec76640c65da99a507d2d9510b4821d2fd574b9d6fa04c46eb18742886"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1777401000000,"buyAmount":17232.28,"sellAmount":14970.11,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"5713f8e2c28e4670d77fc3b3858aa4c8f66eab2751d1a3e655be28fbaf409777"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1777314600000,"buyAmount":18044.05,"sellAmount":16332.04,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"f1f3db20583cc4d82d59308a80f88a9ad100693027d20cd2291ab355014f377b"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1777228200000,"buyAmount":19978.34,"sellAmount":15854.42,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"c9d98ad505d8bd4a05b9b43e8473661f64c60106b4b6d8bbc3c9195b0a6554f0"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1776969000000,"buyAmount":21560.16,"sellAmount":16859.45,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"844ba9a71b7595be8c6cb4a47aa48f07541112e1b0cbf383eb0195e67cb2a139"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1776882600000,"buyAmount":18498.19,"sellAmount":17556.84,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"4f43602474f0145f32cd6f9ac9b1c3d1f3ae38a68ef54c96767274a88e1cb172"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1776796200000,"buyAmount":18704.25,"sellAmount":19752.42,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"308c7767138dbe834429208be0f63baeefdd5c298ba9638a2480cc305bf648bd"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1776709800000,"buyAmount":18366.67,"sellAmount":16145.4,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"602365944e10c4752846266308201fe8c3374246402e08e241b50697ac902446"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1776623400000,"buyAmount":18753.06,"sellAmount":15786.17,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"7d0a3513245933e4600926da8fcc4ed9964acbc24d5a401065ffb332551fd21d"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1776364200000,"buyAmount":17513.99,"sellAmount":22235.47,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"bea3c7978b660c766e718ca8537ed23efe3ec198e69922ae0c9c103c18905603"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1776277800000,"buyAmount":16538.08,"sellAmount":19965.83,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"f3168d86bc9ad27feda8980fc06bf4433adacfa6389bd8584dba3b3c64113b99"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1776191400000,"buyAmount":18499.57,"sellAmount":19068.55,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"23fd03f805d696eef586a270a74cb9b18df9f91add1ec2027b3feb43abebd86b"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1776018600000,"buyAmount":16612.03,"sellAmount":14179.73,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"4a1fa33d516d7dbc3ab11754aa119483422a6bd44699d93c1b8b83a85ac552a4"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1775759400000,"buyAmount":15982.46,"sellAmount":15572.41,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"1414e70c9e1c5e25441c7062b3a19cc89b35b5bcc50a7c84178719ecaa4ad3f0"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1775673000000,"buyAmount":15968.21,"sellAmount":15012.31,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"b8b7ddffbbf5f7935eaa2d90523d6a06979a3d9110547a60b25e14b376fd88f5"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1775586600000,"buyAmount":29003.39,"sellAmount":24835.22,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"fceb73c965170b8597d1b036efcff9937d669eb59c2a3e9080803b7dcb6f4ca1"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1775500200000,"buyAmount":20860.09,"sellAmount":12880.59,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"8d8e0d879f5f17621ecf049c41f53ae052d78bdefef8d65c889364cfa385ba76"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1775413800000,"buyAmount":20445.57,"sellAmount":12356.87,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"7d5a0fc95b6ded46dc257b7bbb720d7537d8cc80059b559099a186a007334912"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1775068200000,"buyAmount":18421.27,"sellAmount":11212.86,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"144350a149b77a7ef37ac2938c4aac3a06ed704d95bd20c16b744e04ba53de4c"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1779993000000,"buyAmount":36999.7,"sellAmount":20235.56,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"14d2f6b8b5e7869ef3e1c750c872c5bad8d9a5bf83e67cd7936078865f073655"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1779820200000,"buyAmount":16893.1,"sellAmount":13072.1,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"91217218b05f6ad583395da2aca5e5ab059af00d2d88fd676aea13926cc6ee28"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1779733800000,"buyAmount":15536.74,"sellAmount":14175.31,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"0e80418e9a57abe048cafcbb3109ba38411bae869921865850670d77b893e06a"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1779647400000,"buyAmount":16434.96,"sellAmount":12578.08,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"a9407f12e2f44d972dffae31a7110d74d1e6f911c4e2993307d27852a04b4e4b"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1779388200000,"buyAmount":18436.72,"sellAmount":12433.19,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"e6691a0e27744c156f03f78c3f5472c4b8aac0fbf1a7349c680b4e0b4d281060"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1779301800000,"buyAmount":15857.03,"sellAmount":13364.61,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"1c60ccb53ec73476fd13540a7441c1318a0746cbaa85fe0fbd59b9d1d6abca07"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1779215400000,"buyAmount":16000.79,"sellAmount":14032.44,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"b701f3009311bb09386f909c7342e19c9ba21531a512dc634664fbbd035dfe2a"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1779129000000,"buyAmount":16951.95,"sellAmount":13150.27,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"df05b35f9d24df12690cf51c9c9c63cc2f3e47bc9216d76ed723300e3d10ba95"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1779042600000,"buyAmount":16844.94,"sellAmount":14162.82,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"7d17217c91fcc6ce6e64a29c9fb721432c0387249f51248cabe0ee3245c07941"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1778783400000,"buyAmount":14961.88,"sellAmount":16920.7,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"89a8fa0e2a7fb42fba90be8dabd5c6d0a53f3f3e6af1cec7e7ffc63cfc51bebf"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1778697000000,"buyAmount":18255.96,"sellAmount":17571.63,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"36fbd3d9e7e958bc2f6ef56e0f446b4842dd822ddbdabe2a4aa96643ab557bd0"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1778610600000,"buyAmount":18872.65,"sellAmount":13003.6,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"e754a7cbef7c93fc9eacbbf624a33cfdb41e840f94bdb1a8d2813b00e56a1987"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1778524200000,"buyAmount":20684.82,"sellAmount":12694.5,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"446b2c8015de7b9120ce373b9765bef70f89968f8c78e4c68f9992dc58bd29c7"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1778437800000,"buyAmount":21626.43,"sellAmount":15686.78,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"ec3924edd31f3256db1e5a4a51f08f8bdb617a645d519ca49785adf1ec5da6ff"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1778178600000,"buyAmount":21296.87,"sellAmount":14548.74,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"697b2fdc11c0f3fd86ee01017d9c03c627185e828a1cac40a23fef691644caab"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1778092200000,"buyAmount":17032.05,"sellAmount":16590.98,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"3f8c690f9069d89a89c9ea99f9c5ef3530f1a5fcd650ce92041dbad06c4677dc"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1778005800000,"buyAmount":22888.16,"sellAmount":16051.29,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"1ddb3d0108bf235a49da130911d5e6ac936e04a5312b3eeb06c1b337e55ba5cc"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1777919400000,"buyAmount":16234.31,"sellAmount":13631.69,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"d84842dc7d04bd6858a56dbb89841a9f1d1a466a6aee11833f537a324d123b99"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1777833000000,"buyAmount":19516.11,"sellAmount":14751.95,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"f1c92270ed72fd4f6466886c52ebf063333de29ac4ca424248d4da5f8b61a0d0"}
```

```json
// File: fiidii/FII-DII.metadata.json
{
  "totalRecords" : 234,
  "fiiRecords" : 195,
  "diiRecords" : 39,
  "lastUpdated" : 1781583476529,
  "latestTimestamp" : 1779993000000,
  "latestFiiDate" : "2026-05-28",
  "latestDiiDate" : "2026-05-28"
}
```
