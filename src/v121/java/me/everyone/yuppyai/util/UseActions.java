package me.everyone.yuppyai.util;

import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.WrappedEnumEntityUseAction;

public final class UseActions {

    public static String of(PacketContainer packet) {
        WrappedEnumEntityUseAction wrapped = packet.getEnumEntityUseActions().readSafely(0);
        return wrapped == null || wrapped.getAction() == null ? null : wrapped.getAction().name();
    }

    private UseActions() {
    }
}
