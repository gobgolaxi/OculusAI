package me.everyone.yuppyai.manager;

import java.util.Locale;

/**
 * Orderings the history menu can lay a player's readings out in.
 *
 * <p>{@link #FRESHNESS} answers "what happened lately", {@link #SUSPICION}
 * answers "what looks worst". The viewer picks between them by clicking a
 * button, and the choice sticks per viewer.
 */
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