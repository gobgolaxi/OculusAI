package me.everyone.oculusai.storage;

import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.core.Service;

public final class Storage implements Service {

    private final DatasetStore datasets;
    private final HistoryStore history;
    private final JournalStore journal;

    public Storage(OculusAI plugin) {
        this.datasets = new DatasetStore(plugin);
        this.history = new HistoryStore(plugin);
        this.journal = new JournalStore(plugin);
    }

    @Override
    public void enable() {
        datasets.enable();
        history.enable();
        journal.enable();
    }

    @Override
    public void disable() {
        journal.disable();
        history.disable();
        datasets.disable();
    }

    public DatasetStore datasets() {
        return datasets;
    }

    public HistoryStore history() {
        return history;
    }

    public JournalStore journal() {
        return journal;
    }
}
