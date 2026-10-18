package net.betterthanvillage.construction;

import net.betterthanvillage.colony.Colony;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;

/** Colony ownership of blocks and restore snapshots for damage detection (05 §6, 02 §2.5) (stub). */
public final class Damage {
    private Damage() {
    }

    /** Colony-owned test (land plot no; managed storage/core yes; owned completed job yes; player block no; wall yes; job box yes; protected house piece). */
    public static boolean isColonyOwned(ServerLevel level, Colony colony, BlockPos pos) {
        return false;
    }

    /** Position lies in a protected vanilla village house piece (template path contains /houses/, not farm/pen/animal). */
    public static boolean isProtectedHousePiece(ServerLevel level, BlockPos pos) {
        return false;
    }

    /** Restore snapshot of a state at pos: empty for air/replaceable/crops/path/farmland, both halves for doors and beds. */
    public static Map<BlockPos, BlockState> restoreSnapshot(ServerLevel level, BlockPos pos, BlockState state) {
        return Map.of();
    }

    /** Records snapshot entries whose current world state differs into the colony repair map (put-if-absent). */
    public static void recordRepairs(ServerLevel level, Colony colony, Map<BlockPos, BlockState> snapshot) {
    }
}
