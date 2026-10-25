package com.example.aiplayers.goal;

import com.example.aiplayers.BotPlayerEntity;
import com.example.aiplayers.ai.LlmClient;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

/**
 * Мелкие "человеческие" привычки. Без флагов - работает параллельно с остальными целями:
 * прыжки на ходу, взмахи рукой, быстрые нажатия шифта, приветственный взмах, когда рядом
 * появляется игрок, и (если не подключён ИИ-мозг) редкие заготовленные реплики по-русски.
 */
public class HumanQuirksGoal extends Goal {

    private static final String[] PHRASES = {
            "кто-нибудь нашёл алмазы?",
            "ну и ночка...",
            "опять крипер подкрался",
            "строю дом, скоро закончу",
            "тут есть деревня неподалёку?",
            "хм, а где моя кирка",
            "вечер в хату",
            "кто со мной в шахту?"
    };

    private static final String[] HELLOS = {"привет!", "о, здарова", "хай", "привет, как жизнь?"};

    private final BotPlayerEntity bot;
    private int chatCooldown;
    private int tapTicks;
    private int waveDelay;
    private boolean playerWasNear;

    public HumanQuirksGoal(BotPlayerEntity bot) {
        this.bot = bot;
        this.chatCooldown = 3000 + bot.getRandom().nextInt(9000);
    }

    @Override
    public boolean canUse() {
        return true;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return false;
    }

    @Override
    public void tick() {
        if (bot.isSleeping()) return;
        var r = bot.getRandom();
        boolean idle = bot.getNavigation().isDone();

        // Прыжок на бегу
        if (bot.onGround() && !idle && r.nextInt(120) == 0) {
            bot.getJumpControl().jump();
        }

        // Взмах рукой (проверяет блок, чешется)
        if (r.nextInt(400) == 0) {
            bot.swing(InteractionHand.MAIN_HAND);
        }

        // Быстрое нажатие шифта, когда стоит без дела (как игроки, когда скучают)
        if (tapTicks > 0) {
            if (--tapTicks == 0) bot.setSneakVisual(false);
        } else if (idle && r.nextInt(900) == 0) {
            bot.setSneakVisual(true);
            tapTicks = 4 + r.nextInt(5);
        }

        // Игрок подошёл - бот оборачивается и иногда машет рукой
        Player near = bot.level().getNearestPlayer(bot, 7.0);
        if (near != null && !near.isSpectator()) {
            if (!playerWasNear) {
                playerWasNear = true;
                if (r.nextInt(100) < 40) waveDelay = 10 + r.nextInt(20);
                if (!LlmClient.enabled() && r.nextInt(100) < 25) {
                    bot.brain().speak(HELLOS[r.nextInt(HELLOS.length)]);
                }
            }
            if (waveDelay > 0 && --waveDelay == 0) {
                bot.getLookControl().setLookAt(near, 40.0F, 40.0F);
                bot.swing(InteractionHand.MAIN_HAND);
            }
        } else if (bot.level().getNearestPlayer(bot, 14.0) == null) {
            playerWasNear = false;
        }

        // Заготовленные реплики - только если нет ИИ-мозга (иначе пишет модель)
        if (--chatCooldown <= 0) {
            chatCooldown = 6000 + r.nextInt(12000);
            if (!LlmClient.enabled()) bot.brain().speak(PHRASES[r.nextInt(PHRASES.length)]);
        }
    }
}
