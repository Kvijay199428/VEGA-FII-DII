package com.vega.fiidii.controller;

import com.vega.fiidii.client.UpstoxFiiDiiClient;
import com.vega.fiidii.model.InstitutionalFlowRecord;
import com.vega.fiidii.service.FiiDiiArchiveService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/api/fiidii")
public class FiiDiiController {

    private final FiiDiiArchiveService archiveService;
    private final UpstoxFiiDiiClient upstoxClient;

    public FiiDiiController(FiiDiiArchiveService archiveService, UpstoxFiiDiiClient upstoxClient) {
        this.archiveService = archiveService;
        this.upstoxClient = upstoxClient;
    }

    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("totalRecords", archiveService.getAllRecords().size());
        
        LocalDate latestFii = archiveService.getLatestFiiDate();
        LocalDate latestDii = archiveService.getLatestDiiDate();
        
        status.put("latestFiiDate", latestFii != null ? latestFii.toString() : null);
        status.put("latestDiiDate", latestDii != null ? latestDii.toString() : null);
        
        LocalDate yesterday = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(1);
        boolean bootstrapRequired = (latestFii == null || latestFii.isBefore(yesterday)) ||
                                    (latestDii == null || latestDii.isBefore(yesterday));
        
        status.put("bootstrapRequired", bootstrapRequired);
        return status;
    }

    @GetMapping
    public List<InstitutionalFlowRecord> getFiiDiiData(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String dataType,
            @RequestParam(required = false) String interval,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        
        if (category != null && !category.equalsIgnoreCase("FII") && !category.equalsIgnoreCase("DII")) {
            throw new IllegalArgumentException("Invalid category. Must be FII or DII.");
        }

        if (interval != null || from != null || to != null) {
            // Ad-hoc user query -> fetch directly without storing
            return upstoxClient.fetchAdHoc(category, dataType, interval, from, to);
        } else {
            return archiveService.filterRecords(category, dataType);
        }
    }
}