package com.example.aiplayers;

import com.example.aiplayers.ai.BotBrain;
import com.example.aiplayers.ai.BuildActions;
import com.example.aiplayers.ai.LlmActionGoal;
import com.example.aiplayers.goal.HumanQuirksGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * ИИ-игрок. Тело - моб с внешностью игрока, инвентарём и голодом.
 * Рефлексы (бой, двери, криперы, автоэкипировка) - здесь, решения верхнего уровня - в {@link BotBrain}.
 */
public class BotPlayerEntity extends PathfinderMob {

    private static final String[] NAMES = {
            "Steve_Builder", "Kolya", "xX_Miner_Xx", "Dasha", "Creeper_Slayer", "Artem",
            "NoobMaster", "Lena", "DiamondHunter", "Vanya", "Pixel", "Sasha", "RedstoneGuy",
            "Max", "Nastya", "Wolfie", "Ghost", "Timur", "Zhenya", "Misha_Krab", "Oleg_Stroy",
            "Katya", "Dimon", "Sergey_Miner", "Ilya", "Vika", "Gosha"
    };

    private static final String[] PERSONALITIES = {
            "любопытный и дружелюбный, любишь болтать с игроками",
            "спокойный и расчётливый, строишь аккуратно и надолго",
            "азартный шахтёр, обожаешь руды и пещеры",
            "осторожный, немного трусоват, всегда следишь за здоровьем",
            "весельчак, шутишь и подкалываешь",
            "молчун, пишешь в чат редко и только по делу",
            "перфекционист-архитектор, мечтаешь о красивой базе",
            "заботливый, любишь делиться и помогать другим игрокам"
    };

    private static final EntityDataAccessor<Boolean> DATA_SNEAK =
            SynchedEntityData.defineId(BotPlayerEntity.class, EntityDataSerializers.BOOLEAN);
    private static final int SNEAK_ANIM = 36;

    /** Личный инвентарь бота. */
    public final SimpleContainer inventory = new SimpleContainer(36);

    private final BotBrain brain = new BotBrain(this);
    private float food = 20.0F;
    private String personality = PERSONALITIES[0];
    private BlockPos home;

    // Приветствие приседанием: [0]=нажат ли шифт, [1]=число нажатий в окне, [2]=тик начала окна
    private final Map<UUID, int[]> sneakTrack = new HashMap<>();
    private int greetDelay, sneakAnim, greetCooldown, lastHurtNote, lastTorchTick = -1000;
    private Player greetTarget;

    public BotPlayerEntity(EntityType<? extends BotPlayerEntity> type, Level level) {
        super(type, level);
        setCanPickUpLoot(true);
        setPersistenceRequired();
        if (getNavigation() instanceof GroundPathNavigation nav) {
            nav.setCanOpenDoors(true);
            nav.setCanFloat(true);
        }
    }

