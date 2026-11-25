/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process.elytra.pathfinder;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ForkJoinTask;

/**
 * A* over cubes of the chunk octrees, as the native PathFinder.cpp had it. A node is a cube of 2
 * to 16 blocks that is all air; a step to a neighbour grows the neighbour to the largest empty
 * cube it sits in, or shrinks it into the four smaller cubes on the shared face until they are
 * empty or too small.
 */
final class PathFinder {

    static final double MIN_DIST_PATH = 5; // might want to increase this
    private static final double MIN_IMPROVEMENT = 0.01;
    // The native PathFinder.cpp's order rather than Direction.values(), so the search expands
    // neighbours as it always has.
    private static final Direction[] ALL_FACES = {Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    static final class Path {
        enum Type {
            SEGMENT,
            FINISHED
        }

        final Type type;
        final BlockPos start;
        final BlockPos goal; // where the path wants to go, not necessarily where it ends
        final List<BlockPos> blocks;

        Path(Type type, BlockPos start, BlockPos goal, List<BlockPos> blocks) {
            this.type = type;
            this.start = start;
            this.goal = goal;
            this.blocks = blocks;
        }

        BlockPos getEndPos() {
            // this should basically never be empty
            return !this.blocks.isEmpty() ? this.blocks.get(this.blocks.size() - 1) : this.start;
        }
    }

    private PathFinder() {}

    private static PathNode getNodeAtPosition(Map<NodePos, PathNode> map, NodePos pos, BlockPos goal) {
        PathNode node = map.get(pos);
        if (node == null) {
            node = new PathNode(pos, goal);
            map.put(pos, node);
        }
        return node;
    }

    private static Path createPath(PathNode end, BlockPos startPos, BlockPos goal, Path.Type type) {
        final List<BlockPos> blocks = new ArrayList<>();
        for (PathNode current = end; current != null; current = current.previous) {
            blocks.add(current.pos.absolutePosCenter());
        }
        Collections.reverse(blocks);
        return new Path(type, startPos, goal, blocks);
    }

    static boolean isInBounds(int worldHeight, int y) {
        return y >= 0 && y < worldHeight;
    }

    private static boolean closeToGoal(NodePos node, BlockPos goal) {
        return node.centerDistSqr(goal) <= 16 * 16;
    }

    private static boolean inGoal(NodePos node, BlockPos goal) {
        if (!closeToGoal(node, goal)) return false;
        final int x = node.minX(), y = node.minY(), z = node.minZ();
        final int w = node.size.width();
        return goal.getX() >= x && goal.getX() <= x + w &&
                goal.getY() >= y && goal.getY() <= y + w &&
                goal.getZ() >= z && goal.getZ() <= z + w;
    }

    private static Path bestPathSoFar(PathNode end, BlockPos startPos, BlockPos goal) {
        final double distSq = end.pos.centerDistSqr(startPos);
        if (distSq > MIN_DIST_PATH * MIN_DIST_PATH) {
            return createPath(end, startPos, goal, Path.Type.SEGMENT);
        }
        // the path took too long and got nowhere
        return null;
    }

    // ---- neighbour expansion ----

    private interface Callback {
        void accept(NodePos neighbor, Chunk chunk, boolean fromCaller);
    }

    /**
     * Called inside a big neighbour cube whose lowest corner is (x, y, z); iterates the 4 sub cubes
     * that are adjacent to the original cube: the corner of the layer that touches it, one step
     * along each of the layer's two axes, and both. The face is relative to the original cube, the
     * size is the sub cubes'.
     */
    private static void forEachSubCube(Chunk chunk, boolean fromCaller, int x, int y, int z, Direction face, Size sz, Size minSize, Callback callback) {
        final int w = sz.width();
        int ax = 0, ay = 0, az = 0, bx = 0, by = 0, bz = 0;
        switch (face) {
            case UP:
                ax = w; bz = w;
                break;
            case DOWN:
                y += w; ax = w; bz = w;
                break;
            case NORTH:
                z += w; ax = w; by = w;
                break;
            case SOUTH:
                ax = w; by = w;
                break;
            case EAST:
                az = w; by = w;
                break;
            default: // WEST
                x += w; az = w; by = w;
                break;
        }
        forEachNeighborInCube(chunk, fromCaller, new NodePos(sz, x, y, z), face, sz, false, minSize, callback);
        forEachNeighborInCube(chunk, fromCaller, new NodePos(sz, x + ax, y + ay, z + az), face, sz, false, minSize, callback);
        forEachNeighborInCube(chunk, fromCaller, new NodePos(sz, x + bx, y + by, z + bz), face, sz, false, minSize, callback);
        forEachNeighborInCube(chunk, fromCaller, new NodePos(sz, x + ax + bx, y + ay + by, z + az + bz), face, sz, false, minSize, callback);
    }

    private static void forEachNeighborInCube(Chunk chunk, boolean fromCaller, NodePos neighborNode, Direction face, Size size, boolean sizeChange, Size minSize, Callback callback) {
        if (sizeChange) {
            callback.accept(neighborNode, chunk, fromCaller);
            return;
        }
        final int x = neighborNode.minX(), y = neighborNode.minY(), z = neighborNode.minZ();
        if (chunk.isEmpty(size, x & 15, y, z & 15)) {
            callback.accept(neighborNode, chunk, fromCaller);
            return;
        }
        if (size != Size.X1) {
            final Size nextSize = size.smaller();
            // Don't shrink cubes to X1 because they suck and make the path try to squeeze through small areas
            if (nextSize.ordinal() < minSize.ordinal()) return;
            forEachSubCube(chunk, fromCaller, x, y, z, face, nextSize, minSize, callback);
        }
    }

    /** Grows the neighbour to the largest empty cube it is in, then iterates what is on the face. */
    private static void growThenIterate(Chunk chunk, boolean fromCaller, NodePos pos, Direction face, Size minSize, Callback callback) {
        final Size originalSize = pos.size;
        final int x = pos.minX(), y = pos.minY(), z = pos.minZ();
        for (Size s = originalSize; ; s = s.larger()) {
            if (s == Size.X16 || !chunk.isEmpty(s.larger(), x & 15, y, z & 15)) {
                forEachNeighborInCube(chunk, fromCaller, new NodePos(s, x, y, z), face, s, originalSize != s, minSize, callback);
                return;
            }
        }
    }

    // ---- the search ----

    private static final class Search implements Callback {
        final Map<NodePos, PathNode> map = new HashMap<>();
        final BinaryHeapOpenSet openSet = new BinaryHeapOpenSet();
        final BlockPos goalCenter;
        final BlockPos startCenter;
        final double fakeChunkCost;
        PathNode currentNode;
        PathNode bestSoFar;
        double bestHeuristicSoFar;
        boolean failing = true;
        // a cube it took from the open set had a face on a chunk the seed made up, not one from the game
        boolean touchedGuess;

        Search(BlockPos goalCenter, BlockPos startCenter, double fakeChunkCost) {
            this.goalCenter = goalCenter;
            this.startCenter = startCenter;
            this.fakeChunkCost = fakeChunkCost;
        }

        @Override
        public void accept(NodePos neighborPos, Chunk chunk, boolean fromCaller) {
            final PathNode neighborNode = getNodeAtPosition(this.map, neighborPos, this.goalCenter);
            final double cost = fromCaller ? 1 : this.fakeChunkCost;
            final double tentativeCost = this.currentNode.cost + cost;
            if (neighborNode.cost - tentativeCost > MIN_IMPROVEMENT) {
                neighborNode.previous = this.currentNode;
                neighborNode.cost = tentativeCost;
                neighborNode.combinedCost = tentativeCost + neighborNode.estimatedCostToGoal;

                if (neighborNode.isOpen()) {
                    this.openSet.update(neighborNode);
                } else {
                    this.openSet.insert(neighborNode); // dont double count, dont insert into open set if it's already there
                }

                final double heuristic = neighborNode.combinedCost;
                if (this.bestHeuristicSoFar - heuristic > MIN_IMPROVEMENT) {
                    this.bestHeuristicSoFar = heuristic;
                    this.bestSoFar = neighborNode;
                    if (this.failing && neighborPos.centerDistSqr(this.startCenter) > MIN_DIST_PATH * MIN_DIST_PATH) {
                        this.failing = false;
                    }
                }
            }
        }
    }

    /**
     * One segment of a path from start towards goal. Returns a FINISHED path if it reached the
     * goal, a SEGMENT if it ran out of time but got somewhere, and null if it got nowhere or was
     * cancelled.
     */
    static Path findPathSegment(NetherPathfinder ctx, NodePos start, NodePos goal, boolean x4Min, int timeoutMs, boolean airIfFake, double fakeChunkCost) {
        return findPathSegment(ctx, start, goal, x4Min, timeoutMs, airIfFake, fakeChunkCost, null);
    }

    /**
     * {@link #findPathSegment(NetherPathfinder, NodePos, NodePos, boolean, int, boolean, double)}, saying in
     * {@code outcome} (when not null) why it returned null
     */
    static Path findPathSegment(NetherPathfinder ctx, NodePos start, NodePos goal, boolean x4Min, int timeoutMs, boolean airIfFake, double fakeChunkCost, NetherPathfinder.Outcome outcome) {
        final NetherPathfinder.CacheMiss fakeChunkMode = airIfFake ? NetherPathfinder.CacheMiss.AIR : NetherPathfinder.CacheMiss.GENERATE;
        final Size minSize = x4Min ? Size.X4 : Size.X2;
        final BlockPos goalCenter = goal.absolutePosCenter();
        final BlockPos startCenter = start.absolutePosCenter();

        final Search s = new Search(goalCenter, startCenter, fakeChunkCost);
        final LongSet doneFull = new LongOpenHashSet();

        final PathNode startNode = getNodeAtPosition(s.map, start, new BlockPos(goal.minX(), goal.minY(), goal.minZ()));
        ctx.tryLoadRegion((start.minX() >> 4), (start.minZ() >> 4));
        // tryLoadRegion boxes a Long and adds it to a concurrent set, which locks the bin when the key is already there
        // (it always is). six times per node. the neighbours are nearly always in the region we just asked about
        long lastRegion = NetherPathfinder.key(start.minX() >> 9, start.minZ() >> 9);
        startNode.cost = 0;
        startNode.combinedCost = startNode.estimatedCostToGoal;
        s.openSet.insert(startNode);
        ctx.getRealChunkFromCacheOrFakeChunkMaybeGen((start.minX() >> 4), (start.minZ() >> 4), fakeChunkMode);

        s.bestSoFar = startNode;
        s.bestHeuristicSoFar = startNode.estimatedCostToGoal;

        final long startTime = System.currentTimeMillis();
        final long primaryTimeoutTime = startTime + 500L;
        final long timeout = timeoutMs != 0 ? timeoutMs : 30_000L;
        final long failureTimeout = startTime + timeout;
        long nanosDoingIO = 0; // spent reading region files, which the timeouts do not count

        int numNodes = 0;
        int fakeChunkVisits = 0; // if this gets too high we return
        boolean timedOut = false;
        while (!s.openSet.isEmpty()) {
            if ((numNodes & 63) == 0) { // only look at the clock once every 64 nodes
                final long now = System.currentTimeMillis() - nanosDoingIO / 1_000_000;
                if (now >= failureTimeout || (!s.failing && now >= primaryTimeoutTime)) {
                    timedOut = true;
                    break;
                } else if (ctx.isCancelled()) {
                    return failed(outcome, NetherPathfinder.Failure.CANCELLED, numNodes);
                }
            }
            numNodes++;

            final PathNode currentNode = s.openSet.removeLowest();
            s.currentNode = currentNode;
            if (inGoal(currentNode.pos, goalCenter)) {
                return createPath(currentNode, startCenter, goalCenter, Path.Type.FINISHED);
            }
            final NodePos pos = currentNode.pos;
            final Size size = pos.size;
            final int x = pos.minX(), y = pos.minY(), z = pos.minZ();
            final int w = size.width();
            final int cx = (x >> 4);
            final int cz = (z >> 4);
            final NetherPathfinder.Entry currentChunk = ctx.getChunkOrAir(cx, cz);
            if (!currentChunk.fromCaller) {
                fakeChunkVisits++;
            } else {
                fakeChunkVisits = 0;
            }
            if (fakeChunkVisits >= 100 && airIfFake) {
                final Path best = bestPathSoFar(s.bestSoFar, startCenter, goalCenter);
                return best != null ? best : failedGettingNoCloser(ctx, outcome, goal, airIfFake, NetherPathfinder.Failure.NO_CLOSER, numNodes);
            }
            if (!airIfFake && doneFull.add(NetherPathfinder.key(cx, cz))) {
                generateMissingNeighbours(ctx, cx, cz);
            }

            for (Direction face : ALL_FACES) {
                final int nx = x + face.getStepX() * w;
                final int ny = y + face.getStepY() * w;
                final int nz = z + face.getStepZ() * w;
                if (face == Direction.UP || face == Direction.DOWN) {
                    if (!isInBounds(ctx.maxHeight, ny)) continue;
                }
                final int neighborCx = (nx >> 4);
                final int neighborCz = (nz >> 4);
                final long region = NetherPathfinder.key(neighborCx >> 5, neighborCz >> 5);
                if (region != lastRegion) {
                    nanosDoingIO += ctx.tryLoadRegion(neighborCx, neighborCz);
                    lastRegion = region;
                }
                final NetherPathfinder.Entry entry = neighborCx == cx && neighborCz == cz ? currentChunk : ctx.getChunkOrAir(neighborCx, neighborCz);
                if (!entry.fromCaller && !airIfFake) {
                    s.touchedGuess = true;
                }
                growThenIterate(entry.chunk, entry.fromCaller, new NodePos(size, nx, ny, nz), face, minSize, s);
            }
        }
        final Path best = bestPathSoFar(s.bestSoFar, startCenter, goalCenter);
        if (best != null) {
            return best;
        }
        if (!timedOut) {
            // the open set ran out: every cube of air the start leads to was searched. a few of them are a pocket the
            // start is shut in; many, and it may be the goal that is shut off from them, or the chunks the game has
            // sent that end them. a highway tunnel runs on in the game where the seed has rock, so the air along it
            // runs out at the last chunk from the game, and a goal far off in the seed's rock would get the blame
            if (numNodes <= SMALL_POCKET_CUBES || outcome == null) {
                return failed(outcome, NetherPathfinder.Failure.SEALED_START, numNodes);
            }
            final Seal goalSeal = seal(ctx, goal, airIfFake, SEALED_GOAL_CUBES);
            final NetherPathfinder.Failure failure;
            if (goalSeal == Seal.BY_GAME) {
                failure = NetherPathfinder.Failure.SEALED_GOAL; // no chunk still to come opens that
            } else if (s.touchedGuess) {
                failure = NetherPathfinder.Failure.ENDS_AT_GUESS;
            } else {
                failure = goalSeal == Seal.OPEN ? NetherPathfinder.Failure.SEALED_START : NetherPathfinder.Failure.SEALED_GOAL;
            }
            return failed(outcome, failure, numNodes);
        }
        return failedGettingNoCloser(ctx, outcome, goal, airIfFake, NetherPathfinder.Failure.TIMEOUT, numNodes);
    }

    // a search that is still looking ran into a pocket of air at the goal that nothing gets into, or it isn't one
    private static final int SEALED_GOAL_CUBES = 4096;
    // a search that ran out of air after this few cubes was shut in where it started
    private static final int SMALL_POCKET_CUBES = 256;

    private static Path failed(NetherPathfinder.Outcome outcome, NetherPathfinder.Failure failure, int nodes) {
        if (outcome != null) {
            outcome.failure = failure;
            outcome.nodes = nodes;
        }
        return null;
    }

    private static Path failedGettingNoCloser(NetherPathfinder ctx, NetherPathfinder.Outcome outcome, NodePos goal, boolean airIfFake, NetherPathfinder.Failure otherwise, int nodes) {
        if (outcome == null) {
            return null;
        }
        return failed(outcome, sealedAround(ctx, goal, airIfFake, SEALED_GOAL_CUBES) ? NetherPathfinder.Failure.SEALED_GOAL : otherwise, nodes);
    }

    /**
     * Whether the cubes of air of {@code from}'s size that {@code from} leads to, face to face, run out within
     * {@code limit} of them. The search never goes through cubes smaller than the one it starts and ends in, so when
     * these run out the search can't get in. A chunk the table doesn't hold counts as air.
     */
    static boolean sealedAround(NetherPathfinder ctx, NodePos from, boolean airIfFake, int limit) {
        return seal(ctx, from, airIfFake, limit) != Seal.OPEN;
    }

    /** How the air around a cube is closed off: not at all, by chunks from the game alone, or partly by the seed's. */
    enum Seal {
        OPEN,
        BY_GAME,
        BY_GUESS
    }

    /**
     * {@link #sealedAround}, and when it is, whether a cube in the way was in a chunk the seed made up, which the game
     * may have air in
     */
    static Seal seal(NetherPathfinder ctx, NodePos from, boolean airIfFake, int limit) {
        final Size size = from.size;
        final int w = size.width();
        final ArrayDeque<NodePos> queue = new ArrayDeque<>();
        final Set<NodePos> seen = new HashSet<>();
        queue.add(from);
        seen.add(from);
        boolean byGuess = false;
        while (!queue.isEmpty()) {
            if (seen.size() > limit) {
                return Seal.OPEN;
            }
            final NodePos node = queue.poll();
            for (Direction face : ALL_FACES) {
                final int nx = node.minX() + face.getStepX() * w;
                final int ny = node.minY() + face.getStepY() * w;
                final int nz = node.minZ() + face.getStepZ() * w;
                if (!isInBounds(ctx.maxHeight, ny)) {
                    continue;
                }
                final NodePos next = new NodePos(size, nx, ny, nz);
                if (seen.contains(next)) {
                    continue;
                }
                final Chunk chunk = airIfFake ? ctx.getChunkOrAir(nx >> 4, nz >> 4).chunk : ctx.getChunkOrDefault(nx >> 4, nz >> 4, false);
                if (chunk.isEmpty(size, nx & 15, ny, nz & 15)) {
                    seen.add(next);
                    queue.add(next);
                } else if (!byGuess && !ctx.hasChunkFromCaller(nx >> 4, nz >> 4)) {
                    byGuess = true;
                }
            }
        }
        return byGuess ? Seal.BY_GUESS : Seal.BY_GAME;
    }

    private static final int[] NEIGHBOUR_OFFSETS = {0, -1, 0, 1, 1, 0, -1, 0}; // north, south, east, west

    /**
     * Generates those of the four chunks around (cx, cz) that the table does not have yet, all at
     * once: the first on this thread and the others on the common pool, as the native library did
     * on its worker threads. A chunk the table has costs one probe. The native library handed all
     * four to its threads whether they existed or not, and its threads were idle workers of its own;
     * a task on the common pool costs more than the probe, and a search over terrain that is all
     * there, which is what a flight over loaded chunks runs, would pay it for every chunk it enters.
     */
    private static void generateMissingNeighbours(NetherPathfinder ctx, int cx, int cz) {
        List<ForkJoinTask<?>> missing = null;
        for (int i = 0; i < NEIGHBOUR_OFFSETS.length; i += 2) {
            final int nx = cx + NEIGHBOUR_OFFSETS[i];
            final int nz = cz + NEIGHBOUR_OFFSETS[i + 1];
            if (ctx.hasChunk(nx, nz)) {
                continue;
            }
            if (missing == null) {
                missing = new ArrayList<>(4);
            }
            missing.add(ForkJoinTask.adapt((Runnable) () -> ctx.getOrGenChunk(nx, nz)));
        }
        if (missing != null) {
            ForkJoinTask.invokeAll(missing); // runs the first here and forks the rest to the pool
        }
    }

    /** Segment after segment until the goal, generating terrain. What the native main.cpp ran. */
    static Path findPathFull(NetherPathfinder ctx, NodePos start, NodePos goal, double fakeChunkCost) {
        if (!isInBounds(ctx.maxHeight, start.absolutePosCenter().getY())) {
            throw new IllegalArgumentException("start is out of bounds");
        }
        final List<Path> segments = new ArrayList<>();
        while (true) {
            final NodePos lastPathEnd;
            if (!segments.isEmpty()) {
                final BlockPos lastEnd = segments.get(segments.size() - 1).getEndPos();
                lastPathEnd = new NodePos(Size.X2, lastEnd.getX(), lastEnd.getY(), lastEnd.getZ());
            } else {
                lastPathEnd = start;
            }
            final Path path = findPathSegment(ctx, lastPathEnd, goal, true, 0, false, fakeChunkCost);
            if (path == null) {
                if (ctx.clearCancelled()) {
                    return null;
                }
                break;
            }
            final BlockPos end = path.getEndPos();
            ctx.cullFarChunks((end.getX() >> 4), (end.getZ() >> 4), 200);
            segments.add(path);
            if (path.type == Path.Type.FINISHED) break;
        }
        if (segments.isEmpty()) {
            return null;
        }
        final List<BlockPos> blocks = new ArrayList<>();
        for (Path segment : segments) {
            blocks.addAll(segment.blocks);
        }
        final Path first = segments.get(0);
        return new Path(segments.get(segments.size() - 1).type, first.start, first.goal, blocks);
    }

    /** The nearest cube of the given size around block (x, y, z) that is all air, searching outwards. */
    static NodePos findAir(NetherPathfinder ctx, Size size, int x, int y, int z, boolean airIfFake) {
        if (!isInBounds(ctx.maxHeight, y)) {
            throw new IllegalArgumentException("position is out of bounds: " + x + ", " + y + ", " + z);
        }
        final NodePos start = new NodePos(size, x, y, z);
        final ArrayDeque<NodePos> queue = new ArrayDeque<>();
        final Set<NodePos> visited = new HashSet<>();
        queue.add(start);
        visited.add(start);
        final int w = size.width();
        while (!queue.isEmpty()) {
            final NodePos node = queue.poll();
            final int nx = node.minX(), ny = node.minY(), nz = node.minZ();
            if (isInBounds(ctx.maxHeight, ny)) {
                final Chunk chunk = airIfFake
                        ? ctx.getChunkOrAir((nx >> 4), (nz >> 4)).chunk
                        : ctx.getOrGenChunk((nx >> 4), (nz >> 4));
                if (chunk.isEmpty(size, nx & 15, ny, nz & 15)) {
                    return node;
                }
                push(queue, visited, new NodePos(size, nx - w, ny, nz)); // west
                push(queue, visited, new NodePos(size, nx + w, ny, nz)); // east
                push(queue, visited, new NodePos(size, nx, ny, nz - w)); // north
                push(queue, visited, new NodePos(size, nx, ny, nz + w)); // south
                push(queue, visited, new NodePos(size, nx, ny + w, nz)); // above
                push(queue, visited, new NodePos(size, nx, ny - w, nz)); // below
            }
        }
        // shouldn't be possible to exit the while loop
        throw new IllegalStateException("no air anywhere around " + x + ", " + y + ", " + z);
    }

    private static void push(ArrayDeque<NodePos> queue, Set<NodePos> visited, NodePos pos) {
        if (visited.add(pos)) queue.add(pos);
    }
}
