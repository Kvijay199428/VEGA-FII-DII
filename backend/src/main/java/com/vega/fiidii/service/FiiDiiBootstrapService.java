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
    }
}