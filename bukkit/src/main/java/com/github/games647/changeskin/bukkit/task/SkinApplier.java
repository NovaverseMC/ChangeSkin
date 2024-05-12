package com.github.games647.changeskin.bukkit.task;

import com.github.games647.changeskin.bukkit.ChangeSkinBukkit;
import com.github.games647.changeskin.core.model.UserPreference;
import com.github.games647.changeskin.core.model.skin.SkinModel;
import com.github.games647.changeskin.core.shared.task.SharedApplier;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.protocol.world.Difficulty;
import com.github.retrooper.packetevents.util.reflection.Reflection;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import com.google.common.hash.Hashing;
import com.nametagedit.plugin.NametagEdit;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.Bukkit;

public class SkinApplier extends SharedApplier {

    private static final boolean NEW_HIDE_METHOD_AVAILABLE;

    private static final Field PLAYER_INTERACT_MANAGER_FIELD;
    private static final Field PREVIOUS_GAMEMODE_FIELD;

    static {
        boolean methodAvailable;
        try {
            Player.class.getDeclaredMethod("hidePlayer", Plugin.class, Player.class);
            methodAvailable = true;
        } catch (NoSuchMethodException noSuchMethodEx) {
            methodAvailable = false;
        }

        NEW_HIDE_METHOD_AVAILABLE = methodAvailable;

        Field playerInteractManagerField = null;
        Field previousGamemodeField = null;

        if (SpigotReflectionUtil.VERSION.isNewerThanOrEquals(ServerVersion.V_1_16)) {

            Class<?> gamemodeClass = SpigotReflectionUtil.getServerClass("world.level.EnumGamemode",
                    "WorldSettings$EnumGamemode");
            Class<?> playerInteractManagerClass = SpigotReflectionUtil.getServerClass(
                    "server.level.PlayerInteractManager", "PlayerInteractManager");
            playerInteractManagerField = Reflection.getField(
                    SpigotReflectionUtil.ENTITY_PLAYER_CLASS, playerInteractManagerClass, 0, true);
            previousGamemodeField = Reflection.getField(playerInteractManagerClass,
                    gamemodeClass, 1, true);
        }

        PLAYER_INTERACT_MANAGER_FIELD = playerInteractManagerField;
        PREVIOUS_GAMEMODE_FIELD = previousGamemodeField;
    }

    protected final ChangeSkinBukkit plugin;
    private final CommandSender invoker;
    private final Player receiver;
    private final SkinModel targetSkin;

    public SkinApplier(ChangeSkinBukkit plugin, CommandSender invoker, Player receiver
            , SkinModel targetSkin, boolean keepSkin) {
        super(plugin.getCore(), targetSkin, keepSkin);

        this.plugin = plugin;
        this.invoker = invoker;
        this.receiver = receiver;
        this.targetSkin = targetSkin;
    }

    @Override
    public void run() {
        if (!isConnected()) {
            return;
        }

        //uuid was successfully resolved, we could now make a cooldown check
        if (invoker instanceof Player && core != null) {
            UUID uniqueId = ((Player) invoker).getUniqueId();
            core.getCooldownService().trackPlayer(uniqueId);
        }

        if (plugin.getStorage() != null) {
            UserPreference preferences = plugin.getStorage().getPreferences(receiver.getUniqueId());
            save(preferences);
        }

        applySkin();
    }

    @Override
    protected boolean isConnected() {
        return receiver != null && receiver.isOnline();
    }

    @Override
    protected void applyInstantUpdate() {
        plugin.getApi().applySkin(receiver, targetSkin);

        UserProfile profile = PacketEvents.getAPI().getPlayerManager().getUser(receiver).getProfile();
        sendUpdateSelf(profile);

        sendUpdateOthers();

        if (receiver.equals(invoker)) {
            plugin.sendMessage(receiver, "skin-changed");
        } else {
            plugin.sendMessage(invoker, "skin-updated");
        }
    }

    @Override
    protected void sendMessage(String key) {
        plugin.sendMessage(invoker, key);
    }

