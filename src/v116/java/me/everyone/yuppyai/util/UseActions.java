package me.everyone.yuppyai.util;

import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.EnumWrappers;

public final class UseActions {

    public static String of(PacketContainer packet) {
        EnumWrappers.EntityUseAction action = packet.getEntityUseActions().readSafely(0);
        return action == null ? null : action.name();
    }

    private UseActions() {
    }
}
