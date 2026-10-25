package com.example.aiplayers.ai;

import com.example.aiplayers.BotPlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

/** Базовые действия бота: ходьба, следование, ожидание, добыча блоков. */
public final class BotActions {

    private BotActions() {}

    public interface Action {
        /** @return true, когда действие закончено. */
        boolean tick();

        String result();

        /** Вызывается, если действие прервали. */
        default void stop() {}
    }

    /**
     * Что боту разрешено ломать: только природные ресурсы.
     * Постройки игроков (доски, стекло, сундуки...) не трогаем - защита от гриферства.
     */
    public static boolean isAllowedBlock(BlockState s) {
        return s.is(BlockTags.LOGS)
                || s.is(Tags.Blocks.ORES)
                || s.is(BlockTags.BASE_STONE_OVERWORLD)
                || s.is(BlockTags.DIRT)
                || s.is(BlockTags.SAND)
                || s.is(Blocks.GRAVEL);
    }

    // ------------------------------------------------------------------ goto

    public static class Goto implements Action {
        private final BotPlayerEntity bot;
        private final BlockPos target;
        private int ticks;
        private int fails;
        private String result = "не дошёл";

        public Goto(BotPlayerEntity bot, BlockPos target) {
            this.bot = bot;
            this.target = target;
        }

        @Override
        public boolean tick() {
            ticks++;
            double d = bot.distanceToSqr(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
            bot.setSprinting(d > 100.0 && bot.food() > 6.0F); // далеко - бежит, как игрок
            if (d < 6.25) {
                result = "пришёл";
                return true;
            }
            if (ticks % 20 == 1) {
                boolean ok = bot.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
                if (!ok && ++fails >= 3) {
                    result = "путь не найден";
                    return true;
                }
            }
            return ticks > 800;
        }

        @Override
        public String result() {
            return result;
        }
    }

    // ---------------------------------------------------------------- follow

    public static class Follow implements Action {
        private final BotPlayerEntity bot;
        private final String name;
        private int ticks;
        private String result = "шёл за игроком";

        public Follow(BotPlayerEntity bot, String name) {
            this.bot = bot;
            this.name = name;
        }

        @Override
        public boolean tick() {
            ticks++;
            if (!(bot.level() instanceof ServerLevel level)) return true;
            ServerPlayer target = null;
            for (ServerPlayer p : level.players()) {
                if (p.getGameProfile().getName().equalsIgnoreCase(name)) {
                    target = p;
                    break;
                }
            }
            if (target == null) {
                result = "игрок не найден";
                return true;
            }
            bot.getLookControl().setLookAt(target, 30.0F, 30.0F);
            bot.setSprinting(bot.distanceToSqr(target) > 100.0 && bot.food() > 6.0F);
            if (bot.distanceToSqr(target) > 9.0 && ticks % 10 == 0) bot.getNavigation().moveTo(target, 1.1);
            return ticks > 600;
        }

        @Override
        public String result() {
            return result;
        }
    }

    // ------------------------------------------------------------------ idle

    public static class Idle implements Action {
        private final BotPlayerEntity bot;
        private final int limit;
        private int ticks;

        public Idle(BotPlayerEntity bot, int limit) {
            this.bot = bot;
            this.limit = limit;
            bot.getNavigation().stop();
        }

        @Override
        public boolean tick() {
            return ++ticks >= limit;
        }

        @Override
        public String result() {
            return "постоял";
        }
    }

    // ------------------------------------------------------------------ mine

    /**
     * Добыча как у игрока: бот берёт в руку подходящий инструмент, ломает блок столько,
     * сколько нужно с этим инструментом, и предмет выпадает только при правильном инструменте
     * (камень без кирки не даёт булыжник). Инструмент изнашивается.
     */
    public static class Mine implements Action {
        private final BotPlayerEntity bot;
        private final Block block;
        private final int count;
        private final Set<BlockPos> skip = new HashSet<>();
        private BlockPos cur;
        private int mined, noDrop, ticks, mineTicks, approach, collect;
        private String result = "";

        public Mine(BotPlayerEntity bot, String name, int count) {
            this.bot = bot;
            this.count = count;
            this.block = BotUtil.block(name);
        }

