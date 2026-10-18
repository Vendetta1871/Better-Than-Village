package net.betterthanvillage.society;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lookup of generated vanilla village jigsaw pieces by their template id. */
final class VillagePieces {
    private static final Pattern ID = Pattern.compile("([a-z0-9_.-]+):([a-z0-9_./-]+)");

    private VillagePieces() {
    }

    /** Template path (e.g. {@code village/plains/houses/plains_small_house_1}) of a village piece containing pos, or null. */
    static String pieceAt(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        StructureStart start = level.structureManager().getStructureWithPieceAt(pos, StructureTags.VILLAGE);
        if (!start.isValid()) {
            return null;
        }
        for (StructurePiece piece : start.getPieces()) {
            if (!piece.getBoundingBox().isInside(pos) || !(piece instanceof PoolElementStructurePiece pool)) continue;
            if (!(pool.getElement() instanceof SinglePoolElement single)) continue;
            // SinglePoolElement exposes its template only through toString ("Single[Left[ns:path]]").
            Matcher m = ID.matcher(single.toString());
            if (m.find()) {
                return m.group(2);
            }
        }
        return null;
    }

    /** Village house, town centre, street or meeting point piece (land-plot exclusion, 06 §2.5). */
    static boolean inSettlementPiece(ServerLevel level, BlockPos pos) {
        String id = pieceAt(level, pos);
        return id != null && id.startsWith("village/")
                && (id.contains("/houses/") || id.contains("/town_centers/") || id.contains("/streets/") || id.contains("meeting_point"));
    }
}
