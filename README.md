# **Twitch Chat Broadcast for Minecraft**  
Bring your Twitch audience closer to your Minecraft server with this **lightweight and efficient plugin**!  

This plugin enables Twitch chat messages to be broadcast directly into your Minecraft server's **global chat**, visible to all players, ensuring no one misses a moment of your stream interaction. Additionally, an optional feature lets you forward Twitch chat to a Discord channel using a webhook for enhanced community connectivity.  

---

## **Features**  
✅ **Twitch Chat in Minecraft**: Broadcast Twitch chat messages to your Minecraft server’s **global chat**, making them visible to all online players.  
✅ **Lightweight and Optimized**: Designed for high performance with minimal resource usage.  
✅ **Customizable Configurations**:  
- Add your Twitch OAuth token and channels for easy setup.  
- Configure custom message formats for Twitch messages in Minecraft chat.  
✅ **Minecraft Heads API Support**: Fetch user avatars from the [Minecraft Heads API](https://mc-heads.net) to display Twitch usernames with unique Minecraft-themed visuals.  
✅ **Custom Avatars**: Prefer a unique bot look? Use your own avatar URL instead.  
✅ **Optional Discord Integration**:  
- Forward Twitch chat messages to a Discord channel using a webhook.  
- Perfect for servers using plugins like **DiscordSRV**, which relay game chat to Discord and back.  
- Ensures **streamer safety** by **not sending Discord or game chat to Twitch**, avoiding potential bans for sharing unmoderated content.  
✅ **Blacklist Support**: Block unwanted users, prefixes, or words to keep the chat clean.  
✅ **Multi-Channel Support**: Connect multiple Twitch channels simultaneously.  
✅ **Localization Ready**: Customize all plugin messages to your native language via a simple `messages.yml` file.  

---

## **How It Works**  
1. Configure your Twitch channel(s), OAuth token, message formats, and other settings in the provided `config.yml` file.  
2. The plugin listens for Twitch chat messages and broadcasts them to **everyone on the server via the global chat**.  
3. Optionally, configure the Discord webhook to send Twitch chat to a Discord channel, ideal for servers that already use **DiscordSRV**.  
4. Blacklist unwanted users, prefixes (e.g., bot commands), or inappropriate words to keep the chat safe and clean.  

---

## **OAuth Token Requirement**  
To use this plugin, you will need a **Twitch OAuth token**. The token should look like this:  
`oauth:123456789123456789123456789123456789`


You can generate your token using services like Twitch Token Generator or any other trusted platform that provides Twitch OAuth tokens.  

---

## **Why Choose This Plugin?**  
This plugin focuses on bringing **Twitch chat into your Minecraft server’s global chat**, fostering interaction between streamers and players. It’s lightweight, feature-rich, and easy to configure, making it perfect for both small and large server communities.  

---

## **Use Cases**  
- **Streamer Integration**: Let your Minecraft players interact with Twitch chat in real time.  
- **Community Engagement**: Build a more immersive experience for your server by connecting Twitch and Minecraft chat.  
- **Streamer Safety**: With Discord integration, keep unmoderated Discord or game chat **out of Twitch** to avoid potential bans.  