    public BotBrain brain() {
        return brain;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.ATTACK_DAMAGE, 1.0)
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new AvoidEntityGoal<>(this, Creeper.class, 8.0F, 1.0, 1.3));
        // Здоровье почти на нуле - убегает от монстров, а не лезет в драку
        goalSelector.addGoal(1, new AvoidEntityGoal<>(this, Monster.class, e -> getHealth() < 7.0F,
                12.0F, 1.1, 1.5, e -> true));
        goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.2, true));
        goalSelector.addGoal(3, new OpenDoorGoal(this, true));
        goalSelector.addGoal(4, new LlmActionGoal(this));
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 1.0));
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 10.0F, 0.04F));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        goalSelector.addGoal(8, new HumanQuirksGoal(this));

        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(
                this, Monster.class, 10, true, false, e -> !(e instanceof Creeper)));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SNEAK, false);
    }

    /** Присед для отрисовки (клиент читает это значение). */
    public boolean isSneakVisual() {
        return entityData.get(DATA_SNEAK);
    }

    public void setSneakVisual(boolean sneak) {
        entityData.set(DATA_SNEAK, sneak);
    }

    public String personality() {
        return personality;
    }

    public BlockPos home() {
        return home;
    }

    public void setHome(BlockPos pos) {
        this.home = pos;
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        brain.tick();
        survivalTick();
        greetingTick();
    }

    // -------------------------------------------------------------- survival

    public float food() {
        return food;
    }

    public void addFood(float amount) {
        food = Math.min(20.0F, food + amount);
    }

    /** Голод, естественная регенерация и автоэкипировка брони - как у настоящего игрока. */
    private void survivalTick() {
        if (isSleeping()) {
            food -= 0.00025F;
        } else {
            food -= getDeltaMovement().horizontalDistanceSqr() > 1.0E-4 ? 0.0015F : 0.0007F;
        }
        food = Math.max(0.0F, food);

        if (tickCount % 80 == 0) {
            if (food >= 18.0F && getHealth() < getMaxHealth()) {
                heal(1.0F);
            } else if (food <= 0.0F && getHealth() > 10.0F) {
                hurt(damageSources().starve(), 1.0F);
            }
        }
        if (tickCount % 100 == 0) equipArmor();

        // В темноте ставит факел, как игрок
        if (tickCount % 100 == 50 && !isSleeping() && level() instanceof ServerLevel sl
                && tickCount - lastTorchTick > 400
                && sl.getMaxLocalRawBrightness(blockPosition()) < 4
                && count(st -> st.is(Items.TORCH)) > 0
                && BuildActions.placeBlockNear(this, sl, Blocks.TORCH) != null) {
            lastTorchTick = tickCount;
        }
    }

    /**
     * Реакция на приветствие: если игрок рядом быстро приседает (2+ раза), бот через долю секунды
     * поворачивается к нему и приседает в ответ три раза.
     */
    private void greetingTick() {
        if (greetCooldown > 0) greetCooldown--;

        if (sneakAnim > 0) {
            sneakAnim--;
            int phase = (SNEAK_ANIM - sneakAnim) / 6;
            setSneakVisual(sneakAnim > 0 && phase % 2 == 0);
            if (greetTarget != null && greetTarget.isAlive()) getLookControl().setLookAt(greetTarget, 40.0F, 40.0F);
            return;
        }
        if (greetDelay > 0) {
            if (--greetDelay == 0) sneakAnim = SNEAK_ANIM;
            return;
        }
        if (isSleeping() || tickCount % 2 != 0 || greetCooldown > 0) return;

        for (Player p : level().getEntitiesOfClass(Player.class, getBoundingBox().inflate(8.0), pl -> !pl.isSpectator())) {
            int[] t = sneakTrack.computeIfAbsent(p.getUUID(), k -> new int[3]);
            boolean sneaking = p.isShiftKeyDown();
            if (sneaking && t[0] == 0) {
                if (tickCount - t[2] > 60) {
                    t[1] = 0;
                    t[2] = tickCount;
                }
                t[1]++;
            }
            t[0] = sneaking ? 1 : 0;
            if (t[1] >= 2) {
                t[1] = 0;
                greetTarget = p;
                greetDelay = 8 + random.nextInt(25);
                greetCooldown = 200;
                brain.noteEvent("игрок " + p.getName().getString()
                        + " поприседал тебе в знак приветствия, и ты ответил приседанием", random.nextInt(10) < 7);
                break;
            }
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (isSleeping() && !level().isClientSide) stopSleeping(); // как игрок: удар будит
        if (!level().isClientSide && source.getEntity() != null && tickCount - lastHurtNote > 100) {
            lastHurtNote = tickCount;
            brain.noteEvent("тебя атаковал " + source.getEntity().getName().getString()
                    + ", здоровье " + (int) getHealth(), getHealth() < 10.0F);
        }
        return super.hurt(source, amount);
    }

    // ------------------------------------------------------------- inventory

    public int count(Predicate<ItemStack> p) {
        int n = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (!s.isEmpty() && p.test(s)) n += s.getCount();
        }
        return n;
    }

    /** Забирает ровно {@code amount} предметов; если не хватает - ничего не трогает. */
    public boolean consume(Predicate<ItemStack> p, int amount) {
        if (count(p) < amount) return false;
        int left = amount;
        for (int i = 0; i < inventory.getContainerSize() && left > 0; i++) {
            ItemStack s = inventory.getItem(i);
            if (s.isEmpty() || !p.test(s)) continue;
            int t = Math.min(left, s.getCount());
            s.shrink(t);
            left -= t;
        }
        return true;
    }

    public void giveItem(ItemStack stack) {
        ItemStack rest = inventory.addItem(stack);
        if (!rest.isEmpty()) spawnAtLocation(rest);
    }

    /** Меняет местами предмет в основной руке и в слоте инвентаря. */
    public void swapMainHand(int slot) {
        ItemStack in = inventory.getItem(slot);
        ItemStack old = getMainHandItem();
        inventory.setItem(slot, old);
        setItemSlot(EquipmentSlot.MAINHAND, in);
    }

    /** Берёт в руку самый быстрый инструмент для этого блока (кирка для камня, топор для дерева...). */
    public void equipToolFor(BlockState state) {
        float best = getMainHandItem().getDestroySpeed(state);
        int bestSlot = -1;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (s.isEmpty()) continue;
            float sp = s.getDestroySpeed(state);
            if (sp > best + 0.01F) {
                best = sp;
                bestSlot = i;
            }
        }
        if (bestSlot >= 0) swapMainHand(bestSlot);
    }

    private static int weaponScore(ItemStack s) {
        if (s.getItem() instanceof SwordItem) return 1000 + s.getMaxDamage();
        if (s.getItem() instanceof AxeItem) return 500 + s.getMaxDamage();
        return 0;
    }

    public void equipBestWeapon() {
        int best = weaponScore(getMainHandItem());
        int bestSlot = -1;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            int sc = weaponScore(inventory.getItem(i));
            if (sc > best) {
                best = sc;
                bestSlot = i;
            }
        }
        if (bestSlot >= 0) swapMainHand(bestSlot);
    }

    @Override
    public void setTarget(LivingEntity target) {
        super.setTarget(target);
        if (target != null && !level().isClientSide) equipBestWeapon();
    }

    private void equipArmor() {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (!(s.getItem() instanceof ArmorItem a)) continue;
            EquipmentSlot slot = a.getEquipmentSlot();
            if (slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) continue;
            ItemStack cur = getItemBySlot(slot);
            int curDef = cur.getItem() instanceof ArmorItem c ? c.getDefense() : -1;
            if (a.getDefense() > curDef) {
                inventory.setItem(i, cur);
                setItemSlot(slot, s);
            }
        }
    }

    /** Подобранные предметы кладём в инвентарь (по умолчанию мобы берут только экипировку). */
    @Override
    protected void pickUpItem(ItemEntity itemEntity) {
        ItemStack stack = itemEntity.getItem();
        ItemStack remaining = inventory.addItem(stack.copy());
        int taken = stack.getCount() - remaining.getCount();
        if (taken > 0) {
            take(itemEntity, taken);
            if (remaining.isEmpty()) itemEntity.discard();
            else itemEntity.setItem(remaining);
        }
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, source, recentlyHit);
        for (ItemStack stack : inventory.removeAllItems()) spawnAtLocation(stack);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.put("BotInventory", inventory.createTag(registryAccess()));
        tag.putFloat("BotFood", food);
        tag.putString("BotPersonality", personality);
        if (home != null) tag.putLong("BotHome", home.asLong());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("BotInventory")) inventory.fromTag(tag.getList("BotInventory", 10), registryAccess());
        if (tag.contains("BotFood")) food = tag.getFloat("BotFood");
        if (tag.contains("BotPersonality")) personality = tag.getString("BotPersonality");
        if (tag.contains("BotHome")) home = BlockPos.of(tag.getLong("BotHome"));
    }

    // ------------------------------------------------------------------ look

    /** Случайное имя и стартовые вещи. Скин определяется по UUID на клиенте. */
    public void randomize(RandomSource r) {
        String name = NAMES[r.nextInt(NAMES.length)];
        if (r.nextInt(3) == 0) { // цифры в конце, но ник не длиннее 16 символов (ограничение Tab)
            String num = String.valueOf(r.nextInt(100));
            name = name.length() + num.length() > 16 ? name.substring(0, 16 - num.length()) + num : name + num;
        }
        personality = PERSONALITIES[r.nextInt(PERSONALITIES.length)];
        setCustomName(Component.literal(name));
        setCustomNameVisible(true);

        inventory.addItem(new ItemStack(Items.BREAD, 4 + r.nextInt(5)));
        if (r.nextFloat() < 0.4F) setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_AXE));
        for (EquipmentSlot slot : EquipmentSlot.values()) setDropChance(slot, 0.05F);
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }
}
