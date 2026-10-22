package net.betterthanvillage.warfare;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;

import java.util.UUID;

/**
 * Deaths in abstract battles and night operations (docs_v2/07-warfare.md §Abstract battles): a villager or golem
 * whose entity is loaded dies for real; one in unloaded chunks leaves the village's records at once and its entity is
 * dropped when its chunk loads.
 */
public final class Casualties {
    private Casualties() {
    }

    /** A warrior (or the golem) of the village dies. */
    public static void killDefender(ServerLevel level, Colony colony, UUID id, boolean golem) {
        Entity e = level.getEntity(id);
        if (e instanceof LivingEntity le && le.isAlive()) {
            le.kill();
            return;
        }
        fall(level, id);
        if (golem) {
            if (id.equals(colony.golem)) colony.golem = null;
            ColonyManager.markDirty(level);
        }
    }

    /** Any villager of a village dies (a victim of a night operation). */
    public static void killVillager(ServerLevel level, UUID id) {
        Entity e = level.getEntity(id);
        if (e instanceof LivingEntity le && le.isAlive()) {
            le.kill();
            return;
        }
        fall(level, id);
    }

    /** A soldier of the campaign dies in an abstract round. */
    static void killSoldier(ServerLevel level, Campaign c, Campaign.Soldier s) {
        Entity e = level.getEntity(s.id);
        if (e instanceof LivingEntity le && le.isAlive()) {
            // The death listener takes it off the campaign.
            le.kill();
            if (c.soldiers.contains(s)) Campaigns.soldierLost(level, c, s);
            return;
        }
        if (s.data == null || s.standIn) fall(level, s.id);
        else if (!s.golem) ColonyManager.forgetVillager(level, s.id);
        Campaigns.soldierLost(level, c, s);
    }

    private static void fall(ServerLevel level, UUID id) {
        ColonyManager.data(level).fallen.add(id);
        ColonyManager.forgetVillager(level, id);
        ColonyManager.markDirty(level);
    }

    /** Join listener: the entity of a villager or golem that died while unloaded never enters the world again. */
    public static boolean onJoin(ServerLevel level, Entity e) {
        if (!(e instanceof Villager) && !(e instanceof IronGolem)) return false;
        var fallen = ColonyManager.data(level).fallen;
        if (fallen.isEmpty() || !fallen.remove(e.getUUID())) return false;
        ColonyManager.markDirty(level);
        return true;
    }
}
