package net.betterthanvillage.ai;

import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.ai.work.WorkTracker;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.StorageKind;
import net.betterthanvillage.colony.StoragePlacer;
import net.betterthanvillage.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Builder task: places the colony's first (GENERAL) double chest and seeds it. */
public final class StarterWarehouseTask extends ColonyTask {
    public static final String STATUS_NO_SITE = "нет безопасного места для начального склада";

    private BlockPos site;
    private long nextSearch;
    private long actionStart = -1;
    private ItemStack previousHand = ItemStack.EMPTY;

    public StarterWarehouseTask() {
        super(2400);
    }

    @Override
    protected boolean shouldRun(ServerLevel level, Villager v, Colony colony) {
        return colony.roleOf(v.getUUID()) == ColonyRole.BUILDER && ColonyManager.terrainChangesAllowed(level) && colony.needsStarterWarehouse();
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Villager v, long time) {
        Colony colony = colony(level, v);
        return colony != null && shouldRun(level, v, colony);
    }

    @Override
    protected void start(ServerLevel level, Villager v, long time) {
        site = null;
        nextSearch = time;
        actionStart = -1;
        previousHand = v.getMainHandItem().copy();
        v.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CHEST));
    }

    @Override
    protected void tick(ServerLevel level, Villager v, long time) {
        Colony colony = colony(level, v);
        if (colony == null) return;
        if (site == null || !StoragePlacer.validSite(level, colony, site)) {
            site = null;
            actionStart = -1;
            if (time < nextSearch) return;
            nextSearch = time + 100;
            site = StoragePlacer.findSite(level, colony);
            if (site == null) {
                WorkTracker.status(v, STATUS_NO_SITE);
                return;
            }
        }
        v.getBrain().setMemory(ModRegistries.BUILD_TARGET_POS.get(), site);
        if (v.blockPosition().distSqr(site) > 9) {
            actionStart = -1;
            Nav.approach(level, v, site, 0.7);
            return;
        }
        v.getNavigation().stop();
        v.getLookControl().setLookAt(site.getX() + 0.5, site.getY() + 0.5, site.getZ() + 0.5);
        if (actionStart < 0) {
            actionStart = time;
        }
        if ((time - actionStart) % 6 == 0) {
            v.swing(InteractionHand.MAIN_HAND);
        }
        if (time - actionStart < 20) return;
        if (StoragePlacer.placeDoubleChest(level, colony, site, StorageKind.GENERAL)) {
            colony.starterWarehousePlaced = true;
            ColonyStorage.deposit(level, colony, new ItemStack(Items.OAK_LOG, 16));
            ColonyStorage.deposit(level, colony, new ItemStack(Items.WHEAT_SEEDS, 16));
            ColonyStorage.recount(level, colony);
            WorkTracker.credit(v, "начальный склад", 1);
            ColonyManager.markDirty(level);
        } else {
            site = null;
            actionStart = -1;
        }
    }

    @Override
    protected void stop(ServerLevel level, Villager v, long time) {
        if (v.getMainHandItem().is(Items.CHEST)) {
            v.setItemSlot(EquipmentSlot.MAINHAND, previousHand);
        }
        previousHand = ItemStack.EMPTY;
        v.getBrain().eraseMemory(ModRegistries.BUILD_TARGET_POS.get());
        site = null;
        actionStart = -1;
    }
}
