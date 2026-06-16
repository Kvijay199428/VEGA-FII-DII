package com.vega.fiidii.config;

import java.util.List;

public class FiiDiiConfig {
    private String provider;
    private String defaultInterval;
    private String defaultStartDate;
    private int refreshIntervalMinutes;
    private int requestIntervalMs = 1000;
    private int maxDaysPerRequest = 30;
    private int retentionDays;
    private StorageConfig storage;
    private DataConfig fii;
    private DataConfig dii;

    public FiiDiiConfig() {
    }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getDefaultInterval() { return defaultInterval; }
    public void setDefaultInterval(String defaultInterval) { this.defaultInterval = defaultInterval; }

    public String getDefaultStartDate() { return defaultStartDate; }
    public void setDefaultStartDate(String defaultStartDate) { this.defaultStartDate = defaultStartDate; }

    public int getRefreshIntervalMinutes() { return refreshIntervalMinutes; }
    public void setRefreshIntervalMinutes(int refreshIntervalMinutes) { this.refreshIntervalMinutes = refreshIntervalMinutes; }

    public int getRequestIntervalMs() { return requestIntervalMs; }
    public void setRequestIntervalMs(int requestIntervalMs) { this.requestIntervalMs = requestIntervalMs; }

    public int getMaxDaysPerRequest() { return maxDaysPerRequest; }
    public void setMaxDaysPerRequest(int maxDaysPerRequest) { this.maxDaysPerRequest = maxDaysPerRequest; }

    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }

    public StorageConfig getStorage() { return storage; }
    public void setStorage(StorageConfig storage) { this.storage = storage; }

    public DataConfig getFii() { return fii; }
    public void setFii(DataConfig fii) { this.fii = fii; }

    public DataConfig getDii() { return dii; }
    public void setDii(DataConfig dii) { this.dii = dii; }

    public static class StorageConfig {
        private String archive;
        private String metadata;

        public StorageConfig() {}

        public String getArchive() { return archive; }
        public void setArchive(String archive) { this.archive = archive; }

        public String getMetadata() { return metadata; }
        public void setMetadata(String metadata) { this.metadata = metadata; }
    }

    public static class DataConfig {
        private String endpoint;
        private List<String> dataTypes;

        public DataConfig() {}

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

        public List<String> getDataTypes() { return dataTypes; }
        public void setDataTypes(List<String> dataTypes) { this.dataTypes = dataTypes; }
    }
}