        private String summary(String prefix) {
            return prefix + (noDrop > 0 ? " (" + noDrop + " без дропа: нужен подходящий инструмент)" : "");
        }

        @Override
        public boolean tick() {
            if (block == null) {
                result = "неизвестный блок";
                return true;
            }
            if (!(bot.level() instanceof ServerLevel level)) return true;
            if (!level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
                result = "ломать блоки нельзя (mobGriefing выключен)";
                return true;
            }
            if (++ticks > 1500) {
                result = summary("добыто " + mined);
                return true;
            }

            // Подбираем выпавшие предметы
            if (collect > 0) {
                collect--;
                var nearest = level.getEntitiesOfClass(ItemEntity.class, bot.getBoundingBox().inflate(6.0))
                        .stream().min(Comparator.comparingDouble(bot::distanceToSqr));
                if (nearest.isPresent()) {
                    bot.getNavigation().moveTo(nearest.get(), 1.2);
                    return false;
                }
                collect = 0;
            }

            if (mined >= count) {
                result = summary("добыто " + mined);
                return true;
            }

            if (cur == null || !level.getBlockState(cur).is(block)) {
                cur = find(level);
                mineTicks = 0;
                approach = 0;
                if (cur == null) {
                    result = summary(mined > 0 ? "добыто " + mined + ", больше рядом нет" : "рядом нет такого блока");
                    return true;
                }
            }

            Vec3 c = Vec3.atCenterOf(cur);
            if (bot.getEyePosition().distanceToSqr(c) > 20.25) { // дальше 4.5 блоков - подходим
                if (ticks % 10 == 0) bot.getNavigation().moveTo(c.x, c.y, c.z, 1.0);
                if (++approach > 200) { // застряли - берём другой блок
                    skip.add(cur);
                    cur = null;
                }
                return false;
            }

            bot.getNavigation().stop();
            bot.getLookControl().setLookAt(c.x, c.y, c.z);
            BlockState st = level.getBlockState(cur);
            bot.equipToolFor(st);
            ItemStack tool = bot.getMainHandItem();
            float speed = Math.max(1.0F, tool.getDestroySpeed(st));
            boolean correct = !st.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(st);
            float hardness = st.getDestroySpeed(level, cur);
            int need = Mth.clamp((int) Math.ceil(hardness * (correct ? 30.0F : 100.0F) / speed), 5, 600);

            mineTicks++;
            if (mineTicks % 6 == 0) bot.swing(InteractionHand.MAIN_HAND);
            level.destroyBlockProgress(bot.getId(), cur, Math.min(9, mineTicks * 10 / need));
            if (mineTicks >= need) {
                level.destroyBlockProgress(bot.getId(), cur, -1);
                BlockEntity be = level.getBlockEntity(cur);
                level.destroyBlock(cur, false, bot);
                if (correct) Block.dropResources(st, level, cur, be, bot, tool);
                else noDrop++;
                if (!tool.isEmpty() && tool.isDamageableItem()) tool.hurtAndBreak(1, bot, EquipmentSlot.MAINHAND);
                mined++;
                cur = null;
                collect = 60;
            }
            return false;
        }

        private BlockPos find(ServerLevel level) {
            BlockPos o = bot.blockPosition();
            BlockPos best = null;
            double bestD = Double.MAX_VALUE;
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            for (int dx = -12; dx <= 12; dx++) {
                for (int dz = -12; dz <= 12; dz++) {
                    for (int dy = -6; dy <= 6; dy++) {
                        if (dx == 0 && dz == 0 && dy < 0) continue; // не копаем под собой
                        m.set(o.getX() + dx, o.getY() + dy, o.getZ() + dz);
                        if (!level.hasChunkAt(m)) continue;
                        BlockState s = level.getBlockState(m);
                        if (!s.is(block) || !isAllowedBlock(s) || s.getDestroySpeed(level, m) < 0) continue;
                        if (skip.contains(m)) continue;
                        double d = o.distSqr(m);
                        if (d < bestD) {
                            bestD = d;
                            best = m.immutable();
                        }
                    }
                }
            }
            return best;
        }

        @Override
        public String result() {
            return result;
        }
    }
}
