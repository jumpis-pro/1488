package com.example.aiplayers.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Процедурный генератор домов. Каждый вызов даёт уникальный дом: размер 5-9 x 5-9, высота стен 3-4,
 * одна из четырёх форм крыши (плоская, двускатная вдоль X или Z, шатровая), случайная сторона
 * и позиция двери, колонны по углам, цоколь, окна (если у бота есть стекло).
 * Все блоки только перечисляются; ставит их {@link BuildActions.BuildHouse}.
 */
public final class HouseGenerator {

    private HouseGenerator() {}

    public enum Role { FOUNDATION, BASE, WALL, PILLAR, WINDOW, ROOF }

    public enum RoofKind {
        FLAT("плоская"), GABLE_X("двускатная"), GABLE_Z("двускатная"), HIP("шатровая");

        public final String title;

        RoofKind(String title) {
            this.title = title;
        }
    }

    public record Cell(BlockPos pos, Role role) {}

    public static final class Plan {
        public final List<Cell> cells = new ArrayList<>();
        public int minX, maxX, minZ, maxZ, y0, wallH;
        public BlockPos doorLow;
        public Direction doorSide;
        public String description = "";

        public BlockPos center() {
            return new BlockPos((minX + maxX) / 2, y0, (minZ + maxZ) / 2);
        }

        public Map<Role, Integer> counts() {
            Map<Role, Integer> m = new EnumMap<>(Role.class);
            for (Cell c : cells) m.merge(c.role(), 1, Integer::sum);
            return m;
        }
    }

    /**
     * @param center  клетка пола в центре дома (y = уровень первого слоя стен)
     * @param rise    максимальный подъём крыши (2-4)
     * @param windows ставить ли окна из стекла
     */
    public static Plan generate(ServerLevel level, BlockPos center, int w, int d, int wallH,
                                RoofKind roof, int rise, boolean windows, int winStep, RandomSource r) {
        Plan p = new Plan();
        p.minX = center.getX() - w / 2;
        p.maxX = p.minX + w - 1;
        p.minZ = center.getZ() - d / 2;
        p.maxZ = p.minZ + d - 1;
        p.y0 = center.getY();
        p.wallH = wallH;

        // Дверь: случайная сторона и небольшой сдвиг от центра
        Direction side = Direction.Plane.HORIZONTAL.getRandomDirection(r);
        int doorX, doorZ;
        switch (side) {
            case SOUTH -> {
                doorZ = p.maxZ;
                doorX = p.minX + w / 2 + (w >= 7 ? r.nextInt(3) - 1 : 0);
            }
            case NORTH -> {
                doorZ = p.minZ;
                doorX = p.minX + w / 2 + (w >= 7 ? r.nextInt(3) - 1 : 0);
            }
            case EAST -> {
                doorX = p.maxX;
                doorZ = p.minZ + d / 2 + (d >= 7 ? r.nextInt(3) - 1 : 0);
            }
            default -> {
                doorX = p.minX;
                doorZ = p.minZ + d / 2 + (d >= 7 ? r.nextInt(3) - 1 : 0);
            }
        }
        p.doorSide = side;
        p.doorLow = new BlockPos(doorX, p.y0, doorZ);

        // Фундамент под стенами (только там, где пусто)
        for (int x = p.minX; x <= p.maxX; x++) {
            for (int z = p.minZ; z <= p.maxZ; z++) {
                if (!onEdge(p, x, z)) continue;
                for (int dy = 1; dy <= 2; dy++) {
                    add(level, p, new BlockPos(x, p.y0 - dy, z), Role.FOUNDATION);
                }
            }
        }

        // Стены слой за слоем
        for (int layer = 0; layer < wallH; layer++) {
            for (int x = p.minX; x <= p.maxX; x++) {
                for (int z = p.minZ; z <= p.maxZ; z++) {
                    if (!onEdge(p, x, z)) continue;
                    if (x == doorX && z == doorZ && layer < 2) continue; // проём двери
                    boolean corner = (x == p.minX || x == p.maxX) && (z == p.minZ || z == p.maxZ);
                    Role role;
                    if (corner) role = Role.PILLAR;
                    else if (layer == 0) role = Role.BASE;
                    else if (windows && isWindow(p, x, z, layer, winStep)) role = Role.WINDOW;
                    else role = Role.WALL;
                    add(level, p, new BlockPos(x, p.y0 + layer, z), role);
                }
            }
        }

        // Крыша: в каждой колонке два блока по высоте -> склон без щелей
        int h = p.y0 + wallH;
        for (int x = p.minX; x <= p.maxX; x++) {
            for (int z = p.minZ; z <= p.maxZ; z++) {
                int ins = switch (roof) {
                    case FLAT -> 0;
                    case GABLE_X -> Math.min(z - p.minZ, p.maxZ - z);
                    case GABLE_Z -> Math.min(x - p.minX, p.maxX - x);
                    case HIP -> Math.min(Math.min(x - p.minX, p.maxX - x), Math.min(z - p.minZ, p.maxZ - z));
                };
                ins = Math.min(ins, rise);
                int lo = h + Math.max(0, ins - 1);
                int hi = h + ins;
                add(level, p, new BlockPos(x, lo, z), Role.ROOF);
                if (hi != lo) add(level, p, new BlockPos(x, hi, z), Role.ROOF);

                // Фронтоны двускатной крыши
                boolean gableEnd = (roof == RoofKind.GABLE_X && (x == p.minX || x == p.maxX))
                        || (roof == RoofKind.GABLE_Z && (z == p.minZ || z == p.maxZ));
                if (gableEnd) {
                    for (int y = h; y < lo; y++) add(level, p, new BlockPos(x, y, z), Role.WALL);
                }
            }
        }

        p.description = w + "x" + d + ", стены " + wallH + ", " + roof.title + " крыша"
                + (windows ? ", с окнами" : "");
        return p;
    }

    private static boolean onEdge(Plan p, int x, int z) {
        return x == p.minX || x == p.maxX || z == p.minZ || z == p.maxZ;
    }

    private static boolean isWindow(Plan p, int x, int z, int layer, int step) {
        if (layer < 1 || layer > (p.wallH >= 4 ? 2 : 1)) return false;
        boolean alongX = z == p.minZ || z == p.maxZ;
        int idx = alongX ? x - p.minX : z - p.minZ;
        int len = alongX ? p.maxX - p.minX + 1 : p.maxZ - p.minZ + 1;
        if (idx < 2 || idx > len - 3 || idx % step != 0) return false;
        // не рядом с дверью
        return Math.max(Math.abs(x - p.doorLow.getX()), Math.abs(z - p.doorLow.getZ())) > 1;
    }

    private static void add(ServerLevel level, Plan p, BlockPos pos, Role role) {
        if (!level.hasChunkAt(pos)) return;
        if (!level.getBlockState(pos).canBeReplaced()) return; // там уже что-то стоит
        p.cells.add(new Cell(pos, role));
    }
}
