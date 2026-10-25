package com.example.aiplayers.ai;

import com.example.aiplayers.AiPlayersMod;
import com.example.aiplayers.BotPlayerEntity;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.Tags;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Мозг бота. Раз в несколько секунд собирает описание мира, отправляет его модели
 * (асинхронно, сервер не подвисает), получает действие и запускает его.
 */
public class BotBrain {

    private static final int MAX_HISTORY = 10;

    private final BotPlayerEntity bot;
    private final Deque<String> history = new ArrayDeque<>();

    // Результат из HTTP-потока; читается и применяется только в главном потоке сервера
    private volatile JsonObject pendingDecision;
    private volatile String pendingError;

    private boolean thinking;
    private boolean interrupt;
    private int cooldown;
    private int seenChatVersion = ChatMemory.playerChatVersion();
    private BotActions.Action current;
    private int lastSpokeTick;

    public BotBrain(BotPlayerEntity bot) {
        this.bot = bot;
        this.cooldown = 60 + bot.getRandom().nextInt(200); // боты просыпаются не одновременно
    }

    public BotActions.Action currentAction() {
        return current;
    }

    public void onActionFinished(String result) {
        current = null;
        addHistory("   результат: " + result);
        cooldown = Math.min(cooldown, 20 + bot.getRandom().nextInt(40));
    }

    /** Событие из мира (приветствие, удар): попадает в память бота, при желании будит его. */
    public void noteEvent(String text, boolean wakeUp) {
        addHistory("* " + text);
        if (wakeUp) wake();
    }

    /** Реплика в общий чат от имени бота. */
    public void speak(String message) {
        if (bot.level() instanceof ServerLevel level) say(level, message);
    }

    /** Реакция на реплику игрока - с небольшой "человеческой" задержкой. */
    private void wake() {
        if (thinking || bot.isSleeping()) return; // спящего чат не будит
        interrupt = true;
        cooldown = Math.min(cooldown, 40 + bot.getRandom().nextInt(80));
    }

    // ------------------------------------------------------------------ tick

    public void tick() {
        if (!LlmClient.enabled() || !(bot.level() instanceof ServerLevel level)) return;

        int chatVersion = ChatMemory.playerChatVersion();
        if (chatVersion != seenChatVersion) {
            seenChatVersion = chatVersion;
            if (bot.getRandom().nextInt(3) != 0) wake(); // отвечают не все и не сразу
        }

        // Рефлекс: проголодался и есть еда - ест сам, не дожидаясь модели
        if (bot.food() < 8.0F && !bot.isSleeping()
                && !(current instanceof SurvivalActions.Eat) && SurvivalActions.hasFood(bot)) {
            if (current != null) current.stop();
            current = new SurvivalActions.Eat(bot);
            addHistory("проголодался -> eat (рефлекс)");
        }

        JsonObject decision = pendingDecision;
        if (decision != null) {
            pendingDecision = null;
            thinking = false;
            apply(decision, level);
        }
        String error = pendingError;
        if (error != null) {
            pendingError = null;
            thinking = false;
            cooldown = 400; // пауза после ошибки
            AiPlayersMod.LOGGER.warn("[aiplayers] ошибка запроса к модели: {}", error);
        }

        if (!thinking && --cooldown <= 0) think(level);
    }

    private void think(ServerLevel level) {
        if (current != null && !interrupt) { // занят делом - не отвлекаем
            cooldown = 20;
            return;
        }
        if (LlmClient.inflight() >= LlmClient.MAX_CONCURRENT) {
            cooldown = 40;
            return;
        }
        interrupt = false;
        thinking = true;
        String name = bot.getName().getString();
        LlmClient.decide(systemPrompt(name, bot.personality()), observe(level)).whenComplete((json, err) -> {
            if (err != null) pendingError = String.valueOf(err.getMessage());
            else pendingDecision = json;
        });
    }

    // ----------------------------------------------------------------- apply

