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