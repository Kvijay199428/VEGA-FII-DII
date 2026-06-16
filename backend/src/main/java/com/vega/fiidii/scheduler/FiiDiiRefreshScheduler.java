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
        if (bootstrapService.getBootstrapState() != com.vega.fiidii.model.BootstrapState.SUCCESS) {
            logger.info("Bootstrap is not completed yet, skipping daily sync.");
            return;
        }

        logger.info("Starting daily market close FII and DII refresh...");
        bootstrapService.syncData("FII", archiveService.getLatestFiiDate());
        bootstrapService.syncData("DII", archiveService.getLatestDiiDate());
        logger.info("Completed daily FII and DII refresh.");
    }

    private boolean shouldRetry(java.time.LocalDate latestStored, java.time.LocalDate providerLatest, java.time.LocalDate today) {
        if (today.equals(latestStored)) {
            return false;
        }

        if (providerLatest != null && !providerLatest.isAfter(latestStored) && !providerLatest.equals(today)) {
            return false;
        }

        return true;
    }

    @Scheduled(cron = "0 0 17-23 * * MON-FRI", zone = "Asia/Kolkata")
    public void retryIfMissing() {
        if (bootstrapService.getBootstrapState() != com.vega.fiidii.model.BootstrapState.SUCCESS) {
            return;
        }

        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));

        if (shouldRetry(archiveService.getLatestFiiDate(), archiveService.getProviderLatestFiiDate(), today)) {
            logger.info("Hourly retry: Syncing FII activity...");
            bootstrapService.syncData("FII", archiveService.getLatestFiiDate());
        } else {
            logger.info("Hourly retry: Skipping FII activity, provider hasn't published newer data or already up to date.");
        }

        if (shouldRetry(archiveService.getLatestDiiDate(), archiveService.getProviderLatestDiiDate(), today)) {
            logger.info("Hourly retry: Syncing DII activity...");
            bootstrapService.syncData("DII", archiveService.getLatestDiiDate());
        } else {
            logger.info("Hourly retry: Skipping DII activity, provider hasn't published newer data or already up to date.");
        }
    }
}