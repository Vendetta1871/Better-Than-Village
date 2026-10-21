package net.betterthanvillage.debug;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A survival "player" for headless server tests (/btv bot): a fake player added to the level, so villagers see it,
 * the law applies to it and warriors fight it. It ticks like a player (physics, attack cooldown), logs every message
 * it is sent and, when killed, fires the death event and gets up again at its spawn point.
 */
public final class TestBot extends FakePlayer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, TestBot> BOTS = new LinkedHashMap<>();

    private BlockPos home;
    private boolean tickFailed;

    private TestBot(ServerLevel level, GameProfile profile) {
        super(level, profile);
    }

    public static TestBot spawn(ServerLevel level, String name, BlockPos pos) {
        remove(name);
        UUID id = UUID.nameUUIDFromBytes(("btv-bot:" + name).getBytes(StandardCharsets.UTF_8));
        TestBot bot = new TestBot(level, new GameProfile(id, name));
        bot.home = pos.immutable();
        bot.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        level.addNewPlayer(bot);
        BOTS.put(name, bot);
        LOGGER.info("[bot {}] spawned at {}", name, pos.toShortString());
        return bot;
    }

    public static TestBot get(String name) {
        TestBot bot = BOTS.get(name);
        return bot != null && !bot.isRemoved() ? bot : null;
    }

    public static Collection<TestBot> all() {
        return BOTS.values();
    }

    public static void remove(String name) {
        TestBot bot = BOTS.remove(name);
        if (bot != null && !bot.isRemoved()) bot.serverLevel().removePlayerImmediately(bot, Entity.RemovalReason.DISCARDED);
    }

    public static void clear() {
        BOTS.clear();
    }

    private String botName() {
        return getGameProfile().getName();
    }

    @Override
    public void tick() {
        if (invulnerableTime > 0) invulnerableTime--;
        spawnInvulnerableTime = 0;
        if (tickFailed) return;
        try {
            doTick();
        } catch (RuntimeException e) {
            // A player tick may touch the (dummy) connection; the bot then stays a static target.
            tickFailed = true;
            LOGGER.warn("[bot {}] tick failed, the bot no longer ticks", botName(), e);
        }
    }

    @Override
    public void displayClientMessage(Component text, boolean actionBar) {
        LOGGER.info("[bot {}] {}{}", botName(), actionBar ? "(bar) " : "", text.getString());
    }

    @Override
    public void sendSystemMessage(Component text) {
        displayClientMessage(text, false);
    }

    @Override
    public void sendSystemMessage(Component text, boolean actionBar) {
        displayClientMessage(text, actionBar);
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return false;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean hurt = super.hurt(source, amount);
        if (hurt) {
            Entity by = source.getEntity();
            LOGGER.info("[bot {}] hurt by {} for {} (health {})", botName(), by == null ? source.getMsgId() : by.getType().toShortString(),
                    amount, getHealth());
        }
        return hurt;
    }

    @Override
    public void die(DamageSource source) {
        if (CommonHooks.onLivingDeath(this, source)) return;
        Entity by = source.getEntity();
        LOGGER.info("[bot {}] died ({})", botName(), by == null ? source.getMsgId() : by.getType().toShortString());
        setHealth(getMaxHealth());
        clearFire();
        if (home != null) teleportTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
    }
}
