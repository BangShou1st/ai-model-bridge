package io.github.bangshou1st.modelbridge.core.model;

public record ToolCallDelta(int index, String id, String name, String argumentsDelta) {
    public ToolCallDelta {
        id = id == null ? "" : id;
        name = name == null ? "" : name;
        argumentsDelta = argumentsDelta == null ? "" : argumentsDelta;
    }
}
