package com.local.documentconverter.model;

import java.util.List;

public class FormatOption {
    private String label;
    private List<String> outputs;

    public FormatOption() {
    }

    public FormatOption(String label, List<String> outputs) {
        this.label = label;
        this.outputs = outputs;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public List<String> getOutputs() {
        return outputs;
    }

    public void setOutputs(List<String> outputs) {
        this.outputs = outputs;
    }
}
