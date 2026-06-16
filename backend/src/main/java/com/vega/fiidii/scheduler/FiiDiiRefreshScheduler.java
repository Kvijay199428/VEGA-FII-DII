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