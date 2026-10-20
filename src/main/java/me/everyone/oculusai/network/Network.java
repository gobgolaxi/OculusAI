package me.everyone.oculusai.network;

import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.core.Service;

public final class Network implements Service {

    private final ApiClient api;
    private final UploadQueue uploads;

    public Network(OculusAI plugin) {
        this.api = new ApiClient(plugin);
        this.uploads = new UploadQueue(plugin);
    }

    @Override
    public void enable() {
        api.enable();
        uploads.enable();
    }

    @Override
    public void disable() {
        uploads.disable();
        api.disable();
    }

    public ApiClient api() {
        return api;
    }

    public UploadQueue uploads() {
        return uploads;
    }
}
