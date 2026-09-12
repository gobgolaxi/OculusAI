package me.everyone.yuppyai.manager;

public interface Manager {

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