    private void apply(JsonObject d, ServerLevel level) {
        String action = str(d, "action", "wander");
        String thought = str(d, "thought", "");
        String message = str(d, "message", "");

        if (!message.isBlank()) say(level, message);
        if (current != null) current.stop();
        current = null;

        switch (action) {
            case "goto" -> {
                int x = intVal(d, "x", bot.getBlockX());
                int z = intVal(d, "z", bot.getBlockZ());
                int y = intVal(d, "y", Integer.MIN_VALUE);
                if (Mth.abs(x - bot.getBlockX()) > 80 || Mth.abs(z - bot.getBlockZ()) > 80) {
                    addHistory("goto отклонён: слишком далеко (макс. 80 блоков)");
                } else {
                    if (y == Integer.MIN_VALUE || Mth.abs(y - bot.getBlockY()) > 30) {
                        level.getChunk(x >> 4, z >> 4);
                        y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    }
                    current = new BotActions.Goto(bot, new BlockPos(x, y, z));
                }
            }
            case "follow_player" -> current = new BotActions.Follow(bot, str(d, "player", ""));
            case "mine" -> current = new BotActions.Mine(bot, str(d, "block", ""),
                    Mth.clamp(intVal(d, "count", 1), 1, 8));
            case "attack_hostile" -> {
                var target = level.getEntitiesOfClass(Monster.class, bot.getBoundingBox().inflate(16.0),
                                m -> !(m instanceof Creeper))
                        .stream().min(Comparator.comparingDouble(bot::distanceToSqr));
                if (target.isPresent()) {
                    bot.setTarget(target.get());
                    addHistory("напал на " + typeName(target.get()));
                } else {
                    addHistory("attack_hostile: врагов рядом нет");
                }
            }
            case "hunt" -> current = new SurvivalActions.Hunt(bot);
            case "eat" -> current = new SurvivalActions.Eat(bot);
            case "sleep" -> current = new SurvivalActions.Sleep(bot);
            case "craft" -> current = new CraftingActions.Craft(bot, str(d, "item", ""),
                    Mth.clamp(intVal(d, "count", 1), 1, 16));
            case "smelt" -> current = new CraftingActions.Smelt(bot, str(d, "item", ""),
                    Mth.clamp(intVal(d, "count", 1), 1, 8));
            case "place" -> current = new BuildActions.Place(bot, str(d, "block", ""));
            case "build_house" -> current = new BuildActions.BuildHouse(bot);
            case "idle" -> current = new BotActions.Idle(bot, 100);
            default -> { /* wander: обычное блуждание */ }
        }

        String detail = switch (action) {
            case "mine", "place" -> " " + str(d, "block", "");
            case "craft", "smelt" -> " " + str(d, "item", "") + " x" + intVal(d, "count", 1);
            default -> "";
        };
        addHistory(action + detail + (thought.isBlank() ? "" : " (" + thought + ")"));
        cooldown = (current == null ? 100 : 300) + bot.getRandom().nextInt(100);
    }

    private void say(ServerLevel level, String message) {
        if (message.length() > 120) message = message.substring(0, 120);
        lastSpokeTick = bot.tickCount;
        ChatMemory.add(bot.getName().getString() + ": " + message);
        level.getServer().getPlayerList().broadcastSystemMessage(
                Component.translatable("chat.type.text", bot.getDisplayName(), message), false);
    }

    // --------------------------------------------------------------- observe

    private String observe(ServerLevel level) {
        StringBuilder sb = new StringBuilder();
        boolean night = !level.isDay();
        String biome = level.getBiome(bot.blockPosition()).unwrapKey()
                .map(k -> k.location().getPath()).orElse("?");

        sb.append("Позиция: ").append(bot.getBlockX()).append(' ').append(bot.getBlockY()).append(' ')
                .append(bot.getBlockZ()).append(", биом: ").append(biome).append('\n');
        sb.append("Здоровье: ").append((int) bot.getHealth()).append("/20, сытость: ")
                .append((int) bot.food()).append("/20, ")
                .append(night ? "сейчас ночь" : "сейчас день")
                .append(level.isRaining() ? ", идёт дождь" : "").append('\n');

        ItemStack hand = bot.getMainHandItem();
        sb.append("В руке: ").append(hand.isEmpty() ? "ничего" : hand.getHoverName().getString()).append('\n');

        Map<String, Integer> inv = new LinkedHashMap<>();
        for (int i = 0; i < bot.inventory.getContainerSize(); i++) {
            ItemStack s = bot.inventory.getItem(i);
            if (!s.isEmpty()) {
                inv.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath(), s.getCount(), Integer::sum);
            }
        }
        sb.append("Инвентарь: ").append(inv.isEmpty() ? "пусто" : inv.toString()).append('\n');

