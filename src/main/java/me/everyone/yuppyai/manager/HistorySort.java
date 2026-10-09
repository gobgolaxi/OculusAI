package me.everyone.yuppyai.manager;

import java.util.Locale;

public enum HistorySort {

    FRESHNESS,
    SUSPICION;

    public HistorySort next() {
        return this == FRESHNESS ? SUSPICION : FRESHNESS;
    }

    public String langKey() {
        return "history.sort." + name().toLowerCase(Locale.ROOT);
    }
}
