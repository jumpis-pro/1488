package com.example.aiplayers;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Показывает ботов в списке игроков (Tab) как настоящих.
 * Для каждого бота создаётся "призрачная" запись игрока (FakePlayer только как источник данных профиля,
 * в мир она не добавляется), которую рассылаем всем клиентам. Сам бот в мире остаётся обычной сущностью,
 * а скин в Tab и в мире совпадает (оба берутся по UUID).
 */
public final class BotTabList {

    private static final EnumSet<Action> ADD = EnumSet.of(
            Action.ADD_PLAYER, Action.UPDATE_LISTED, Action.UPDATE_LATENCY, Action.UPDATE_GAME_MODE);

    /** UUID бота -> запись в Tab. Только главный поток сервера. */
    private static final Map<UUID, FakePlayer> GHOSTS = new LinkedHashMap<>();
    /** Боты, для которых запись ещё надо создать (создаём не в момент загрузки чанка, а в тике сервера). */
    private static final Map<UUID, BotPlayerEntity> PENDING = new HashMap<>();

    private BotTabList() {}

    /** Ник в Tab: латиница/цифры/_, максимум 16 символов (иначе пакет не запишется). */
    private static String safeName(String raw) {
        String n = raw.replaceAll("[^A-Za-z0-9_]", "_");
        if (n.isEmpty()) n = "Bot";
        return n.length() > 16 ? n.substring(0, 16) : n;
    }

    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (event.getEntity() instanceof BotPlayerEntity bot && !GHOSTS.containsKey(bot.getUUID())) {
            PENDING.put(bot.getUUID(), bot);
        }
    }

    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof BotPlayerEntity bot)) return;
        PENDING.remove(bot.getUUID());
        // Из Tab убираем только когда бот действительно погиб или удалён, а не при выгрузке чанка
        var reason = bot.getRemovalReason();
        if (reason != null && reason.shouldDestroy() && GHOSTS.remove(bot.getUUID()) != null) {
            MinecraftServer server = event.getLevel().getServer();
            if (server != null) {
                server.getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(bot.getUUID())));
            }
        }
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty() || event.getServer().getTickCount() % 10 != 0) return;
        List<ServerPlayer> fresh = new ArrayList<>();
        for (BotPlayerEntity bot : new ArrayList<>(PENDING.values())) {
            PENDING.remove(bot.getUUID());
            if (bot.isRemoved() || !(bot.level() instanceof ServerLevel level)) continue;
            if (GHOSTS.containsKey(bot.getUUID())) continue;
            GameProfile profile = new GameProfile(bot.getUUID(), safeName(bot.getName().getString()));
            FakePlayer ghost = new FakePlayer(level, profile);
            GHOSTS.put(bot.getUUID(), ghost);
            fresh.add(ghost);
        }
        if (!fresh.isEmpty()) {
            event.getServer().getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(ADD, fresh));
        }
    }

    /** Новому игроку отправляем всех уже существующих ботов. */
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (GHOSTS.isEmpty() || !(event.getEntity() instanceof ServerPlayer player)) return;
        player.connection.send(new ClientboundPlayerInfoUpdatePacket(ADD, new ArrayList<ServerPlayer>(GHOSTS.values())));
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        GHOSTS.clear();
        PENDING.clear();
    }
}
