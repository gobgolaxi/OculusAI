package me.everyone.oculusai.check;

/** A rule that can fail for a player.
 *
 * <p>Checks only answer the question "did this player break this rule right
 * now". Everything that happens after a failure - confirmations, cooldowns,
 * chat, sound, history, the call to the service - is decided in {@link Alerts},
 * so a new check does not have to repeat any of it. */
public interface Check {

    String id();

    /** Config key that turns this check on and off. */
    default String enabledPath() {
        return "checks." + id() + ".enabled";
    }

    /** Report as a test: the alert is marked, but the rule still applies. */
    default boolean testOnly() {
        return false;
    }

    /** Report the first failure instead of waiting for confirmations. */
    default boolean immediate() {
        return false;
    }

    /** Failures needed before an alert; -1 takes the global setting. */
    default int confirmations() {
        return -1;
    }

    /** Quiet time between two alerts for one player; -1 takes the global
     *  setting, 0 means every confirmed failure is shown. */
    default long cooldownMs() {
        return -1;
    }

    /** Whether an alert for this check is also sent to the service. */
    default boolean reportsToService() {
        return false;
    }

    /** Whether the alert line should include the neural probability/buffer
     *  block. Only checks that actually use the model may show it. */
    default boolean showsMetrics() {
        return false;
    }
}
