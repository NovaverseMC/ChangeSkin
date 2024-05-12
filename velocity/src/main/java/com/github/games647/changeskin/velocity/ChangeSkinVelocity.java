package com.github.games647.changeskin.velocity;

import com.github.games647.changeskin.core.ChangeSkinCore;
import com.github.games647.changeskin.core.PlatformPlugin;
import com.github.games647.changeskin.core.SkinStorage;
import com.github.games647.changeskin.core.message.*;
import com.github.games647.changeskin.core.model.UserPreference;
import com.google.common.collect.MapMaker;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelRegistrar;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;

@Plugin(id = PomData.ARTIFACT_ID, name = PomData.NAME, version = PomData.VERSION,
        url = PomData.URL, description = PomData.DESCRIPTION)
public class ChangeSkinVelocity implements PlatformPlugin<CommandSource> {

    private final ProxyServer server;
    private final Logger logger;
    private final Path path;

    private final ConcurrentMap<Player, UserPreference> loginSessions = new MapMaker().weakKeys().makeMap();
    private final VelocitySkinAPI api = new VelocitySkinAPI(this);

    private ChangeSkinCore core;

    @Inject
    public ChangeSkinVelocity(ProxyServer server, Logger logger, @DataDirectory Path path) {
        this.server = server;
        this.logger = logger;
        this.path = path;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        core = new ChangeSkinCore(this);
        try {
            core.load(true);
        } catch (Exception ioExc) {
            logger.error("Error initializing plugin. Disabling...", ioExc);
            return;
        }

        server.getEventManager().register(this, );

        ChannelRegistrar channelRegistry = server.getChannelRegistrar();
        channelRegistry.register(MinecraftChannelIdentifier.create(getName(), ForwardMessage.FORWARD_COMMAND_CHANNEL));
        channelRegistry.register(MinecraftChannelIdentifier.create(getName(), CheckPermMessage.CHECK_PERM_CHANNEL));
        channelRegistry.register(MinecraftChannelIdentifier.create(getName(), PermResultMessage.PERMISSION_RESULT_CHANNEL));
        channelRegistry.register(MinecraftChannelIdentifier.create(getName(), SkinUpdateMessage.UPDATE_SKIN_CHANNEL));
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (core != null) {
            core.close();
        }
    }

    @Override
    public String getName() {
        return PomData.NAME;
    }

    public VelocitySkinAPI getApi() {
        return api;
    }

    @Override
    public Logger getLog() {
        return logger;
    }

    @Override
    public Path getPluginFolder() {
        return path;
    }

    @Override
    public void sendMessage(CommandSource receiver, String key) {
        String message = core.getMessage(key);
        if (message != null && receiver != null) {
            receiver.sendMessage(LegacyComponentSerializer.legacySection().deserialize(message));
        }
    }

    public void sendPluginMessage(ServerConnection server, ChannelMessage message) {
        if (server != null) {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            message.writeTo(out);

            MinecraftChannelIdentifier channel = MinecraftChannelIdentifier.create(getName(), message.getChannelName());
            server.sendPluginMessage(channel, out.toByteArray());
        }
    }

    public UserPreference getLoginSession(Player id) {
        return loginSessions.get(id);
    }

    public void startSession(Player id, UserPreference preferences) {
        loginSessions.put(id, preferences);
    }

    public UserPreference endSession(Player id) {
        return loginSessions.remove(id);
    }

    public SkinStorage getStorage() {
        return core.getStorage();
    }

    public ChangeSkinCore getCore() {
        return core;
    }

    @Override
    public boolean hasSkinPermission(CommandSource invoker, UUID uuid, boolean sendMessage) {
        if (invoker.hasPermission(getName().toLowerCase() + ".skin.whitelist." + uuid)) {
            return true;
        } else if (invoker.hasPermission(getName().toLowerCase() + ".skin.whitelist.*")) {
            if (invoker.hasPermission('-' + getName().toLowerCase() + ".skin.whitelist." + uuid)) {
                //blacklisted explicit
                if (sendMessage) {
                    sendMessage(invoker, "no-permission");
                }

                return false;
            }

            return true;
        }

        //disallow - not whitelisted or blacklisted
        if (sendMessage) {
            sendMessage(invoker, "no-permission");
        }

        return false;
    }
}
