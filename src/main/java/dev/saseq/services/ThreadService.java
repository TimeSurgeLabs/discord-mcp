package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.attribute.IThreadContainer;
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.requests.restaction.ThreadChannelAction;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ThreadService {

    private final JDA jda;

    @Value("${DISCORD_GUILD_ID:}")
    private String defaultGuildId;

    public ThreadService(JDA jda) {
        this.jda = jda;
    }

    private String resolveGuildId(String guildId) {
        if ((guildId == null || guildId.isEmpty()) && defaultGuildId != null && !defaultGuildId.isEmpty()) {
            return defaultGuildId;
        }
        return guildId;
    }

    private Guild getGuild(String guildId) {
        guildId = resolveGuildId(guildId);
        if (guildId == null || guildId.isEmpty()) {
            throw new IllegalArgumentException("guildId cannot be null");
        }

        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalArgumentException("Discord server not found by guildId");
        }
        return guild;
    }

    @Tool(name = "create_thread", description = "Create a thread in a text or announcement channel, optionally from an existing message")
    public String createThread(@ToolParam(description = "Discord server ID", required = false) String guildId,
                               @ToolParam(description = "Parent text or announcement channel ID") String channelId,
                               @ToolParam(description = "Thread name") String name,
                               @ToolParam(description = "Message ID to create the thread from. Omit to create a standalone thread", required = false) String messageId,
                               @ToolParam(description = "Whether to create a private thread. Only valid when messageId is omitted", required = false) String isPrivate,
                               @ToolParam(description = "Auto-archive duration in minutes: 60, 1440, 4320, or 10080", required = false) String autoArchiveMinutes,
                               @ToolParam(description = "Thread slowmode in seconds (0-21600)", required = false) String slowmode,
                               @ToolParam(description = "Whether members can add other members. Only valid for private threads", required = false) String invitable,
                               @ToolParam(description = "Reason for audit log", required = false) String reason) {
        Guild guild = getGuild(guildId);
        if (channelId == null || channelId.isEmpty()) throw new IllegalArgumentException("channelId cannot be null");
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("name cannot be null");

        GuildChannel guildChannel = guild.getGuildChannelById(channelId);
        if (guildChannel == null) throw new IllegalArgumentException("Channel not found by channelId");
        if (guildChannel instanceof ForumChannel) {
            throw new IllegalArgumentException("Use create_forum_post to create a thread in a forum channel");
        }
        if (!(guildChannel instanceof IThreadContainer threadContainer)) {
            throw new IllegalArgumentException("Channel does not support threads");
        }

        boolean privateThread = isPrivate != null && !isPrivate.isEmpty() && Boolean.parseBoolean(isPrivate);
        boolean hasMessageId = messageId != null && !messageId.isEmpty();
        if (hasMessageId && privateThread) {
            throw new IllegalArgumentException("Private threads cannot be created from an existing message");
        }

        ThreadChannelAction action = hasMessageId
                ? threadContainer.createThreadChannel(name, messageId)
                : threadContainer.createThreadChannel(name, privateThread);

        if (autoArchiveMinutes != null && !autoArchiveMinutes.isEmpty()) {
            action.setAutoArchiveDuration(ThreadChannel.AutoArchiveDuration.fromKey(Integer.parseInt(autoArchiveMinutes)));
        }
        if (slowmode != null && !slowmode.isEmpty()) {
            action.setSlowmode(Integer.parseInt(slowmode));
        }
        if (invitable != null && !invitable.isEmpty()) {
            if (!privateThread) {
                throw new IllegalArgumentException("invitable can only be set for private threads");
            }
            action.setInvitable(Boolean.parseBoolean(invitable));
        }
        if (reason != null && !reason.isEmpty()) {
            action.reason(reason);
        }

        ThreadChannel thread = action.complete();
        String parentName = thread.getParentChannel() != null ? thread.getParentChannel().getName() : guildChannel.getName();
        return "Created thread: " + thread.getName() + " (ID: " + thread.getId() + ") in #" + parentName;
    }

    /**
     * Lists all active threads in a specified Discord server.
     *
     * @param guildId Optional ID of the Discord server (guild). If not provided, the default server will be used.
     * @return A formatted string listing all active threads in the server, including their name, ID, and parent channel.
     */
    @Tool(name = "list_active_threads", description = "List all active threads in the server")
    public String listActiveThreads(@ToolParam(description = "Discord server ID", required = false) String guildId) {
        Guild guild = getGuild(guildId);

        // Retrieve active threads from Discord API
        List<ThreadChannel> threads = guild.retrieveActiveThreads().complete();

        if (threads.isEmpty()) {
            return "No active threads found in the server.";
        }

        return "Retrieved " + threads.size() + " active threads:\n" +
                threads.stream()
                        .map(t -> {
                            String parentName = t.getParentChannel() != null ? t.getParentChannel().getName() : "unknown";
                            String archived = t.isArchived() ? " (archived)" : "";
                            return "- " + t.getName() + " (ID: " + t.getId() + ") in #" + parentName + archived;
                        })
                        .collect(Collectors.joining("\n"));
    }
}
