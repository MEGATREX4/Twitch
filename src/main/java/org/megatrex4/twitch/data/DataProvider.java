package org.megatrex4.twitch.data;

public interface DataProvider {
    boolean isUserBlacklisted(String username);
    boolean isMessageBlacklisted(String message);
}
