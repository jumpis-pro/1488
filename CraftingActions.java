package com.example.aiplayers.ai;

import com.example.aiplayers.BotPlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.TreeSet;

/**
 * Крафт и плавка по НАСТОЯЩИМ рецептам игры (через RecipeManager) - работает и с рецептами других модов.
 * Рецепты 3x3 требуют верстак рядом (или в инвентаре - тогда бот сам его поставит).
 */
public final class CraftingActions {

    private CraftingActions() {}

    private static final String[] USEFUL = {
            "planks", "stick", "crafting_table", "furnace", "pickaxe", "_axe", "sword", "shovel", "hoe",
            "chest", "_bed", "_door", "torch", "helmet", "chestplate", "leggings", "boots", "bread",
            "ladder", "shield", "bucket"
    };

    private static boolean useful(String path) {
        for (String u : USEFUL) if (path.contains(u)) return true;
        return false;
    }

    public static boolean tableNearby(BotPlayerEntity bot, ServerLevel level) {
        return BotUtil.findNearest(level, bot.blockPosition(), 4, 2, s -> s.is(Blocks.CRAFTING_TABLE)) != null;
    }

    public static boolean furnaceNearby(BotPlayerEntity bot, ServerLevel level) {
        return BotUtil.findNearest(level, bot.blockPosition(), 5, 2, s -> s.is(Blocks.FURNACE)) != null;
    }

