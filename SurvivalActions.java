package com.example.aiplayers.ai;

import com.example.aiplayers.BotPlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;

/** Еда, сон и охота - то, без чего живой игрок не обходится. */
public final class SurvivalActions {

    private SurvivalActions() {}

    /** Съедобно и без вредных эффектов (гнилую плоть, ядовитую картошку и т.п. бот не ест). */
    public static boolean isSafeFood(ItemStack s, LivingEntity e) {
        FoodProperties fp = s.getFoodProperties(e);
        return fp != null && fp.effects().isEmpty();
    }

    public static boolean hasFood(BotPlayerEntity bot) {
        for (int i = 0; i < bot.inventory.getContainerSize(); i++) {
            ItemStack s = bot.inventory.getItem(i);
            if (!s.isEmpty() && isSafeFood(s, bot)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------- eat

    public static class Eat implements BotActions.Action {
        private final BotPlayerEntity bot;
        private int slot = -1;
        private int ticks;
        private boolean swapped;
        private String result = "нечего есть";

        public Eat(BotPlayerEntity bot) {
            this.bot = bot;
            int bestN = -1;
            for (int i = 0; i < bot.inventory.getContainerSize(); i++) {
                ItemStack s = bot.inventory.getItem(i);
                if (s.isEmpty() || !isSafeFood(s, bot)) continue;
                int n = s.getFoodProperties(bot).nutrition();
                if (n > bestN) {
                    bestN = n;
                    slot = i;
                }
            }
            if (slot >= 0) {
                bot.swapMainHand(slot); // еда оказывается в руке - видно, что бот ест
                swapped = true;
            }
        }

        @Override
        public boolean tick() {
            if (slot < 0) return true;
            ticks++;
            bot.getNavigation().stop();
            if (ticks % 5 == 0) {
                bot.playSound(SoundEvents.GENERIC_EAT, 0.6F, 0.9F + bot.getRandom().nextFloat() * 0.2F);
            }
            if (ticks < 32) return false;

            ItemStack hand = bot.getMainHandItem();
            FoodProperties fp = hand.getFoodProperties(bot);
            if (fp != null) {
                bot.addFood(fp.nutrition());
                result = "поел: " + BuiltInRegistries.ITEM.getKey(hand.getItem()).getPath();
                hand.shrink(1);
                bot.playSound(SoundEvents.PLAYER_BURP, 0.5F, 1.0F);
            }
            restore();
            return true;
        }

        private void restore() {
            if (swapped) {
                bot.swapMainHand(slot);
                swapped = false;
            }
        }

        @Override
        public void stop() {
            restore();
        }

        @Override
        public String result() {
            return result;
        }
    }

    // ----------------------------------------------------------------- sleep

    /** Идёт к ближайшей кровати и спит до утра. Удар или рассвет будит. */
    public static class Sleep implements BotActions.Action {
        private final BotPlayerEntity bot;
        private BlockPos bed;
        private int ticks;
        private boolean inBed;
        private String result = "не получилось лечь";

        public Sleep(BotPlayerEntity bot) {
            this.bot = bot;
        }

        @Override
        public boolean tick() {
            if (!(bot.level() instanceof ServerLevel level)) return true;
            ticks++;

            if (inBed) {
                if (!bot.isSleeping()) {
                    result = "проснулся";
                    return true;
                }
                if (level.isDay()) {
                    bot.stopSleeping();
                    result = "выспался, наступило утро";
                    return true;
                }
                return false;
            }

            if (ticks > 900) {
                result = "не дошёл до кровати";
                return true;
            }
            if (level.isDay()) {
                result = "сейчас день, спать рано";
                return true;
            }
            if (bed == null) {
                bed = BotUtil.findNearest(level, bot.blockPosition(), 24, 6, s -> s.is(BlockTags.BEDS));
                if (bed == null) {
                    result = "кровати рядом нет (нужно скрафтить и поставить bed)";
                    return true;
                }
            }

            BlockState st = level.getBlockState(bed);
            if (!(st.getBlock() instanceof BedBlock)) { // кровать пропала
                bed = null;
                return false;
            }
            BlockPos head = st.getValue(BedBlock.PART) == BedPart.FOOT
                    ? bed.relative(st.getValue(BedBlock.FACING)) : bed;
            if (level.getBlockState(head).getValue(BedBlock.OCCUPIED)) {
                result = "кровать занята";
                return true;
            }

            Vec3 c = Vec3.atCenterOf(head);
            if (bot.distanceToSqr(c) > 9.0) {
                if (ticks % 20 == 1) bot.getNavigation().moveTo(c.x, c.y, c.z, 1.0);
                return false;
            }
            bot.getNavigation().stop();
            bot.setPos(head.getX() + 0.5, head.getY() + 0.6875, head.getZ() + 0.5);
            bot.startSleeping(head);
            inBed = true;
            return false;
        }

        @Override
        public void stop() {
            if (bot.isSleeping()) bot.stopSleeping();
        }

        @Override
        public String result() {
            return result;
        }
    }

    // ------------------------------------------------------------------ hunt

    /** Охота на корову, свинью, овцу, курицу или кролика; затем сбор добычи. */
    public static class Hunt implements BotActions.Action {
        private final BotPlayerEntity bot;
        private LivingEntity target;
        private int ticks, collect;
        private boolean killed;
        private String result = "охота не удалась";

        public Hunt(BotPlayerEntity bot) {
            this.bot = bot;
            if (bot.level() instanceof ServerLevel level) {
                target = level.getEntitiesOfClass(LivingEntity.class, bot.getBoundingBox().inflate(24.0),
                                e -> e instanceof Cow || e instanceof Pig || e instanceof Sheep
                                        || e instanceof Chicken || e instanceof Rabbit)
                        .stream().min(Comparator.comparingDouble(bot::distanceToSqr)).orElse(null);
            }
        }

        @Override
        public boolean tick() {
            if (target == null) {
                result = "животных рядом нет";
                return true;
            }
            if (++ticks > 900) return true;

            if (!killed) {
                if (target.isAlive()) {
                    if (bot.getTarget() != target) bot.setTarget(target);
                    return false;
                }
                killed = true;
                collect = 100;
                bot.setTarget(null);
            }

            if (collect-- > 0 && bot.level() instanceof ServerLevel level) {
                var drop = level.getEntitiesOfClass(ItemEntity.class, target.getBoundingBox().inflate(8.0))
                        .stream().min(Comparator.comparingDouble(bot::distanceToSqr));
                if (drop.isPresent()) {
                    bot.getNavigation().moveTo(drop.get(), 1.2);
                    return false;
                }
            }
            result = "поохотился, добыча собрана";
            return true;
        }

        @Override
        public void stop() {
            if (bot.getTarget() == target) bot.setTarget(null);
        }

        @Override
        public String result() {
            return result;
        }
    }
}
