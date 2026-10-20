package net.betterthanvillage.economy;

import net.betterthanvillage.ai.ColonyTask;
import net.betterthanvillage.ai.VillagerUtil;
import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.ai.work.WorkTracker;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.gather.LumberjackTask;
import net.betterthanvillage.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Processing work at the home chest (docs_v2/02-professions.md): the villager walks home and turns inputs into
 * products by the {@link Workshop} recipes of its trade, a batch every few seconds. Products are delivered like
 * any production (levy in kind included).
 */
public final class WorkshopTask extends ColonyTask {
    public static final String STATUS = "мастерская: ";
    private static final int BATCH_TICKS = 60;
    private static final int MAX_BATCHES = 8;

    private long cooldown;
    private long nextBatch;
    private int batches;
    private boolean done;

    public WorkshopTask() {
        super(2400);
    }

    static boolean workshopTrade(ColonyRole role) {
        return role == ColonyRole.CARPENTER || role == ColonyRole.SMITH || role == ColonyRole.LIBRARIAN || role == ColonyRole.HEALER;
    }

    @Override
    protected boolean shouldRun(ServerLevel level, Villager v, Colony colony) {
        ColonyRole role = colony.roleOf(v.getUUID());
        if (v.isSleeping() || v.getTradingPlayer() != null || level.getGameTime() < cooldown || !workshopTrade(role)) return false;
        // A carpenter fells trees or works wood, never both at once.
        if (role == ColonyRole.CARPENTER && VillagerUtil.isRunning(v, LumberjackTask.class)) return false;
        Household h = colony.households.get(v.getUUID());
        if (h == null || !h.hasChest()) return false;
        return Workshop.ready(Workshop.recipes(role), ColonyStorage.usable(level, h)) != null;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Villager v, long time) {
        Colony colony = ColonyManager.colonyOf(level, v);
        return !done && !v.isSleeping() && v.getTradingPlayer() == null && colony != null && workshopTrade(colony.roleOf(v.getUUID()));
    }

    @Override
    protected void start(ServerLevel level, Villager v, long now) {
        done = false;
        batches = 0;
        nextBatch = now + BATCH_TICKS;
    }

    @Override
    protected void tick(ServerLevel level, Villager v, long now) {
        Colony colony = ColonyManager.colonyOf(level, v);
        Household h = colony == null ? null : colony.households.get(v.getUUID());
        if (h == null || !h.hasChest()) {
            done = true;
            return;
        }
        BlockPos chest = h.chests.get(0);
        v.getBrain().setMemory(ModRegistries.STORAGE_CHEST_POS.get(), chest);
        if (v.blockPosition().distSqr(chest) > 9) {
            if (!Nav.approach(level, v, chest, 0.7) && Nav.isBlacklisted(v, chest, now)) done = true;
            nextBatch = now + BATCH_TICKS;
            return;
        }
        v.getNavigation().stop();
        v.getLookControl().setLookAt(chest.getX() + 0.5, chest.getY() + 0.5, chest.getZ() + 0.5);
        if (now < nextBatch) return;
        nextBatch = now + BATCH_TICKS;
        List<ColonyStorage.Slot> slots = ColonyStorage.usable(level, h);
        Workshop.Recipe recipe = Workshop.ready(Workshop.recipes(colony.roleOf(v.getUUID())), slots);
        if (recipe == null) {
            done = true;
            return;
        }
        ItemStack product = Workshop.craft(recipe, slots);
        if (product.isEmpty()) {
            done = true;
            return;
        }
        v.swing(InteractionHand.MAIN_HAND);
        WorkTracker.deliver(level, colony, v, product);
        if (!product.isEmpty()) {
            // No room at home: the product stays in the villager's hands until the next delivery.
            v.getInventory().addItem(product);
        }
        WorkTracker.status(v, STATUS + recipe.name());
        if (++batches >= MAX_BATCHES) done = true;
    }

    @Override
    protected void stop(ServerLevel level, Villager v, long now) {
        v.getBrain().eraseMemory(ModRegistries.STORAGE_CHEST_POS.get());
        cooldown = now + (batches > 0 ? 200 : 600);
    }
}
