package org.megatrex4.twitch.data;

import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class SqlDataProvider implements DataProvider {

    private final HikariDataSource dataSource;

    public SqlDataProvider(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public boolean isUserBlacklisted(String username) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM twitch_blacklist_users WHERE username = ?")) {
            stmt.setString(1, username);
            return stmt.executeQuery().next();
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public boolean isMessageBlacklisted(String message) {
        if (message.isEmpty()) return false;

        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement("SELECT prefix FROM twitch_blacklist_prefixes");
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    if (message.charAt(0) == rs.getString("prefix").charAt(0)) return true;
                }
            }

            try (PreparedStatement stmt = conn.prepareStatement("SELECT word FROM twitch_blacklist_words");
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    if (message.contains(rs.getString("word"))) return true;
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return false;
    }
}
