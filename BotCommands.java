package com.example.aiplayers;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.List;

/**
 * /aiplayers spawn <count> [radius]  - раскидать ботов вокруг тебя
 * /aiplayers clear                   - убрать всех ботов в текущем мире
 */
public class BotCommands {

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("aiplayers")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("spawn")
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 50))
                                        .executes(ctx -> spawn(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "count"), 80))
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(10, 150))
                                                .executes(ctx -> spawn(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                        IntegerArgumentType.getInteger(ctx, "radius"))))))
                        .then(Commands.literal("clear")
                                .executes(ctx -> clear(ctx.getSource())))
        );
    }

    private static int spawn(CommandSourceStack src, int count, int radius) {
        ServerLevel level = src.getLevel();
        BlockPos center = BlockPos.containing(src.getPosition());
        var rnd = level.getRandom();
        int spawned = 0;

        for (int i = 0; i < count; i++) {
            for (int attempt = 0; attempt < 15; attempt++) {
                int x = center.getX() + rnd.nextInt(radius * 2 + 1) - radius;
                int z = center.getZ() + rnd.nextInt(radius * 2 + 1) - radius;

                level.getChunk(x >> 4, z >> 4); // гарантируем, что чанк загружен
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos pos = new BlockPos(x, y, z);

                // Не спавним в воде/лаве и в пустоте
                if (y <= level.getMinBuildHeight()) continue;
                if (!level.getFluidState(pos).isEmpty() || !level.getFluidState(pos.below()).isEmpty()) continue;

                BotPlayerEntity bot = AiPlayersMod.BOT.get().create(level);
                if (bot == null) break;
                bot.moveTo(x + 0.5, y, z + 0.5, rnd.nextFloat() * 360F, 0F);
                bot.randomize(rnd);
                level.addFreshEntity(bot);

                // Сообщение "зашёл в игру" - для атмосферы
                level.getServer().getPlayerList().broadcastSystemMessage(
                        Component.translatable("multiplayer.player.joined", bot.getDisplayName())
                                .withStyle(ChatFormatting.YELLOW), false);
                spawned++;
                break;
            }
        }

        int total = spawned;
        src.sendSuccess(() -> Component.literal("Создано ИИ-игроков: " + total), true);
        return spawned;
    }

    private static int clear(CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        List<? extends BotPlayerEntity> bots = level.getEntities(AiPlayersMod.BOT.get(), b -> true);
        bots.forEach(b -> b.discard());
        int n = bots.size();
        src.sendSuccess(() -> Component.literal("Удалено ИИ-игроков: " + n), true);
        return n;
    }
}
