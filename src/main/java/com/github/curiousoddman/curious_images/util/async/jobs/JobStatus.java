package com.github.curiousoddman.curious_images.util.async.jobs;

public enum JobStatus {
    NEVER_RUN,
    RUNNING,
    COMPLETED,
    INTERRUPT_REQUESTED,
    INTERRUPTED,
    FAILED;

    public String asText() {
        return switch (this) {
            case RUNNING -> "Running…";
            case COMPLETED -> "Completed";
            case FAILED -> "Failed";
            case INTERRUPTED -> "Interrupted";
            case INTERRUPT_REQUESTED -> "Stopping…";
            case NEVER_RUN -> "Never run";
        };
    }
}