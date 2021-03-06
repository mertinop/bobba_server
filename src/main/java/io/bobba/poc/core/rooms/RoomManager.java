package io.bobba.poc.core.rooms;

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
import io.bobba.poc.communication.outgoing.roomdata.HeightMapComposer;
import io.bobba.poc.communication.outgoing.roomdata.RoomDataComposer;
import io.bobba.poc.communication.outgoing.roomdata.RoomModelInfoComposer;
import io.bobba.poc.core.rooms.gamemap.RoomModel;
import io.bobba.poc.core.rooms.roomdata.LockType;
import io.bobba.poc.core.rooms.roomdata.RoomData;
import io.bobba.poc.core.users.User;
import io.bobba.poc.misc.logging.LogLevel;
import io.bobba.poc.misc.logging.Logging;

public class RoomManager {
	private static int roomId = 1;
	private Map<Integer, Room> rooms;
	private Map<String, RoomModel> models;
	
	public RoomManager() {
		this.rooms = new HashMap<>();
		this.models = new HashMap<>();
	}
	
	public RoomModel getModel(String modelId) {
		return models.getOrDefault(modelId, null);
	}
	
	public Room getLoadedRoom(int roomId) {
		return rooms.getOrDefault(roomId, null);
	}
	
	public void initialize() throws SQLException {
		this.loadModelsFromDb();
		this.loadRoomsFromDB();
	}
	
	private void loadModelsFromDb() throws SQLException {	
        try (Connection connection = BobbaEnvironment.getGame().getDatabase().getDataSource().getConnection(); Statement statement = connection.createStatement()) {
            if (statement.execute("SELECT id, door_x, door_y, door_z, door_dir, heightmap FROM room_models")) {
                try (ResultSet set = statement.getResultSet()) {
                    while (set.next()) {                    	
                    	String name = set.getString("id");
        				int doorX = set.getInt("door_x");
        				int doorY = set.getInt("door_y");
        				int doorZ = set.getInt("door_z");
        				int doorDir = set.getInt("door_dir");
        				String heightmap = set.getString("heightmap");

        				models.put(name, new RoomModel(doorX, doorY, doorZ, doorDir, heightmap));
                    }
                }
            }
        } catch (SQLException e) {
            throw e;
        }
	}
	private void loadRoomsFromDB() throws SQLException {
		try (Connection connection = BobbaEnvironment.getGame().getDatabase().getDataSource().getConnection(); Statement statement = connection.createStatement()) {
            if (statement.execute("SELECT id, name, owner, description, capacity, password, model_id, lock_type FROM room")) {
                try (ResultSet set = statement.getResultSet()) {
                    while (set.next()) {
                    	int id = set.getInt("id");
                    	String name = set.getString("name");
                    	String owner = set.getString("owner");
                    	String description = set.getString("description");
                    	int capacity = set.getInt("capacity");
                    	String password = set.getString("password");
                    	String modelId = set.getString("model_id");
                    	String lockTypeName = set.getString("lock_type");
                    	LockType lockType = LockType.valueOf(lockTypeName);
                    	RoomData roomData = new RoomData(id, name, owner, description, capacity, password, modelId, lockType);
                    	Room room = new Room(roomData, getModel(roomData.getModelId()));
                    	this.rooms.put(id, room);
                    }
                }
            }
        } catch (SQLException e) {
            throw e;
        }
	}
	
	public void onCycle() {
		List<Room> cyclingRooms = new ArrayList<>(rooms.values());
		for (Room room : cyclingRooms) {
			room.onCycle();
		}
	}
	
	public void prepareRoomForUser(User user, int roomId, String password) {
		Room currentRoom = user.getCurrentRoom();
		if (currentRoom != null) {
			currentRoom.getRoomUserManager().removeUserFromRoom(user);
		}
		Room newRoom = null;
		if (roomId == -1 && this.getLoadedRooms().size() > 0) {
			newRoom = this.getLoadedRooms().get(0); //Home room
		} else {
			newRoom = this.getLoadedRoom(roomId);	
		}
		 
		if (newRoom != null) {
			user.setLoadingRoomId(newRoom.getRoomData().getId());
			user.getClient().sendMessage(new RoomModelInfoComposer(newRoom.getRoomData().getModelId(), newRoom.getRoomData().getId()));
		}
	}
	
	public void prepareHeightMapForUser(User user) {
		Room room = this.getLoadedRoom(user.getLoadingRoomId());
		if (room != null) {
			user.getClient().sendMessage(new HeightMapComposer(room.getGameMap().getRoomModel()));
		}
	}

	public void finishRoomLoadingForUser(User user) {
		Room room = this.getLoadedRoom(user.getLoadingRoomId());
		if (room != null) {
			room.getRoomUserManager().addUserToRoom(user);
			user.setLoadingRoomId(0);
			user.getClient().sendMessage(new RoomDataComposer(room.getRoomData()));
		}
	}

	public void handleUserLeaveRoom(User user) {
		Room currentRoom = user.getCurrentRoom();
		if (currentRoom != null) {
			currentRoom.getRoomUserManager().removeUserFromRoom(user);
		}
	}
	
	public List<Room> getLoadedRooms() {
		return new ArrayList<>(rooms.values());
	}

	public void createRoom(User user, String roomName, String modelId) {
		if (roomName.length() > 0) {
			RoomModel model = getModel(modelId);
			if (model != null) {
				int roomId = -1;
				RoomData roomData = new RoomData(roomId, roomName, user.getUsername(), "", 25, "", modelId, LockType.Open);
				Room room = new Room(roomData, model);
				roomId = this.insertRoomToDB(room);
				if(roomId > 0) {
					room.setId(roomId);
					this.rooms.put(roomId, room);
					prepareRoomForUser(user, roomId, "");
				}
			}
		}
	}
	/**
	 * @return stored id or -1 on failure
	 */
	private int insertRoomToDB(Room room) {
		int roomId = -1;
		try {
			Connection connection = BobbaEnvironment.getGame().getDatabase().getDataSource().getConnection();
			PreparedStatement statement = connection.prepareStatement("INSERT INTO room (name, owner, description, capacity, password, model_id, lock_type) VALUES (?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
			RoomData roomData = room.getRoomData();
			statement.setString(1, roomData.getName());
			statement.setString(2, roomData.getOwner());
			statement.setString(3, roomData.getDescription());
			statement.setInt(4, roomData.getCapacity());
			statement.setString(5, roomData.getPassword());
			statement.setString(6, roomData.getModelId());
			statement.setString(7, roomData.getLockType().toString());
			statement.execute();
			ResultSet resultSet = statement.getGeneratedKeys();
	        if (resultSet.next()) {
	            roomId = resultSet.getInt(1);
	        }
		} catch (SQLException e) {
			Logging.getInstance().writeLine(e, LogLevel.Verbose, this.getClass());
		}
		Logging.getInstance().writeLine("Created roomId:" + roomId, LogLevel.Verbose, this.getClass());
		return roomId;
	}
}
