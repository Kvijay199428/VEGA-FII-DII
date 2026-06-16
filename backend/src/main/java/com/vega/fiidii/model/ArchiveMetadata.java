package com.vega.fiidii.model;

public class ArchiveMetadata {
    private long totalRecords;
    private long fiiRecords;
    private long diiRecords;
    private long lastUpdated;
    private long latestTimestamp;
    private String latestFiiDate;
    private String latestDiiDate;
    private String providerLatestFiiDate;
    private String providerLatestDiiDate;

    public ArchiveMetadata() {
    }

    public long getTotalRecords() { return totalRecords; }
    public void setTotalRecords(long totalRecords) { this.totalRecords = totalRecords; }

    public long getFiiRecords() { return fiiRecords; }
    public void setFiiRecords(long fiiRecords) { this.fiiRecords = fiiRecords; }

    public long getDiiRecords() { return diiRecords; }
    public void setDiiRecords(long diiRecords) { this.diiRecords = diiRecords; }

    public long getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(long lastUpdated) { this.lastUpdated = lastUpdated; }

    public long getLatestTimestamp() { return latestTimestamp; }
    public void setLatestTimestamp(long latestTimestamp) { this.latestTimestamp = latestTimestamp; }

    public String getLatestFiiDate() { return latestFiiDate; }
    public void setLatestFiiDate(String latestFiiDate) { this.latestFiiDate = latestFiiDate; }

    public String getLatestDiiDate() { return latestDiiDate; }
    public void setLatestDiiDate(String latestDiiDate) { this.latestDiiDate = latestDiiDate; }

    public String getProviderLatestFiiDate() { return providerLatestFiiDate; }
    public void setProviderLatestFiiDate(String providerLatestFiiDate) { this.providerLatestFiiDate = providerLatestFiiDate; }

    public String getProviderLatestDiiDate() { return providerLatestDiiDate; }
    public void setProviderLatestDiiDate(String providerLatestDiiDate) { this.providerLatestDiiDate = providerLatestDiiDate; }
}