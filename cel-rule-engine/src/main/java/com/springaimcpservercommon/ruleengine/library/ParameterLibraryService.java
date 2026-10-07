package com.springaimcpservercommon.ruleengine.library;

import com.springaimcpservercommon.ruleengine.config.RuleEngineProperties;
import com.springaimcpservercommon.ruleengine.repo.LibraryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps the parameter library in memory: loaded once at start-up, reloaded when the library's fingerprint in the
 * database changes (checked every {@code ruleengine.library-refresh}) or when {@link #refresh()} is called after an
 * edit. Readers always see a complete snapshot; a failing reload keeps the previous one.
 */
@Service
public class ParameterLibraryService {

    private static final Logger log = LoggerFactory.getLogger(ParameterLibraryService.class);

    private final LibraryRepository repository;
    private final Duration interval;
    private final AtomicReference<ParameterLibrary> current = new AtomicReference<>();
    private volatile String loadedFingerprint = "";
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "parameter-library-refresh");
        t.setDaemon(true);
        return t;
    });

    public ParameterLibraryService(LibraryRepository repository, RuleEngineProperties properties) {
        this.repository = repository;
        this.interval = properties.libraryRefresh();
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        refresh();
        scheduler.scheduleWithFixedDelay(this::refreshIfChanged, interval.toMillis(), interval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /**
     * The cached snapshot (loaded on first use if start-up has not done it yet).
     *
     * @return the library
     */
    public ParameterLibrary current() {
        ParameterLibrary library = current.get();
        return library != null ? library : refresh();
    }

    /**
     * Reloads the library from the database now.
     *
     * @return the new snapshot
     */
    public synchronized ParameterLibrary refresh() {
        String fingerprint = repository.fingerprint();
        ParameterLibrary library = ParameterLibrary.of(fingerprint, repository.loadAll());
        current.set(library);
        loadedFingerprint = fingerprint;
        log.info("parameter library loaded: {} objects, {} attributes", library.objects().size(),
                library.celNames().size());
        return library;
    }

    private void refreshIfChanged() {
        try {
            if (!repository.fingerprint().equals(loadedFingerprint)) {
                refresh();
            }
        } catch (RuntimeException e) {
            log.warn("parameter library refresh failed, keeping the cached one: {}", e.getMessage());
        }
    }
}
