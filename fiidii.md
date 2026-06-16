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

    public List<InstitutionalFlowRecord> fetchAdHoc(String category, String dataType, String interval, String fromDate) {
        String endpoint = "FII".equalsIgnoreCase(category) ? 
                configService.getConfig().getFii().getEndpoint() : 
                configService.getConfig().getDii().getEndpoint();
        
        List<String> dataTypes = (dataType != null && !dataType.isEmpty()) ? 
                List.of(dataType) : 
                ("FII".equalsIgnoreCase(category) ? configService.getFiiDataTypes() : configService.getDiiDataTypes());
                
        return fetchFiiDiiData(endpoint, dataTypes, category.toUpperCase(), interval, fromDate);
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
                                record.getCategory(), record.getDataType(), record.getTimeStamp()
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

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/fiidii")
public class FiiDiiController {

    private final FiiDiiArchiveService archiveService;
    private final UpstoxFiiDiiClient upstoxClient;

    public FiiDiiController(FiiDiiArchiveService archiveService, UpstoxFiiDiiClient upstoxClient) {
        this.archiveService = archiveService;
        this.upstoxClient = upstoxClient;
    }

    @GetMapping
    public List<InstitutionalFlowRecord> getFiiDiiData(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String dataType,
            @RequestParam(required = false) String interval,
            @RequestParam(required = false) String from) {
        
        if (interval != null || from != null) {
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

import com.vega.fiidii.client.UpstoxFiiDiiClient;
import com.vega.fiidii.model.InstitutionalFlowRecord;
import com.vega.fiidii.service.FiiDiiArchiveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class FiiDiiRefreshScheduler {
    private static final Logger logger = LoggerFactory.getLogger(FiiDiiRefreshScheduler.class);

    private final UpstoxFiiDiiClient client;
    private final FiiDiiArchiveService archiveService;

    public FiiDiiRefreshScheduler(UpstoxFiiDiiClient client, FiiDiiArchiveService archiveService) {
        this.client = client;
        this.archiveService = archiveService;
    }

    @Scheduled(fixedRateString = "#{@fiiDiiConfigService.getRefreshIntervalMinutes() * 60000}", initialDelay = 10000)
    public void refreshFiiDii() {
        logger.info("Starting scheduled FII and DII refresh...");

        logger.info("Fetching FII activity...");
        List<InstitutionalFlowRecord> fiiRecords = client.fetchFii();
        if (!fiiRecords.isEmpty()) {
            logger.info("Fetched {} FII records. Appending to archive.", fiiRecords.size());
            archiveService.appendRecords(fiiRecords);
        }

        logger.info("Fetching DII activity...");
        List<InstitutionalFlowRecord> diiRecords = client.fetchDii();
        if (!diiRecords.isEmpty()) {
            logger.info("Fetched {} DII records. Appending to archive.", diiRecords.size());
            archiveService.appendRecords(diiRecords);
        }

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
                } catch (JsonProcessingException e) {
                    logger.warn("Failed to parse line: {}", line);
                }
            });
            logger.info("Loaded {} records from archive.", inMemoryArchive.size());
        } catch (IOException e) {
            logger.error("Failed to load archive", e);
        }
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
                .orElse(0);
        metadata.setLatestTimestamp(latestTimestamp);

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
// File: src/main/java/com/vega/fiidii/util/HashUtil.java
package com.vega.fiidii.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class HashUtil {
    public static String generateSourceHash(String category, String dataType, long timeStamp) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String input = category + dataType + timeStamp;
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

```jsonl
// File: fiidii/FII-DII.jsonl
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1781461800000,"buyAmount":22590.98,"sellAmount":22563.67,"buyContracts":346959,"sellContracts":340265,"oiContracts":7480354,"oiAmount":467886.91,"totalLongContracts":4135474,"totalShortContracts":3344880,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"9c0d857b9887082cd4fd9dba1cc515918ab50196c691ae7cbd4587e515a92373"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1781202600000,"buyAmount":23008.57,"sellAmount":19167.7,"buyContracts":355451,"sellContracts":290659,"oiContracts":7462082,"oiAmount":460815.7,"totalLongContracts":4122991,"totalShortContracts":3339091,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"8c07a68d8f3b053e08b7640667cd6ba108fefe7c340e5dbad7ddf55e18218fb6"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1781116200000,"buyAmount":15269.58,"sellAmount":16208.8,"buyContracts":243893,"sellContracts":253945,"oiContracts":7442168,"oiAmount":449167.3,"totalLongContracts":4080638,"totalShortContracts":3361530,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"55913943b42cee22a1237c33c8e7e46629805de76aab4fc31082241b6e87cf5e"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1781029800000,"buyAmount":16945.35,"sellAmount":18047.8,"buyContracts":264654,"sellContracts":273705,"oiContracts":7388696,"oiAmount":448947.5,"totalLongContracts":4058928,"totalShortContracts":3329768,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"4472767930065b94929939d5981b755901cf04b096f0d38153fa2f156ccf5a5c"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1780943400000,"buyAmount":18904.5,"sellAmount":16030.55,"buyContracts":284230,"sellContracts":245768,"oiContracts":7354913,"oiAmount":452543.62,"totalLongContracts":4046562,"totalShortContracts":3308351,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"ecb8aac13d10f2f4f535b6677ed19c94e646f67112bddd28aff2b151d364ed6a"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1780857000000,"buyAmount":14697.92,"sellAmount":14677.28,"buyContracts":228502,"sellContracts":229731,"oiContracts":7333401,"oiAmount":445583.96,"totalLongContracts":4016575,"totalShortContracts":3316826,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"3395a450a96eaf592f7ebb885abedaf750eedc99c7c7387178626d703381219d"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1780597800000,"buyAmount":17799.54,"sellAmount":17179.41,"buyContracts":270686,"sellContracts":258526,"oiContracts":7316102,"oiAmount":451762.99,"totalLongContracts":4008540,"totalShortContracts":3307562,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"4242a23da71be59cc8661c776d46d9611f66350dc5a1a95254ee1375d2069ec0"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1780511400000,"buyAmount":17752.29,"sellAmount":17978.83,"buyContracts":281654,"sellContracts":283656,"oiContracts":7300478,"oiAmount":451798.42,"totalLongContracts":3994648,"totalShortContracts":3305830,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"f3605edc796f79ac02efeac95dad60de50365148a1c2b42039ee67b0c2863b1d"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1780425000000,"buyAmount":22279.77,"sellAmount":24013.83,"buyContracts":358715,"sellContracts":402712,"oiContracts":7283224,"oiAmount":450335.53,"totalLongContracts":3987022,"totalShortContracts":3296202,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"f1b4705e74ce0959585d2f67aeafa4ebb0ebd92a70671372c66ee6610f29e87b"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1780338600000,"buyAmount":27613.62,"sellAmount":22401.95,"buyContracts":450720,"sellContracts":355703,"oiContracts":7320239,"oiAmount":456347.33,"totalLongContracts":4027528,"totalShortContracts":3292711,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"ce137bf52442e04c58bede2ecad055899065d2500570571e4e5ea2a6b4af5846"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1780252200000,"buyAmount":19513.34,"sellAmount":20046.0,"buyContracts":304449,"sellContracts":310861,"oiContracts":7408352,"oiAmount":457138.63,"totalLongContracts":4024076,"totalShortContracts":3384276,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"d5a87a6cece7c6f36404ceae35fc76a7030f8de886f1e7037a5fc481c245ef96"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1779993000000,"buyAmount":45943.66,"sellAmount":47073.79,"buyContracts":741325,"sellContracts":733581,"oiContracts":7365604,"oiAmount":460332.04,"totalLongContracts":4005908,"totalShortContracts":3359696,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"0623027ed14adf88b3716b8978a2ae2477ff56bef00f840d26edf17a9041fbe2"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1779820200000,"buyAmount":18955.42,"sellAmount":19659.22,"buyContracts":290470,"sellContracts":301089,"oiContracts":7452284,"oiAmount":469032.79,"totalLongContracts":4045376,"totalShortContracts":3406908,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"6825e0b379847c113ba2318a038ca7447b6f22eace55731ce9d53b080e5c89ea"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1779733800000,"buyAmount":38003.09,"sellAmount":36622.88,"buyContracts":595489,"sellContracts":570933,"oiContracts":7411359,"oiAmount":464329.99,"totalLongContracts":4030223,"totalShortContracts":3381136,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"d46946c2ddc8a1dbbf19500592fb7aff97d63dfc1bc7c75fa0de2f78e6f17618"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1779647400000,"buyAmount":118081.48,"sellAmount":115597.36,"buyContracts":1879403,"sellContracts":1844609,"oiContracts":7727712,"oiAmount":484959.81,"totalLongContracts":4199409,"totalShortContracts":3528303,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"52b43cc42f93337c1080bbac01929f361caf0301f8b325872091f9f6df7625c4"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1779388200000,"buyAmount":169457.57,"sellAmount":164983.84,"buyContracts":2728932,"sellContracts":2670076,"oiContracts":7729034,"oiAmount":479369.65,"totalLongContracts":4182673,"totalShortContracts":3546361,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"b66b1d063df20d8a7dfdce1836275b53fd33626d73cc59ba03082a608200a3f2"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1779301800000,"buyAmount":159884.28,"sellAmount":159741.77,"buyContracts":2594725,"sellContracts":2598525,"oiContracts":7736634,"oiAmount":476278.32,"totalLongContracts":4157045,"totalShortContracts":3579589,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"88ba6fb8ad7610cef82c61c7eccd401162145fadf791663ea7384862ddebb633"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1779215400000,"buyAmount":34990.1,"sellAmount":35920.16,"buyContracts":559444,"sellContracts":585110,"oiContracts":7785508,"oiAmount":478173.87,"totalLongContracts":4183382,"totalShortContracts":3602126,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"4b5522808b840beacd2f2e311d819f78192b0fa1be38a7ff53fb028390b19dfb"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1779129000000,"buyAmount":24093.53,"sellAmount":25972.17,"buyContracts":381303,"sellContracts":407653,"oiContracts":7713152,"oiAmount":472447.0,"totalLongContracts":4160037,"totalShortContracts":3553115,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"6462fe71776f1ae1d3b0672ad6483747f43b2a6f775a5d42cd07f56dfb370711"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1779042600000,"buyAmount":21917.31,"sellAmount":22496.35,"buyContracts":350226,"sellContracts":359085,"oiContracts":7684266,"oiAmount":468055.53,"totalLongContracts":4158769,"totalShortContracts":3525497,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"2bf21ecec8b153bd07c50b761c5fa329858b75876a5d0cf21f38c44c06508a50"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1778783400000,"buyAmount":20741.67,"sellAmount":21940.1,"buyContracts":333139,"sellContracts":344349,"oiContracts":7656067,"oiAmount":467793.07,"totalLongContracts":4149099,"totalShortContracts":3506968,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"094c84133f547c4286c4880fc17e1824405be35fb5a50a8c66ce2478f4b25417"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1778697000000,"buyAmount":23470.81,"sellAmount":23083.97,"buyContracts":370362,"sellContracts":388497,"oiContracts":7617543,"oiAmount":467464.42,"totalLongContracts":4135442,"totalShortContracts":3482101,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"6c970d6e0edb23ea9c92f5af10a96a1778201db102923e6c3846687c37dfdb86"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1778610600000,"buyAmount":24810.45,"sellAmount":22731.28,"buyContracts":392165,"sellContracts":365045,"oiContracts":7535946,"oiAmount":460387.75,"totalLongContracts":4103711,"totalShortContracts":3432235,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"97f1147164f5fbaabe834d24a6a0b3d7dba7342bf213184247c98b346763c754"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1778524200000,"buyAmount":21355.91,"sellAmount":22006.94,"buyContracts":334981,"sellContracts":349280,"oiContracts":7490868,"oiAmount":456049.31,"totalLongContracts":4067612,"totalShortContracts":3423256,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"d8fef594bc71adbf3cdba14853c0be58c8a04e823db28434872c4bbf16d92793"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1778437800000,"buyAmount":22023.48,"sellAmount":23240.59,"buyContracts":325672,"sellContracts":349900,"oiContracts":7424899,"oiAmount":464218.57,"totalLongContracts":4041777,"totalShortContracts":3383122,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"f783688a96b965fa43687623fc03f705d83e70ee5862ad0a94233e40b010de1c"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1778178600000,"buyAmount":21162.65,"sellAmount":25353.81,"buyContracts":314611,"sellContracts":373638,"oiContracts":7401559,"oiAmount":469673.56,"totalLongContracts":4042221,"totalShortContracts":3359338,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"8b0a256ee836a0be43678c377123dd4502b2f0d548a3c20bca3aa746bcf5f699"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1778092200000,"buyAmount":21822.61,"sellAmount":24554.66,"buyContracts":319770,"sellContracts":376900,"oiContracts":7406864,"oiAmount":471902.44,"totalLongContracts":4074387,"totalShortContracts":3332477,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"9ec9a3859f47b8536cca7a259785893da257dc1f074c7b78d9a7d91eb1e3d73a"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1778005800000,"buyAmount":26478.69,"sellAmount":25749.01,"buyContracts":399145,"sellContracts":391377,"oiContracts":7373080,"oiAmount":469871.32,"totalLongContracts":4086060,"totalShortContracts":3287020,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"222b1e71acd57dda10e3fdf42ab54f280d8545a326bb384c8589fd7e13412782"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1777919400000,"buyAmount":20391.55,"sellAmount":21066.11,"buyContracts":305088,"sellContracts":319654,"oiContracts":7327780,"oiAmount":460039.85,"totalLongContracts":4059526,"totalShortContracts":3268254,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"c5de22592a8fe273c3c950b6476521ae39a29ae77016ea638cad13c7578b709f"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_FUTURES","timeStamp":1777833000000,"buyAmount":24351.6,"sellAmount":23525.25,"buyContracts":356937,"sellContracts":349905,"oiContracts":7283052,"oiAmount":457284.13,"totalLongContracts":4044445,"totalShortContracts":3238607,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"75672b1ff688f4440e3554466c9d4f10ed6c61082da489872fda9839dfd16f53"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1781461800000,"buyAmount":1411384.71,"sellAmount":1416831.33,"buyContracts":9041105,"sellContracts":9081837,"oiContracts":3342427,"oiAmount":529033.28,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":672396,"totalPutLongContracts":1145247,"totalCallShortContracts":924723,"totalPutShortContracts":600061,"sourceHash":"008377a96762b9fea4ea48498c50087012945daf2acc18b985cdf270bda45472"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1781202600000,"buyAmount":1253977.39,"sellAmount":1240995.25,"buyContracts":8158334,"sellContracts":8068655,"oiContracts":3301371,"oiAmount":517772.82,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":623418,"totalPutLongContracts":1194064,"totalCallShortContracts":832438,"totalPutShortContracts":651452,"sourceHash":"743b40654e80d6b359354f9f939d021e3bd3320cbe840b87baf2c9a0c2453698"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1781116200000,"buyAmount":687055.25,"sellAmount":689063.63,"buyContracts":4512314,"sellContracts":4521962,"oiContracts":2821600,"oiAmount":433863.46,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":537462,"totalPutLongContracts":995295,"totalCallShortContracts":831387,"totalPutShortContracts":457456,"sourceHash":"ab1b97923c5d834ec73b1d7ba1040cf4639a42ae0c1365df043ef36a5246dfe1"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1781029800000,"buyAmount":570614.94,"sellAmount":571269.73,"buyContracts":3718066,"sellContracts":3725093,"oiContracts":2649882,"oiAmount":408515.1,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":521246,"totalPutLongContracts":930475,"totalCallShortContracts":798673,"totalPutShortContracts":399488,"sourceHash":"3d7fa511fe3690c5b021aa0178b6ce31fd6935ab193516a1d5e538c33ad7e53b"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1780943400000,"buyAmount":2706514.1,"sellAmount":2696076.27,"buyContracts":17877264,"sellContracts":17806343,"oiContracts":2373711,"oiAmount":367044.36,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":446983,"totalPutLongContracts":870166,"totalCallShortContracts":713453,"totalPutShortContracts":343108,"sourceHash":"2c77d1139d2310a39bd4635e1a56050084b5c9ee0af8a5e39796086eb2d96f9f"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1780857000000,"buyAmount":1278740.98,"sellAmount":1278619.83,"buyContracts":8420601,"sellContracts":8419270,"oiContracts":3089037,"oiAmount":470743.77,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":672055,"totalPutLongContracts":1000213,"totalCallShortContracts":965991,"totalPutShortContracts":450778,"sourceHash":"1082e55b4e7097327af09f124a5aff1902542cabd3cc8a1ef8a0b7be7e661756"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1780597800000,"buyAmount":1334789.59,"sellAmount":1332593.52,"buyContracts":8715018,"sellContracts":8703727,"oiContracts":2883538,"oiAmount":444073.97,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":591082,"totalPutLongContracts":977772,"totalCallShortContracts":867141,"totalPutShortContracts":447544,"sourceHash":"f07788edaac0b47895c0676aeff42633900dda28186d77c253ed3098697917e2"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1780511400000,"buyAmount":716383.94,"sellAmount":723226.26,"buyContracts":4673448,"sellContracts":4717228,"oiContracts":2625630,"oiAmount":405394.8,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":514106,"totalPutLongContracts":920148,"totalCallShortContracts":788094,"totalPutShortContracts":403282,"sourceHash":"3c269ab61955e444f70a5edf24f53f83994464c7f5a4e7061e262de456f4c268"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1780425000000,"buyAmount":880165.31,"sellAmount":868752.84,"buyContracts":5732201,"sellContracts":5660862,"oiContracts":2493813,"oiAmount":385015.58,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":487011,"totalPutLongContracts":903225,"totalCallShortContracts":738356,"totalPutShortContracts":365221,"sourceHash":"e6d3abbb91eb8a5e04ebff60598f11d057e565a11220a7eb955889dfe73afec7"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1780338600000,"buyAmount":3756939.07,"sellAmount":3775091.96,"buyContracts":24630534,"sellContracts":24739191,"oiContracts":2139322,"oiAmount":330975.9,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":382230,"totalPutLongContracts":795091,"totalCallShortContracts":662013,"totalPutShortContracts":299989,"sourceHash":"20a53e7aaba9603b1e62e6e3f14cbd6a9878b00bf9137b2ba816aa52166cdff6"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1780252200000,"buyAmount":1454786.85,"sellAmount":1446979.22,"buyContracts":9438471,"sellContracts":9389174,"oiContracts":2779133,"oiAmount":426652.54,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":640525,"totalPutLongContracts":869221,"totalCallShortContracts":894212,"totalPutShortContracts":375175,"sourceHash":"d1d0ae70bd4e231605cd670c558470f52cb4050158ad86a2dcc001cb640891d7"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1779993000000,"buyAmount":1094401.38,"sellAmount":1099772.59,"buyContracts":7017508,"sellContracts":7056687,"oiContracts":2600970,"oiAmount":402408.27,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":553247,"totalPutLongContracts":842769,"totalCallShortContracts":823871,"totalPutShortContracts":381083,"sourceHash":"2601cfb1f745138b7f5b6626bb2931489b9332addb8f92f7c1cb4e4527191f14"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1779820200000,"buyAmount":554682.58,"sellAmount":555013.3,"buyContracts":3543149,"sellContracts":3547295,"oiContracts":2159019,"oiAmount":339005.3,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":426192,"totalPutLongContracts":768438,"totalCallShortContracts":633361,"totalPutShortContracts":331028,"sourceHash":"8180e833897d3a3d371ba69a4ab8d217b4d26a08c2bb9b34cc33352ff47ba558"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1779733800000,"buyAmount":3440544.8,"sellAmount":3478562.51,"buyContracts":21670897,"sellContracts":21910333,"oiContracts":1769228,"oiAmount":278180.98,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":320160,"totalPutLongContracts":681647,"totalCallShortContracts":520515,"totalPutShortContracts":246905,"sourceHash":"4168f13092d3a87e58f183c5b667a84b639cbf6e18ff8c47b70c62e988f24eed"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1779647400000,"buyAmount":1297756.48,"sellAmount":1285074.46,"buyContracts":8208191,"sellContracts":8128579,"oiContracts":3337091,"oiAmount":529400.5,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":799957,"totalPutLongContracts":1024401,"totalCallShortContracts":843791,"totalPutShortContracts":668943,"sourceHash":"e0f239f4d28c14c2f4b5e2a5abb3dd7799954710c0682a34d0ed245a009d4baa"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1779388200000,"buyAmount":891073.54,"sellAmount":879018.42,"buyContracts":5690787,"sellContracts":5617160,"oiContracts":2928710,"oiAmount":458670.19,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":653857,"totalPutLongContracts":926504,"totalCallShortContracts":815806,"totalPutShortContracts":532543,"sourceHash":"8f672e265ccf9186a87f59810b6ea050a2ba38740241f0e29d4a4889f892cee5"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1779301800000,"buyAmount":804018.96,"sellAmount":803332.82,"buyContracts":5174288,"sellContracts":5172219,"oiContracts":2809846,"oiAmount":437199.86,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":564691,"totalPutLongContracts":919425,"totalCallShortContracts":820753,"totalPutShortContracts":504978,"sourceHash":"1595f1c060b6a543728b63b73d45230872c11eb71d8eda072c05a28cef194108"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1779215400000,"buyAmount":706114.09,"sellAmount":716249.46,"buyContracts":4542269,"sellContracts":4608422,"oiContracts":2435035,"oiAmount":379169.82,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":441464,"totalPutLongContracts":854211,"totalCallShortContracts":663620,"totalPutShortContracts":475740,"sourceHash":"1fa1919436f8063507ab9c5aa409621644fc37e1841aed26710321151b4363a8"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1779129000000,"buyAmount":3184215.11,"sellAmount":3211466.15,"buyContracts":20635541,"sellContracts":20816453,"oiContracts":2217183,"oiAmount":344582.58,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":406028,"totalPutLongContracts":813798,"totalCallShortContracts":611362,"totalPutShortContracts":385995,"sourceHash":"7f9bb5b3363ac48097e2645b4eb7cf25d6500177d24489b5d295f9dc2b5a58c1"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1779042600000,"buyAmount":1608003.87,"sellAmount":1600256.49,"buyContracts":10461032,"sellContracts":10406956,"oiContracts":3024351,"oiAmount":468808.3,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":517083,"totalPutLongContracts":1089492,"totalCallShortContracts":731449,"totalPutShortContracts":686328,"sourceHash":"6b3ac9942f44827c7a397628fe874a02601d488e882201c606939dd031e19edf"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1778783400000,"buyAmount":1124260.11,"sellAmount":1126393.59,"buyContracts":7248342,"sellContracts":7261201,"oiContracts":2739544,"oiAmount":425175.17,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":532822,"totalPutLongContracts":904311,"totalCallShortContracts":789341,"totalPutShortContracts":513070,"sourceHash":"6e9376024d2d144c3df4aef8e8ca4c71a34ec693e756f56d12f27523207eda21"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1778697000000,"buyAmount":727977.9,"sellAmount":734634.84,"buyContracts":4705278,"sellContracts":4747733,"oiContracts":2430007,"oiAmount":378564.02,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":424050,"totalPutLongContracts":864744,"totalCallShortContracts":656098,"totalPutShortContracts":485115,"sourceHash":"43aa3aaa270bc4c14515c3b736d381fc20faf57ae3c649465d0473a53bd4d75d"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1778610600000,"buyAmount":651824.36,"sellAmount":657576.58,"buyContracts":4230690,"sellContracts":4266600,"oiContracts":2331683,"oiAmount":359072.26,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":428267,"totalPutLongContracts":832593,"totalCallShortContracts":682279,"totalPutShortContracts":388545,"sourceHash":"355d19537230ac97b96e466dc12215289ab858d50d29ff3d3ced3a30e164d3ee"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1778524200000,"buyAmount":2842619.23,"sellAmount":2848379.49,"buyContracts":18436209,"sellContracts":18482668,"oiContracts":2144896,"oiAmount":330161.16,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":373291,"totalPutLongContracts":812130,"totalCallShortContracts":623294,"totalPutShortContracts":336181,"sourceHash":"72843f3df15a09f63e34b4f6bdb2ca2e3cc8a627a0eb54b593f61824f6df0da9"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1778437800000,"buyAmount":1351538.38,"sellAmount":1354718.0,"buyContracts":8636103,"sellContracts":8658082,"oiContracts":2652125,"oiAmount":414522.7,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":523725,"totalPutLongContracts":859256,"totalCallShortContracts":822482,"totalPutShortContracts":446661,"sourceHash":"2b5d32d853d4e7cdfa6683a546b02685895b9515b9b08fe41c53a1e2407a71c2"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1778178600000,"buyAmount":905591.95,"sellAmount":902623.61,"buyContracts":5711420,"sellContracts":5696888,"oiContracts":2443340,"oiAmount":388025.89,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":472541,"totalPutLongContracts":817039,"totalCallShortContracts":722675,"totalPutShortContracts":431086,"sourceHash":"267a0973495ee195d7a9b714f32866dc9b048d05064e7b4dd4ac85664c580b9d"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1778092200000,"buyAmount":739954.96,"sellAmount":747238.64,"buyContracts":4639963,"sellContracts":4684661,"oiContracts":2153916,"oiAmount":344787.71,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":356458,"totalPutLongContracts":781143,"totalCallShortContracts":613358,"totalPutShortContracts":402957,"sourceHash":"56d12d5df6403ee8d744407e9f30705796758c5f7c89b4cc8f577cb927fbc620"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1778005800000,"buyAmount":852162.62,"sellAmount":853472.77,"buyContracts":5394369,"sellContracts":5403192,"oiContracts":2075173,"oiAmount":331933.91,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":368811,"totalPutLongContracts":751767,"totalCallShortContracts":559231,"totalPutShortContracts":395364,"sourceHash":"db67af83f3413cbe1f4bbd4ec3cca29ca4791840ddbda6d6c0c8d01ec8a6833b"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1777919400000,"buyAmount":3676612.75,"sellAmount":3703779.04,"buyContracts":23492302,"sellContracts":23661914,"oiContracts":1785307,"oiAmount":281394.16,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":289256,"totalPutLongContracts":690801,"totalCallShortContracts":496740,"totalPutShortContracts":308510,"sourceHash":"d3710463ff1c9b63919ec72c3be670db5d629a038338bfd5ee181bbd4f8d5dfa"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_OPTIONS","timeStamp":1777833000000,"buyAmount":1939940.28,"sellAmount":1935304.7,"buyContracts":12312942,"sellContracts":12287303,"oiContracts":2568707,"oiAmount":405114.15,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":563705,"totalPutLongContracts":802982,"totalCallShortContracts":750271,"totalPutShortContracts":451750,"sourceHash":"f8189bf5b301beed9d4f3ec666f4df8e251f92bb3f64d12f606ec8ba6dd6f4b9"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1781461800000,"buyAmount":3098.32,"sellAmount":2769.44,"buyContracts":19440,"sellContracts":17058,"oiContracts":323389,"oiAmount":51514.98,"totalLongContracts":41074,"totalShortContracts":282315,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"afa1a3570085655f67046f9306f5d34e8b7b43e98c951f5073b1406d6142c0df"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1781202600000,"buyAmount":7207.2,"sellAmount":3604.23,"buyContracts":46080,"sellContracts":22720,"oiContracts":323565,"oiAmount":51118.98,"totalLongContracts":39971,"totalShortContracts":283594,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"53b27d5c33a3e8ac6f94c6973b1d93793b56efe4f3562dd817a06711e15bece7"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1781116200000,"buyAmount":2981.99,"sellAmount":2201.44,"buyContracts":19186,"sellContracts":14190,"oiContracts":336111,"oiAmount":51886.25,"totalLongContracts":34564,"totalShortContracts":301547,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"fabf249f724cc90b60f67f9844db6c825ad50e87e660b606f1ab91331c2e24ac"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1781029800000,"buyAmount":2300.12,"sellAmount":1504.48,"buyContracts":14466,"sellContracts":9444,"oiContracts":337375,"oiAmount":52145.34,"totalLongContracts":32698,"totalShortContracts":304677,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"3994b4824fe0d4790c8885769419bda40a708ca9b2170f4154e3d3a10fb61ba0"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1780943400000,"buyAmount":2411.49,"sellAmount":2225.56,"buyContracts":15107,"sellContracts":14494,"oiContracts":339341,"oiAmount":52702.14,"totalLongContracts":31170,"totalShortContracts":308171,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"63742e135f89a221955df0f512da0ba00fa1d979cd2880274f5f4bbfca908557"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1780857000000,"buyAmount":1459.64,"sellAmount":2974.66,"buyContracts":9342,"sellContracts":19250,"oiContracts":327234,"oiAmount":50299.51,"totalLongContracts":24810,"totalShortContracts":302424,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"f9decc068d5ef0f50d7c974967dba73dd8aaf9bd2a06562deb47bf6d7f6fd533"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1780597800000,"buyAmount":3150.31,"sellAmount":3621.0,"buyContracts":19900,"sellContracts":23038,"oiContracts":319622,"oiAmount":49719.71,"totalLongContracts":25958,"totalShortContracts":293664,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"5755adf03e639606490aacad68c5dbb6f45778c89a682507197487988997b6c0"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1780511400000,"buyAmount":3053.1,"sellAmount":3851.22,"buyContracts":19659,"sellContracts":24974,"oiContracts":320910,"oiAmount":50027.74,"totalLongContracts":28171,"totalShortContracts":292739,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"016a2b6cbb52c770931886a6a768d0e847a2ba1a8e64770d6ca3cb9b4ceb662d"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1780425000000,"buyAmount":4699.11,"sellAmount":9118.9,"buyContracts":30303,"sellContracts":59460,"oiContracts":311155,"oiAmount":48524.5,"totalLongContracts":25951,"totalShortContracts":285204,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"a15d09a62c2f7f74eb87c75e589bbd8aac82fde47eff480fa451aa4481186147"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1780338600000,"buyAmount":3124.78,"sellAmount":4165.94,"buyContracts":20005,"sellContracts":26921,"oiContracts":279172,"oiAmount":43697.17,"totalLongContracts":24538,"totalShortContracts":254634,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"9ae6fa441826740342c1c85fe0027f2b728e8242206910d967d48097a0cc1621"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1780252200000,"buyAmount":1698.66,"sellAmount":5105.41,"buyContracts":10846,"sellContracts":32717,"oiContracts":276824,"oiAmount":43170.67,"totalLongContracts":26822,"totalShortContracts":250002,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"a09181e39af03a9399532f470ad889f20e8684fe0ab68c42e7b6e0a001402f8d"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1779993000000,"buyAmount":1656.52,"sellAmount":7655.91,"buyContracts":10275,"sellContracts":48572,"oiContracts":264757,"oiAmount":41886.85,"totalLongContracts":31724,"totalShortContracts":233033,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"c0c801c2ef0cb664f3db3933acc8fd64f19c9e4eea6833cc196c05dcdd3e5288"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1779820200000,"buyAmount":1259.85,"sellAmount":2851.57,"buyContracts":7792,"sellContracts":17939,"oiContracts":240740,"oiAmount":38610.9,"totalLongContracts":38864,"totalShortContracts":201876,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"7dfbe907f34d87cfd63254a824749a5e6e10a073a1d407b0b357f09ffd7e628e"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1779733800000,"buyAmount":5098.7,"sellAmount":8362.25,"buyContracts":31918,"sellContracts":52644,"oiContracts":229387,"oiAmount":36850.67,"totalLongContracts":38261,"totalShortContracts":191126,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"5b8b76c25734d11dfd8460d4e741f581bcfc39e6a183c211d669b028b6b4cba5"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1779647400000,"buyAmount":9720.81,"sellAmount":8125.43,"buyContracts":60499,"sellContracts":50484,"oiContracts":317357,"oiAmount":50947.99,"totalLongContracts":51190,"totalShortContracts":266167,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"e100185eb2c2d45a98aeb3b321b4c6b57d17a8783265bed21c87d1afd1f6dec9"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1779388200000,"buyAmount":11534.62,"sellAmount":10545.47,"buyContracts":72238,"sellContracts":66040,"oiContracts":312052,"oiAmount":49253.88,"totalLongContracts":43530,"totalShortContracts":268522,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"23e3a9128a31270ba96e0a7cd371ae33beff68197987363aabf015197c5bd4d2"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1779301800000,"buyAmount":5600.9,"sellAmount":6950.05,"buyContracts":34835,"sellContracts":43505,"oiContracts":302570,"oiAmount":47395.9,"totalLongContracts":35690,"totalShortContracts":266880,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"f3bba4691c3846972d859e4d5d1a21eb591fe0b2b21caf79e4f7ae15ffd3ac3f"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1779215400000,"buyAmount":1955.92,"sellAmount":2920.88,"buyContracts":12474,"sellContracts":18714,"oiContracts":292706,"oiAmount":45938.38,"totalLongContracts":35093,"totalShortContracts":257613,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"fc54ab36e92af21501b66757e6e5b9ffd12e879bece1a1bf840cf026e89309f4"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1779129000000,"buyAmount":2049.19,"sellAmount":3047.82,"buyContracts":12958,"sellContracts":19372,"oiContracts":286756,"oiAmount":44865.67,"totalLongContracts":35238,"totalShortContracts":251518,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"fc64bff29a4749e22af66a8228c6b28af0772d08e6b9b72e213d74846a99ccbb"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1779042600000,"buyAmount":4364.63,"sellAmount":3362.67,"buyContracts":28182,"sellContracts":21541,"oiContracts":279710,"oiAmount":43823.27,"totalLongContracts":34922,"totalShortContracts":244788,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"9e2182209cb5fe633ab043ed54417d4f9286d2d361528435ba699c4af5d23665"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1778783400000,"buyAmount":4188.02,"sellAmount":2794.84,"buyContracts":26853,"sellContracts":17749,"oiContracts":288159,"oiAmount":45123.02,"totalLongContracts":35826,"totalShortContracts":252333,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"27f528dda2d5cbfe7e3df222f3d108bdeb868bcef002ad5d9a056e0c5314c86b"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1778697000000,"buyAmount":4564.03,"sellAmount":2651.51,"buyContracts":29109,"sellContracts":16846,"oiContracts":295759,"oiAmount":46451.86,"totalLongContracts":35074,"totalShortContracts":260685,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"cefd36eda065347f5353dfdbc2e9b33e238cfcfbe66e700b809cb74da0179954"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1778610600000,"buyAmount":2767.16,"sellAmount":2894.74,"buyContracts":17713,"sellContracts":18443,"oiContracts":303616,"oiAmount":47144.32,"totalLongContracts":32871,"totalShortContracts":270745,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"7a999418bf42ce6088e48fafe99d69d384f2b9a4f43acb0815587fcd4e056736"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1778524200000,"buyAmount":2623.34,"sellAmount":5131.16,"buyContracts":16848,"sellContracts":33023,"oiContracts":304670,"oiAmount":47236.3,"totalLongContracts":33763,"totalShortContracts":270907,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"5ba83ee33d9036999ad646be5e1b5f6dce5e72563d70ef1def9bd860b1b90879"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1778437800000,"buyAmount":2252.23,"sellAmount":3937.64,"buyContracts":14113,"sellContracts":24970,"oiContracts":287717,"oiAmount":45486.61,"totalLongContracts":33374,"totalShortContracts":254343,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"04c0c8b243052951f8c75a3e86d8d889452454677163c880024a6edd756e2f2b"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1778178600000,"buyAmount":1379.87,"sellAmount":3657.63,"buyContracts":8501,"sellContracts":22665,"oiContracts":279344,"oiAmount":44880.9,"totalLongContracts":34616,"totalShortContracts":244728,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"52cfeed368424824d054d1f56e32b0c7d3e33cd2cc92a4b7c09ee5ec765fa840"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1778092200000,"buyAmount":1986.18,"sellAmount":2163.58,"buyContracts":11914,"sellContracts":13267,"oiContracts":270302,"oiAmount":43783.64,"totalLongContracts":37177,"totalShortContracts":233125,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"f57bc105f0629c5182b12c2019d85365587bf155d74cebf7b3338c2f0fc36dd9"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1778005800000,"buyAmount":3826.79,"sellAmount":3192.0,"buyContracts":23401,"sellContracts":19863,"oiContracts":266095,"oiAmount":43163.83,"totalLongContracts":35750,"totalShortContracts":230345,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"08d41479714817cd7a5b2f2470822c89029e801d4bec21eefd196ade4556e021"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1777919400000,"buyAmount":1233.83,"sellAmount":2105.35,"buyContracts":7723,"sellContracts":13284,"oiContracts":257331,"oiAmount":40983.83,"totalLongContracts":29599,"totalShortContracts":227732,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"cf250d86a93210cb625419b1d9809b1519ec2ada5e06af52f42bafcd54770d6c"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|INDEX_FUTURES","timeStamp":1777833000000,"buyAmount":1886.22,"sellAmount":3157.34,"buyContracts":11645,"sellContracts":19741,"oiContracts":251188,"oiAmount":40171.74,"totalLongContracts":29308,"totalShortContracts":221880,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"cf16268f5ee3ceb6f04506bbca67a1ee24150e3bf50a24b10a6ebda27469543b"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1781461800000,"buyAmount":33586.75,"sellAmount":34099.46,"buyContracts":519853,"sellContracts":528788,"oiContracts":1182129,"oiAmount":75543.01,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":236813,"totalPutLongContracts":332125,"totalCallShortContracts":378538,"totalPutShortContracts":234653,"sourceHash":"7bd1235ec03f541fb16a8191e561d244f52322d5dd132197916f47e2bc8b3538"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1781202600000,"buyAmount":31947.85,"sellAmount":31918.84,"buyContracts":497175,"sellContracts":496161,"oiContracts":1087948,"oiAmount":68341.84,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":211652,"totalPutLongContracts":314663,"totalCallShortContracts":359343,"totalPutShortContracts":202290,"sourceHash":"52ab332d75596fa3a3fbd261296edbb5714cd49c2f7bc850e42eeefe8681670f"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1781116200000,"buyAmount":24854.33,"sellAmount":25302.5,"buyContracts":387783,"sellContracts":396319,"oiContracts":1023804,"oiAmount":62925.48,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":187925,"totalPutLongContracts":305811,"totalCallShortContracts":349370,"totalPutShortContracts":180698,"sourceHash":"56ea95a1e9c4358e613582e7b920f2648b8435b0471c53638e62782b4e01d70d"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1781029800000,"buyAmount":26349.52,"sellAmount":25992.79,"buyContracts":413898,"sellContracts":408824,"oiContracts":955610,"oiAmount":59075.54,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":177305,"totalPutLongContracts":286602,"totalCallShortContracts":319195,"totalPutShortContracts":172508,"sourceHash":"260c164d69f60bab17de1e19101fe2e31cdb838a01c11b35c4c701f306325f49"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1780943400000,"buyAmount":18189.76,"sellAmount":18209.57,"buyContracts":274620,"sellContracts":275170,"oiContracts":892384,"oiAmount":55664.17,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":163044,"totalPutLongContracts":266713,"totalCallShortContracts":298250,"totalPutShortContracts":164377,"sourceHash":"67c2ca545f4c9b08a452f50da89923768704c2c221dcfd2803818c35cc3e8325"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1780857000000,"buyAmount":13240.54,"sellAmount":13311.22,"buyContracts":202846,"sellContracts":203431,"oiContracts":840344,"oiAmount":51886.06,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":151193,"totalPutLongContracts":252819,"totalCallShortContracts":278223,"totalPutShortContracts":158109,"sourceHash":"900a739d440ae44788c38d6aea76731d8ed86ba02f2d1cf6bc78f6ad27d6e4df"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1780597800000,"buyAmount":24854.77,"sellAmount":25390.8,"buyContracts":378734,"sellContracts":385931,"oiContracts":793529,"oiAmount":49767.53,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":145131,"totalPutLongContracts":235766,"totalCallShortContracts":261997,"totalPutShortContracts":150635,"sourceHash":"62b8437a7b419bdd2d4b9aa173dd674bb1919d05e0ca5ad33e4674b97baca4e5"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1780511400000,"buyAmount":19324.74,"sellAmount":19140.36,"buyContracts":302742,"sellContracts":302537,"oiContracts":742672,"oiAmount":46210.94,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":139392,"totalPutLongContracts":219675,"totalCallShortContracts":241321,"totalPutShortContracts":142284,"sourceHash":"2c26ce3fe4b0fbfc0d0328af1ce06fc361f4a0efc820eb2177fcca3556c0736e"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1780425000000,"buyAmount":24952.46,"sellAmount":24344.52,"buyContracts":400800,"sellContracts":390599,"oiContracts":695353,"oiAmount":43114.5,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":133089,"totalPutLongContracts":202216,"totalCallShortContracts":227173,"totalPutShortContracts":132875,"sourceHash":"aa3a0e267d308665f88636baf37e7c8ae644ea15d60fe23bf6d107025fd50544"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1780338600000,"buyAmount":22071.33,"sellAmount":21264.05,"buyContracts":362432,"sellContracts":345896,"oiContracts":605782,"oiAmount":38244.29,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":115920,"totalPutLongContracts":169499,"totalCallShortContracts":193513,"totalPutShortContracts":126850,"sourceHash":"4542cca1e827e08a0e486b049cab401ecd510ea076dfcd94697237742908ecd9"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1780252200000,"buyAmount":18024.96,"sellAmount":18439.51,"buyContracts":284219,"sellContracts":291107,"oiContracts":517726,"oiAmount":32634.99,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":88784,"totalPutLongContracts":144339,"totalCallShortContracts":173666,"totalPutShortContracts":110937,"sourceHash":"6202e384ac43b52dd303d9ee1569fac59de1ff66a2708917211ef8dec1badb14"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1779993000000,"buyAmount":20421.79,"sellAmount":21683.15,"buyContracts":322513,"sellContracts":340637,"oiContracts":449650,"oiAmount":28525.2,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":77080,"totalPutLongContracts":125449,"totalCallShortContracts":151398,"totalPutShortContracts":95723,"sourceHash":"093cd4e8b5248bcaf3da20c65c630d3d7c286062c9895a4777aa1aaab906cd8a"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1779820200000,"buyAmount":16071.2,"sellAmount":16577.34,"buyContracts":252380,"sellContracts":259785,"oiContracts":347048,"oiAmount":22290.28,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":60047,"totalPutLongContracts":100243,"totalCallShortContracts":115467,"totalPutShortContracts":71291,"sourceHash":"2c8de56bb1ecb57093ba6c269e273c159be7449d42834a97a83c1ae6de72cbec"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1779733800000,"buyAmount":15305.37,"sellAmount":17326.88,"buyContracts":237892,"sellContracts":266971,"oiContracts":253265,"oiAmount":16299.68,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":43016,"totalPutLongContracts":74085,"totalCallShortContracts":85655,"totalPutShortContracts":50509,"sourceHash":"9d50b70ca96618d354ac08bb483494e18d180f013be88b7b7c9675f14ede13e5"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1779647400000,"buyAmount":39364.13,"sellAmount":39174.2,"buyContracts":578967,"sellContracts":576120,"oiContracts":1100988,"oiAmount":72687.37,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":231621,"totalPutLongContracts":294910,"totalCallShortContracts":348010,"totalPutShortContracts":226447,"sourceHash":"25edc0891666a872fe7480a7ff47544cee8d496b41573631355b9dd5553265ef"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1779388200000,"buyAmount":52766.61,"sellAmount":52402.64,"buyContracts":829981,"sellContracts":827415,"oiContracts":1155731,"oiAmount":75143.46,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":244303,"totalPutLongContracts":308176,"totalCallShortContracts":366368,"totalPutShortContracts":236884,"sourceHash":"0d32e188e224bbb7cee08b6fc43963914341dbe50c4bdad0f6d62b51dc6c47d6"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1779301800000,"buyAmount":52114.08,"sellAmount":51254.26,"buyContracts":808626,"sellContracts":795871,"oiContracts":1170523,"oiAmount":75482.05,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":246627,"totalPutLongContracts":311965,"totalCallShortContracts":374615,"totalPutShortContracts":237316,"sourceHash":"96edb876286e066f4a149a296d7b6416ed6d69c40c4660df71fe79254a393c05"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1779215400000,"buyAmount":48315.94,"sellAmount":48634.87,"buyContracts":746688,"sellContracts":754127,"oiContracts":1168434,"oiAmount":75290.96,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":241966,"totalPutLongContracts":309204,"totalCallShortContracts":376650,"totalPutShortContracts":240614,"sourceHash":"e529767ae73622de335a1831644d7aab81b2e5c7d2ff643e8643b87447016996"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1779129000000,"buyAmount":44532.0,"sellAmount":44446.06,"buyContracts":693571,"sellContracts":689122,"oiContracts":1192209,"oiAmount":76623.75,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":252804,"totalPutLongContracts":313973,"totalCallShortContracts":381233,"totalPutShortContracts":244199,"sourceHash":"9ec732af614875f6b6ba55c7614c022bcdfb24c386d41a16cd71f7d0a11d1a66"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1779042600000,"buyAmount":40301.81,"sellAmount":40500.82,"buyContracts":634355,"sellContracts":630762,"oiContracts":1139870,"oiAmount":72679.27,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":236516,"totalPutLongContracts":301867,"totalCallShortContracts":370828,"totalPutShortContracts":230659,"sourceHash":"1a3d3b6876304729c7036fa0c05910315a6382823444948ccd5fd7386b92c1fb"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1778783400000,"buyAmount":34494.04,"sellAmount":34525.75,"buyContracts":547953,"sellContracts":538625,"oiContracts":1066067,"oiAmount":68180.4,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":218916,"totalPutLongContracts":280769,"totalCallShortContracts":348988,"totalPutShortContracts":217394,"sourceHash":"0386ea486c3063ae49f4a63b3eabe8a0a1c21d3797e55ee402272e428f695a74"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1778697000000,"buyAmount":36203.86,"sellAmount":37956.83,"buyContracts":563978,"sellContracts":592470,"oiContracts":993829,"oiAmount":63813.54,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":198046,"totalPutLongContracts":260856,"totalCallShortContracts":335171,"totalPutShortContracts":199756,"sourceHash":"42246fad78468ab5b7ce79dbe6c4f784c9e8311130fda23cd96febf5f56fbe58"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1778610600000,"buyAmount":31544.44,"sellAmount":31121.73,"buyContracts":485948,"sellContracts":481337,"oiContracts":922559,"oiAmount":59018.17,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":183325,"totalPutLongContracts":254188,"totalCallShortContracts":306611,"totalPutShortContracts":178435,"sourceHash":"e8ac21a1ebced7efdc5ffa79fd05845bced002b1c4d0bc6c9a68f3fc843b7929"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1778524200000,"buyAmount":29242.74,"sellAmount":29127.44,"buyContracts":451011,"sellContracts":450442,"oiContracts":856236,"oiAmount":54638.53,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":166814,"totalPutLongContracts":235232,"totalCallShortContracts":286866,"totalPutShortContracts":167324,"sourceHash":"f31e1bcb5075394525a0cc340d554b83ea2857590562e8a3aa1fe7a4d065df2a"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1778437800000,"buyAmount":28620.89,"sellAmount":28939.2,"buyContracts":404802,"sellContracts":409058,"oiContracts":792841,"oiAmount":51734.97,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":163799,"totalPutLongContracts":206265,"totalCallShortContracts":260004,"totalPutShortContracts":162773,"sourceHash":"1cb9b31c9018df0ba2e876d3732f11887cc417d36ea8a66ce208439000b42909"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1778178600000,"buyAmount":25143.96,"sellAmount":25871.21,"buyContracts":360454,"sellContracts":368691,"oiContracts":726967,"oiAmount":47966.75,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":157287,"totalPutLongContracts":181968,"totalCallShortContracts":233957,"totalPutShortContracts":153755,"sourceHash":"4963019be5b1f81dd858c7c5e06eec6e8c4961e82953aef3992357a937255780"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1778092200000,"buyAmount":23247.95,"sellAmount":23253.77,"buyContracts":343196,"sellContracts":343105,"oiContracts":670936,"oiAmount":44145.75,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":145968,"totalPutLongContracts":169390,"totalCallShortContracts":214376,"totalPutShortContracts":141202,"sourceHash":"53ff437c5e774bcae813e41d46b1603ca67a6e575f422cd0cc83a965581b00a2"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1778005800000,"buyAmount":27451.32,"sellAmount":27472.76,"buyContracts":423913,"sellContracts":425579,"oiContracts":613427,"oiAmount":40186.02,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":129453,"totalPutLongContracts":157105,"totalCallShortContracts":199718,"totalPutShortContracts":127151,"sourceHash":"f6dfd1c174c20889c987b7f0721cb3ea0e57bd8add71c5f45f290734109870c1"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1777919400000,"buyAmount":17873.07,"sellAmount":18255.17,"buyContracts":269946,"sellContracts":275693,"oiContracts":515227,"oiAmount":33320.57,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":103483,"totalPutLongContracts":134808,"totalCallShortContracts":175780,"totalPutShortContracts":101156,"sourceHash":"59545cde3e7a24ca338a998ed53f90b0cfeee8cdad9d3b76dcc7d881284d0edb"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_FO|STOCK_OPTIONS","timeStamp":1777833000000,"buyAmount":18978.19,"sellAmount":19953.76,"buyContracts":284885,"sellContracts":300260,"oiContracts":469718,"oiAmount":30351.47,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":94204,"totalPutLongContracts":124206,"totalCallShortContracts":158038,"totalPutShortContracts":93270,"sourceHash":"335011b15a3ee9a31b19e98e84e782213f786f6ed7f4c738aeaab5d18058b3a1"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1781461800000,"buyAmount":15650.2,"sellAmount":15450.15,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"fe264611eb0d1d997a163ccd4912546ee966e37b1674027e914dfcc4b4948eae"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1781202600000,"buyAmount":12064.61,"sellAmount":13146.79,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"7154233888a96b73c8288e564df9d488ffefb6a3c8ea22bf2bb841bd03b8d42f"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1781116200000,"buyAmount":14000.58,"sellAmount":15987.67,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"02ce46bfb80ead1d19b8aafdf7720bbfb95d46be5b0221d6a29079156e4ba30f"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1781029800000,"buyAmount":14047.79,"sellAmount":16172.77,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"928538b4629610eeccbb1672243549ce488902e82ecd2e57b99409e258801c71"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1780943400000,"buyAmount":14735.47,"sellAmount":19301.5,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"b99a4792be76bfe1b7cc14e7df54ddeb6c8872c2407c775c6af8ca2012423225"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1780857000000,"buyAmount":8842.08,"sellAmount":14397.75,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"e12287b82069efffddefd1f592e9cc5d9ea1971e42fcd0796f80c7d129068b96"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1780597800000,"buyAmount":11044.57,"sellAmount":19820.82,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"a13de90cc0d3021f11984109a203e225a139c00b0f7d2765d066af8cb266310b"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1780511400000,"buyAmount":14012.52,"sellAmount":18459.58,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"e02593bcc24c1da989fcf677e50fe825578a7b68163d4639d8e5183bbbd11e24"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1780425000000,"buyAmount":17053.63,"sellAmount":22670.19,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"5f5b7888e928a779c42976ca91de19d5611d5b1f19fce87961bd42f437c0a6d4"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1780338600000,"buyAmount":16955.9,"sellAmount":25318.82,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"07043fcb81d7ee4610d26f3b53e6fba10284c5eecc7259f4f853ac4595487782"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1780252200000,"buyAmount":17725.89,"sellAmount":21637.57,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"e61f00cbe1d7959b7ff8b49fcdc0baae142f9a5230ada3e408164b9917aaaf07"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1779993000000,"buyAmount":89733.64,"sellAmount":110839.5,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"453b8ecefe4c4b45b6ef687612b2a032cc513f6a2b3b73ba60b9e56d0821fbd2"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1779820200000,"buyAmount":11418.65,"sellAmount":12461.35,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"95645d6633dacd81cf1f7194629fc4b0d3309bb5fc767ea04aef2f518e2a7cdb"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1779733800000,"buyAmount":13127.02,"sellAmount":15534.89,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"af09d53b6c89edd6c64e5e78f987a0481d91487e393606cbbf131495d82a7c69"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1779647400000,"buyAmount":12083.12,"sellAmount":11261.37,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"e35d144c3e36ae66a28c4498c874bc97c4e36a69903755ecb77544a89991338d"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1779388200000,"buyAmount":10972.76,"sellAmount":15413.23,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"ccaecf94ec4df491754570783fa367363c8a6b1b5da1ca0473a2375d29779c87"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1779301800000,"buyAmount":12322.16,"sellAmount":14213.37,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"5ef3fb90f353742f5c1cd9a73c87c3418bc195c78a21a25dd52ee9051a952fa5"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1779215400000,"buyAmount":14139.56,"sellAmount":15736.91,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"19dc11256da4ea9bae75b28860f619927229f44667414ddfb9d7ad74d6790a6f"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1779129000000,"buyAmount":17907.55,"sellAmount":20365.04,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"0a87873a5da1fcb6fdb0bc8a7bd37bb4ed92cb4488f62160ba9386f8722b5e05"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1779042600000,"buyAmount":17222.18,"sellAmount":14408.49,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"c1b8e1d043a0d772d901da548581739249179443456b141f61c249deb887383b"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1778783400000,"buyAmount":16299.6,"sellAmount":14970.43,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"f050f1cc49f453a9f1e744b8583e3ce60d78ff73f922745415ed6f2d20041f57"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1778697000000,"buyAmount":17350.56,"sellAmount":17163.1,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"aeaa2021cc5706e082990054b0adb5097dee6010c7597a1f72df9718b20a77e4"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1778610600000,"buyAmount":14373.99,"sellAmount":19077.14,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"a2aadcaab66137ea07e8eb31ef210f69b6cdef479a8fdcb0935e11bbac4c17eb"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1778524200000,"buyAmount":16555.27,"sellAmount":18514.66,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"656cb1485b17fd9a6b4c70e020e5d888850a2ea2f47c121327a44a58405c0bbd"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1778437800000,"buyAmount":12813.74,"sellAmount":21251.3,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"8711a0b6d09b267a788687eabd730c561abd04befa0ced7d0da30f918b8bbbd5"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1778178600000,"buyAmount":15083.49,"sellAmount":19194.09,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"84c3d99b988088fcedad3917151e16561eced6041139da822c62007533431853"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1778092200000,"buyAmount":17997.95,"sellAmount":18338.84,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"0d84adcf1e41c135f2afa7186d2791542f99b19bfd83514ab418af54992ce115"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1778005800000,"buyAmount":14459.21,"sellAmount":20294.11,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"eaaec224ab304c607be1fd7f90022233892ef88cbf1e882f98898ad4b0403c7e"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1777919400000,"buyAmount":10392.91,"sellAmount":14014.49,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"533d8540174d198db76c2f0b499b3b90b4642524fca99b3d6afd9c03a46d91c9"}
{"provider":"UPSTOX","category":"FII","dataType":"NSE_EQ|CASH","timeStamp":1777833000000,"buyAmount":19660.47,"sellAmount":16824.85,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"b3f8aebd4ea7484c3e6ee92bc0743a727417eee5bb747e8d87df8e6ff7c056a6"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1781461800000,"buyAmount":21080.9,"sellAmount":17891.64,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"2526c38f2737d9dbb43b13a7aa74bff390cf4e179d979d19cec86b83153b1382"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1781202600000,"buyAmount":18877.03,"sellAmount":13535.74,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"c4564dc76d12a2061b4d5398abd438b9dfd68866064dec81e4952846e3de8172"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1781116200000,"buyAmount":16822.57,"sellAmount":12598.06,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"8208a63df1d233041012369e0bb76674dcc69ce9ab57141b85398cf6971b94e4"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1781029800000,"buyAmount":17396.4,"sellAmount":14272.45,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"922ba1fa2be5aaa16be347d98674779ab820bea31e0e6f58ca683b5107bbe38c"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1780943400000,"buyAmount":17664.98,"sellAmount":11505.5,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"f52ee11ca389bb978ca553f29f17580d652f3bab2331374039bb4b07b6b81711"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1780857000000,"buyAmount":16683.18,"sellAmount":11517.94,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"432fe1c99c9f485d755b2f594ceca3e81b824f405c71cd9736c7b44661e99dc2"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1780597800000,"buyAmount":22779.32,"sellAmount":13645.75,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"89fa770ebcb6f28557d341bc9767424a3efd6c12d6026f713dbe0c52e1ca1632"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1780511400000,"buyAmount":16824.35,"sellAmount":12464.21,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"01c84f5f9d8950cab5db7eeafa2b78cab91a7dc14ec60fb432cd12dc6c077b75"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1780425000000,"buyAmount":17530.0,"sellAmount":11789.11,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"a973fae29a6a1eb9e1f9632b468afcd9d1742421367b108a25838440e283ca45"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1780338600000,"buyAmount":22508.77,"sellAmount":12919.45,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"f016a850109d0bee526901e65d078f787824cbc9d2a6552b37394c4396109b2c"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1780252200000,"buyAmount":15226.29,"sellAmount":10117.16,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"87dde5099f6d44b5a4222897a20e5732cb4150a2ea8168760ba74b7a5b8cde26"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1779993000000,"buyAmount":36999.7,"sellAmount":20235.56,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"e2c362fdaff633b2471f37df82da239a2f0fe64fa37baa5f60523959bc1bdb61"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1779820200000,"buyAmount":16893.1,"sellAmount":13072.1,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"7c39860baad7ee34b617d93502316424e840ca2837daf64027a9f7d195b7e901"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1779733800000,"buyAmount":15536.74,"sellAmount":14175.31,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"018a2a54c083ab43e2e960578cae1d1f2ee00e7f6bfa8a1a532212575e46593b"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1779647400000,"buyAmount":16434.96,"sellAmount":12578.08,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"ef41c27b9b7720b29c7221d859c75fe8f30808cf615c594c0b5cea3c4863a169"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1779388200000,"buyAmount":18436.72,"sellAmount":12433.19,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"b46e2383bb244a2ed892c924f10eca2be4b0607cbcd6369ccf305753f9298cc5"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1779301800000,"buyAmount":15857.03,"sellAmount":13364.61,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"7b49b93799d580d207cbc2dbbdba3389731040dc19efb892560760c80c1c3737"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1779215400000,"buyAmount":16000.79,"sellAmount":14032.44,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"9d5a3e7f019e52e1a2724c17b1bb1664ec1f4d66e3ed2d2669e505b42aa1f751"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1779129000000,"buyAmount":16951.95,"sellAmount":13150.27,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"4808faf124cc9f369ca715416af1e68d52b188758fbf430c6b74f37f1c26f20f"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1779042600000,"buyAmount":16844.94,"sellAmount":14162.82,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"c5f79f913ffae9df8ccabd7dc4a13f1ac15ef9061c78a1e53b85cfcc5086c0c6"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1778783400000,"buyAmount":14961.88,"sellAmount":16920.7,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"d244656eaed57cafcaaf72e704450f723490fd912e2becb005dcb2eaf6a75690"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1778697000000,"buyAmount":18255.96,"sellAmount":17571.63,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"071caddd1ccb4295e8873d18380272063d7f034d62464421a1b0d74628123f4c"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1778610600000,"buyAmount":18872.65,"sellAmount":13003.6,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"4b536bffacbdead0d8364f49fc1fe925d57e5727e2dff4f68cb77b4597d72771"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1778524200000,"buyAmount":20684.82,"sellAmount":12694.5,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"d0edc242c9597f4834c8fd4f92fc0fcf8efa28b951a94f07f5c4dc136971b806"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1778437800000,"buyAmount":21626.43,"sellAmount":15686.78,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"8298b1d098a518cd9c8799892c8efe32459e45faaa52c9365d8f017f55525dd8"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1778178600000,"buyAmount":21296.87,"sellAmount":14548.74,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"48e8f8bf2e590ed77aef98600fd49b49adee046ad943fa64266881e95e8ba231"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1778092200000,"buyAmount":17032.05,"sellAmount":16590.98,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"40c3185871c6121cd8a4f98863ef3bd1343503a8cfd1e810225c26d52c9835ad"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1778005800000,"buyAmount":22888.16,"sellAmount":16051.29,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"d6339d50fce514bbc1591dc6fef4dfb8a06f15cff44f7324b3518fdebd3cd728"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1777919400000,"buyAmount":16234.31,"sellAmount":13631.69,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"8e1ebbb24a230868abf26f1aa20009a62ec15b84016660938cee6fb289c0cb5a"}
{"provider":"UPSTOX","category":"DII","dataType":"NSE_EQ|CASH","timeStamp":1777833000000,"buyAmount":19516.11,"sellAmount":14751.95,"buyContracts":0,"sellContracts":0,"oiContracts":0,"oiAmount":0.0,"totalLongContracts":0,"totalShortContracts":0,"totalCallLongContracts":0,"totalPutLongContracts":0,"totalCallShortContracts":0,"totalPutShortContracts":0,"sourceHash":"ca7b227471f592f15f14eabf9ae70caab88d8ef36c77c9bfa3216b0b6dbef1ba"}
```

```json
// File: fiidii/FII-DII.metadata.json
{
  "totalRecords" : 180,
  "fiiRecords" : 150,
  "diiRecords" : 30,
  "lastUpdated" : 1781581398977,
  "latestTimestamp" : 1781461800000
}
```