    @Override
    protected void runAsync(Runnable runnable) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable);
    }

    private void sendUpdateOthers() {
        //triggers an update for others player to see the new skin
        Bukkit.getOnlinePlayers().stream()
                .filter(onlinePlayer -> !onlinePlayer.equals(receiver))
                .filter(onlinePlayer -> onlinePlayer.canSee(receiver))
                .forEach(this::hideAndShow);

        //tell NameTagEdit to refresh the scoreboard
        if (Bukkit.getPluginManager().isPluginEnabled("NametagEdit")) {
            NametagEdit.getApi().reloadNametag(receiver);
        }
    }

    private void sendUpdateSelf(UserProfile gameProfile) {
        Optional.ofNullable(receiver.getVehicle()).ifPresent(Entity::eject);

        sendPacketsSelf(gameProfile);

        //trigger update exp
        receiver.setExp(receiver.getExp());

        //triggers updateAbilities
        receiver.setWalkSpeed(receiver.getWalkSpeed());

        //send the current inventory - otherwise player would have an empty inventory
        receiver.updateInventory();

        PlayerInventory inventory = receiver.getInventory();
        inventory.setHeldItemSlot(inventory.getHeldItemSlot());

        //trigger update attributes like health modifier for generic.maxHealth
        try {
            receiver.getClass().getDeclaredMethod("updateScaledHealth").invoke(receiver);
        } catch (ReflectiveOperationException reflectiveEx) {
            plugin.getLog().error("Failed to invoke updateScaledHealth for attributes", reflectiveEx);
        }
    }

    private void sendPacketsSelf(UserProfile gameProfile) {
        PacketWrapper<?> removeInfo;
        PacketWrapper<?> addInfo;
        PacketWrapper<?> respawn;
        PacketWrapper<?> teleport;

        try {
            //remove the old skin - client updates it only on a complete remove and add
            removeInfo = createRemovePacket(gameProfile);

            //add info containing the skin data
            addInfo = createAddPacket(gameProfile);

            // Respawn packet - notify the client that it should update the own skin
            respawn = createRespawnPacket();

            //prevent the moved too quickly message
            teleport = createTeleportPacket();
        } catch (ReflectiveOperationException reflectiveEx) {
            plugin.getLog().error("Error occurred preparing packets. Cancelling self update", reflectiveEx);
            return;
        }

        sendPackets(removeInfo, addInfo, respawn, teleport);
    }

    private PacketWrapper<?> createAddPacket(UserProfile gameProfile) {
        PacketWrapper<?> addInfo;
        if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_19_3)) {
            addInfo = new WrapperPlayServerPlayerInfoUpdate(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                    getModernPlayerInfoData(gameProfile));
        } else {
            addInfo = new WrapperPlayServerPlayerInfo(WrapperPlayServerPlayerInfo.Action.ADD_PLAYER,
                    getLegacyPlayerInfoData(gameProfile));
        }
        return addInfo;
    }

    private PacketWrapper<?> createRemovePacket(UserProfile gameProfile) {
        PacketWrapper<?> removeInfo;
        if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_19_3)) {
            removeInfo = new WrapperPlayServerPlayerInfoRemove(receiver.getUniqueId());
        } else {
            removeInfo = new WrapperPlayServerPlayerInfo(WrapperPlayServerPlayerInfo.Action.REMOVE_PLAYER,
                    getLegacyPlayerInfoData(gameProfile));
        }
        return removeInfo;
    }

    @SuppressWarnings("deprecation")
    private void hideAndShow(Player other) {
        //removes the entity and display the new skin
        if (NEW_HIDE_METHOD_AVAILABLE) {
            other.hidePlayer(plugin, receiver);
            other.showPlayer(plugin, receiver);
        } else {
            other.hidePlayer(receiver);
            other.showPlayer(receiver);
        }
    }

    private void sendPackets(PacketWrapper<?>... packets) {
        ProtocolManager protocolManager = PacketEvents.getAPI().getProtocolManager();
        for (PacketWrapper<?> packet : packets) {
            protocolManager.sendPackets(receiver, packet);
        }
    }

    private PacketWrapper<?> createRespawnPacket() throws ReflectiveOperationException {
        World world = receiver.getWorld();
        return new WrapperPlayServerRespawn(
                SpigotConversionUtil.fromBukkitWorld(world),
                world.getName(),
                Difficulty.valueOf(receiver.getWorld().getDifficulty().name()),
                Hashing.sha256().hashLong(world.getSeed()).asLong(),
                SpigotConversionUtil.fromBukkitGameMode(receiver.getGameMode()),
                getPreviousGamemode(),
                false,
                receiver.getWorld().getWorldType() == WorldType.FLAT,
                true,
                null,
                null,
                null
        );
    }

    private PacketWrapper<?> createTeleportPacket() {
        Location location = receiver.getLocation().clone();
        return new WrapperPlayServerPlayerPositionAndLook(
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch(),
                (byte) 0,
                -1337, //send an invalid teleport id in order to let Bukkit ignore the incoming confirm packet
                true
        );
    }

    private WrapperPlayServerPlayerInfo.PlayerData getLegacyPlayerInfoData(UserProfile gameProfile) {
        GameMode gamemode = SpigotConversionUtil.fromBukkitGameMode(receiver.getGameMode());
        TextComponent displayName = LegacyComponentSerializer.legacySection().deserialize(receiver.getPlayerListName());
        return new WrapperPlayServerPlayerInfo.PlayerData(displayName, gameProfile, gamemode, 0);
    }

    private WrapperPlayServerPlayerInfoUpdate.PlayerInfo getModernPlayerInfoData(UserProfile gameProfile) {
        GameMode gamemode = SpigotConversionUtil.fromBukkitGameMode(receiver.getGameMode());
        TextComponent displayName = LegacyComponentSerializer.legacySection().deserialize(receiver.getPlayerListName());
        return new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(gameProfile, true,
                0, gamemode, displayName, null);
    }

    private GameMode getPreviousGamemode() {
        if (PREVIOUS_GAMEMODE_FIELD == null) {
            return null;
        }

        try {
            Object nmsPlayer = SpigotReflectionUtil.getEntityPlayer(receiver);
            Object interactionManager = PLAYER_INTERACT_MANAGER_FIELD.get(nmsPlayer);
            Enum<?> gamemode = (Enum<?>) PREVIOUS_GAMEMODE_FIELD.get(interactionManager);
            if (gamemode == null) {
                return null;
            }

            return GameMode.valueOf(gamemode.name());
        } catch (IllegalAccessException e) {
            plugin.getLog().error("Failed to fetch previous gamemode of player {}", receiver, e);
        }

        return SpigotConversionUtil.fromBukkitGameMode(receiver.getGameMode());
    }
}