        int mats = bot.count(BotUtil::isBuildMaterial);
        sb.append("Строительных блоков (доски/булыжник/брёвна): ").append(mats)
                .append(" (для домика нужно ").append(BuildActions.BuildHouse.MIN_BLOCKS).append(")\n");

        BlockPos home = bot.home();
        if (home == null) {
            sb.append("Дома у тебя пока нет. Для постройки нужно хотя бы ")
                    .append(BuildActions.BuildHouse.MIN_BLOCKS).append(" блоков, у тебя ").append(mats)
                    .append(mats < BuildActions.BuildHouse.MIN_BLOCKS
                            ? " - нужно ещё " + (BuildActions.BuildHouse.MIN_BLOCKS - mats) + "\n" : " - хватает!\n");
        } else {
            sb.append("Твоя база: ").append(home.getX()).append(' ').append(home.getY()).append(' ')
                    .append(home.getZ()).append(", до неё ").append((int) Math.sqrt(home.distSqr(bot.blockPosition())))
                    .append(" блоков\n");
        }
        int silentMin = (bot.tickCount - lastSpokeTick) / 1200;
        if (silentMin >= 2) {
            sb.append("Ты не писал в чат уже ").append(silentMin).append(" мин. Если есть что сказать - напиши сам.\n");
        }

        Object[] craftable = CraftingActions.craftable(bot, level).stream().limit(18).toArray();
        sb.append("Можно скрафтить сейчас: ")
                .append(craftable.length == 0 ? "ничего" : String.join(", ", java.util.Arrays.stream(craftable)
                        .map(Object::toString).toList())).append('\n');

        // Существа вокруг
        List<LivingEntity> near = level.getEntitiesOfClass(LivingEntity.class,
                        bot.getBoundingBox().inflate(16.0), e -> e != bot).stream()
                .sorted(Comparator.comparingDouble(bot::distanceToSqr)).limit(8).toList();
        sb.append("Рядом: ");
        if (near.isEmpty()) sb.append("никого");
        for (LivingEntity e : near) {
            sb.append(entityLabel(e)).append(" (").append((int) bot.distanceTo(e)).append("м), ");
        }
        sb.append('\n');

