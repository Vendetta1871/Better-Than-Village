package net.betterthanvillage.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.Comparator;

/**
 * Loads a small area for a moment so that something decided in the abstract world can happen with real blocks and
 * entities (docs_v2/08-simulation.md §Materialization): a search party founding its village, an army storming a
 * player's base. The ticket expires by itself; callers retry while the area is not entity-ticking yet.
 */
public final class SiteLoader {
    /** Chunks around the site that tick entities: (2 * REACH + 1)^2. */
    private static final int REACH = 2;
    /** Ticket level 33 - radius: entity ticking up to REACH chunks from the centre. */
    private static final int RADIUS = 2 + REACH;
    private static final TicketType<ChunkPos> TICKET = TicketType.create("better_than_village_site", Comparator.comparingLong(ChunkPos::toLong), 600);

    private SiteLoader() {
    }

    /** Requests the area around pos; true once its centre ticks entities (the caller acts then). */
    public static boolean load(ServerLevel level, BlockPos pos) {
        ChunkPos chunk = new ChunkPos(pos);
        level.getChunkSource().addRegionTicket(TICKET, chunk, RADIUS, chunk);
        return level.isPositionEntityTicking(pos);
    }

    /** Releases the area early (the ticket would expire anyway). */
    public static void release(ServerLevel level, BlockPos pos) {
        ChunkPos chunk = new ChunkPos(pos);
        level.getChunkSource().removeRegionTicket(TICKET, chunk, RADIUS, chunk);
    }

    /** Blocks from the centre that are loaded and ticking while the site is held. */
    public static int reach() {
        return REACH * 16;
    }
}
