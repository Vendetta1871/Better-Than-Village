package net.betterthanvillage.colony;

import net.minecraft.world.entity.npc.VillagerProfession;

import java.util.List;
import java.util.Locale;

/**
 * Colony professions (docs_v2/02-professions.md). A villager keeps its profession for life; the elder office is
 * the only change. FOUNDER is a temporary expedition state (replaced by search parties in phase 2) and CITIZEN
 * marks villagers without a profession yet (children, unassigned newcomers).
 */
public enum ColonyRole {
    ELDER,
    WARRIOR,
    FARMER,
    HERDER,
    MASON,
    CARPENTER,
    SMITH,
    HEALER,
    MINER,
    LIBRARIAN,
    FOUNDER,
    CITIZEN;

    /** Trades in the order unemployed starting villagers are given them (02 §Generation rules). */
    public static final List<ColonyRole> STARTER_ORDER = List.of(FARMER, WARRIOR, CARPENTER, MASON, MINER, HERDER, SMITH, HEALER, LIBRARIAN);

    /** Every profession a villager can hold for life (the elder office excluded). */
    public static final List<ColonyRole> TRADES = List.of(WARRIOR, FARMER, HERDER, MASON, CARPENTER, SMITH, HEALER, MINER, LIBRARIAN);

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String translationKey() {
        return "entity.better_than_village.role." + serializedName();
    }

    /** True for a profession (a trade or the elder office). */
    public boolean isProfession() {
        return this != FOUNDER && this != CITIZEN;
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
        // Role names saved before the v2 professions.
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "builder" -> MASON;
            case "guard" -> WARRIOR;
            case "lumberjack" -> CARPENTER;
            case "fisherman", "rancher" -> HERDER;
            default -> CITIZEN;
        };
    }

    /** The vanilla profession that gives the villager its look. */
    public VillagerProfession profession() {
        return switch (this) {
            case ELDER, FOUNDER -> VillagerProfession.CARTOGRAPHER;
            case WARRIOR -> VillagerProfession.WEAPONSMITH;
            case FARMER -> VillagerProfession.FARMER;
            case HERDER -> VillagerProfession.SHEPHERD;
            case MASON -> VillagerProfession.MASON;
            case CARPENTER -> VillagerProfession.FLETCHER;
            case SMITH -> VillagerProfession.ARMORER;
            case HEALER -> VillagerProfession.CLERIC;
            case MINER -> VillagerProfession.TOOLSMITH;
            case LIBRARIAN -> VillagerProfession.LIBRARIAN;
            case CITIZEN -> VillagerProfession.NONE;
        };
    }

    /**
     * The profession a generated villager with this vanilla profession gets; null for unemployed villagers and
     * nitwits. Cartographers become the elder (the caller turns extra cartographers into librarians).
     */
    public static ColonyRole fromVanilla(VillagerProfession profession) {
        if (profession == VillagerProfession.FARMER) return FARMER;
        if (profession == VillagerProfession.SHEPHERD || profession == VillagerProfession.BUTCHER
                || profession == VillagerProfession.LEATHERWORKER || profession == VillagerProfession.FISHERMAN) return HERDER;
        if (profession == VillagerProfession.MASON) return MASON;
        if (profession == VillagerProfession.FLETCHER) return CARPENTER;
        if (profession == VillagerProfession.ARMORER) return SMITH;
        if (profession == VillagerProfession.TOOLSMITH) return MINER;
        if (profession == VillagerProfession.WEAPONSMITH) return WARRIOR;
        if (profession == VillagerProfession.CLERIC) return HEALER;
        if (profession == VillagerProfession.LIBRARIAN) return LIBRARIAN;
        if (profession == VillagerProfession.CARTOGRAPHER) return ELDER;
        return null;
    }

    /** Storage kind that a role considers its own (trade stock, market sales). */
    public StorageKind ownKind() {
        return switch (this) {
            case MASON, CARPENTER -> StorageKind.WOOD;
            case MINER, SMITH -> StorageKind.MINING;
            case FARMER -> StorageKind.FOOD;
            case HERDER -> StorageKind.ANIMAL;
            default -> StorageKind.GENERAL;
        };
    }
}
