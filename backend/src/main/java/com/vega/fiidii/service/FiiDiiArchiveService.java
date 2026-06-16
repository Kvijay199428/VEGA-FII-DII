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