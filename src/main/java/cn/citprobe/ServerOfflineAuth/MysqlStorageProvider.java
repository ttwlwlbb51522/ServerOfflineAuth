package cn.citprobe.ServerOfflineAuth;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MysqlStorageProvider implements IStorageProvider {
    private Connection connection;
    private String tableName;
    private Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();
    private boolean cacheLoaded = false;

    @Override
    public void init() throws Exception {
        String host = Config.SERVER.mysqlHost.get();
        int port = Config.SERVER.mysqlPort.get();
        String database = Config.SERVER.mysqlDatabase.get();
        String user = Config.SERVER.mysqlUsername.get();
        String password = Config.SERVER.mysqlPassword.get();
        tableName = Config.SERVER.mysqlTable.get();

        // 建议添加 autoReconnect=true 参数
        String url = "jdbc:mysql://" + host + ":" + port + "/" + database +
                "?useSSL=false&serverTimezone=UTC&autoReconnect=true";
        Class.forName("com.mysql.cj.jdbc.Driver");
        connection = DriverManager.getConnection(url, user, password);

        createTableIfNotExists();
        ServerOfflineAuth.LOGGER.info("MySQL 存储初始化成功，表: {}", tableName);
    }

    private void createTableIfNotExists() throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS " + tableName + " (" +
                "uuid CHAR(36) PRIMARY KEY, " +
                "player_name VARCHAR(36), " +
                "hashed_password TEXT NOT NULL, " +
                "registered_time BIGINT NOT NULL, " +
                "last_ip VARCHAR(45), " +
                "last_dimension VARCHAR(255), " +
                "last_x DOUBLE, last_y DOUBLE, last_z DOUBLE, " +
                "last_yrot FLOAT, last_xrot FLOAT, " +
                "game_mode INT, may_fly BOOLEAN, flying BOOLEAN, " +
                "login_token VARCHAR(36), token_expiry BIGINT" +
                ")";
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }

    // 检查连接是否有效
    private boolean isConnectionValid() {
        try {
            if (connection == null || connection.isClosed()) return false;
            return connection.isValid(5); // 5秒超时
        } catch (SQLException e) {
            return false;
        }
    }

    // 确保连接可用，失效则重连
    private void ensureConnection() throws SQLException {
        if (!isConnectionValid()) {
            reconnect();
        }
    }

    private void reconnect() throws SQLException {
        ServerOfflineAuth.LOGGER.info("MySQL 连接已断开，尝试重新连接...");
        close(); // 关闭旧的连接
        String host = Config.SERVER.mysqlHost.get();
        int port = Config.SERVER.mysqlPort.get();
        String database = Config.SERVER.mysqlDatabase.get();
        String user = Config.SERVER.mysqlUsername.get();
        String password = Config.SERVER.mysqlPassword.get();
        String url = "jdbc:mysql://" + host + ":" + port + "/" + database +
                "?useSSL=false&serverTimezone=UTC&autoReconnect=true";
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            connection = DriverManager.getConnection(url, user, password);
            ServerOfflineAuth.LOGGER.info("MySQL 重新连接成功");
        } catch (ClassNotFoundException e) {
            throw new SQLException("MySQL 驱动未找到", e);
        }
    }

    private void ensureCacheLoaded() {
        if (!cacheLoaded) {
            cache.putAll(loadAll());
            cacheLoaded = true;
        }
    }

    @Override
    public PlayerData getPlayerData(UUID uuid) {
        ensureCacheLoaded();
        return cache.get(uuid);
    }

    @Override
    public void putPlayerData(UUID uuid, PlayerData data) {
        ensureCacheLoaded();
        cache.put(uuid, data);
        try {
            ensureConnection(); // 关键：确保连接有效
        } catch (SQLException e) {
            ServerOfflineAuth.LOGGER.error("无法建立 MySQL 连接，保存玩家数据失败", e);
            return;
        }
        String upsertSql = "INSERT INTO " + tableName + " (uuid, player_name, hashed_password, registered_time, last_ip, " +
                "last_dimension, last_x, last_y, last_z, last_yrot, last_xrot, game_mode, may_fly, flying, login_token, token_expiry) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE " +
                "player_name=VALUES(player_name), hashed_password=VALUES(hashed_password), registered_time=VALUES(registered_time), " +
                "last_ip=VALUES(last_ip), last_dimension=VALUES(last_dimension), last_x=VALUES(last_x), last_y=VALUES(last_y), " +
                "last_z=VALUES(last_z), last_yrot=VALUES(last_yrot), last_xrot=VALUES(last_xrot), game_mode=VALUES(game_mode), " +
                "may_fly=VALUES(may_fly), flying=VALUES(flying), login_token=VALUES(login_token), token_expiry=VALUES(token_expiry)";
        try (PreparedStatement ps = connection.prepareStatement(upsertSql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, data.getPlayerName());
            ps.setString(3, data.getHashedPassword());
            ps.setLong(4, data.getRegisteredTime());
            ps.setString(5, data.getLastIp());
            ps.setString(6, data.getLastDimension());
            ps.setDouble(7, data.getLastX());
            ps.setDouble(8, data.getLastY());
            ps.setDouble(9, data.getLastZ());
            ps.setFloat(10, data.getLastYRot());
            ps.setFloat(11, data.getLastXRot());
            ps.setInt(12, data.getGameMode());
            ps.setBoolean(13, data.isMayFly());
            ps.setBoolean(14, data.isFlying());
            ps.setString(15, data.getLoginToken());
            ps.setLong(16, data.getTokenExpiry());
            ps.executeUpdate();
        } catch (SQLException e) {
            ServerOfflineAuth.LOGGER.error("保存玩家数据到 MySQL 失败", e);
        }
    }

    @Override
    public boolean hasPlayerData(UUID uuid) {
        ensureCacheLoaded();
        return cache.containsKey(uuid);
    }

    @Override
    public Map<UUID, PlayerData> loadAll() {
        Map<UUID, PlayerData> map = new HashMap<>();
        try {
            ensureConnection();
        } catch (SQLException e) {
            ServerOfflineAuth.LOGGER.error("无法建立 MySQL 连接，加载数据失败", e);
            return map;
        }
        String sql = "SELECT uuid, player_name, hashed_password, registered_time, last_ip, last_dimension, " +
                "last_x, last_y, last_z, last_yrot, last_xrot, game_mode, may_fly, flying, login_token, token_expiry " +
                "FROM " + tableName;
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("uuid"));
                PlayerData data = new PlayerData();
                data.setUuid(uuid);
                data.setPlayerName(rs.getString("player_name"));
                data.setHashedPassword(rs.getString("hashed_password"));
                data.setRegisteredTime(rs.getLong("registered_time"));
                data.setLastIp(rs.getString("last_ip"));
                data.setLastDimension(rs.getString("last_dimension"));
                data.setLastX(rs.getDouble("last_x"));
                data.setLastY(rs.getDouble("last_y"));
                data.setLastZ(rs.getDouble("last_z"));
                data.setLastYRot(rs.getFloat("last_yrot"));
                data.setLastXRot(rs.getFloat("last_xrot"));
                data.setGameMode(rs.getInt("game_mode"));
                data.setMayFly(rs.getBoolean("may_fly"));
                data.setFlying(rs.getBoolean("flying"));
                data.setLoginToken(rs.getString("login_token"));
                data.setTokenExpiry(rs.getLong("token_expiry"));
                map.put(uuid, data);
            }
        } catch (SQLException e) {
            ServerOfflineAuth.LOGGER.error("从 MySQL 加载所有玩家数据失败", e);
        }
        return map;
    }

    @Override
    public void saveAll(Map<UUID, PlayerData> allData) {
        cache.putAll(allData);
        try {
            ensureConnection();
        } catch (SQLException e) {
            ServerOfflineAuth.LOGGER.error("无法建立 MySQL 连接，批量保存失败", e);
            return;
        }
        String deleteSql = "DELETE FROM " + tableName;
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(deleteSql);
        } catch (SQLException e) {
            ServerOfflineAuth.LOGGER.error("清空 MySQL 表失败", e);
            return;
        }

        String insertSql = "INSERT INTO " + tableName + " (uuid, player_name, hashed_password, registered_time, last_ip, last_dimension, " +
                "last_x, last_y, last_z, last_yrot, last_xrot, game_mode, may_fly, flying, login_token, token_expiry) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(insertSql)) {
            for (Map.Entry<UUID, PlayerData> entry : allData.entrySet()) {
                PlayerData data = entry.getValue();
                ps.setString(1, entry.getKey().toString());
                ps.setString(2, data.getPlayerName());
                ps.setString(3, data.getHashedPassword());
                ps.setLong(4, data.getRegisteredTime());
                ps.setString(5, data.getLastIp());
                ps.setString(6, data.getLastDimension());
                ps.setDouble(7, data.getLastX());
                ps.setDouble(8, data.getLastY());
                ps.setDouble(9, data.getLastZ());
                ps.setFloat(10, data.getLastYRot());
                ps.setFloat(11, data.getLastXRot());
                ps.setInt(12, data.getGameMode());
                ps.setBoolean(13, data.isMayFly());
                ps.setBoolean(14, data.isFlying());
                ps.setString(15, data.getLoginToken());
                ps.setLong(16, data.getTokenExpiry());
                ps.addBatch();
            }
            ps.executeBatch();
            ServerOfflineAuth.LOGGER.info("MySQL 批量保存完成，共 {} 条记录", allData.size());
        } catch (SQLException e) {
            ServerOfflineAuth.LOGGER.error("批量插入 MySQL 数据失败", e);
        }
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                ServerOfflineAuth.LOGGER.error("关闭 MySQL 连接失败", e);
            }
        }
    }
}