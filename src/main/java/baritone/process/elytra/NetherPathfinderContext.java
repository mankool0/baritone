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

package baritone.process.elytra;

import baritone.Baritone;
import baritone.process.elytra.pathfinder.Chunk;
import baritone.process.elytra.pathfinder.NetherPathfinder;
import baritone.process.elytra.pathfinder.PathSegment;
import baritone.process.elytra.pathfinder.Raytracer;
import baritone.utils.accessor.IPalettedContainer;
import net.minecraft.core.BlockPos;
import baritone.api.utils.Pair;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PaletteResize;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.SoftReference;
import java.util.List;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * @author Brady
 */
public final class NetherPathfinderContext implements IElytraPathFinder {

    // Chunk is not synchronized, and this lock is what keeps its writers and readers apart. Packing
    // fills a chunk after the table already holds it and a block update changes one that a search
    // may be reading, so both take the write lock, as culling does; every search, one that generates
    // terrain too, and ElytraBehavior's tick and solver, take the read lock, which also makes the last
    // write visible to them. The table itself is concurrent: a chunk generated or read from a region
    // file goes in complete, and a culled one stays valid for whoever still holds it.
    // The one ray cast without the lock is the landing check in ElytraBehavior.setPath, on the game
    // thread, which can see a chunk that is being written and so a stale block, but nothing worse.
    public final ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();
    public final ReentrantReadWriteLock.ReadLock readLock = rwl.readLock();
    public final ReentrantReadWriteLock.WriteLock writeLock = rwl.writeLock();
    private final int maxHeight;

    // Visible for access in BlockStateOctreeInterface
    final NetherPathfinder context;
    private final long seed;
    // write locked operations
    private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor();
    // operations that don't make changes to the chunk cache. could use multiple threads but i'm not sure if it would cause problems.
    private final ExecutorService readExecutor = Executors.newSingleThreadExecutor();
    // chunks queued to be packed and not packed yet, all told and by chunk
    private final AtomicInteger packsQueued = new AtomicInteger();
    private final java.util.concurrent.ConcurrentHashMap<Long, Integer> queuedChunks = new java.util.concurrent.ConcurrentHashMap<>();
    // chunks from the game packed into the table so far
    private final AtomicInteger chunksPacked = new AtomicInteger();
    // said once: a section the server sent as empty had blocks in it
    private final java.util.concurrent.atomic.AtomicBoolean saidSentEmpty = new java.util.concurrent.atomic.AtomicBoolean();
    private final ResourceKey<Level> dimension;
    final int minY;
    private final BlockStateOctreeInterface boi;

    public NetherPathfinderContext(long seed, Path cache, Level world) {
        this.dimension = world.dimension();
        this.minY = world.dimensionType().minY();
        final NetherPathfinder.Dimension dim;
        if (this.dimension == Level.NETHER) dim = NetherPathfinder.Dimension.NETHER;
        else if (this.dimension == Level.END) dim = NetherPathfinder.Dimension.END;
        else dim = NetherPathfinder.Dimension.OVERWORLD;
        int height = Math.min(world.dimensionType().height(), 384);
        if (!Baritone.settings().elytraAllowAboveRoof.value && dim == NetherPathfinder.Dimension.NETHER) height = Math.min(height, 128);
        this.maxHeight = height;
        this.context = new NetherPathfinder(seed, cache != null ? cache.toString() : null, dim, height);
        this.seed = seed;
        this.boi = new BlockStateOctreeInterface(this);
    }

    public boolean hasChunk(ChunkPos pos) {
        return this.context.hasChunkFromCaller(pos.x, pos.z);
    }

    public void queueCacheCulling(int chunkX, int chunkZ, int maxDistanceBlocks) {
        this.writeExecutor.execute(() -> {
            writeLock.lock();
            try {
                this.boi.chunk = null;
                this.context.cullFarChunks(chunkX, chunkZ, maxDistanceBlocks);
            } finally {
                writeLock.unlock();
            }
        });
    }

