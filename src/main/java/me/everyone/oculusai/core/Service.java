package me.everyone.oculusai.core;

public interface Service {

    default void enable() {
    }

    default void disable() {
    }

    default void reload() {
    }

    default String name() {
        return getClass().getSimpleName();
    }
}
