package com.example.aiplayers.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;

import java.util.function.Predicate;

/** Общие помощники для действий бота. */
public final class BotUtil {

    private BotUtil() {}

    public static ResourceLocation id(String name) {
        if (name == null || name.isBlank()) return null;
        return ResourceLocation.tryParse(name.contains(":") ? name : "minecraft:" + name);
    }

    /** @return предмет или null, если такого нет. */
    public static Item item(String name) {
        ResourceLocation id = id(name);
        if (id == null) return null;
        Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        return item == Items.AIR ? null : item;
    }

    /** @return блок или null, если такого нет. */
    public static Block block(String name) {
        ResourceLocation id = id(name);
        if (id == null) return null;
        Block b = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        return b == Blocks.AIR ? null : b;
    }

    /** Блоки, из которых бот строит дом: доски, булыжник, брёвна. */
    public static boolean isBuildMaterial(ItemStack s) {
        if (!(s.getItem() instanceof BlockItem bi)) return false;
        BlockState st = bi.getBlock().defaultBlockState();
        return st.is(BlockTags.PLANKS) || st.is(Tags.Blocks.COBBLESTONES) || st.is(BlockTags.LOGS);
    }

    /** Ближайший блок, подходящий под условие (только в загруженных чанках). */
    public static BlockPos findNearest(ServerLevel level, BlockPos o, int rh, int rv, Predicate<BlockState> test) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -rh; dx <= rh; dx++) {
            for (int dz = -rh; dz <= rh; dz++) {
                for (int dy = -rv; dy <= rv; dy++) {
                    m.set(o.getX() + dx, o.getY() + dy, o.getZ() + dz);
                    if (!level.hasChunkAt(m)) continue;
                    if (!test.test(level.getBlockState(m))) continue;
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
}
