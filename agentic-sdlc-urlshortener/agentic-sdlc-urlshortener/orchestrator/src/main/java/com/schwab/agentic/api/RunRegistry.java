package com.schwab.agentic.api;

import com.schwab.agentic.domain.Run;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory registry of runs so the API can report status after a background start. */
@Component
public class RunRegistry {

    private final Map<String, Run> runs = new ConcurrentHashMap<>();

    /**
     * Registers (or replaces) a run by its id.
     *
     * @param run the run to store
     */
    public void put(Run run) {
        runs.put(run.id(), run);
    }

    /**
     * Looks up a run.
     *
     * @param id the run id
     * @return the run, or {@code null} if unknown
     */
    public Run get(String id) {
        return runs.get(id);
    }

    /** @return all registered runs */
    public Collection<Run> all() {
        return runs.values();
    }
}
