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

    @Scheduled(fixedRateString = "#{@fiiDiiConfigService.getRefreshIntervalMinutes() * 60000}", initialDelay = 10000)
    public void refreshFiiDii() {
        logger.info("Starting scheduled FII and DII refresh...");

        logger.info("Syncing FII activity...");
        bootstrapService.syncData("FII", archiveService.getLatestFiiDate());

        logger.info("Syncing DII activity...");
        bootstrapService.syncData("DII", archiveService.getLatestDiiDate());

        logger.info("Completed scheduled FII and DII refresh.");
    }
}