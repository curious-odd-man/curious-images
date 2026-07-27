package com.github.curiousoddman.curious_images.domain.imports.metadata;

import com.github.curiousoddman.curious_images.domain.imports.data.ImportJobType;
import com.github.curiousoddman.curious_images.persistence.ImportJobStatsRepository;
import com.github.curiousoddman.curious_images.util.TimeProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class StatsSessionFactory {

    private final ImportJobStatsRepository importJobStatsRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final TimeProvider timeProvider;

    public StatsSession getSession(ImportJobType jobType, List<String> effectiveRootPaths, AtomicBoolean interrupted) {
        //noinspection resource
        return new StatsSession(
                importJobStatsRepository,
                applicationEventPublisher,
                timeProvider,
                interrupted
        )
                .begin(jobType, effectiveRootPaths);
    }
}
