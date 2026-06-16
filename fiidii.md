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
                .header("Accept-Encoding", "gzip, deflate")
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

```
