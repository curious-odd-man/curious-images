package com.github.curiousoddman.curious_images.config;

public enum AiExecutionProvider {
    /**
     * Run on CPU only (ONNX Runtime default).
     */
    CPU,
    /**
     * NVIDIA CUDA GPU execution. Requires CUDA 12+ drivers.
     */
    CUDA,
    /**
     * DirectX 12 GPU execution (AMD / Intel / NVIDIA, no CUDA drivers needed).
     */
    DIRECTML
}