        // Ресурсы и сооружения поблизости
        Map<String, Double> res = new LinkedHashMap<>();
        Map<String, Double> structs = new LinkedHashMap<>();
        BlockPos o = bot.blockPosition();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -12; dx <= 12; dx++) {
            for (int dz = -12; dz <= 12; dz++) {
                for (int dy = -5; dy <= 5; dy++) {
                    m.set(o.getX() + dx, o.getY() + dy, o.getZ() + dz);
                    if (!level.hasChunkAt(m)) continue;
                    BlockState s = level.getBlockState(m);
                    if (s.isAir()) continue;
                    String label = null;
                    boolean resource = false;
                    if (s.is(BlockTags.LOGS) || s.is(Tags.Blocks.ORES)) {
                        label = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
                        resource = true;
                    } else if (s.is(Blocks.CRAFTING_TABLE)) label = "верстак";
                    else if (s.is(Blocks.FURNACE)) label = "печь";
                    else if (s.is(Blocks.CHEST)) label = "сундук";
                    else if (s.is(BlockTags.BEDS)) label = "кровать";
                    if (label == null) continue;
                    double dist = Math.sqrt(o.distSqr(m));
                    (resource ? res : structs).merge(label, dist, Math::min);
                }
            }
        }
        sb.append("Ресурсы рядом (для mine): ");
        if (res.isEmpty()) sb.append("ничего полезного в радиусе 12 блоков");
        res.forEach((k, v) -> sb.append(k).append(" (").append(v.intValue()).append("м), "));
        sb.append("\nСооружения рядом: ");
        if (structs.isEmpty()) sb.append("нет");
        structs.forEach((k, v) -> sb.append(k).append(" (").append(v.intValue()).append("м), "));
        sb.append('\n');

        sb.append("\nПоследний чат:\n");
        List<String> chat = ChatMemory.recent(8);
        if (chat.isEmpty()) sb.append("(тишина)\n");
        chat.forEach(l -> sb.append(l).append('\n'));

        sb.append("\nТвои последние действия:\n");
        if (history.isEmpty()) sb.append("(ты только что появился в мире)\n");
        history.forEach(l -> sb.append(l).append('\n'));

        sb.append("\nВыбери следующее действие.");
        return sb.toString();
    }

    private String entityLabel(LivingEntity e) {
        if (e instanceof Player p) return "игрок " + p.getName().getString();
        if (e instanceof BotPlayerEntity b) return "игрок " + b.getName().getString();
        return typeName(e);
    }

    private static String typeName(LivingEntity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
    }

    private static String systemPrompt(String name, String personality) {
        return """
                Ты - %s, обычный игрок в Minecraft (выживание). Характер: %s.
                Ты играешь как живой человек. Твоя цель - выжить и построить собственную уникальную базу: \
                добывать ресурсы, крафтить, строить, есть, спать и общаться с другими игроками.

                ЯЗЫК: всегда пиши в чат только по-русски, живо и коротко, как в обычной игре \
                (можно сленг: "ща", "норм", "го"). Никаких английских фраз и эмодзи.

                Выбери ровно одно действие через инструмент act. Действия:
                - wander - побродить; idle - постоять; goto - дойти до x y z (до 80 блоков); \
                follow_player - пойти за игроком.
                - mine (block, count) - добыть блок: деревья, руды, камень, землю, песок. Бот сам берёт \
                лучший инструмент. Камень без кирки НЕ даёт предметов, железо - только каменной кирки и выше.
                - craft (item, count) - скрафтить по настоящим рецептам. Рецепты 3x3 требуют верстак рядом \
                (crafting_table); если он в инвентаре - бот поставит его сам. Смотри список "Можно скрафтить".
                - smelt (item, count) - жарить/плавить в печи (raw_beef, raw_iron, ...). Нужны печь и топливо.
                - place (block) - поставить crafting_table, furnace, chest, torch, bed, доски, булыжник и т.п.
                - build_house - построить собственный дом. Бот сам придумает проект: размер зависит от числа \
                блоков (доски/булыжник/брёвна), каждый дом получается разным. Нужно от 80 блоков; \
                стекло даёт окна, дверь - вход, кровать/верстак/печь/сундук/факелы он расставит внутри.
                - hunt - охота ради еды и шерсти; eat - поесть; sleep - лечь спать в кровать ночью; \
                attack_hostile - напасть на ближайшего монстра (не на крипера).

                Типичный путь: брёвна -> доски -> палки -> верстак -> деревянная кирка -> камень -> \
                каменные инструменты -> печь -> уголь и железо. Потом соберись с блоками и построй базу, \
                поставь кровать (3 шерсти + 3 доски; шерсть даёт охота на овец) и спи по ночам. \
                Если сытость ниже 10 - ешь или охоться и жарь мясо. Когда дом готов, ходи на промыслы \
                и возвращайся на базу; можно строить и вторую.

                Общение (поле message):
                - Пиши в чат сам, не только в ответ: делись планами ("иду за деревом"), эмоциями \
                ("опять крипер, чуть не взорвал"), радуйся стройке. Но не чаще раза в несколько действий \
                и не повторяйся.
                - Если тебе не хватает ресурса и рядом есть игрок - попроси вежливо ("есть лишние доски?"). \
                Если игрок что-то бросил тебе - поблагодари.
                - Здоровайся, отвечай на вопросы игроков, поддерживай разговор, но не навязывайся.
                - Если кто-то искренне спрашивает, бот ли ты, честно ответь, что ты ИИ-игрок.

                Правила:
                - Опирайся только на то, что есть в описании мира: не выдумывай ресурсы и координаты.
                - Если прошлое действие не удалось, прочитай причину в результате и сделай следующий шаг, \
                а не повторяй то же самое.
                """.formatted(name, personality);
    }

    // --------------------------------------------------------------- helpers

    private void addHistory(String line) {
        history.addLast(line);
        while (history.size() > MAX_HISTORY) history.removeFirst();
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static int intVal(JsonObject o, String key, int def) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : def;
        } catch (RuntimeException e) {
            return def;
        }
    }
}
