package com.github.curiousoddman.curious_images.domain.ai;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.github.curiousoddman.curious_images.config.AiConfig;
import com.github.curiousoddman.curious_images.util.async.jobs.IrrecoverableIterationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class OnnxModelRegistry implements DisposableBean {

    private final OrtEnvironment                        env      = OrtEnvironment.getEnvironment();
    private final ConcurrentHashMap<String, OrtSession> sessions = new ConcurrentHashMap<>();
    private final AiConfig                              config;

    public OrtSession getOrLoad(String modelKey, Path modelPath, List<String> expectedOutputNames) throws IrrecoverableIterationException {
        OrtSession ortSession = sessions.get(modelKey);
        if (ortSession != null) {
            return ortSession;
        }

        try {
            log.info("Loading ONNX model '{}' from {}: {}", modelKey, modelPath, OrtEnvironment.getAvailableProviders());
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(config.getOnnxIntraOpThreads());
            switch (config.getAiExecutionProvider()) {
                case CUDA -> opts.addCUDA(0);
                case DIRECTML -> opts.addDirectML(0);
                case CPU -> { /* ONNX Runtime default — no extra provider needed */ }
            }
            OrtSession session = env.createSession(modelPath.toString(), opts);
            log.info("ONNX model '{}' loaded successfully", modelKey);
            sessions.put(modelKey, session);

            List<String> sessionNames = session.getOutputNames()
                                               .stream()
                                               .toList();
            // Verify that output order matches indexes that are used to fetch outputs
            if (!sessionNames.equals(expectedOutputNames)) {
                throw new IrrecoverableIterationException(new IllegalArgumentException(sessionNames + " vs " + expectedOutputNames));
            }

            return session;
        } catch (OrtException e) {
            throw new IrrecoverableIterationException("Failed to load ONNX model '" + modelKey + "' from " + modelPath, e);
        }
    }

    public void evict(String modelKey) {
        OrtSession removed = sessions.remove(modelKey);
        if (removed != null) {
            try {
                removed.close();
                log.info("Evicted ONNX model '{}'", modelKey);
            } catch (OrtException e) {
                log.warn("Error closing evicted ONNX session '{}'", modelKey, e);
            }
        }
    }

    public void evictAll() {
        sessions.keySet()
                .forEach(this::evict);
        log.info("Evicted all ONNX sessions (settings change)");
    }

    /**
     * Closes all sessions and the shared {@link OrtEnvironment} on Spring shutdown.
     */
    @Override
    public void destroy() {
        sessions.forEach((key, session) -> {
            try {
                session.close();
            } catch (OrtException e) {
                log.warn("Error closing ONNX session '{}'", key, e);
            }
        });
        sessions.clear();
        env.close();
    }
}
