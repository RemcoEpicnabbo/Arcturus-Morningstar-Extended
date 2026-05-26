package com.eu.habbo.messages.outgoing.housekeeping;

import com.eu.habbo.habbohotel.rooms.Room;
import com.eu.habbo.messages.ServerMessage;
import com.eu.habbo.messages.outgoing.MessageComposer;
import com.eu.habbo.messages.outgoing.Outgoing;

import java.util.List;

public class HousekeepingRoomListComposer extends MessageComposer {
    private final List<Room> rooms;

    public HousekeepingRoomListComposer(List<Room> rooms) {
        this.rooms = rooms;
    }

    @Override
    protected ServerMessage composeInternal() {
        this.response.init(Outgoing.HousekeepingRoomListComposer);
        this.response.appendInt(this.rooms != null ? this.rooms.size() : 0);

        if (this.rooms != null) {
            for (Room room : this.rooms) {
                HousekeepingRoomDetailComposer.appendRoomFields(this.response, room);
            }
        }

        return this.response;
    }
}
