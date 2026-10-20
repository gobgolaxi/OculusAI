package me.everyone.oculusai.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ServiceHost {

    private final Logger logger;
    private final Map<Class<? extends Service>, Service> services = new LinkedHashMap<>();

    public ServiceHost(Logger logger) {
        this.logger = logger;
    }

    public <T extends Service> T add(T service) {
        services.put(service.getClass(), service);
        return service;
    }

    public void enableAll() {
        for (Service service : services.values()) {
            try {
                service.enable();
            } catch (Throwable failure) {
                logger.log(Level.SEVERE, "Failed to enable " + service.name(), failure);
                disableAll();
                throw failure;
            }
        }
    }

    public void disableAll() {
        List<Service> reversed = new ArrayList<>(services.values());
        Collections.reverse(reversed);
        for (Service service : reversed) {
            try {
                service.disable();
            } catch (Throwable failure) {
                logger.log(Level.SEVERE, "Failed to disable " + service.name(), failure);
            }
        }
    }

    public void reloadAll() {
        for (Service service : services.values()) {
            try {
                service.reload();
            } catch (Throwable failure) {
                logger.log(Level.SEVERE, "Failed to reload " + service.name(), failure);
            }
        }
    }

    public void clear() {
        services.clear();
    }
}