    public void queueForPacking(final LevelChunk chunkIn) {
        final SoftReference<LevelChunk> ref = new SoftReference<>(chunkIn);
        final long key = chunkIn.getPos().toLong();
        this.packsQueued.incrementAndGet();
        this.queuedChunks.merge(key, 1, Integer::sum);
        this.writeExecutor.execute(() -> {
            // TODO: Prioritize packing recent chunks and/or ones that the path goes through,
            //       and prune the oldest chunks per chunkPackerQueueMaxSize
            try {
                final LevelChunk chunk = ref.get();
                if (chunk != null) {
                    writeLock.lock();
                    try {
                        this.pack(chunk);
                    } finally {
                        writeLock.unlock();
                    }
                }
            } finally {
                this.packsQueued.decrementAndGet();
                this.queuedChunks.computeIfPresent(key, (k, n) -> n > 1 ? n - 1 : null);
            }
        });
    }

    /**
     * @return Whether chunk ({@code cx}, {@code cz}) is waiting to be packed
     */
    public boolean packQueued(final int cx, final int cz) {
        return this.queuedChunks.containsKey(ChunkPos.asLong(cx, cz));
    }

    /**
     * @return How many chunks are waiting to be packed
     */
    public int packsQueued() {
        return this.packsQueued.get();
    }

    /**
     * @return How many chunks from the game have been packed so far: when it changes, a search can see more of them
     */
    public int chunksPacked() {
        return this.chunksPacked.get();
    }

    /**
     * Packs {@code chunk} on the calling thread, so a path asked for after this sees it. A path asked for while it is
     * only queued finds no chunk there, which counts as air, and can start in the middle of a lava lake.
     *
     * @return Whether it was packed, {@code false} when something else is writing (or the caller holds the read lock)
     * or packing it failed, and it should be queued instead
     */
    public boolean tryPackNow(final LevelChunk chunk) {
        if (!writeLock.tryLock()) {
            return false;
        }
        try {
            this.pack(chunk);
            return true;
        } catch (RuntimeException e) {
            // already printed. the caller is the game thread, which a chunk that can't be packed mustn't stop
            return false;
        } finally {
            writeLock.unlock();
        }
    }

    // required write lock to be held
    private void pack(final LevelChunk chunk) {
        // we might replace this chunk
        this.boi.chunk = null;
        final Chunk packed = this.context.allocateAndInsertChunk(chunk.getPos().x, chunk.getPos().z);
        final int sentEmpty = writeChunkData(chunk, packed);
        if (sentEmpty > 0 && !this.saidSentEmpty.getAndSet(true)) {
            baritone.api.utils.Helper.HELPER.logDirect(String.format(java.util.Locale.ROOT,
                    "The server sent %d section%s of the chunk at %s, %s as empty, though %s blocks in it: the game takes those for air (not drawn, nothing to hit), the pathfinder has them as they are",
                    sentEmpty, sentEmpty == 1 ? "" : "s",
                    baritone.api.utils.SettingsUtil.maybeCensor(chunk.getPos().getMinBlockX()),
                    baritone.api.utils.SettingsUtil.maybeCensor(chunk.getPos().getMinBlockZ()),
                    sentEmpty == 1 ? "it has" : "they have"));
        }
        if (Baritone.settings().elytraKeepOffLava.value) {
            this.keepOffBurning(chunk, packed);
        }
        this.chunksPacked.incrementAndGet();
    }

    /**
     * Whether {@code state} sets a player on fire: lava, or fire. Without tags, which come from the server
     */
    public static boolean burns(final BlockState state) {
        return state != null && (state.is(Blocks.LAVA) || state.getBlock() instanceof BaseFireBlock);
    }

