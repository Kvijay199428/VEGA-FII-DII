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
    private final FiiDiiSyncDecisionService syncDecisionService;
    private volatile com.vega.fiidii.model.BootstrapState state = com.vega.fiidii.model.BootstrapState.NOT_STARTED;
    private final java.util.concurrent.locks.ReentrantLock fiiLock = new java.util.concurrent.locks.ReentrantLock();
    private final java.util.concurrent.locks.ReentrantLock diiLock = new java.util.concurrent.locks.ReentrantLock();

    private java.util.concurrent.locks.ReentrantLock getLock(String category) {
        return "FII".equalsIgnoreCase(category) ? fiiLock : diiLock;
    }

    public FiiDiiBootstrapService(FiiDiiArchiveService archiveService, UpstoxFiiDiiClient upstoxClient, FiiDiiConfigService configService, FiiDiiSyncDecisionService syncDecisionService) {
        this.archiveService = archiveService;
        this.upstoxClient = upstoxClient;
        this.configService = configService;
        this.syncDecisionService = syncDecisionService;
    }

    public com.vega.fiidii.model.BootstrapState getBootstrapState() {
        return state;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        logger.info("Starting FII/DII Data Bootstrap...");
        state = com.vega.fiidii.model.BootstrapState.RUNNING;
        try {
            syncData("FII", archiveService.getLatestFiiDate(), archiveService.getProviderLatestFiiDate());
            syncData("DII", archiveService.getLatestDiiDate(), archiveService.getProviderLatestDiiDate());
            state = com.vega.fiidii.model.BootstrapState.SUCCESS;
            logger.info("FII/DII Data Bootstrap completed successfully.");
        } catch (Exception e) {
            state = com.vega.fiidii.model.BootstrapState.FAILED;
            logger.error("Error during bootstrap sync", e);
        }
    }

    public void syncData(String category, LocalDate latestStoredDate, LocalDate providerLatestDate) {
        com.vega.fiidii.model.SyncDecision decision = syncDecisionService.determineSyncMode(category, latestStoredDate, providerLatestDate);
        switch(decision.getMode()) {
            case INITIAL_LOAD:
            case BACKFILL:
                syncHistoricalData(category, decision.getFromDate());
                break;
            case LATEST_REFRESH:
                syncLatestSnapshot(category);
                break;
            case NO_SYNC_REQUIRED:
                logger.info("[{}] Archive already synchronized with provider. Skipping bootstrap.", category);
                break;
        }
    }

    public void syncLatestSnapshot(String category) {
        java.util.concurrent.locks.ReentrantLock lock = getLock(category);
        if (!lock.tryLock()) {
            logger.warn("[{}] Sync already running, skipping concurrent execution.", category);
            return;
        }
        try {
            logger.info("[{}] Fetching latest snapshot...", category);
            List<InstitutionalFlowRecord> records;
            if ("FII".equalsIgnoreCase(category)) {
                records = upstoxClient.fetchLatestFii();
            } else {
                records = upstoxClient.fetchLatestDii();
            }

            LocalDate maxReturnedDate = records.stream()
                    .map(r -> java.time.Instant.ofEpochMilli(r.getTimeStamp())
                            .atZone(ZoneId.of("Asia/Kolkata"))
                            .toLocalDate())
                    .max(LocalDate::compareTo)
                    .orElse(null);

            if (maxReturnedDate != null) {
                logger.info("[{}] Fetched {} records from snapshot. Latest returned: {}. Appending...", category, records.size(), maxReturnedDate);
                archiveService.appendRecords(records);
                archiveService.setProviderLatestDate(category, maxReturnedDate);
            } else {
                logger.info("[{}] No records returned from snapshot.", category);
            }
        } finally {
            lock.unlock();
        }
    }

    public void syncHistoricalData(String category, LocalDate startDate) {
        java.util.concurrent.locks.ReentrantLock lock = getLock(category);
        if (!lock.tryLock()) {
            logger.warn("[{}] Sync already running, skipping concurrent execution.", category);
            return;
        }
        try {
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
            LocalDate currentDate = startDate;
            long intervalMs = configService.getRequestIntervalMs();

            while (!currentDate.isAfter(today)) {
                String fromStr = currentDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
                logger.info("[{}] Fetching historical data from {}", category, fromStr);

                List<InstitutionalFlowRecord> records;
                if ("FII".equalsIgnoreCase(category)) {
                    records = upstoxClient.fetchFiiHistorical(fromStr);
                } else {
                    records = upstoxClient.fetchDiiHistorical(fromStr);
                }

                LocalDate maxReturnedDate = records.stream()
                        .map(r -> java.time.Instant.ofEpochMilli(r.getTimeStamp())
                                .atZone(ZoneId.of("Asia/Kolkata"))
                                .toLocalDate())
                        .max(LocalDate::compareTo)
                        .orElse(null);

                if (maxReturnedDate != null) {
                    logger.info("[{}] Fetched {} historical records. Latest returned: {}. Appending...", category, records.size(), maxReturnedDate);
                    archiveService.appendRecords(records);
                    archiveService.setProviderLatestDate(category, maxReturnedDate);
                }

                if (maxReturnedDate == null || maxReturnedDate.isBefore(currentDate)) {
                    logger.warn("[{}] No newer data returned (latest: {}). Stopping historical sync for {}.", category, maxReturnedDate, category);
                    break;
                }

                currentDate = maxReturnedDate.plusDays(1);

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
            lock.unlock();
        }
    }
}