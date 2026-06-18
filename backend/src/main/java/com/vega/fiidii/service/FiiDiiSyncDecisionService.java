package com.vega.fiidii.service;

import com.vega.fiidii.config.FiiDiiConfigService;
import com.vega.fiidii.model.SyncDecision;
import com.vega.fiidii.model.SyncMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;

@Service
public class FiiDiiSyncDecisionService {
    private static final Logger logger = LoggerFactory.getLogger(FiiDiiSyncDecisionService.class);
    private final FiiDiiConfigService configService;

    public FiiDiiSyncDecisionService(FiiDiiConfigService configService) {
        this.configService = configService;
    }

    public SyncDecision determineSyncMode(String category, LocalDate latestStoredDate, LocalDate providerLatestDate) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        
        // Rule 1: Archive empty
        if (latestStoredDate == null) {
            LocalDate defaultStartDate = configService.getDefaultStartDate();
            logger.info("[{}] Archive empty. Decision: INITIAL_LOAD from {}", category, defaultStartDate);
            return new SyncDecision(SyncMode.INITIAL_LOAD, defaultStartDate, null, providerLatestDate);
        }

        // Rule 2: Archive exists, Provider date unknown
        if (providerLatestDate == null) {
            LocalDate nextDay = latestStoredDate.plusDays(1);
            if (nextDay.isAfter(today)) {
                logger.info("[{}] Provider date unknown, but archive is up to today ({}). Decision: LATEST_REFRESH", category, latestStoredDate);
                return new SyncDecision(SyncMode.LATEST_REFRESH, null, latestStoredDate, null);
            }
            logger.info("[{}] Provider date unknown. Decision: BACKFILL from {}", category, nextDay);
            return new SyncDecision(SyncMode.BACKFILL, nextDay, latestStoredDate, null);
        }

        // Rule 3: Provider newer than archive
        if (providerLatestDate.isAfter(latestStoredDate)) {
            LocalDate nextDay = latestStoredDate.plusDays(1);
            logger.info("[{}] Provider has newer data ({} > {}). Decision: BACKFILL from {}", category, providerLatestDate, latestStoredDate, nextDay);
            return new SyncDecision(SyncMode.BACKFILL, nextDay, latestStoredDate, providerLatestDate);
        }

        // Rule 4: Provider equals archive
        if (providerLatestDate.equals(latestStoredDate)) {
            logger.info("[{}] Archive is synchronized with provider ({}). Decision: NO_SYNC_REQUIRED", category, latestStoredDate);
            return new SyncDecision(SyncMode.NO_SYNC_REQUIRED, null, latestStoredDate, providerLatestDate);
        }

        // Rule 5: Provider older than archive (unexpected)
        if (providerLatestDate.isBefore(latestStoredDate)) {
            logger.warn("[{}] Provider latest date ({}) is older than archive latest date ({}). Decision: NO_SYNC_REQUIRED", category, providerLatestDate, latestStoredDate);
            return new SyncDecision(SyncMode.NO_SYNC_REQUIRED, null, latestStoredDate, providerLatestDate);
        }

        // Fallback (should not happen)
        return new SyncDecision(SyncMode.NO_SYNC_REQUIRED, null, latestStoredDate, providerLatestDate);
    }
}