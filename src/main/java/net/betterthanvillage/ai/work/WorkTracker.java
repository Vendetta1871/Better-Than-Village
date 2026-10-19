package net.betterthanvillage.ai.work;

import net.betterthanvillage.ai.VillagerUtil;
import net.betterthanvillage.ai.nav.AntiStuck;
import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.gather.MinerTask;
import net.betterthanvillage.construction.HouseConstructionTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Per-villager work statistics, status line and the 60-second "no result" watchdog. */
public final class WorkTracker {
    public static final String STATUS_DELIVERED = "доставка на склад";
    public static final String STATUS_STORAGE_FULL = "склад заполнен";
    public static final String STATUS_MINING = "добыча/расчистка";
    public static final String STATUS_WATCHDOG = "нет результата 60 с: повторный выбор работы";

    private static final Map<Villager, Stats> STATS = new WeakHashMap<>();

    public static final class Stats {
        public String status = "";
        public int worked;
        public int delivered;
        public final Map<Item, Integer> deliveredItems = new LinkedHashMap<>();
        public long lastProgress = Long.MIN_VALUE;
        public int noResult;
        public int resets;
    }

    private WorkTracker() {
    }

    public static Stats stats(Villager v) {
        return STATS.computeIfAbsent(v, k -> new Stats());
    }

    public static void status(Villager v, String status) {
        stats(v).status = status;
    }

    public static String status(Villager v) {
        Stats s = STATS.get(v);
        return s == null ? "" : s.status;
    }

    /** Successful work: credit, refresh progress time and reset the watchdog. */
    public static void credit(Villager v, String label, int count) {
        Stats s = stats(v);
        s.status = label;
        s.worked += count;
        s.lastProgress = v.level().getGameTime();
        s.noResult = 0;
    }

    /** Marks progress without changing statistics. */
    public static void progress(Villager v) {
        Stats s = stats(v);
        s.lastProgress = v.level().getGameTime();
        s.noResult = 0;
    }

    public static long lastProgress(Villager v) {
        Stats s = STATS.get(v);
        return s == null ? Long.MIN_VALUE / 2 : s.lastProgress;
    }

    /** Deposits a stack into colony storage with statistics. Returns the number moved. */
    public static int deliver(ServerLevel level, Colony colony, Villager v, ItemStack stack) {
        int before = stack.getCount();
        Item item = stack.getItem();
        ColonyStorage.deposit(level, colony, stack);
        int moved = before - stack.getCount();
        if (moved > 0) {
            Stats s = stats(v);
            s.delivered += moved;
            s.deliveredItems.merge(item, moved, Integer::sum);
            credit(v, STATUS_DELIVERED, moved);
        } else if (before > 0) {
            status(v, STATUS_STORAGE_FULL);
        }
        return moved;
    }

    /** Watchdog, called from the villager tick. */
    public static void watchdog(ServerLevel level, Colony colony, Villager v) {
        long now = level.getGameTime();
        if ((now + v.getId()) % 20 != 0) return;
        long t = Math.floorMod(level.getDayTime(), 24000L);
        if (v.isSleeping() || t < 1000 || t >= 9000 || colony.roleOf(v.getUUID()) == ColonyRole.GUARD) return;
        Stats s = stats(v);
        boolean modTask = false;
        boolean exempt = false;
        for (BehaviorControl<? super Villager> b : v.getBrain().getRunningBehaviors()) {
            if (VillagerUtil.isModTask(b)) {
                modTask = true;
                if (b instanceof MinerTask || b instanceof HouseConstructionTask) exempt = true;
            }
        }
        if (!modTask) return;
        s.noResult += 20;
        if (s.noResult >= 1200 && !exempt) {
            Nav.blacklistCurrentAndStop(v, now, 600);
            VillagerUtil.stopModTasks(level, v);
            s.noResult = 0;
            s.resets++;
            s.status = STATUS_WATCHDOG;
            AntiStuck.suppress(v, now + 100);
        }
    }
}
