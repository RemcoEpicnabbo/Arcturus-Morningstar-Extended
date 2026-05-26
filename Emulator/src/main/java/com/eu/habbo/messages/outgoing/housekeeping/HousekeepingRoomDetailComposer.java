package com.eu.habbo.messages.outgoing.housekeeping;

import com.eu.habbo.habbohotel.rooms.Room;
import com.eu.habbo.habbohotel.rooms.RoomState;
import com.eu.habbo.messages.ServerMessage;
import com.eu.habbo.messages.outgoing.MessageComposer;
import com.eu.habbo.messages.outgoing.Outgoing;

public class HousekeepingRoomDetailComposer extends MessageComposer {
    private final Room room;

    public HousekeepingRoomDetailComposer(Room room) {
        this.room = room;
    }

    @Override
    protected ServerMessage composeInternal() {
        this.response.init(Outgoing.HousekeepingRoomDetailComposer);

        if (this.room == null) {
            this.response.appendBoolean(false);
            return this.response;
        }

        this.response.appendBoolean(true);
        appendRoomFields(this.response, this.room);

        return this.response;
    }

    /** Shared by HousekeepingRoomListComposer too. */
    public static void appendRoomFields(ServerMessage response, Room room) {
        response.appendInt(room.getId());
        response.appendString(safe(room.getName()));
        response.appendString(safe(room.getDescription()));
        response.appendInt(room.getOwnerId());
        response.appendString(safe(room.getOwnerName()));
        response.appendInt(room.getUserCount());
        response.appendInt(room.getUsersMax());
        response.appendBoolean(room.getState() != null && room.getState() != RoomState.OPEN);
        response.appendBoolean(room.isMuted());
        response.appendBoolean(room.isPublicRoom());
        response.appendInt(0); // createdAt — Room doesn't expose; left as 0 until a schema-side timestamp surfaces.
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }
}
