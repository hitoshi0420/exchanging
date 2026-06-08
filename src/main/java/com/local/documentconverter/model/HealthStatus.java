package com.local.documentconverter.model;

public class HealthStatus {
    private boolean soffice;
    private boolean pandoc;

    public HealthStatus() {
    }

    public HealthStatus(boolean soffice, boolean pandoc) {
        this.soffice = soffice;
        this.pandoc = pandoc;
    }

    public boolean isSoffice() {
        return soffice;
    }

    public void setSoffice(boolean soffice) {
        this.soffice = soffice;
    }

    public boolean isPandoc() {
        return pandoc;
    }

    public void setPandoc(boolean pandoc) {
        this.pandoc = pandoc;
    }
}
