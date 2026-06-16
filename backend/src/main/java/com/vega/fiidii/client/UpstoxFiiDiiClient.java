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
                null, null
        );
    }

    public List<InstitutionalFlowRecord> fetchDii() {
        return fetchFiiDiiData(
                configService.getConfig().getDii().getEndpoint(),
                configService.getDiiDataTypes(),
                "DII",
                null, null
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
            queryString += "&from_date=" + URLEncoder.encode(fromDate, StandardCharsets.UTF_8);
        }
                
        String url = BASE_URL + endpoint + "?" + queryString;
        
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
        
        if (dataNode.isArray()) {
            for (JsonNode node : dataNode) {
                InstitutionalFlowRecord record = new InstitutionalFlowRecord();
                record.setProvider("UPSTOX");
                record.setCategory(category);
                
                // Extract dataType from node (fallback if missing)
                String dt = node.path("dataType").asText("");
                if (dt.isEmpty()) {
                    dt = node.path("segment").asText(""); 
                }
                record.setDataType(dt);
                
                // Assuming 'date' in yyyy-MM-dd format or 'timestamp' in epoch millis
                if(node.has("timestamp")) {
                     record.setTimeStamp(node.path("timestamp").asLong());
                } else if(node.has("date")) {
                     record.setTimeStamp(java.time.LocalDate.parse(node.path("date").asText())
                         .atStartOfDay(java.time.ZoneId.of("Asia/Kolkata"))
                         .toInstant().toEpochMilli());
                }
                
                record.setBuyAmount(node.path("buyAmount").asDouble(0.0));
                record.setSellAmount(node.path("sellAmount").asDouble(0.0));
                record.setBuyContracts(node.path("buyContracts").asLong(0));
                record.setSellContracts(node.path("sellContracts").asLong(0));
                record.setOiContracts(node.path("oiContracts").asLong(0));
                record.setOiAmount(node.path("oiAmount").asDouble(0.0));
                record.setTotalLongContracts(node.path("totalLongContracts").asLong(0));
                record.setTotalShortContracts(node.path("totalShortContracts").asLong(0));
                record.setTotalCallLongContracts(node.path("totalCallLongContracts").asLong(0));
                record.setTotalPutLongContracts(node.path("totalPutLongContracts").asLong(0));
                record.setTotalCallShortContracts(node.path("totalCallShortContracts").asLong(0));
                record.setTotalPutShortContracts(node.path("totalPutShortContracts").asLong(0));
                
                record.setSourceHash(com.vega.fiidii.util.HashUtil.generateSourceHash(
                        record.getCategory(), record.getDataType(), record.getTimeStamp()
                ));
                
                records.add(record);
            }
        }
        return records;
    }
}