package org.megatrex4.twitch.db;

import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class DatabaseManager {

    private final HikariDataSource dataSource;

    public DatabaseManager(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public boolean addBlacklistedUser(String username) {
        return executeUpdate("INSERT IGNORE INTO twitch_blacklist_users (username) VALUES (?)", username);
    }

    public boolean removeBlacklistedUser(String username) {
        return executeUpdate("DELETE FROM twitch_blacklist_users WHERE username = ?", username);
    }

    public boolean addBlacklistedWord(String word) {
        return executeUpdate("INSERT IGNORE INTO twitch_blacklist_words (word) VALUES (?)", word);
    }

    public boolean removeBlacklistedWord(String word) {
        return executeUpdate("DELETE FROM twitch_blacklist_words WHERE word = ?", word);
    }

    public boolean addBlacklistedPrefix(String prefix) {
        return executeUpdate("INSERT IGNORE INTO twitch_blacklist_prefixes (prefix) VALUES (?)", prefix);
    }

    public boolean removeBlacklistedPrefix(String prefix) {
        return executeUpdate("DELETE FROM twitch_blacklist_prefixes WHERE prefix = ?", prefix);
    }

    public boolean addStreamer(String channel, String playerName) {
        return executeUpdate("INSERT IGNORE INTO twitch_streamers (channel, player_name) VALUES (?, ?)", channel, playerName);
    }

    public boolean removeStreamer(String channel) {
        return executeUpdate("DELETE FROM twitch_streamers WHERE channel = ?", channel);
    }

    private boolean executeUpdate(String sql, String... params) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                stmt.setString(i + 1, params[i]);
            }
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public HikariDataSource getDataSource() {
        return dataSource;
    }

    public List<String> fetchColumn(String query) {
        List<String> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                results.add(rs.getString(1));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return results;
    }
    public void initializeSchema() {
        try (Connection conn = dataSource.getConnection()) {
            conn.prepareStatement("""
            CREATE TABLE IF NOT EXISTS twitch_blacklist_users (
                username VARCHAR(255) PRIMARY KEY
            )
        """).executeUpdate();

            conn.prepareStatement("""
            CREATE TABLE IF NOT EXISTS twitch_blacklist_words (
                word VARCHAR(255) PRIMARY KEY
            )
        """).executeUpdate();

            conn.prepareStatement("""
            CREATE TABLE IF NOT EXISTS twitch_blacklist_prefixes (
                prefix VARCHAR(255) PRIMARY KEY
            )
        """).executeUpdate();

            conn.prepareStatement("""
            CREATE TABLE IF NOT EXISTS twitch_streamers (
                channel VARCHAR(255) PRIMARY KEY,
                player_name VARCHAR(255)
            )
        """).executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }


}
