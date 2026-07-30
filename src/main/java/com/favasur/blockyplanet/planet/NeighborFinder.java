package com.favasur.blockyplanet.planet;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles neighbor finding on the spherical planet.
 *
 * This is the MOST challenging part of the implementation, as described in the blog post.
 *
 * Challenges:
 * 1. Vertical shell boundaries: a block at the top of shell N maps to 4 blocks at the
 *    bottom of shell N+1. The inverse is also true — those 4 blocks share a single
 *    downward neighbor.
 * 2. Sector boundaries: when crossing between sectors, axes may be swapped or flipped
 *    depending on the edge pairing. Each of the 12 edge pairings has a specific
 *    transformation.
 *
 * Reference: https://www.favasur.com/posts/blocky-planet/#whos-my-neighbor
 */
public final class NeighborFinder {

    // ─── Axis Directions ─────────────────────────────────────────────────────

    public enum Direction {
        LEFT(-1, 0, 0),
        RIGHT(1, 0, 0),
        DOWN(0, -1, 0),
        UP(0, 1, 0),
        BACK(0, 0, -1),
        FRONT(0, 0, 1);

        public final int dx;
        public final int dy;
        public final int dz;

        Direction(int dx, int dy, int dz) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }

        /** Returns the opposite direction. */
        public Direction opposite() {
            return switch (this) {
                case LEFT -> RIGHT;
                case RIGHT -> LEFT;
                case DOWN -> UP;
                case UP -> DOWN;
                case BACK -> FRONT;
                case FRONT -> BACK;
            };
        }
    }

    // ─── Sector Face Definitions ─────────────────────────────────────────────
    //
    // Each sector is a square face on the cube. We define the local (u, v) axes
    // for each face. When crossing an edge between two sectors, the axes may be
    // swapped or flipped.
    //
    // The sector layout (cube net / unrolled cube):
    //
    //           ┌─────┐
    //           │ UP  │  (+Y)
    //           │     │
    //      ┌────┼─────┼────┬────┐
    //      │LEFT│FRONT│RIGHT│BACK│
    //      │(-X)│(+Z) │(+X) │(-Z) │
    //      └────┴─────┴────┴────┘
    //           │ DOWN│
    //           │(-Y) │
    //           └─────┘
    //
    // For each face, the local (u, v) axes are:
    //   FRONT (+Z): u=+X, v=+Y
    //   BACK  (-Z): u=-X, v=+Y
    //   LEFT  (-X): u=+Z, v=+Y
    //   RIGHT (+X): u=-Z, v=+Y
    //   UP    (+Y): u=+X, v=+Z
    //   DOWN  (-Y): u=+X, v=-Z

    /**
     * Edge pairing descriptor: when crossing from one sector to another,
     * this describes how the (u, v) axes transform.
     *
     * @param neighborSector The sector on the other side of the edge
     * @param ourEdgeAxis    Which of our edges is being crossed (0=u-, 1=u+, 2=v-, 3=v+)
     * @param swapAxes       Whether u and v are swapped in the neighbor's space
     * @param flipU          Whether u is flipped in the neighbor's space
     * @param flipV          Whether v is flipped in the neighbor's space
     */
    private record EdgeTransform(
        int neighborSector,
        int ourEdgeAxis,
        boolean swapAxes,
        boolean flipU,
        boolean flipV
    ) {}

    /**
     * For each sector (0-5), for each of the 4 edges (u-, u+, v-, v+),
     * define how to cross to the adjacent sector.
     *
     * Edge indices: 0 = -u, 1 = +u, 2 = -v, 3 = +v
     */
    private static final EdgeTransform[][] EDGE_TRANSFORMS = buildEdgeTransforms();

    private static EdgeTransform[][] buildEdgeTransforms() {
        EdgeTransform[][] edges = new EdgeTransform[6][4];

        // FRONT (4) - u is +X, v is +Y
        // FRONT u- (u=-1 → LEFT boundary)
        edges[4][0] = new EdgeTransform(1, 1, false, false, false); // LEFT at v, no swap
        // FRONT u+ (u=+1 → RIGHT boundary)
        edges[4][1] = new EdgeTransform(0, 0, false, false, false); // RIGHT at v, no swap
        // FRONT v- (v=-1 → DOWN boundary)
        edges[4][2] = new EdgeTransform(3, 0, true, false, false);  // swap → DOWN
        // FRONT v+ (v=+1 → UP boundary)
        edges[4][3] = new EdgeTransform(2, 0, true, false, false);  // swap → UP

        // BACK (5) - u is -X, v is +Y
        edges[5][0] = new EdgeTransform(0, 1, false, false, false);  // BACK u- → RIGHT
        edges[5][1] = new EdgeTransform(1, 0, false, false, false);  // BACK u+ → LEFT
        edges[5][2] = new EdgeTransform(3, 1, true, false, true);    // BACK v- → DOWN
        edges[5][3] = new EdgeTransform(2, 1, true, false, true);    // BACK v+ → UP

        // RIGHT (0) - u is -Z, v is +Y
        edges[0][0] = new EdgeTransform(4, 1, false, false, false);  // RIGHT u- → FRONT
        edges[0][1] = new EdgeTransform(5, 0, false, false, false);  // RIGHT u+ → BACK
        edges[0][2] = new EdgeTransform(3, 2, true, false, false);   // RIGHT v- → DOWN
        edges[0][3] = new EdgeTransform(2, 2, true, true, false);    // RIGHT v+ → UP

        // LEFT (1) - u is +Z, v is +Y
        edges[1][0] = new EdgeTransform(5, 1, false, false, false);   // LEFT u- → BACK
        edges[1][1] = new EdgeTransform(4, 0, false, false, false);   // LEFT u+ → FRONT
        edges[1][2] = new EdgeTransform(3, 3, true, true, false);     // LEFT v- → DOWN
        edges[1][3] = new EdgeTransform(2, 3, true, false, false);    // LEFT v+ → UP

        // UP (2) - u is +X, v is +Z
        edges[2][0] = new EdgeTransform(4, 3, true, true, false);    // UP u- → FRONT
        edges[2][1] = new EdgeTransform(5, 3, true, false, true);    // UP u+ → BACK
        edges[2][2] = new EdgeTransform(1, 3, false, false, false);  // UP v- → LEFT
        edges[2][3] = new EdgeTransform(0, 3, false, false, false);  // UP v+ → RIGHT

        // DOWN (3) - u is +X, v is -Z
        edges[3][0] = new EdgeTransform(4, 2, true, false, true);    // DOWN u- → FRONT
        edges[3][1] = new EdgeTransform(5, 2, true, true, true);     // DOWN u+ → BACK
        edges[3][2] = new EdgeTransform(1, 2, false, false, false);  // DOWN v- → LEFT
        edges[3][3] = new EdgeTransform(0, 2, false, false, false);  // DOWN v+ → RIGHT

        return edges;
    }

    // ─── Main neighbor lookup ───────────────────────────────────────────────

    /**
     * Given a BlockAddress and a direction, find the neighboring block address(es).
     *
     * Most of the time, there is exactly one neighbor. However, at vertical shell
     * boundaries, a single block may map to multiple blocks (1→4 or 4→1).
     *
     * @param address The source block address
     * @param dir     The direction to look
     * @return List of neighbor addresses (usually 1, sometimes 4)
     */
    public static List<BlockAddress> getNeighbors(BlockAddress address, Direction dir) {
        List<BlockAddress> result = new ArrayList<>();

        int blocksPerSide = QuadSphere.getBlocksPerSide(address.shellIndex());
        int totalBlockX = address.chunkIndex().x() * QuadSphere.CHUNK_SIZE + address.blockIndex().x();
        int totalBlockY = address.chunkIndex().y() * QuadSphere.CHUNK_SIZE + address.blockIndex().y();
        int totalBlockZ = address.chunkIndex().z() * QuadSphere.CHUNK_SIZE + address.blockIndex().z();

        // Determine which local direction this maps to
        // In the local space of a sector face:
        // - For FRONT/BACK/LEFT/RIGHT: u is horizontal, v is vertical (Y), w is radial
        // - For UP/DOWN: u is horizontal, v is the other horizontal, w is radial
        //
        // Direction mapping depends on the sector.

        // Map the direction to local coordinates based on sector
        int localU, localV, localW;
        localU = localV = localW = 0;

        switch (address.sectorIndex()) {
            case 4: // FRONT (+Z)
                switch (dir) {
                    case RIGHT -> { localU = 1; }
                    case LEFT  -> { localU = -1; }
                    case UP    -> { localV = 1; }
                    case DOWN  -> { localV = -1; }
                    case FRONT -> { localW = 1; } // toward surface
                    case BACK  -> { localW = -1; } // toward center
                }
                break;
            case 5: // BACK (-Z)
                switch (dir) {
                    case RIGHT -> { localU = -1; }
                    case LEFT  -> { localU = 1; }
                    case UP    -> { localV = 1; }
                    case DOWN  -> { localV = -1; }
                    case FRONT -> { localW = -1; }
                    case BACK  -> { localW = 1; }
                }
                break;
            case 0: // RIGHT (+X)
                switch (dir) {
                    case RIGHT -> { localU = -1; }
                    case LEFT  -> { localU = 1; }
                    case UP    -> { localV = 1; }
                    case DOWN  -> { localV = -1; }
                    case FRONT -> { localW = 1; }
                    case BACK  -> { localW = -1; }
                }
                break;
            case 1: // LEFT (-X)
                switch (dir) {
                    case RIGHT -> { localU = 1; }
                    case LEFT  -> { localU = -1; }
                    case UP    -> { localV = 1; }
                    case DOWN  -> { localV = -1; }
                    case FRONT -> { localW = -1; }
                    case BACK  -> { localW = 1; }
                }
                break;
            case 2: // UP (+Y)
                switch (dir) {
                    case RIGHT -> { localU = 1; }
                    case LEFT  -> { localU = -1; }
                    case UP    -> { localV = 1; }
                    case DOWN  -> { localV = -1; }
                    case FRONT -> { localW = 1; }
                    case BACK  -> { localW = -1; }
                }
                break;
            case 3: // DOWN (-Y)
                switch (dir) {
                    case RIGHT -> { localU = 1; }
                    case LEFT  -> { localU = -1; }
                    case UP    -> { localV = -1; }
                    case DOWN  -> { localV = 1; }
                    case FRONT -> { localW = -1; }
                    case BACK  -> { localW = 1; }
                }
                break;
        }

        // ─── Handle radial movement (in/out) ─────────────────────────────
        if (localW != 0) {
            return handleRadialNeighbor(address, totalBlockY, localW, result);
        }

        // ─── Handle horizontal movement within the shell ──────────────────
        int newU = totalBlockX + localU;
        int newV = totalBlockZ + localV;

        // Check if we cross a sector boundary
        if (newU < 0 || newU >= blocksPerSide || newV < 0 || newV >= blocksPerSide) {
            return handleSectorBoundary(address, blocksPerSide, totalBlockX, totalBlockY, totalBlockZ, newU, newV, result);
        }

        // Same sector, same shell — simple case
        int newChunkX = newU / QuadSphere.CHUNK_SIZE;
        int newChunkZ = newV / QuadSphere.CHUNK_SIZE;
        int newBlockX = newU % QuadSphere.CHUNK_SIZE;
        int newBlockZ = newV % QuadSphere.CHUNK_SIZE;

        result.add(new BlockAddress(
            address.sectorIndex(),
            address.shellIndex(),
            new Int3(newChunkX, address.chunkIndex().y(), newChunkZ),
            new Int3(newBlockX, address.blockIndex().y(), newBlockZ)
        ));
        return result;
    }

    /**
     * Handle neighbor lookup across a radial (inward/outward) direction.
     * This is where vertical shell boundary logic lives.
     */
    private static List<BlockAddress> handleRadialNeighbor(
        BlockAddress address, int totalBlockY, int direction,
        List<BlockAddress> result
    ) {
        int newLayer = totalBlockY + direction;

        // Same shell?
        if (newLayer >= 0 && newLayer < QuadSphere.LAYERS_PER_SHELL) {
            // Simple vertical move within the same shell
            int newChunkY = newLayer / QuadSphere.CHUNK_SIZE;
            int newBlockY = newLayer % QuadSphere.CHUNK_SIZE;
            result.add(new BlockAddress(
                address.sectorIndex(),
                address.shellIndex(),
                new Int3(address.chunkIndex().x(), newChunkY, address.chunkIndex().z()),
                new Int3(address.blockIndex().x(), newBlockY, address.blockIndex().z())
            ));
            return result;
        }

        // Crossed a shell boundary!
        if (direction > 0) {
            // Moving outward → entering next shell (shellIndex + 1)
            return handleOutwardShellBoundary(address, result);
        } else {
            // Moving inward → entering previous shell (shellIndex - 1)
            return handleInwardShellBoundary(address, result);
        }
    }

    /**
     * Moving outward from the top of shell N to the bottom of shell N+1.
     * One block at the top of shell N maps to 4 blocks at the bottom of shell N+1.
     */
    private static List<BlockAddress> handleOutwardShellBoundary(
        BlockAddress address, List<BlockAddress> result
    ) {
        int nextShell = address.shellIndex() + 1;
        int currentBlocksPerSide = QuadSphere.getBlocksPerSide(address.shellIndex());
        int nextBlocksPerSide = QuadSphere.getBlocksPerSide(nextShell);

        // The mapping: each block in the current shell maps to 4 blocks (2×2) in the next shell
        // because the next shell has 2× the linear resolution.
        double scale = (double) nextBlocksPerSide / currentBlocksPerSide; // Should be 2

        int totalBlockX = address.chunkIndex().x() * QuadSphere.CHUNK_SIZE + address.blockIndex().x();
        int totalBlockZ = address.chunkIndex().z() * QuadSphere.CHUNK_SIZE + address.blockIndex().z();

        int baseU = (int) (totalBlockX * scale);
        int baseV = (int) (totalBlockZ * scale);

        // Layer 0 of the next shell
        int newLayer = 0;

        // Four blocks in the 2×2 grid
        for (int du = 0; du < (int) scale; du++) {
            for (int dv = 0; dv < (int) scale; dv++) {
                int newU = Math.min(baseU + du, nextBlocksPerSide - 1);
                int newV = Math.min(baseV + dv, nextBlocksPerSide - 1);

                int newChunkX = newU / QuadSphere.CHUNK_SIZE;
                int newChunkZ = newV / QuadSphere.CHUNK_SIZE;
                int newChunkY = newLayer / QuadSphere.CHUNK_SIZE;
                int newBlockX = newU % QuadSphere.CHUNK_SIZE;
                int newBlockY = newLayer % QuadSphere.CHUNK_SIZE;
                int newBlockZ = newV % QuadSphere.CHUNK_SIZE;

                result.add(new BlockAddress(
                    address.sectorIndex(),
                    nextShell,
                    new Int3(newChunkX, newChunkY, newChunkZ),
                    new Int3(newBlockX, newBlockY, newBlockZ)
                ));
            }
        }

        return result;
    }

    /**
     * Moving inward from the bottom of shell N to the top of shell N-1.
     * 4 blocks at the bottom of shell N map to 1 block at the top of shell N-1.
     */
    private static List<BlockAddress> handleInwardShellBoundary(
        BlockAddress address, List<BlockAddress> result
    ) {
        if (address.shellIndex() <= 0) {
            // We're at the innermost shell — no blocks further in
            return result; // empty
        }

        int prevShell = address.shellIndex() - 1;
        int currentBlocksPerSide = QuadSphere.getBlocksPerSide(address.shellIndex());
        int prevBlocksPerSide = QuadSphere.getBlocksPerSide(prevShell);

        double scale = (double) prevBlocksPerSide / currentBlocksPerSide; // Should be 0.5

        int totalBlockX = address.chunkIndex().x() * QuadSphere.CHUNK_SIZE + address.blockIndex().x();
        int totalBlockZ = address.chunkIndex().z() * QuadSphere.CHUNK_SIZE + address.blockIndex().z();

        // Map to parent block
        int parentU = (int) (totalBlockX * scale);
        int parentV = (int) (totalBlockZ * scale);

        // Last layer of the previous shell
        int newLayer = QuadSphere.LAYERS_PER_SHELL - 1;

        int newChunkX = parentU / QuadSphere.CHUNK_SIZE;
        int newChunkZ = parentV / QuadSphere.CHUNK_SIZE;
        int newChunkY = newLayer / QuadSphere.CHUNK_SIZE;
        int newBlockX = parentU % QuadSphere.CHUNK_SIZE;
        int newBlockY = newLayer % QuadSphere.CHUNK_SIZE;
        int newBlockZ = parentV % QuadSphere.CHUNK_SIZE;

        result.add(new BlockAddress(
            address.sectorIndex(),
            prevShell,
            new Int3(newChunkX, newChunkY, newChunkZ),
            new Int3(newBlockX, newBlockY, newBlockZ)
        ));

        return result;
    }

    /**
     * Handle crossing a sector boundary.
     */
    private static List<BlockAddress> handleSectorBoundary(
        BlockAddress address, int blocksPerSide,
        int totalBlockX, int totalBlockY, int totalBlockZ,
        int newU, int newV, List<BlockAddress> result
    ) {
        // Determine which edge we're crossing
        int edgeIndex;
        if (newU < 0) edgeIndex = 0;        // u- edge
        else if (newU >= blocksPerSide) edgeIndex = 1; // u+ edge
        else if (newV < 0) edgeIndex = 2;  // v- edge
        else edgeIndex = 3;                  // v+ edge

        EdgeTransform trans = EDGE_TRANSFORMS[address.sectorIndex()][edgeIndex];

        // Compute the position along the edge (0 to blocksPerSide-1)
        double edgePos;
        if (edgeIndex == 0 || edgeIndex == 1) {
            // Crossing u edge: position along v
            edgePos = Math.max(0, Math.min(blocksPerSide - 1, newV));
        } else {
            // Crossing v edge: position along u
            edgePos = Math.max(0, Math.min(blocksPerSide - 1, newU));
        }

        // Convert edge position to neighbor's local coordinates
        int neighborBlocksPerSide = QuadSphere.getBlocksPerSide(address.shellIndex());
        double normalizedPos = edgePos / (blocksPerSide - 1); // 0 to 1

        // In the neighbor's local space:
        double neighborU, neighborV;

        if (trans.swapAxes()) {
            neighborU = normalizedPos;
            neighborV = 0.0; // we're at the edge
        } else {
            neighborU = trans.flipU() ? (1.0 - normalizedPos) : normalizedPos;
            neighborV = trans.flipV() ? 1.0 : 0.0;
        }

        // The neighbor is on the other side of the edge, so its local coordinate
        // for the axis perpendicular to the edge is:
        // - If we crossed u- (edgeIndex 0), neighbor is at u+ (edgeIndex 1)
        // - If we crossed u+ (edgeIndex 1), neighbor is at u- (edgeIndex 0)
        // - etc.
        int neighborEdgeIndex = switch (edgeIndex) {
            case 0 -> 1; // u- → u+
            case 1 -> 0; // u+ → u-
            case 2 -> 3; // v- → v+
            case 3 -> 2; // v+ → v-
            default -> 0;
        };

        // Compute the coordinate on the neighbor's edge
        EdgeTransform neighborTrans = EDGE_TRANSFORMS[trans.neighborSector()][neighborEdgeIndex];

        // Actually, for the neighbor, we just need to place the block at the inner edge.
        // The neighbor's local (U, V) at its boundary edge:

        double neighborLocalU, neighborLocalV;

        if (neighborEdgeIndex == 0) {
            // neighbor's u- edge: u=0
            neighborLocalU = 0;
            neighborLocalV = normalizedPos;
        } else if (neighborEdgeIndex == 1) {
            // neighbor's u+ edge: u=max
            neighborLocalU = 1;
            neighborLocalV = normalizedPos;
        } else if (neighborEdgeIndex == 2) {
            // neighbor's v- edge: v=0
            neighborLocalU = normalizedPos;
            neighborLocalV = 0;
        } else {
            // neighbor's v+ edge: v=max
            neighborLocalU = normalizedPos;
            neighborLocalV = 1;
        }

        // Apply flips
        if (trans.flipU()) neighborLocalU = 1.0 - neighborLocalU;
        if (trans.flipV()) neighborLocalV = 1.0 - neighborLocalV;

        // Convert to block coordinates
        int neighborBlockU = (int) (neighborLocalU * (neighborBlocksPerSide - 1));
        int neighborBlockV = (int) (neighborLocalV * (neighborBlocksPerSide - 1));

        neighborBlockU = Math.max(0, Math.min(neighborBlocksPerSide - 1, neighborBlockU));
        neighborBlockV = Math.max(0, Math.min(neighborBlocksPerSide - 1, neighborBlockV));

        int neighborChunkX = neighborBlockU / QuadSphere.CHUNK_SIZE;
        int neighborChunkZ = neighborBlockV / QuadSphere.CHUNK_SIZE;
        int neighborBlockX = neighborBlockU % QuadSphere.CHUNK_SIZE;
        int neighborBlockZ = neighborBlockV % QuadSphere.CHUNK_SIZE;

        result.add(new BlockAddress(
            trans.neighborSector(),
            address.shellIndex(),
            new Int3(neighborChunkX, address.chunkIndex().y(), neighborChunkZ),
            new Int3(neighborBlockX, address.blockIndex().y(), neighborBlockZ)
        ));

        return result;
    }
}
