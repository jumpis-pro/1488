package com.example.aiplayers;

import com.example.aiplayers.ai.ChatMemory;
import com.example.aiplayers.ai.LlmClient;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

@Mod(AiPlayersMod.MODID)
public class AiPlayersMod {
    public static final String MODID = "aiplayers";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<BotPlayerEntity>> BOT =
            ENTITIES.register("bot", () -> EntityType.Builder
                    .of(BotPlayerEntity::new, MobCategory.MISC)
                    .sized(0.6F, 1.8F)          // размеры как у игрока
                    .eyeHeight(1.62F)
                    .clientTrackingRange(10)
                    .build("bot"));

    public AiPlayersMod(IEventBus modBus) {
        ENTITIES.register(modBus);
        modBus.addListener(AiPlayersMod::onAttributes);
        NeoForge.EVENT_BUS.addListener(BotCommands::register);
        NeoForge.EVENT_BUS.addListener(ChatMemory::onChat);
        NeoForge.EVENT_BUS.addListener(BotTabList::onJoin);
        NeoForge.EVENT_BUS.addListener(BotTabList::onLeave);
        NeoForge.EVENT_BUS.addListener(BotTabList::onServerTick);
        NeoForge.EVENT_BUS.addListener(BotTabList::onLogin);
        NeoForge.EVENT_BUS.addListener(BotTabList::onServerStopped);

        if (LlmClient.enabled()) {
            LOGGER.info("[aiplayers] LLM-мозг включён, модель: {}", LlmClient.model());
        } else {
            LOGGER.warn("[aiplayers] ANTHROPIC_API_KEY не задан - боты будут просто бродить без мозга");
        }
    }

    private static void onAttributes(EntityAttributeCreationEvent event) {
        event.put(BOT.get(), BotPlayerEntity.createAttributes().build());
    }
}
