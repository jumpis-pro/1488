package com.example.aiplayers.ai;

import com.example.aiplayers.BotPlayerEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/** Исполняет действие, выбранное мозгом. Пока действия нет - работает обычное блуждание. */
public class LlmActionGoal extends Goal {

    private final BotPlayerEntity bot;

    public LlmActionGoal(BotPlayerEntity bot) {
        this.bot = bot;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return bot.brain().currentAction() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return bot.brain().currentAction() != null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        BotActions.Action action = bot.brain().currentAction();
        if (action != null && action.tick()) {
            bot.brain().onActionFinished(action.result());
        }
    }

    @Override
    public void stop() {
        bot.getNavigation().stop();
        bot.setSprinting(false);
    }
}
