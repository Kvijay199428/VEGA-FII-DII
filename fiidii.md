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
        status.put("bootstrapComplete", bootstrapService.isBootstrapCompleted());
        
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

    @Scheduled(cron = "0 0 16 * * MON-FRI", zone = "Asia/Kolkata")
    public void startDailySync() {
        if (!bootstrapService.isBootstrapCompleted()) {
            logger.info("Bootstrap is not completed yet, skipping daily sync.");
            return;
        }

        logger.info("Starting daily market close FII and DII refresh...");
        bootstrapService.syncData("FII", archiveService.getLatestFiiDate());
        bootstrapService.syncData("DII", archiveService.getLatestDiiDate());
        logger.info("Completed daily FII and DII refresh.");
    }

    @Scheduled(cron = "0 0 17-23 * * MON-FRI", zone = "Asia/Kolkata")
    public void retryIfMissing() {
        if (!bootstrapService.isBootstrapCompleted()) {
            return;
        }

        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));

        if (archiveService.getLatestFiiDate() == null || !today.equals(archiveService.getLatestFiiDate())) {
            logger.info("Hourly retry: Syncing FII activity...");
            bootstrapService.syncData("FII", archiveService.getLatestFiiDate());
        }

        if (archiveService.getLatestDiiDate() == null || !today.equals(archiveService.getLatestDiiDate())) {
            logger.info("Hourly retry: Syncing DII activity...");
            bootstrapService.syncData("DII", archiveService.getLatestDiiDate());
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
    private volatile boolean bootstrapCompleted = false;
    private final java.util.concurrent.locks.ReentrantLock syncLock = new java.util.concurrent.locks.ReentrantLock();

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
        if (!syncLock.tryLock()) {
            logger.warn("[{}] Sync already running, skipping concurrent execution.", category);
            return;
        }
        try {
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

            LocalDate maxReturnedDate = null;
            if (!records.isEmpty()) {
                maxReturnedDate = records.stream()
                        .map(r -> java.time.Instant.ofEpochMilli(r.getTimeStamp())
                                .atZone(ZoneId.of("Asia/Kolkata"))
                                .toLocalDate())
                        .max(LocalDate::compareTo)
                        .orElse(null);

                if (maxReturnedDate != null && maxReturnedDate.isBefore(chunkEnd)) {
                    logger.warn("[{}] Provider returned stale or missing data. Requested to {}, latest returned {}", category, chunkEnd, maxReturnedDate);
                }

                logger.info("[{}] Fetched {} records. Appending...", category, records.size());
                archiveService.appendRecords(records);
            }

            if (maxReturnedDate != null) {
                archiveService.setProviderLatestDate(category, maxReturnedDate);
            }

            if (maxReturnedDate == null || maxReturnedDate.isBefore(currentDate)) {
                logger.warn("[{}] Provider has no data for requested range {} to {}. Latest available appears to be {}. Stopping sync for this category.", category, fromStr, toStr, maxReturnedDate);
                break;
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
        } finally {
            syncLock.unlock();
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
```

```json
// File: fiidii/FII-DII.metadata.json
{
  "totalRecords" : 120,
  "fiiRecords" : 100,
  "diiRecords" : 20,
  "lastUpdated" : 1781607459489,
  "latestTimestamp" : 1777487400000,
  "latestFiiDate" : "2026-04-30",
  "latestDiiDate" : "2026-04-30",
  "providerLatestFiiDate" : "2026-04-30",
  "providerLatestDiiDate" : "2026-04-30"
}
```