    /**
     * Whether a block next to {@code pos}, above, below or beside it, sets a player on fire
     */
    public static boolean nextToBurning(final BlockGetter level, final BlockPos pos) {
        for (final Direction direction : Direction.values()) {
            if (burns(level.getBlockState(pos.relative(direction)))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Makes the blocks next to lava and fire in {@code chunk} solid, its own and the edges of the chunks beside it
     * that are packed already, and its own edge next to lava and fire in the chunks beside it the game has. A path
     * then keeps a block off them: through two-high cubes it would run a standing box a tenth of a block off lava, and
     * the server sets the player alight there
     */
    private void keepOffBurning(final LevelChunk chunk, final Chunk packed) {
        final int cx = chunk.getPos().x;
        final int cz = chunk.getPos().z;
        final LevelChunkSection[] sections = chunk.getSections();
        final int maxSections = Math.min(sections.length, Chunk.SECTIONS);
        for (int s = 0; s < maxSections; s++) {
            final LevelChunkSection section = sections[s];
            if (section == null || !section.getStates().maybeHas(NetherPathfinderContext::burns)) {
                continue;
            }
            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        if (!burns(section.getBlockState(lx, ly, lz))) {
                            continue;
                        }
                        final int y = (s << 4) + ly;
                        for (final Direction direction : Direction.values()) {
                            this.setSolid(cx, cz, packed, lx + direction.getStepX(), y + direction.getStepY(), lz + direction.getStepZ());
                        }
                    }
                }
            }
        }
        // the chunks beside it the game has: what burns on their edge next to this one
        for (final Direction side : Direction.Plane.HORIZONTAL) {
            final LevelChunk beside = chunk.getLevel().getChunkSource().getChunk(cx + side.getStepX(), cz + side.getStepZ(), false);
            if (beside == null || beside.isEmpty()) {
                continue;
            }
            final LevelChunkSection[] besideSections = beside.getSections();
            final int besideMax = Math.min(besideSections.length, Chunk.SECTIONS);
            for (int s = 0; s < besideMax; s++) {
                final LevelChunkSection section = besideSections[s];
                if (section == null || !section.getStates().maybeHas(NetherPathfinderContext::burns)) {
                    continue;
                }
                for (int ly = 0; ly < 16; ly++) {
                    for (int along = 0; along < 16; along++) {
                        // the column of the chunk beside that touches this one, and this one's column next to it
                        final int bx = side.getStepX() > 0 ? 0 : side.getStepX() < 0 ? 15 : along;
                        final int bz = side.getStepZ() > 0 ? 0 : side.getStepZ() < 0 ? 15 : along;
                        if (burns(section.getBlockState(bx, ly, bz))) {
                            final int ox = side.getStepX() > 0 ? 15 : side.getStepX() < 0 ? 0 : along;
                            final int oz = side.getStepZ() > 0 ? 15 : side.getStepZ() < 0 ? 0 : along;
                            final int y = (s << 4) + ly;
                            if (y >= 0 && y < Chunk.HEIGHT) {
                                packed.setBlock(ox, y, oz, true);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Sets block ({@code x}, {@code y}, {@code z}) relative to chunk ({@code cx}, {@code cz}) solid: in {@code packed},
     * or in the chunk beside it when it's over the edge and that one is packed
     */
    private void setSolid(final int cx, final int cz, final Chunk packed, final int x, final int y, final int z) {
        if (y < 0 || y >= Chunk.HEIGHT) {
            return;
        }
        if (x >= 0 && x < 16 && z >= 0 && z < 16) {
            packed.setBlock(x, y, z, true);
            return;
        }
        final int ncx = cx + (x >> 4);
        final int ncz = cz + (z >> 4);
        if (this.context.hasChunkFromCaller(ncx, ncz)) {
            final Chunk beside = this.context.getChunk(ncx, ncz);
            if (beside != null) {
                beside.setBlock(x & 15, y, z & 15, true);
            }
        }
    }

    /**
     * Sets blocks solid or not: {@code writes}, worked out on the game thread from the world as it is there
     * ({@link #solidAt}), since a changed block can change the ones next to it
     */
    public void queueBlockWrites(final List<Pair<BlockPos, Boolean>> writes) {
        this.writeExecutor.execute(() -> {
            // not inserting or deleting from the cache hashmap but it would still be bad for this function to race with itself
            writeLock.lock();
            try {
                for (final Pair<BlockPos, Boolean> write : writes) {
                    // a block next to the change can be in the chunk beside it
                    final Chunk chunk = this.context.getChunk(write.first().getX() >> 4, write.first().getZ() >> 4);
                    if (chunk == null || !this.context.hasChunkFromCaller(write.first().getX() >> 4, write.first().getZ() >> 4)) {
                        continue; // not packed yet: packing it reads it as it is by then
                    }
                    final BlockPos pos = write.first().below(minY);
                    if (pos.getY() < 0 || pos.getY() >= 384) continue;
                    chunk.setBlock(pos.getX() & 15, pos.getY(), pos.getZ() & 15, write.second());
                }
            } finally {
                writeLock.unlock();
            }
        });
    }

    public CompletableFuture<UnpackedSegment> pathFindAsync(final BlockPos src, final BlockPos dst) {
        return this.pathFindAsync(src, dst, !Baritone.settings().elytraAllowTightSpaces.value);
    }

    /**
     * @param atLeastX4 Whether the path only goes through 4 block cubes of air, rather than 2 block ones
     */
    public CompletableFuture<UnpackedSegment> pathFindAsync(final BlockPos src, final BlockPos dst, final boolean atLeastX4) {
        final BlockPos adjustedSrc = src.below(minY);
        final BlockPos adjustedDst = dst.below(minY);
        boolean generate = Baritone.settings().elytraPredictTerrain.value && this.dimension == Level.NETHER;
        // a search that generates terrain takes the read lock like any other: it only adds whole chunks to the table,
        // which is concurrent, and every reader sees a chunk complete or not at all. under the write lock it would shut
        // the flight's tick out for as long as it runs, up to its 10 s timeout, and a glide moved by steps holds still
        Lock l = readLock;
        ExecutorService exec = readExecutor;
        return CompletableFuture.supplyAsync(() -> {
            l.lock();
            try {
                final NetherPathfinder.Outcome outcome = new NetherPathfinder.Outcome();
                final PathSegment segment = this.context.pathFind(
                        adjustedSrc.getX(), adjustedSrc.getY(), adjustedSrc.getZ(),
                        adjustedDst.getX(), adjustedDst.getY(), adjustedDst.getZ(),
                        atLeastX4,
                        false, // refine
                        10000, // timeoutMs
                        !generate, // useAirIfChunkNotLoaded
                        // TODO: Determine appropriate cost value
                        8.0, // fakeChunkCost
                        outcome
                );
                if (segment == null) {
                    throw new PathCalculationException("Path calculation failed", outcome);
                }

                return new UnpackedSegment(UnpackedSegment.from(segment).collect().stream().map(pos -> pos.above(minY)), segment.finished);
            } finally {
                l.unlock();
            }
        }, exec);
    }

    /**
     * Performs a raytrace from the given start position to the given end position, returning {@code true} if there is
     * visibility between the two points.
     *
     * @param startX The start X coordinate
     * @param startY The start Y coordinate
     * @param startZ The start Z coordinate
     * @param endX   The end X coordinate
     * @param endY   The end Y coordinate
     * @param endZ   The end Z coordinate
     * @return {@code true} if there is visibility between the points
     */
    public boolean raytrace(final double startX, final double startY, final double startZ,
                            final double endX, final double endY, final double endZ) {
        return Raytracer.raytrace(this.context, startX, startY - this.minY, startZ, endX, endY - this.minY, endZ, NetherPathfinder.CacheMiss.SOLID) == null;
    }

    /**
     * Performs a raytrace from the given start position to the given end position, returning {@code true} if there is
     * visibility between the two points.
     *
     * @param start The starting point
     * @param end   The ending point
     * @return {@code true} if there is visibility between the points
     */
    public boolean raytrace(final Vec3 start, final Vec3 end) {
        return raytrace(start.x, start.y, start.z, end.x, end.y, end.z);
    }

    public boolean raytrace(final int count, final double[] src, final double[] dst, final int visibility) {
        if (src.length != count * 3 || dst.length != count * 3) {
            throw new IllegalArgumentException("Bad array lengths");
        }

        for(int i = 1; i < src.length; i+= 3) {
            src[i] -= this.minY;
            dst[i] -= this.minY;
        }

        switch (visibility) {
            case Visibility.ALL:
                for (int i = 0; i < count; i++) {
                    if (!clear(src, dst, i)) {
                        return false;
                    }
                }
                return true;
            case Visibility.NONE:
                for (int i = 0; i < count; i++) {
                    if (clear(src, dst, i)) {
                        return false;
                    }
                }
                return true;
            case Visibility.ANY:
                for (int i = 0; i < count; i++) {
                    if (clear(src, dst, i)) {
                        return true;
                    }
                }
                return false;
            default:
                throw new IllegalArgumentException("lol");
        }
    }

    /** Whether ray i of a batch already in the pathfinder's y range reaches its end. */
    private boolean clear(double[] src, double[] dst, int i) {
        final int o = i * 3;
        return Raytracer.raytrace(this.context, src[o], src[o + 1], src[o + 2], dst[o], dst[o + 1], dst[o + 2], NetherPathfinder.CacheMiss.SOLID) == null;
    }

    public void raytrace(final int count, final double[] src, final double[] dst, final boolean[] hitsOut, final double[] hitPosOut) {
        if (src.length != count * 3 || dst.length != count * 3) {
            throw new IllegalArgumentException("Bad array lengths");
        }

        for(int i = 1; i < src.length; i+= 3) {
            src[i] -= this.minY;
            dst[i] -= this.minY;
        }

        for (int i = 0; i < count; i++) {
            final int o = i * 3;
            final Vec3 hit = Raytracer.raytrace(this.context, src[o], src[o + 1], src[o + 2], dst[o], dst[o + 1], dst[o + 2], NetherPathfinder.CacheMiss.SOLID);
            hitsOut[i] = hit != null;
            if (hit != null && hitPosOut != null) {
                hitPosOut[o] = hit.x;
                hitPosOut[o + 1] = hit.y;
                hitPosOut[o + 2] = hit.z;
            }
        }
    }

    public boolean passable(int x, int y, int z) {
        return !this.boi.get0(x, y, z);
    }

    /**
     * What the pathfinder has at a block
     */
    public enum Held {
        /** No chunk there: a search takes it for air, or for the seed's terrain when predicting, and a ray for solid */
        NOTHING,
        /** Only the seed's guess at the chunk */
        PREDICTED,
        /** A chunk packed from the game's, with nothing there that a glide can't go through */
        AIR,
        /** A chunk packed from the game's, with a block there */
        SOLID
    }

    /**
     * What the pathfinder has at world block ({@code x}, {@code y}, {@code z}). Takes no lock: a chunk being packed as
     * this looks reads as before or after.
     */
    public Held held(final int x, final int y, final int z) {
        final int cx = x >> 4;
        final int cz = z >> 4;
        final Chunk chunk = this.context.getChunk(cx, cz);
        if (chunk == null) {
            return Held.NOTHING;
        }
        if (!this.context.hasChunkFromCaller(cx, cz)) {
            return Held.PREDICTED;
        }
        final int adjustedY = y - this.minY;
        if (adjustedY < 0 || adjustedY >= Chunk.HEIGHT) {
            return Held.AIR;
        }
        return chunk.isSolid(x & 15, adjustedY, z & 15) ? Held.SOLID : Held.AIR;
    }

    public void cancel() {
        this.context.cancel();
    }

    public void destroy() {
        this.cancel();
        // Ignore anything that was queued up, just shutdown the executor
        this.readExecutor.shutdownNow();
        this.writeExecutor.shutdownNow();

        try {
            while (!this.readExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {}
            while (!this.writeExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {}
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        this.context.close();
    }

    public long getSeed() {
        return this.seed;
    }

    public void acquireReadLock() {
        this.readLock.lock();
    }

    public boolean tryAcquireReadLock() {
        return this.readLock.tryLock();
    }

    /**
     * {@link #tryAcquireReadLock()}, and when a chunk is being packed right then, a few milliseconds' wait for that pack
     * to end. A pack holds the lock for one chunk, and when the server sends chunks again and again (its view distance
     * going up and down) the packs come one after another, so a tick that only takes the lock when it happens to be
     * free can miss it tick after tick, and the flight holds still for as long. The wait queues behind a writer that
     * waits for a search, so it gives up after the few milliseconds rather than for the search.
     */
    public boolean tryAcquireReadLockSoon() {
        if (this.readLock.tryLock()) {
            return true;
        }
        try {
            return this.readLock.tryLock(READ_LOCK_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static final long READ_LOCK_WAIT_MILLIS = 5;

    public void releaseReadLock() {
        this.readLock.unlock();
    }

    public int getMaxHeight() {
        return this.maxHeight;
    }

    // per block state id: 0 not looked at yet, 1 a glide goes through it, 2 it doesn't. races only look twice
    private static volatile byte[] throughById;

    /**
     * Whether a glide goes through {@code state} as if it were air: nothing to collide with, and nothing that hurts,
     * slows, holds or carries off the player in it. Signs, banners, torches, buttons, levers, rails, roots, sprouts and
     * the like. Taken as solid, they leave the pathfinder no way along a road with a sign on it, while the game lets
     * the box straight through. Fire, portals, cobwebs, berry bushes, wither roses, powder snow, anything
     * climbable (vines) and anything with fluid in it stay solid.
     */
    public static boolean glidesThrough(final BlockState state) {
        return state != null && (state.isAir() || kind(state) == THROUGH);
    }

    /**
     * Whether {@code state} is solid and its collision box goes on up into the block over it: fences, walls, closed
     * fence gates, a block and a half high. With one bit a block the block over a fence is air, a path can run through
     * it low enough for the box to catch the fence's top, and the glide sticks on it.
     */
    public static boolean reachesAbove(final BlockState state) {
        return state != null && !state.isAir() && kind(state) == REACHES_ABOVE;
    }

    /**
     * Whether the pathfinder has block {@code pos}, holding {@code state}, as solid: something a glide doesn't go
     * through, or the top of a block under it that {@link #reachesAbove}
     */
    public static boolean solidAt(final BlockGetter level, final BlockPos pos, final BlockState state) {
        return !glidesThrough(state) || reachesAbove(level.getBlockState(pos.below()))
                || (Baritone.settings().elytraKeepOffLava.value && nextToBurning(level, pos));
    }

    private static final byte THROUGH = 1;
    private static final byte SOLID = 2;
    private static final byte REACHES_ABOVE = 3;

    private static byte kind(final BlockState state) {
        final int id = Block.getId(state);
        byte[] cache = throughById;
        if (cache == null || id >= cache.length) {
            cache = throughById = new byte[Math.max(id + 1, Block.BLOCK_STATE_REGISTRY.size())];
        }
        if (id >= 0 && cache[id] != 0) {
            return cache[id];
        }
        final byte kind = lookThrough(state) ? THROUGH : reachesUp(state) ? REACHES_ABOVE : SOLID;
        if (id >= 0) {
            cache[id] = kind;
        }
        return kind;
    }

    private static boolean reachesUp(final BlockState state) {
        try {
            final VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            return !shape.isEmpty() && shape.max(Direction.Axis.Y) > 1;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean lookThrough(final BlockState state) {
        if (!state.getFluidState().isEmpty() || state.is(BlockTags.FIRE) || state.is(BlockTags.PORTALS) || state.is(BlockTags.CLIMBABLE)) {
            return false;
        }
        final Block block = state.getBlock();
        // the tags as well as the blocks: tags come from the server, and are empty until it has sent them
        if (block instanceof BaseFireBlock || block instanceof NetherPortalBlock || block instanceof EndPortalBlock
                || block instanceof EndGatewayBlock || block instanceof VineBlock || block instanceof GrowingPlantBlock
                || block instanceof CaveVines || block instanceof ScaffoldingBlock || block instanceof LadderBlock
                || block == Blocks.COBWEB || block == Blocks.SWEET_BERRY_BUSH || block == Blocks.WITHER_ROSE
                || block == Blocks.POWDER_SNOW || block == Blocks.MOVING_PISTON || block == Blocks.BUBBLE_COLUMN) {
            return false;
        }
        try {
            return state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
        } catch (RuntimeException e) {
            // a shape that needs a world to work out: solid
            return false;
        }
    }

    private static boolean reachesAbove(final Palette<BlockState> palette, final int value) {
        try {
            return reachesAbove(palette.valueFor(value));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean glidesThrough(final Palette<BlockState> palette, final int value) {
        try {
            return glidesThrough(palette.valueFor(value));
        } catch (RuntimeException e) {
            // an id the palette doesn't have
            return false;
        }
    }

    /**
     * Whether the server sent {@code section} as empty, a count of no blocks, and it has states other than air in it.
     * The game takes the count from the packet as it is, and a section it says is empty is all air to it: not drawn,
     * nothing to collide with, air to {@link LevelChunk#getBlockState}. The states can be stale palette entries, so
     * whether a block is really there is for {@link LevelChunkSection#getBlockState} to say.
     */
    public static boolean sentEmpty(final LevelChunkSection section) {
        return section != null && section.hasOnlyAir() && section.getStates().maybeHas(state -> !state.isAir());
    }

    /**
     * @return How many of the sections the server sent as empty had blocks to write in them
     */
    private static int writeChunkData(LevelChunk chunk, Chunk packed) {
        int sentEmptyWithBlocks = 0;
        try {
            LevelChunkSection[] chunkInternalStorageArray = chunk.getSections();
            final int maxSections = Math.min(chunkInternalStorageArray.length, 24); // pathfinder support stops at 384/16 sections
            for (int y0 = 0; y0 < maxSections; y0++) {
                final LevelChunkSection extendedblockstorage = chunkInternalStorageArray[y0];
                // a section the server's count says is empty is packed from its blocks all the same, when it has any:
                // they're the server's, which turns down a glide through them, while to the game they're air
                final boolean sentEmpty = sentEmpty(extendedblockstorage);
                if (extendedblockstorage == null || (extendedblockstorage.hasOnlyAir() && !sentEmpty)) {
                    continue;
                }
                boolean wrote = false;
                final PalettedContainer<BlockState> bsc = extendedblockstorage.getStates();
                IPalettedContainer<BlockState> iPalettedContainer = (IPalettedContainer<BlockState>) bsc;
                var palette = iPalettedContainer.getPalette();
                // a global palette's size is every block state there is: those are looked up as they come instead
                final int paletteSize = palette.getSize();
                final boolean[] open = paletteSize <= 256 ? new boolean[paletteSize] : null;
                final boolean[] tall = open != null ? new boolean[paletteSize] : null;
                if (open != null) {
                    boolean anyOpen = false;
                    boolean anyTall = false;
                    for (int i = 0; i < paletteSize; i++) {
                        final BlockState state = palette.valueFor(i);
                        open[i] = glidesThrough(state);
                        tall[i] = reachesAbove(state);
                        anyOpen |= open[i];
                        anyTall |= tall[i];
                    }
                    if (!anyOpen && !anyTall) {
                        packed.fillSection(y0, true);
                        if (sentEmpty) {
                            sentEmptyWithBlocks++;
                        }
                        continue;
                    }
                }
                // pasted from FasterWorldScanner
                final BitStorage array = iPalettedContainer.getStorage();
                if (array == null) continue;
                final long[] longArray = array.getRaw();
                final int arraySize = array.getSize();
                int bitsPerEntry = array.getBits();
                long maxEntryValue = (1L << bitsPerEntry) - 1L;

                final int yReal = y0 << 4;
                for (int i = 0, idx = 0; i < longArray.length && idx < arraySize; ++i) {
                    long l = longArray[i];
                    for (int offset = 0; offset <= (64 - bitsPerEntry) && idx < arraySize; offset += bitsPerEntry, ++idx) {
                        int value = (int) ((l >> offset) & maxEntryValue);
                        int x = (idx & 15);
                        int y = yReal + (idx >> 8);
                        int z = ((idx >> 4) & 15);

                        // a block a glide goes through isn't written at all: a write may allocate the section
                        final boolean through = open != null ? value < open.length && open[value] : glidesThrough(palette, value);
                        if (!through) {
                            packed.setBlock(x, y, z, true);
                            wrote = true;
                        }
                        // the top of a fence or a wall, in the block over it
                        final boolean reaches = tall != null ? value < tall.length && tall[value] : reachesAbove(palette, value);
                        if (reaches && y + 1 < Chunk.HEIGHT) {
                            packed.setBlock(x, y + 1, z, true);
                            wrote = true;
                        }
                    }
                }
                if (sentEmpty && wrote) {
                    sentEmptyWithBlocks++;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
        return sentEmptyWithBlocks;
    }

    public static final class Visibility {
        public static final int ALL = 0;
        public static final int NONE = 1;
        public static final int ANY = 2;
        private Visibility() {}
    }
}
