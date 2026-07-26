package com.github.curiousoddman.curious_images.domain.imports.data;

import java.nio.file.Path;
import java.util.List;

public record ScanResult(List<Path> files, List<Path> unsupportedExtensionFiles) {}
