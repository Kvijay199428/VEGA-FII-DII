package com.vega.fiidii.model;

import java.time.LocalDate;

public class SyncDecision {
    private SyncMode mode;
    private LocalDate fromDate;
    private LocalDate latestStoredDate;
    private LocalDate providerLatestDate;

    public SyncDecision(SyncMode mode, LocalDate fromDate, LocalDate latestStoredDate, LocalDate providerLatestDate) {
        this.mode = mode;
        this.fromDate = fromDate;
        this.latestStoredDate = latestStoredDate;
        this.providerLatestDate = providerLatestDate;
    }

    public SyncMode getMode() {
        return mode;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public LocalDate getLatestStoredDate() {
        return latestStoredDate;
    }

    public LocalDate getProviderLatestDate() {
        return providerLatestDate;
    }

    @Override
    public String toString() {
        return "SyncDecision{" +
                "mode=" + mode +
                ", fromDate=" + fromDate +
                ", latestStoredDate=" + latestStoredDate +
                ", providerLatestDate=" + providerLatestDate +
                '}';
    }
}