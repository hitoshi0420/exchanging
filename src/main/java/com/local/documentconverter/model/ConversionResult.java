package com.local.documentconverter.model;

public class ConversionResult {
    private final String outputFileName;
    private final String method;
    private final String message;

    public ConversionResult(String outputFileName, String method, String message) {
        this.outputFileName = outputFileName;
        this.method = method;
        this.message = message;
    }

    public String getOutputFileName() {
        return outputFileName;
    }

    public String getMethod() {
        return method;
    }

    public String getMessage() {
        return message;
    }
}
