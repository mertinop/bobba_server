package io.bobba.poc.core.rooms.items;

import java.awt.Point;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.bobba.poc.BobbaEnvironment;
import io.bobba.poc.communication.outgoing.rooms.FurniRemoveComposer;
import io.bobba.poc.communication.outgoing.rooms.SerializeFloorItemComposer;
import io.bobba.poc.communication.outgoing.rooms.SerializeWallItemComposer;
import io.bobba.poc.core.items.BaseItem;
import io.bobba.poc.core.items.ItemType;
import io.bobba.poc.core.rooms.Room;
import io.bobba.poc.core.rooms.roomdata.RoomData;
import io.bobba.poc.core.rooms.users.RoomUser;
import io.bobba.poc.core.users.inventory.UserItem;
import io.bobba.poc.misc.logging.LogLevel;
import io.bobba.poc.misc.logging.Logging;

public class RoomItemManager {
	private Map<Integer, RoomItem> floorItems;
	private Map<Integer, WallItem> wallItems;
	private Room room;

	public RoomItemManager(Room room) {
		this.room = room;
		floorItems = new HashMap<>();
		wallItems = new HashMap<>();
	}

	public RoomItem getItem(int id) {
		if (floorItems.containsKey(id))
			return floorItems.get(id);
		if (wallItems.containsKey(id))
			return wallItems.get(id);
		return null;
	}

	public List<RoomItem> getFloorItems() {
		return new ArrayList<>(floorItems.values());
	}

	public List<WallItem> getWallItems() {
		return new ArrayList<>(wallItems.values());
	}

	public void addFloorItemToRoom(int id, int x, int y, double z, int rot, int state, BaseItem baseItem) {
		if (getItem(id) == null) {
			floorItems.put(id, new RoomItem(id, x, y, z, rot, state, room, baseItem));
			RoomItem item = floorItems.get(id);
			insertItemToDB(item);
			room.getGameMap().addItemToMap(item);
			room.sendMessage(new SerializeFloorItemComposer(item));
			room.getRoomUserManager().updateUserStatusses();
		}
	}

	public void removeItem(int id) {
		RoomItem item = getItem(id);
		if (item != null) {
			if (item instanceof WallItem) {
				wallItems.remove(id);
			} else {
				floorItems.remove(id);
				room.getGameMap().removeItemFromMap(item);
				room.getRoomUserManager().updateUserStatusses();
			}
			room.sendMessage(new FurniRemoveComposer(id));
		}
	}

	public void removeAllFurniture() {
		List<Integer> items = new ArrayList<>();
		items.addAll(floorItems.keySet());
		items.addAll(wallItems.keySet());
		for (int itemId : items) {
			removeItem(itemId);
		}
	}

	public void addWallItemToRoom(int id, int x, int y, int rot, int state, BaseItem baseItem) {
		if (getItem(id) == null) {
			wallItems.put(id, new WallItem(id, x, y, rot, state, room, baseItem));
			room.sendMessage(new SerializeWallItemComposer(wallItems.get(id)));
		}
	}

	public void furniInteract(RoomUser user, int itemId) {
		RoomItem item = getItem(itemId);
		if (item != null) {
			item.getInteractor().onTrigger(user, true);
		}
	}

	public void onCycle() {
	}

	public void handleItemMovement(int itemId, int x, int y, int rot, RoomUser user) {
		RoomItem item = getItem(itemId);
		if (item != null) {
			if (item.getBaseItem().getDirections().contains(rot)) {
				this.removeItem(itemId);
				if (item instanceof WallItem) {
					this.addWallItemToRoom(itemId, x, y, rot, item.getState(), item.getBaseItem());
				} else {
					double nextZ = room.getGameMap().sqAbsoluteHeight(new Point(x, y));
					this.addFloorItemToRoom(itemId, x, y, nextZ, rot, item.getState(), item.getBaseItem());
					this.updateItemFromDB(item);
				}
			}
		}
	}

	public void handleItemPickUp(int itemId, RoomUser user) {
		RoomItem item = getItem(itemId);
		if (item != null) {
			this.removeItem(itemId);
			
			user.getUser().getInventory().addItem(itemId, item.getBaseItem(), item.getState());
		}
	}
	
	public void handlePickAll(RoomUser user) {
		for (RoomItem item: new ArrayList<>(floorItems.values())) {
			handleItemPickUp(item.getId(), user);
		}
		for (RoomItem item: new ArrayList<>(wallItems.values())) {
			handleItemPickUp(item.getId(), user);
		}
	}

	public void handleItemPlacement(int itemId, int x, int y, int rot, RoomUser user) {
		UserItem userItem = user.getUser().getInventory().getItem(itemId);
		if (userItem != null) {
			user.getUser().getInventory().removeItem(itemId);
			if (userItem.getBaseItem().getType() == ItemType.WallItem) {
				addWallItemToRoom(itemId, x, y, rot, userItem.getState(), userItem.getBaseItem());
			} else {
				double nextZ = room.getGameMap().sqAbsoluteHeight(new Point(x, y));
				this.addFloorItemToRoom(itemId, x, y, nextZ, rot, userItem.getState(), userItem.getBaseItem());
			}
		}

	}

	public void insertItemToDB(RoomItem item) {
		try {
			Connection connection = BobbaEnvironment.getGame().getDatabase().getDataSource().getConnection();
			PreparedStatement statement = connection.prepareStatement("INSERT INTO room_item (room_id, base_id, state, x, y, z, rotation) VALUES (?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
			int roomId = item.getRoom().getRoomData().getId();
			int baseId = item.getBaseItem().getId();
			int state = item.getState();
			int x = item.getX();
			int y = item.getY();
			double z = item.getZ();
			int rotation = item.getRot();
			statement.setInt(1, roomId);
			statement.setInt(2, baseId);
			statement.setInt(3, state);
			statement.setInt(4, x);
			statement.setInt(5, y);
			statement.setDouble(6, z);
			statement.setInt(7, rotation);
			statement.execute();
			Logging.getInstance().writeLine("Inserted item", LogLevel.Verbose, this.getClass());
		} catch (SQLException e) {
			Logging.getInstance().writeLine(e, LogLevel.Verbose, this.getClass());
		}
	}
	public void updateItemFromDB(RoomItem item) {
		int itemId = -1;
		try {
			Connection connection = BobbaEnvironment.getGame().getDatabase().getDataSource().getConnection();
			PreparedStatement statement = connection.prepareStatement("UPDATE room_item SET state = ?, x = ?, y = ?, z = ?, rotation = ? WHERE room_id = ?", Statement.RETURN_GENERATED_KEYS);
			int state = item.getState();
			int x = item.getX();
			int y = item.getY();
			double z = item.getZ();
			int rotation = item.getRot();
			int baseId = item.getBaseItem().getId();
			statement.setInt(1, state);
			statement.setInt(2, baseId);
			statement.setInt(3, state);
			statement.setInt(4, x);
			statement.setInt(5, y);
			statement.setDouble(6, z);
			statement.setInt(7, rotation);
			statement.execute();

		} catch (SQLException e) {
			Logging.getInstance().writeLine(e, LogLevel.Verbose, this.getClass());
		}
		return roomitemId;
	}
}
