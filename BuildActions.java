package com.example.aiplayers.ai;

import com.example.aiplayers.BotPlayerEntity;
import com.example.aiplayers.ai.HouseGenerator.Cell;
import com.example.aiplayers.ai.HouseGenerator.Plan;
import com.example.aiplayers.ai.HouseGenerator.Role;
import com.example.aiplayers.ai.HouseGenerator.RoofKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Стройка: поставить блок, кровать и целый уникальный дом. Блоки берутся из инвентаря бота. */
public final class BuildActions {

    private BuildActions() {}

    /** Блоки, которые бот ставит по команде place. */
    public static boolean isPlaceable(BlockState s) {
        return s.is(BlockTags.PLANKS) || s.is(BlockTags.LOGS) || s.is(Tags.Blocks.COBBLESTONES)
                || s.is(Tags.Blocks.STONES) || s.is(BlockTags.DIRT) || s.is(BlockTags.BEDS)
                || s.is(Blocks.CRAFTING_TABLE) || s.is(Blocks.FURNACE) || s.is(Blocks.CHEST)
                || s.is(Blocks.TORCH);
    }

    private static boolean free(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).canBeReplaced()
                && level.getEntities((Entity) null, new AABB(p), e -> true).isEmpty();
    }

    private static boolean sturdy(ServerLevel level, BlockPos below) {
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    private static void placeSound(ServerLevel level, BlockPos p, BlockState st) {
        level.playSound(null, p, st.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 0.8F);
    }

    /** Ставит блок рядом с ботом (впереди или сбоку). @return позиция или null. */
    public static BlockPos placeBlockNear(BotPlayerEntity bot, ServerLevel level, Block block) {
        Item item = block.asItem();
        if (bot.count(s -> s.is(item)) < 1) return null;
        BlockPos o = bot.blockPosition();
        Direction front = bot.getDirection();

        List<BlockPos> cand = new ArrayList<>();
        for (int r = 1; r <= 3; r++) {
            cand.add(o.relative(front, r));
            for (Direction d : Direction.Plane.HORIZONTAL) if (d != front) cand.add(o.relative(d, r));
        }
        for (BlockPos p : cand) {
            if (!free(level, p) || !sturdy(level, p.below())) continue;
            BlockState st = block.defaultBlockState();
            if (st.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                st = st.setValue(BlockStateProperties.HORIZONTAL_FACING, front.getOpposite());
            }
            if (!st.canSurvive(level, p)) continue;
            level.setBlock(p, st, 3);
            bot.consume(s -> s.is(item), 1);
            placeSound(level, p, st);
            bot.swing(InteractionHand.MAIN_HAND);
            return p;
        }
        return null;
    }

    /** Ставит двухблочную кровать рядом с ботом. */
    public static BlockPos placeBed(BotPlayerEntity bot, ServerLevel level, Block bed) {
        Item item = bed.asItem();
        if (bot.count(s -> s.is(item)) < 1) return null;
        BlockPos o = bot.blockPosition();
        for (int r = 1; r <= 3; r++) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos foot = o.relative(side, r);
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    BlockPos head = foot.relative(d);
                    if (foot.equals(o) || head.equals(o)) continue;
                    if (!free(level, foot) || !free(level, head)) continue;
                    if (!sturdy(level, foot.below()) || !sturdy(level, head.below())) continue;
                    BlockState f = bed.defaultBlockState().setValue(BedBlock.FACING, d).setValue(BedBlock.PART, BedPart.FOOT);
                    BlockState h = f.setValue(BedBlock.PART, BedPart.HEAD);
                    level.setBlock(foot, f, 26); // 26 = как в ванили, чтобы половинки не сломали друг друга
                    level.setBlock(head, h, 26);
                    bot.consume(s -> s.is(item), 1);
                    placeSound(level, foot, f);
                    bot.swing(InteractionHand.MAIN_HAND);
                    return foot;
                }
            }
        }
        return null;
    }

    // ----------------------------------------------------------------- place

    public static class Place implements BotActions.Action {
        private final BotPlayerEntity bot;
        private final String name;
        private final Block block;
        private String result = "";

        public Place(BotPlayerEntity bot, String name) {
            this.bot = bot;
            this.name = name;
            this.block = BotUtil.block(name);
        }

        @Override
        public boolean tick() {
            if (block == null || !isPlaceable(block.defaultBlockState())) {
                result = "этот блок ставить нельзя: " + name;
                return true;
            }
            if (!(bot.level() instanceof ServerLevel level)) return true;
            BlockPos p = block.defaultBlockState().is(BlockTags.BEDS)
                    ? placeBed(bot, level, block)
                    : placeBlockNear(bot, level, block);
            result = p != null ? "поставил " + name : "не вышло: нет такого предмета или нет места рядом";
            return true;
        }

        @Override
        public String result() {
            return result;
        }
    }

    // ----------------------------------------------------------- build house

    /**
     * Бот сам придумывает дом: размер зависит от того, сколько у него блоков, форма крыши, высота,
     * положение двери и материалы выбираются случайно, поэтому дома получаются разными.
     * Он находит ровное место, строит стоя внутри будущего дома, ставит дверь, обставляет жильё
     * (кровать, верстак, печь, сундук, факелы) и запоминает дом как свою базу.
     */
    public static class BuildHouse implements BotActions.Action {
        public static final int MIN_BLOCKS = 80;
        private static final double REACH_SQR = 6.5 * 6.5;

        private final BotPlayerEntity bot;
        private final ArrayDeque<Cell> queue = new ArrayDeque<>();
        private final Map<Role, Block> palette = new EnumMap<>(Role.class);
        private Plan plan;
        private boolean init;
        private int ticks, placed, outOfReach;
        private String failReason = "";
        private String result = "";

        public BuildHouse(BotPlayerEntity bot) {
            this.bot = bot;
        }

        @Override
        public boolean tick() {
            if (!(bot.level() instanceof ServerLevel level)) return true;
            if (++ticks > 6000) {
                result = "стройка затянулась, поставлено " + placed + " блоков";
                return true;
            }

            if (!init) {
                init = true;
                int mats = bot.count(BotUtil::isBuildMaterial);
                if (mats < MIN_BLOCKS) {
                    result = "мало блоков для дома: " + mats + " из " + MIN_BLOCKS;
                    return true;
                }
                plan = choosePlan(level, mats);
                if (plan == null) {
                    result = failReason;
                    return true;
                }
                buildPalette(plan);
                queue.addAll(plan.cells);
            }

            Cell next = queue.peek();
            if (next != null) {
                BlockPos p = next.pos();
                if (bot.getEyePosition().distanceToSqr(Vec3.atCenterOf(p)) > REACH_SQR) {
                    // Идём к ближайшей точке внутри будущего дома
                    BlockPos stand = new BlockPos(
                            Math.max(plan.minX + 1, Math.min(plan.maxX - 1, p.getX())), plan.y0,
                            Math.max(plan.minZ + 1, Math.min(plan.maxZ - 1, p.getZ())));
                    if (ticks % 10 == 1 || bot.getNavigation().isDone()) {
                        bot.getNavigation().moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 1.0);
                    }
                    if (++outOfReach > 300) { // не дойти - пропускаем клетку
                        queue.poll();
                        outOfReach = 0;
                    }
                    return false;
                }
                outOfReach = 0;
            }
            bot.getNavigation().stop();
            if (ticks % 3 != 0) return false;

            Cell cell = queue.poll();
            if (cell == null) {
                finish(level);
                return true;
            }
            BlockPos pos = cell.pos();
            if (!level.getBlockState(pos).canBeReplaced()) return false;

            Block b = takeBlock(cell.role());
            if (b == null) {
                result = "блоки кончились, дом не достроен (" + placed + " блоков)";
                return true;
            }
            BlockState st = b.defaultBlockState();
            level.setBlock(pos, st, 3);
            placed++;
            placeSound(level, pos, st);
            bot.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            bot.swing(InteractionHand.MAIN_HAND);
            return false;
        }

        // ------------------------------------------------------- planning

        private Plan choosePlan(ServerLevel level, int mats) {
            RandomSource r = bot.getRandom();
            int w = 5 + r.nextInt(5);
            int d = 5 + r.nextInt(5);
            int wallH = 3 + r.nextInt(2);
            RoofKind roof = RoofKind.values()[r.nextInt(RoofKind.values().length)];
            int rise = 2 + r.nextInt(3);
            boolean glass = bot.count(s -> s.is(Items.GLASS)) >= 4;
            int winStep = 2 + r.nextInt(2);

            int smallest = Integer.MAX_VALUE;
            boolean siteFound = false;
            for (int attempt = 0; attempt < 14; attempt++) {
                BlockPos site = findSite(level, bot.blockPosition(), w, d);
                if (site != null) {
                    siteFound = true;
                    Plan p = HouseGenerator.generate(level, site, w, d, wallH, roof, rise, glass, winStep, r);
                    if (p.cells.size() <= mats) return p;
                    smallest = Math.min(smallest, p.cells.size());
                }
                // Не хватает блоков или места - упрощаем проект
                if (roof != RoofKind.FLAT && attempt % 2 == 0) roof = RoofKind.FLAT;
                else if (wallH > 3) wallH--;
                else if (w >= d && w > 5) w--;
                else if (d > 5) d--;
                else if (roof != RoofKind.FLAT) roof = RoofKind.FLAT;
                else if (w > 5) w--;
                else break;
            }
            failReason = siteFound
                    ? "не хватает блоков даже на самый маленький дом (нужно около " + smallest + ", есть " + mats + ")"
                    : "рядом нет ровного места для дома";
            return null;
        }

        /** Распределяем материалы по ролям: у каждого дома своя "палитра". */
        private void buildPalette(Plan p) {
            Map<Block, Integer> remain = new LinkedHashMap<>();
            for (int i = 0; i < bot.inventory.getContainerSize(); i++) {
                ItemStack s = bot.inventory.getItem(i);
                if (s.isEmpty() || !BotUtil.isBuildMaterial(s)) continue;
                remain.merge(((BlockItem) s.getItem()).getBlock(), s.getCount(), Integer::sum);
            }
            Map<Role, Integer> need = p.counts();
            int wallN = need.getOrDefault(Role.WALL, 0);
            int roofN = need.getOrDefault(Role.ROOF, 0);
            int pillarN = need.getOrDefault(Role.PILLAR, 0);
            int baseN = need.getOrDefault(Role.BASE, 0) + need.getOrDefault(Role.FOUNDATION, 0);
            RandomSource r = bot.getRandom();

            Block wall = pick(remain, b -> !b.defaultBlockState().is(BlockTags.LOGS), wallN, null, r);
            if (wall == null) wall = pick(remain, b -> true, wallN, null, r);
            reserve(remain, wall, wallN);
            Block roofB = pick(remain, b -> !b.defaultBlockState().is(BlockTags.LOGS), roofN, null, r);
            if (roofB == null) roofB = wall;
            reserve(remain, roofB, roofN);
            Block pillar = pick(remain, b -> b.defaultBlockState().is(BlockTags.LOGS), pillarN, null, r);
            if (pillar == null) pillar = wall;
            reserve(remain, pillar, pillarN);
            Block base = pick(remain, b -> b.defaultBlockState().is(Tags.Blocks.COBBLESTONES), baseN, null, r);
            if (base == null) base = wall;

            palette.put(Role.WALL, wall);
            palette.put(Role.ROOF, roofB);
            palette.put(Role.PILLAR, pillar);
            palette.put(Role.BASE, base);
            palette.put(Role.FOUNDATION, base);
            palette.put(Role.WINDOW, Blocks.GLASS);
        }

        private static Block pick(Map<Block, Integer> remain, java.util.function.Predicate<Block> filter,
                                  int need, Block exclude, RandomSource r) {
            List<Block> ok = new ArrayList<>();
            for (Map.Entry<Block, Integer> e : remain.entrySet()) {
                if (e.getKey() != exclude && filter.test(e.getKey()) && e.getValue() >= need) ok.add(e.getKey());
            }
            return ok.isEmpty() ? null : ok.get(r.nextInt(ok.size()));
        }

        private static void reserve(Map<Block, Integer> remain, Block b, int n) {
            if (b != null) remain.merge(b, -n, Integer::sum);
        }

        /** Достаёт один блок из инвентаря по роли; если выбранный закончился - берёт любой подходящий. */
        private Block takeBlock(Role role) {
            Block want = palette.get(role);
            if (want != null && bot.consume(s -> s.is(want.asItem()), 1)) return want;
            if (role == Role.WINDOW) { // стекло кончилось - обычная стена
                Block wall = palette.get(Role.WALL);
                if (wall != null && bot.consume(s -> s.is(wall.asItem()), 1)) return wall;
            }
            ItemStack best = null;
            for (int i = 0; i < bot.inventory.getContainerSize(); i++) {
                ItemStack s = bot.inventory.getItem(i);
                if (!s.isEmpty() && BotUtil.isBuildMaterial(s) && (best == null || s.getCount() > best.getCount())) {
                    best = s;
                }
            }
            if (best == null) return null;
            Block b = ((BlockItem) best.getItem()).getBlock();
            best.shrink(1);
            return b;
        }

        // -------------------------------------------------------- finishing

        private void finish(ServerLevel level) {
            placeDoor(level);
            // Обставляем: кровать, верстак, печь, сундук, факелы
            for (int i = 0; i < bot.inventory.getContainerSize(); i++) {
                ItemStack s = bot.inventory.getItem(i);
                if (!s.isEmpty() && s.getItem() instanceof BlockItem bi
                        && bi.getBlock().defaultBlockState().is(BlockTags.BEDS)) {
                    placeBed(bot, level, bi.getBlock());
                    break;
                }
            }
            for (Block b : new Block[]{Blocks.CRAFTING_TABLE, Blocks.FURNACE, Blocks.CHEST, Blocks.TORCH, Blocks.TORCH}) {
                if (bot.count(s -> s.is(b.asItem())) > 0) placeBlockNear(bot, level, b);
            }
            bot.setHome(plan.center());
            result = "построил дом (" + plan.description + "), блоков: " + placed + ", теперь это моя база";
        }

        private void placeDoor(ServerLevel level) {
            for (int i = 0; i < bot.inventory.getContainerSize(); i++) {
                ItemStack s = bot.inventory.getItem(i);
                if (s.isEmpty() || !(s.getItem() instanceof BlockItem bi)) continue;
                BlockState def = bi.getBlock().defaultBlockState();
                if (!def.is(BlockTags.WOODEN_DOORS)) continue;
                BlockPos low = plan.doorLow;
                if (!level.getBlockState(low).canBeReplaced() || !level.getBlockState(low.above()).canBeReplaced()) return;
                BlockState lower = def.setValue(DoorBlock.FACING, plan.doorSide.getOpposite())
                        .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
                level.setBlock(low, lower, 26);
                level.setBlock(low.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 26);
                s.shrink(1);
                placeSound(level, low, lower);
                return;
            }
        }

        /** Ищет ровную площадку (дом + запас по 1 блоку) без воды неподалёку. */
        private static BlockPos findSite(ServerLevel level, BlockPos origin, int w, int d) {
            int[][] dirs = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
            for (int r = 0; r <= 20; r += 4) {
                for (int[] dir : dirs) {
                    if (r == 0 && (dir[0] != 0 || dir[1] != 0)) continue;
                    BlockPos site = testSite(level, origin.getX() + dir[0] * r, origin.getZ() + dir[1] * r, w, d);
                    if (site != null) return site;
                }
            }
            return null;
        }

        private static BlockPos testSite(ServerLevel level, int cx, int cz, int w, int d) {
            int minX = cx - w / 2 - 1, maxX = cx - w / 2 + w;
            int minZ = cz - d / 2 - 1, maxZ = cz - d / 2 + d;
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!level.hasChunkAt(new BlockPos(x, 64, z))) return null;
                    int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    if (!level.getFluidState(new BlockPos(x, h - 1, z)).isEmpty()) return null;
                    min = Math.min(min, h);
                    max = Math.max(max, h);
                }
            }
            return max - min <= 1 ? new BlockPos(cx, max, cz) : null;
        }

        @Override
        public void stop() {
            bot.getNavigation().stop();
        }

        @Override
        public String result() {
            return result;
        }
    }
}
