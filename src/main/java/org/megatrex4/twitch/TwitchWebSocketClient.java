package org.megatrex4.twitch;

import org.java_websocket.WebSocket;
import org.java_websocket.WebSocketAdapter;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.nio.ByteBuffer;

public class TwitchWebSocketClient extends WebSocketClient {

    public TwitchWebSocketClient(URI serverUri) {
        super(serverUri);
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        System.out.println("WebSocket connected!");
        // Example of subscribing to a Twitch topic
        String message = "{\"type\":\"LISTEN\",\"data\":{\"topics\":[\"channel-points-channel-v1.<your_channel_id>\"],\"auth_token\":\"<your_auth_token>\"}}";
        this.send(message);
    }

    @Override
    public void onMessage(String message) {
        System.out.println("Received: " + message);
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        System.out.println("WebSocket closed: " + reason);
    }

    @Override
    public void onError(Exception ex) {
        System.out.println("WebSocket error: " + ex.getMessage());
    }

    @Override
    public void onMessage(ByteBuffer bytes) {
        System.out.println("Received binary message.");
    }
}
