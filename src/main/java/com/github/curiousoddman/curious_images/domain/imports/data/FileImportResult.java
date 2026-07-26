package com.github.curiousoddman.curious_images.domain.imports.data;

public record FileImportResult(ImportOutcome outcome, boolean video, long fileSize, String reason) {}
