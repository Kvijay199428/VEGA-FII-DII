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