package net.betterthanvillage.construction;

import net.betterthanvillage.ai.ColonyTask;
import net.betterthanvillage.ai.VillagerUtil;
import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.ai.work.BlockWorkTimer;
import net.betterthanvillage.ai.work.WorkTracker;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.SafeEditScope;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.economy.Commissions;
import net.betterthanvillage.economy.WorkshopTask;
import net.betterthanvillage.gather.LumberjackTask;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The carpenter builds a planned lamp post ({@link LampPosts}): two fences and a lantern or a torch on top, all
 * from the treasury (public work). Fences are made from planks or a log on the spot if the treasury has none.
 */
public final class LampPostTask extends ColonyTask {
    static final String STATUS = "фонарный столб";
    static final String STATUS_NO_MATERIAL = "нет забора или факела для фонарного столба";

    /** Logs the treasury keeps for houses and repairs (its reserve, Needs.treasury). */
    private static final int LOG_RESERVE = 48;

    private final BlockWorkTimer timer = new BlockWorkTimer();
    private int built;

    public LampPostTask() {
        super(3000);
    }

    @Override
    public boolean publicWork(Villager v) {
        return true;
    }

    /**
     * The carpenter has a post to build, no house is under construction (houses come first) and the treasury can
     * pay (the lumber and workshop tasks then yield).
     */
    public static boolean wanted(ServerLevel level, Villager v, Colony colony) {
        return !v.isSleeping() && v.getTradingPlayer() == null && colony.roleOf(v.getUUID()) == ColonyRole.CARPENTER
                && ColonyManager.terrainChangesAllowed(level) && LampPosts.siteCount(colony) > 0 && !colony.hasActiveConstruction()
                && Commissions.canPay(level, colony) && hasMaterials(level, colony);
    }

    private static boolean hasMaterials(ServerLevel level, Colony colony) {
        java.util.List<ColonyStorage.Slot> treasury = ColonyStorage.treasury(level, colony);
        boolean posts = net.betterthanvillage.economy.Chests.count(treasury, s -> s.is(ItemTags.WOODEN_FENCES)) >= LampPosts.FENCES
                || net.betterthanvillage.economy.Chests.count(treasury, s -> s.is(ItemTags.PLANKS)) >= 2 * LampPosts.FENCES
                || net.betterthanvillage.economy.Chests.count(treasury, s -> s.is(ItemTags.LOGS)) > LOG_RESERVE;
        boolean light = net.betterthanvillage.economy.Chests.count(treasury, s -> s.is(Items.TORCH) || s.is(Items.LANTERN)
                || s.is(Items.COAL) || s.is(Items.CHARCOAL)) > 0;
        return posts && light;
    }

    @Override
    protected boolean shouldRun(ServerLevel level, Villager v, Colony colony) {
        return wanted(level, v, colony) && !VillagerUtil.isRunning(v, LumberjackTask.class) && !VillagerUtil.isRunning(v, WorkshopTask.class);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Villager v, long now) {
        Colony colony = colony(level, v);
        return colony != null && wanted(level, v, colony) && built < 4;
    }

    @Override
    protected void start(ServerLevel level, Villager v, long now) {
        built = 0;
    }

    @Override
    protected void stop(ServerLevel level, Villager v, long now) {
        timer.cancel(level);
    }

    @Override
    protected void tick(ServerLevel level, Villager v, long now) {
        if (now % 5 != 0) return;
        Colony colony = colony(level, v);
        if (colony == null) return;
        BlockPos site = LampPosts.next(level, colony, v.blockPosition());
        if (site == null) return;
        WorkTracker.status(v, STATUS);
        if (v.blockPosition().distSqr(site) > 9) {
            Nav.approach(level, v, site, 0.6, 6);
            if (Nav.isBlacklisted(v, site, now)) LampPosts.done(colony, site);
            return;
        }
        if (!LampPosts.valid(level, colony, site) || site.equals(v.blockPosition())) {
            LampPosts.done(colony, site);
            return;
        }
        if (!timer.tickAction(level, v, site, ColonyConfig.get().work.placement_ticks * 3)) return;
        timer.reset();
        if (!takePost(level, colony)) {
            WorkTracker.status(v, STATUS_NO_MATERIAL);
            LampPosts.done(colony, site);
            return;
        }
        BlockState light = takeLight(level, colony);
        if (light == null) {
            ColonyStorage.deposit(level, colony, new ItemStack(Items.OAK_FENCE, LampPosts.FENCES));
            WorkTracker.status(v, STATUS_NO_MATERIAL);
            return;
        }
        SafeEditScope.run(() -> {
            for (int i = 0; i < LampPosts.FENCES; i++) level.setBlock(site.above(i), Blocks.OAK_FENCE.defaultBlockState(), 3);
            level.setBlock(site.above(LampPosts.FENCES), light, 3);
        });
        LampPosts.done(colony, site);
        built++;
        v.swing(InteractionHand.MAIN_HAND);
        WorkTracker.credit(v, STATUS, LampPosts.FENCES + 1);
        ColonyManager.markDirty(level);
    }

    /** Fences for one post from the treasury (planks or a log make them when there are none). */
    private static boolean takePost(ServerLevel level, Colony colony) {
        if (ColonyStorage.count(level, colony, s -> s.is(ItemTags.WOODEN_FENCES)) >= LampPosts.FENCES) {
            return ColonyStorage.consume(level, colony, s -> s.is(ItemTags.WOODEN_FENCES), LampPosts.FENCES);
        }
        if (ColonyStorage.consume(level, colony, s -> s.is(ItemTags.PLANKS), 2 * LampPosts.FENCES)) return true;
        // One log makes the planks and sticks of a post; logs kept for houses and repairs are not used.
        if (ColonyStorage.count(level, colony, s -> s.is(ItemTags.LOGS)) <= LOG_RESERVE) return false;
        return ColonyStorage.consume(level, colony, s -> s.is(ItemTags.LOGS), 1);
    }

    /** A lantern if the treasury has one, else a torch (one coal makes four; the rest go to the treasury). */
    private static BlockState takeLight(ServerLevel level, Colony colony) {
        if (ColonyStorage.consume(level, colony, s -> s.is(Items.LANTERN), 1)) return Blocks.LANTERN.defaultBlockState();
        if (ColonyStorage.consume(level, colony, s -> s.is(Items.TORCH), 1)) return Blocks.TORCH.defaultBlockState();
        if (ColonyStorage.consume(level, colony, s -> s.is(Items.COAL) || s.is(Items.CHARCOAL), 1)) {
            ColonyStorage.deposit(level, colony, new ItemStack(Items.TORCH, 3));
            return Blocks.TORCH.defaultBlockState();
        }
        return null;
    }
}