    /** Хватает ли в инвентаре ингредиентов (форма рецепта неважна - её можно выложить). */
    private static boolean satisfiable(BotPlayerEntity bot, CraftingRecipe r) {
        int[] counts = new int[bot.inventory.getContainerSize()];
        for (int i = 0; i < counts.length; i++) counts[i] = bot.inventory.getItem(i).getCount();
        for (Ingredient ing : r.getIngredients()) {
            if (ing.isEmpty()) continue;
            boolean ok = false;
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] > 0 && ing.test(bot.inventory.getItem(i))) {
                    counts[i]--;
                    ok = true;
                    break;
                }
            }
            if (!ok) return false;
        }
        return true;
    }

    /** Что бот может скрафтить прямо сейчас (для подсказки модели). */
    public static Set<String> craftable(BotPlayerEntity bot, ServerLevel level) {
        boolean table = tableNearby(bot, level) || bot.count(s -> s.is(Items.CRAFTING_TABLE)) > 0;
        Set<String> out = new TreeSet<>();
        for (RecipeHolder<CraftingRecipe> h : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            CraftingRecipe r = h.value();
            ItemStack res = r.getResultItem(level.registryAccess());
            if (res.isEmpty()) continue;
            String path = BuiltInRegistries.ITEM.getKey(res.getItem()).getPath();
            if (!useful(path)) continue;
            if (!r.canCraftInDimensions(2, 2) && !table) continue;
            if (satisfiable(bot, r)) out.add(path);
        }
        return out;
    }

    private static void consume(BotPlayerEntity bot, CraftingRecipe r) {
        for (Ingredient ing : r.getIngredients()) {
            if (ing.isEmpty()) continue;
            for (int i = 0; i < bot.inventory.getContainerSize(); i++) {
                ItemStack s = bot.inventory.getItem(i);
                if (!s.isEmpty() && ing.test(s)) {
                    ItemStack remainder = s.getCraftingRemainingItem(); // ведро, бутылка и т.п.
                    s.shrink(1);
                    if (!remainder.isEmpty()) bot.giveItem(remainder);
                    break;
                }
            }
        }
    }

    // ----------------------------------------------------------------- craft

    public static class Craft implements BotActions.Action {
        private final BotPlayerEntity bot;
        private final Item target;
        private final int times;
        private final String name;
        private int done, ticks;
        private String result = "";

        public Craft(BotPlayerEntity bot, String name, int times) {
            this.bot = bot;
            this.name = name;
            this.target = BotUtil.item(name);
            this.times = times;
        }

        @Override
        public boolean tick() {
            if (target == null) {
                result = "неизвестный предмет: " + name;
                return true;
            }
            if (!(bot.level() instanceof ServerLevel level)) return true;
            bot.getNavigation().stop();
            if (++ticks % 12 != 0) return false; // каждый крафт занимает время

            CraftingRecipe recipe = null;
            for (RecipeHolder<CraftingRecipe> h : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
                CraftingRecipe r = h.value();
                ItemStack res = r.getResultItem(level.registryAccess());
                if (!res.isEmpty() && res.is(target) && satisfiable(bot, r)) {
                    recipe = r;
                    break;
                }
            }
            if (recipe == null) {
                result = done > 0 ? "скрафтил " + done + " (дальше не хватает ресурсов)"
                        : "не хватает ресурсов или нет рецепта для " + name;
                return true;
            }

            if (!recipe.canCraftInDimensions(2, 2) && !tableNearby(bot, level)) {
                if (bot.count(s -> s.is(Items.CRAFTING_TABLE)) > 0) {
                    if (BuildActions.placeBlockNear(bot, level, Blocks.CRAFTING_TABLE) == null) {
                        result = "негде поставить верстак";
                        return true;
                    }
                    return false; // верстак поставлен, продолжаем крафт
                }
                result = "для этого нужен верстак (скрафти crafting_table из 4 досок)";
                return true;
            }

            ItemStack out = recipe.getResultItem(level.registryAccess()).copy();
            consume(bot, recipe);
            bot.giveItem(out);
            bot.swing(InteractionHand.MAIN_HAND);
            bot.playSound(SoundEvents.UI_STONECUTTER_TAKE_RESULT, 0.4F, 1.2F);
            done++;
            if (done >= times) {
                result = "скрафтил " + done + "x " + name;
                return true;
            }
            return false;
        }

        @Override
        public String result() {
            return result;
        }
    }

    // ----------------------------------------------------------------- smelt

    /**
     * Плавка/жарка в печи рядом (или бот сам ставит печь из инвентаря).
     * Упрощение: используется "виртуальная" печь - бот ждёт нужное время и тратит топливо.
     */
    public static class Smelt implements BotActions.Action {
        private final BotPlayerEntity bot;
        private final Item input;
        private final int count;
        private final String name;
        private int ticks, done, burn;
        private BlockPos furnace;
        private String result = "";

        public Smelt(BotPlayerEntity bot, String name, int count) {
            this.bot = bot;
            this.name = name;
            this.input = BotUtil.item(name);
            this.count = count;
        }

        private static int fuelTime(ItemStack s) {
            return AbstractFurnaceBlockEntity.getFuel().getOrDefault(s.getItem(), 0);
        }

        @Override
        public boolean tick() {
            if (input == null) {
                result = "неизвестный предмет: " + name;
                return true;
            }
            if (!(bot.level() instanceof ServerLevel level)) return true;
            if (++ticks > 2500) {
                result = "приготовил " + done;
                return true;
            }

            if (furnace == null) {
                furnace = BotUtil.findNearest(level, bot.blockPosition(), 5, 2, s -> s.is(Blocks.FURNACE));
                if (furnace == null) {
                    if (bot.count(s -> s.is(Items.FURNACE)) > 0) {
                        furnace = BuildActions.placeBlockNear(bot, level, Blocks.FURNACE);
                    }
                    if (furnace == null) {
                        result = "нужна печь (скрафти furnace из 8 булыжников и поставь)";
                        return true;
                    }
                }
            }
            Vec3 c = Vec3.atCenterOf(furnace);
            if (bot.getEyePosition().distanceToSqr(c) > 12.0) {
                if (ticks % 20 == 1) bot.getNavigation().moveTo(c.x, c.y, c.z, 1.0);
                return false;
            }
            bot.getNavigation().stop();
            bot.getLookControl().setLookAt(c.x, c.y, c.z);

            if (done >= count) {
                result = "приготовил " + done + "x " + name;
                return true;
            }
            if (bot.count(s -> s.is(input)) < 1) {
                result = done > 0 ? "приготовил " + done + ", сырьё кончилось" : "нет предмета " + name;
                return true;
            }

            ItemStack sample = null;
            for (int i = 0; i < bot.inventory.getContainerSize(); i++) {
                if (bot.inventory.getItem(i).is(input)) {
                    sample = bot.inventory.getItem(i);
                    break;
                }
            }
            ItemStack out = ItemStack.EMPTY;
            for (RecipeHolder<SmeltingRecipe> h : level.getRecipeManager().getAllRecipesFor(RecipeType.SMELTING)) {
                if (!h.value().getIngredients().isEmpty() && h.value().getIngredients().get(0).test(sample)) {
                    out = h.value().getResultItem(level.registryAccess()).copy();
                    break;
                }
            }
            if (out.isEmpty()) {
                result = name + " нельзя переплавить";
                return true;
            }

            // Топливо: одно "сжигание" = 200 тиков огня на предмет
            if (burn < 200) {
                boolean fueled = false;
                for (int i = 0; i < bot.inventory.getContainerSize(); i++) {
                    ItemStack s = bot.inventory.getItem(i);
                    int t = s.isEmpty() ? 0 : fuelTime(s);
                    if (t > 0 && !s.is(input)) {
                        burn += t;
                        s.shrink(1);
                        fueled = true;
                        break;
                    }
                }
                if (!fueled) {
                    result = done > 0 ? "приготовил " + done + ", топливо кончилось" : "нет топлива (уголь, доски, брёвна)";
                    return true;
                }
            }

            if (ticks % 100 == 0) { // один предмет готовится ~5 секунд
                bot.consume(s -> s.is(input), 1);
                burn -= 200;
                bot.giveItem(out);
                done++;
                bot.playSound(SoundEvents.FURNACE_FIRE_CRACKLE, 0.6F, 1.0F);
            }
            return false;
        }

        @Override
        public String result() {
            return result;
        }
    }
}
