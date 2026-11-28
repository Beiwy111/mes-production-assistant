package com.upc.computer.assistant.v2.runtime;

/** Model binding for a full production workflow. Old requests use the original API workflow. */
public enum WorkflowModel {
    API, LOCAL;

    public static final String LOCAL_MODEL_ID = "mes-qwen14b-v2";

    public static WorkflowModel fromRequest(String value) {
        return value == null ? API : valueOf(value);
    }
}
