package net.betterthanvillage.colony;

import net.minecraft.world.entity.npc.VillagerProfession;

import java.util.List;
import java.util.Locale;

/** Colony roles, in the fixed ordinal order used throughout the mod. */
public enum ColonyRole {
    BUILDER,
    GUARD,
    LUMBERJACK,
    MINER,
    FARMER,
    FISHERMAN,
    RANCHER,
    ELDER,
    FOUNDER,
    CITIZEN;

    /** Work roles in the fixed apportionment order. */
    public static final List<ColonyRole> WORK_ROLES = List.of(BUILDER, FARMER, LUMBERJACK, MINER, FISHERMAN, RANCHER, GUARD);

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String translationKey() {
        return "entity.better_than_village.role." + serializedName();
    }

    public static ColonyRole byName(String name) {
        if (name == null) {
            return CITIZEN;
        }
        for (ColonyRole role : values()) {
            if (role.serializedName().equalsIgnoreCase(name)) {
                return role;
            }
        }
        return CITIZEN;
    }

    public VillagerProfession profession() {
        return switch (this) {
            case BUILDER -> VillagerProfession.MASON;
            case GUARD -> VillagerProfession.ARMORER;
            case LUMBERJACK, MINER -> VillagerProfession.TOOLSMITH;
            case FARMER -> VillagerProfession.FARMER;
            case FISHERMAN -> VillagerProfession.FISHERMAN;
            case RANCHER -> VillagerProfession.SHEPHERD;
            case ELDER, FOUNDER -> VillagerProfession.CARTOGRAPHER;
            case CITIZEN -> VillagerProfession.NONE;
        };
    }

    /** Storage kind that a role considers its own (trade stock, market sales). */
    public StorageKind ownKind() {
        return switch (this) {
            case BUILDER, LUMBERJACK -> StorageKind.WOOD;
            case MINER -> StorageKind.MINING;
            case FARMER, FISHERMAN -> StorageKind.FOOD;
            case RANCHER -> StorageKind.ANIMAL;
            default -> StorageKind.GENERAL;
        };
    }
}
