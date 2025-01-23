# Twitch Chat Plugin

A Minecraft plugin that bridges the gap between your server and Twitch chat, allowing players to interact with Twitch channels directly from the game.

## How It Works
The plugin uses Twitch's IRC WebSocket protocol to connect to Twitch chat. Once configured, it listens to messages from specific Twitch channels and broadcasts them to your Minecraft server. Players can also manage linked Twitch channels in real-time through in-game commands.

## Key Features
- **Twitch Integration**  
  Connect to Twitch chat channels and receive messages directly in-game.  
- **Channel Management**  
  Add, remove, or manage Twitch channels dynamically using simple in-game commands.  
- **Customizable Chat Format**  
  Easily configure how Twitch messages appear in-game (e.g., color codes, prefixes).  
- **Ping/Pong Protocol Support**  
  Maintains an active connection to Twitch servers with automatic WebSocket ping/pong handling.  
- **Permission-Based Commands**  
  Ensure only authorized players can add/remove channels or reload the plugin.  
- **Dynamic Configuration**  
  Modify the configuration file or reload settings without restarting your server.

## Commands
- **`/twitchlink add <channel>`**  
  Links a new Twitch channel to the server.  
  *(Requires permission: `twitch.add`)*  
- **`/twitchlink remove <channel>`**  
  Unlinks a Twitch channel from the server.  
  *(Requires permission: `twitch.remove`)*  
- **`/twitchlink reload`**  
  Reloads the plugin’s configuration.  
  *(Requires permission: `twitch.reload`)*  

## Permissions
- **`twitch.add`** — Allows adding new Twitch channels.  
- **`twitch.remove`** — Allows removing linked Twitch channels.  
- **`twitch.reload`** — Allows reloading the configuration.  

## Configuration
Edit the `config.yml` file to:
- Add an OAuth token for Twitch authentication.  
- Define a list of default channels to connect to.  
- Customize the message format (e.g., `[TWITCH] %nickname%: %message%`).  

### Example `config.yml`
```yaml
twitch:
  token: your_twitch_oauth_token
  channels:
    - examplechannel1
    - examplechannel2
  message_format: '&7[TWITCH] &9%nickname%&7: %message%'
```


## Requirements
- **Minecraft Server Version:** 1.21 or higher  
- **Twitch OAuth Token**.  

## Installation
1. Download the plugin `.jar` file from the [Releases](#) page.  
2. Place the `.jar` file in your server’s `plugins` folder.  
3. Start the server to generate the default configuration file.  
4. Open `config.yml` and add your Twitch OAuth token.  
   - You can generate an OAuth token.
5. Add the Twitch channels you want to connect to under the `channels` section in `config.yml`.  
6. Reload or restart the server, and the plugin will connect to the specified Twitch channels!  
