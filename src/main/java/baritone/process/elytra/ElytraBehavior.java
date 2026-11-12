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
import baritone.api.Settings;
import baritone.api.behavior.look.IAimProcessor;
import baritone.api.behavior.look.ITickableAimProcessor;
import baritone.api.event.events.*;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.process.IGlideControl;
import baritone.api.utils.*;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.process.ElytraProcess;
import baritone.utils.BlockStateInterface;
import baritone.utils.IRenderer;
import baritone.utils.PathRenderer;
import baritone.utils.accessor.IFireworkRocketEntity;
import com.mojang.blaze3d.vertex.BufferBuilder;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.floats.FloatIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntPredicate;
import java.util.function.ToDoubleFunction;
import java.util.function.UnaryOperator;

import static baritone.utils.BaritoneMath.fastCeil;
import static baritone.utils.BaritoneMath.fastFloor;

public final class ElytraBehavior implements Helper {
    private final Baritone baritone;
    private final IPlayerContext ctx;

    // Render stuff
    private final List<Pair<Vec3, Vec3>> clearLines;
    private final List<Pair<Vec3, Vec3>> blockedLines;
    private List<Vec3> simulationLine;
    private BlockPos aimPos;
    private List<BetterBlockPos> visiblePath;

    // :sunglasses:
    public NetherPathfinderContext npfContext;
    public IElytraPathFinder pathFinder;

    public final PathManager pathManager;
    private final ElytraProcess process;

    /**
     * Remaining cool-down ticks between firework usage
     */
    private int remainingFireworkTicks;

    /**
     * Remaining cool-down ticks after the player's position and rotation are reset by the server
     */
    private int remainingSetBackTicks;

    public boolean landingMode;

    /**
     * The step worked out this tick for an {@link IGlideControl} to move the glide by, {@code null} when there is none.
     * {@link ElytraProcess} clears it at the end of every tick.
     */
    public Vec3 glideStep;

    /**
     * Whether a landing moved by steps has made its one rise before touching down
     */
    private boolean landingRose;

    /**
     * Where a glide moved by steps has been over its last {@link #GLIDE_STALL_TICKS} ticks, oldest first, and the tick
     * of the newest
     */
    private final ArrayDeque<Vec3> glideHistory = new ArrayDeque<>();
    private int glideHistoryTick;

    /**
     * Where a glide moved by steps that got nowhere goes first, to see on along the path from there; {@code null} when
     * it isn't going round anything
     */
    private Vec3 glideDetour;
    private int nextGlideDetourSearch;

    /**
     * The first path point the fallback of a glide moved by steps last headed for, the ones before it found behind the
     * box or under it; -1 for none. They stay behind: looked at afresh, a point the path turns back at can be behind
     * the box at it and ahead of the box one step on from it, and the fallback went back and forth between the two
     */
    private int glideFallbackFrom = -1;

    /**
     * The most recent minimum number of firework boost ticks, equivalent to {@code 10 * (1 + Flight)}
     * <p>
     * Updated every time a firework is automatically used
     */
    private int minimumBoostTicks;

    private boolean deployedFireworkLastTick;
    private final int[] nextTickBoostCounter;

    private BlockStateInterface bsi;
    public final BetterBlockPos destination;
    private final boolean appendDestination;
    /**
     * Whether the appended destination is the block to stand in once landed, with its floor right under it, rather
     * than a spot high above the floor
     */
    private final boolean landsOnDestination;

    // short of touching what cut a step: the server moves the player again from its own position
    private static final double GLIDE_STEP_MARGIN = 0.01;
    // a landing moved by steps comes down the middle of its column, which can be a single block wide
    private static final double LANDING_CENTERED = 0.1;
    // the most a landing falls after its rise, short of the 3 blocks of fall that hurt
    private static final double LANDING_FALL = 2.5;
    private static final double LANDING_RISE = 0.05;
    // a landing on the destination block comes in this far over its floor
    private static final double LANDING_HOVER = 0.5;
    // a glide moved by steps that has gone less than GLIDE_STALL_DISTANCE in GLIDE_STALL_TICKS looks for a detour
    private static final int GLIDE_STALL_TICKS = 20;
    private static final double GLIDE_STALL_DISTANCE = 0.5;
    // with nothing ahead in a straight line, a glide moved by steps heads for one of this many points from the nearest
    private static final int GLIDE_FALLBACK_POINTS = 3;
    // a detour is a spot this close that the box gets to in one straight move, looked for on a grid this fine, nearest
    // first, from which one of the next GLIDE_DETOUR_POINTS points is in a straight line
    private static final double GLIDE_DETOUR_RANGE = 2.5;
    private static final double GLIDE_DETOUR_GRID = 0.5;
    private static final int GLIDE_DETOUR_POINTS = 5;
    private static final double GLIDE_DETOUR_REACHED = 0.05;
    // a glide moved by steps is at a spot less than this squared distance from it, and a step there is a step of nothing
    private static final double GLIDE_AT_SQR = 1.0E-6;
    private static final List<Vec3> GLIDE_DETOURS = glideDetours();
    // a path from a player in lava starts at most this far straight up, out of it (lavaStartY)
    static final int LAVA_START_RISE = 64;

    private final ExecutorService solverExecutor;
    private Future<Solution> solver;
    private Solution pendingSolution;
    private boolean solveNextTick;

    private long timeLastCacheCull = 0L;

    // auto swap
    private int invTickCountdown = 0;
    private final Queue<Runnable> invTransactionQueue = new LinkedList<>();

    public ElytraBehavior(Baritone baritone, ElytraProcess process, NetherPathfinderContext npf, BlockPos destination, boolean appendDestination, boolean landsOnDestination) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
        this.clearLines = new CopyOnWriteArrayList<>();
        this.blockedLines = new CopyOnWriteArrayList<>();
        this.pathManager = this.new PathManager();
        this.process = process;
        this.destination = new BetterBlockPos(destination);
        this.appendDestination = appendDestination;
        this.landsOnDestination = landsOnDestination;
        this.solverExecutor = Executors.newSingleThreadExecutor();
        this.nextTickBoostCounter = new int[2];

        this.npfContext = npf;

        if (landsOnDestination) {
            // the block to stand in can be in a tunnel only 2 blocks of air wider than the hitbox, and the last stretch
            // there is short
            this.pathFinder = (src, dst) -> npfContext.pathFindAsync(src, dst, false);
        } else if(ctx.world().dimension() == Level.NETHER) {
            this.pathFinder = Baritone.settings().elytraAllowAboveRoof.value && Baritone.settings().elytraAllowAboveBuildLimit.value
                    ? new BuildLimitPathFinder(ctx, npfContext)
                    : npfContext;
        } else {
            this.pathFinder = Baritone.settings().elytraAllowAboveBuildLimit.value ? new BuildLimitPathFinder(ctx, npfContext) : npfContext;
        }
    }

    public final class PathManager {

        public NetherPath path;
        private boolean completePath;
        private boolean recalculating;

        private int maxPlayerNear;
        private int ticksNearUnchanged;
        private int playerNear;

        public PathManager() {
            // lol imagine initializing fields normally
            this.clear();
        }

        public void tick() {
            // Recalculate closest path node
            this.updatePlayerNear();
            final int prevMaxNear = this.maxPlayerNear;
            this.maxPlayerNear = Math.max(this.maxPlayerNear, this.playerNear);

            if (this.maxPlayerNear == prevMaxNear && ctx.player().isFallFlying()) {
                this.ticksNearUnchanged++;
            } else {
                this.ticksNearUnchanged = 0;
            }

            int minY = ctx.world().dimensionType().minY();
            int y = ctx.playerFeet().y;

            npfContext.acquireReadLock();
            try {
                // Obstacles are more important than an incomplete path, handle those first.
                this.pathfindAroundObstacles();
            } finally {
                npfContext.releaseReadLock();
            }
            this.attemptNextSegment();

        }

        public CompletableFuture<Void> pathToDestination() {
            return this.pathToDestination(this.pathStart());
        }

        /**
         * Where a path from the player starts: its feet, or in lava the first block straight over them out of it
         * ({@link #lavaStartY}). A glide moved by steps goes through lava from inside it, so that stretch is flown.
         */
        private BetterBlockPos pathStart() {
            final BetterBlockPos feet = ctx.playerFeet();
            if (!ctx.player().isInLava()) {
                return feet;
            }
            final Level world = ctx.world();
            final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
            final int y = lavaStartY(feet.y,
                    h -> world.getFluidState(at.set(feet.x, h, feet.z)).is(FluidTags.LAVA),
                    h -> world.getBlockState(at.set(feet.x, h, feet.z)).isAir(),
                    LAVA_START_RISE);
            return y == feet.y ? feet : new BetterBlockPos(feet.x, y, feet.z);
        }

        public CompletableFuture<Void> pathToDestination(final BlockPos from) {
            final long start = System.nanoTime();
            return this.path0(from, destinationFixed(), UnaryOperator.identity())
                    .thenRun(() -> {
                        final double distance = this.path.get(0).distanceTo(this.path.get(this.path.size() - 1));
                        if (this.completePath) {
                            logVerbose(String.format("Computed path (%.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
                        } else {
                            logVerbose(String.format("Computed segment (Next %.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
                        }
                    })
                    .whenComplete((result, ex) -> {
                        this.recalculating = false;
                        if (ex != null) {
                            final Throwable cause = ex.getCause();
                            if (cause instanceof PathCalculationException) {
                                logDirect("Failed to compute path to destination");
                            } else {
                                logUnhandledException(cause);
                            }
                        }
                    });
        }

        public CompletableFuture<Void> pathRecalcSegment(final OptionalInt upToIncl) {
            if (this.recalculating) {
                throw new IllegalStateException("already recalculating");
            }

            this.recalculating = true;
            final List<BetterBlockPos> after = upToIncl.isPresent() ? this.path.subList(upToIncl.getAsInt() + 1, this.path.size()) : Collections.emptyList();
            final boolean complete = this.completePath;

            return this.path0(this.pathStart(), upToIncl.isPresent() ? fixDestination(this.path.get(upToIncl.getAsInt())) : destinationFixed(), segment -> segment.append(after.stream(), complete || (segment.isFinished() && !upToIncl.isPresent())))
                    .whenComplete((result, ex) -> {
                        this.recalculating = false;
                        if (ex != null) {
                            final Throwable cause = ex.getCause();
                            if (cause instanceof PathCalculationException) {
                                logDirect("Failed to recompute segment");
                            } else {
                                logUnhandledException(cause);
                            }
                        }
                    });
        }

        public void pathNextSegment(final int afterIncl) {
            if (this.recalculating) {
                return;
            }

            this.recalculating = true;
            final List<BetterBlockPos> before = this.path.subList(0, afterIncl + 1);
            final long start = System.nanoTime();
            final BetterBlockPos pathStart = this.path.get(afterIncl);

            this.path0(pathStart, destinationFixed(), segment -> segment.prepend(before.stream()))
                    .thenRun(() -> {
                        final int recompute = this.path.size() - before.size() - 1;
                        final double distance = recompute > 0 ? this.path.get(0).distanceTo(this.path.get(recompute)) : 0;

                        if (this.completePath) {
                            logVerbose(String.format("Computed path (%.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
                        } else {
                            logVerbose(String.format("Computed segment (Next %.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
                        }
                    })
                    .whenComplete((result, ex) -> {
                        this.recalculating = false;
                        if (ex != null) {
                            final Throwable cause = ex.getCause();
                            if (cause instanceof PathCalculationException) {
                                logDirect("Failed to compute next segment");
                                if (ctx.player().distanceToSqr(pathStart.getCenter()) < 16 * 16) {
                                    logVerbose("Player is near the segment start, therefore repeating this calculation is pointless. Marking as complete");
                                    completePath = true;
                                }
                            } else {
                                logUnhandledException(cause);
                            }
                        }
                    });
        }

        public void clear() {
            this.path = NetherPath.emptyPath();
            this.completePath = true;
            this.recalculating = false;
            this.playerNear = 0;
            this.ticksNearUnchanged = 0;
            this.maxPlayerNear = 0;
        }

        private void setPath(final UnpackedSegment segment) {
            List<BetterBlockPos> path = segment.collect();
            if (ElytraBehavior.this.appendDestination) {
                BlockPos dest = destinationFixed();
                BlockPos last = !path.isEmpty() ? path.get(path.size() - 1) : null;
                // a block to stand in is reached at its middle, its lower corner touches the blocks beside it
                final Vec3 at = ElytraBehavior.this.landsOnDestination ? Vec3.atBottomCenterOf(dest).add(0, LANDING_HOVER, 0) : Vec3.atLowerCornerOf(dest);
                if (last != null && ElytraBehavior.this.clearView(at, Vec3.atLowerCornerOf(last), false)) {
                    path.add(new BetterBlockPos(dest));
                } else {
                    logDirect("unable to land at " + dest);
                    process.landingSpotIsBad(new BetterBlockPos(dest));
                }
            }
            this.path = new NetherPath(path);
            this.completePath = segment.isFinished();
            // a detour was to see on along the old path, and the points behind were the old path's
            ElytraBehavior.this.glideDetour = null;
            ElytraBehavior.this.glideFallbackFrom = -1;
            this.playerNear = 0;
            this.ticksNearUnchanged = 0;
            this.maxPlayerNear = 0;
        }

        public NetherPath getPath() {
            return this.path;
        }

        public int getNear() {
            return this.playerNear;
        }

        // mickey resigned
        private CompletableFuture<Void> path0(BlockPos src, BlockPos dst, UnaryOperator<UnpackedSegment> operator) {
            return ElytraBehavior.this.pathFinder.pathFindAsync(src, dst)
                    .thenApply(operator)
                    .thenAcceptAsync(this::setPath, ctx.minecraft()::execute);
        }

        // required read lock to be held
        private void pathfindAroundObstacles() {
            if (this.recalculating) {
                return;
            }

            int rangeStartIncl = playerNear;
            int rangeEndExcl = playerNear;
            while (rangeEndExcl < path.size() && npfContext.hasChunk(new ChunkPos(path.get(rangeEndExcl)))) {
                rangeEndExcl++;
            }
            // rangeEndExcl now represents an index either not in the path, or just outside render distance
            if (rangeStartIncl >= rangeEndExcl) {
                // not loaded yet?
                return;
            }
            // before the wall check: a path found while the player's chunk wasn't packed yet runs through blocks that
            // were air to it, lava as well, and once they're packed the nearest point is in one for good. nothing else
            // here re-paths then, and the flight sat still until the whole flight was asked for again
            if (ElytraBehavior.this.process.state != ElytraProcess.State.LANDING && this.ticksNearUnchanged > 100) {
                this.pathRecalcSegment(OptionalInt.of(rangeEndExcl - 1))
                        .thenRun(() -> {
                            logVerbose("Recalculating segment, no progress in last 100 ticks");
                        });
                this.ticksNearUnchanged = 0;
                return;
            }

            final BetterBlockPos rangeStart = path.get(rangeStartIncl);
            if (!ElytraBehavior.this.passable(rangeStart.x, rangeStart.y, rangeStart.z, false)) {
                // we're in a wall
                return; // previous iterations of this function SHOULD have fixed this by now :rage_cat:
            }

            boolean canSeeAny = false;
            for (int i = rangeStartIncl; i < rangeEndExcl - 1; i++) {
                // once we can see one node there's no point casting two more rays to every node after it, and those rays
                // get longer the further along the path we go. the range runs as far as the npf has chunks, which
                // includes regions it read off disk, so this could be thousands of rays a tick
                if (!canSeeAny && (ElytraBehavior.this.clearView(ctx.playerFeetAsVec(), this.path.getVec(i), false) || ElytraBehavior.this.clearView(ctx.playerHead(), this.path.getVec(i), false))) {
                    canSeeAny = true;
                }
                if (!ElytraBehavior.this.clearView(this.path.getVec(i), this.sightOf(i + 1), false)) {
                    // obstacle. where do we return to pathing?
                    // if the end of render distance is closer to goal, then that's fine, otherwise we'd be "digging our hole deeper" and making an already bad backtrack worse
                    OptionalInt rejoinMainPathAt;
                    var dest = destinationFixed();
                    if (this.path.get(rangeEndExcl - 1).distanceSq(dest) < ctx.playerFeet().distanceSq(dest)) {
                        rejoinMainPathAt = OptionalInt.of(rangeEndExcl - 1); // rejoin after current render distance
                    } else {
                        rejoinMainPathAt = OptionalInt.empty(); // large backtrack detected. ignore render distance, rejoin later on
                    }

                    final BetterBlockPos blockage = this.path.get(i);
                    final double distance = ctx.playerFeet().distanceTo(this.path.get(rejoinMainPathAt.orElse(path.size() - 1)));

                    final long start = System.nanoTime();
                    this.pathRecalcSegment(rejoinMainPathAt)
                            .thenRun(() -> {
                                logVerbose(String.format("Recalculated segment around path blockage near %s %s %s (next %.1f blocks in %.4f seconds)",
                                        SettingsUtil.maybeCensor(blockage.x),
                                        SettingsUtil.maybeCensor(blockage.y),
                                        SettingsUtil.maybeCensor(blockage.z),
                                        distance,
                                        (System.nanoTime() - start) / 1e9d
                                ));
                            });
                    return;
                }
            }
            if (!canSeeAny && rangeStartIncl < rangeEndExcl - 2 && process.state != ElytraProcess.State.GET_TO_JUMP) {
                this.pathRecalcSegment(OptionalInt.of(rangeEndExcl - 1)).thenRun(() -> logVerbose("Recalculated segment since no path points were visible"));
            }
        }

        /**
         * Where to look at path point {@code i} from the one before it: the point, or for an appended block to stand in,
         * where the landing comes in over it. That block's lower corner is on its floor, and a ray ending there can
         * count the floor as in the way, which recalculated the path every tick.
         */
        private Vec3 sightOf(final int i) {
            final BetterBlockPos pos = this.path.get(i);
            if (ElytraBehavior.this.appendDestination && ElytraBehavior.this.landsOnDestination
                    && i == this.path.size() - 1 && pos.equals(destinationFixed())) {
                return Vec3.atBottomCenterOf(pos).add(0, LANDING_HOVER, 0);
            }
            return this.path.getVec(i);
        }

        private void attemptNextSegment() {
            if (this.recalculating) {
                return;
            }

            final int last = this.path.size() - 1;
            final BetterBlockPos lastPos = this.path.get(this.path.size() - 1);
            // `ctx.world().isLoaded` cannot be used here as it returns false is the y-value is beyond the build limits.
            if (!this.completePath && ctx.world().getChunkSource().hasChunk(lastPos.x >> 4,lastPos.z >> 4)) {
                this.pathNextSegment(last);
            }
        }

        public void updatePlayerNear() {
            if (this.path.isEmpty()) {
                return;
            }

            final ToDoubleFunction<BetterBlockPos> distance;
            final IGlideControl control = ElytraBehavior.this.process.activeGlideControl();
            if (control != null) {
                // a glide moved by steps puts the box's middle on the points. measured from the feet block, a point
                // the box is on ties with the one below it, and the nearest stays a point behind
                final Vec3 middle = ElytraBehavior.this.glideBox(control).getCenter();
                distance = p -> middle.distanceToSqr(p.x, p.y, p.z);
            } else {
                final BetterBlockPos pos = ctx.playerFeet();
                distance = p -> p.distanceSq(pos);
            }
            int index = this.playerNear;
            for (int i = index; i >= Math.max(index - 1000, 0); i -= 10) {
                if (distance.applyAsDouble(path.get(i)) < distance.applyAsDouble(path.get(index))) {
                    index = i; // intentional: this changes the bound of the loop
                }
            }
            for (int i = index; i < Math.min(index + 1000, path.size()); i += 10) {
                if (distance.applyAsDouble(path.get(i)) < distance.applyAsDouble(path.get(index))) {
                    index = i; // intentional: this changes the bound of the loop
                }
            }
            for (int i = index; i >= Math.max(index - 50, 0); i--) {
                if (distance.applyAsDouble(path.get(i)) < distance.applyAsDouble(path.get(index))) {
                    index = i; // intentional: this changes the bound of the loop
                }
            }
            for (int i = index; i < Math.min(index + 50, path.size()); i++) {
                if (distance.applyAsDouble(path.get(i)) < distance.applyAsDouble(path.get(index))) {
                    index = i; // intentional: this changes the bound of the loop
                }
            }
            this.playerNear = index;
        }

        public boolean isComplete() {
            return this.completePath;
        }
    }

    public void onRenderPass(RenderEvent event) {

        final Settings settings = Baritone.settings();
        if (this.visiblePath != null) {
            PathRenderer.drawPath(event.getModelViewStack(), this.visiblePath, 0, Color.RED, false, 0, 0, 0.0D);
        }
        if (this.aimPos != null) {
            PathRenderer.drawGoal(event.getModelViewStack(), ctx, new GoalBlock(this.aimPos), event.getPartialTicks(), Color.GREEN);
        }
        if (!this.clearLines.isEmpty() && settings.elytraRenderRaytraces.value) {
            BufferBuilder bufferBuilder = IRenderer.startLines(Color.GREEN);
            for (Pair<Vec3, Vec3> line : this.clearLines) {
                IRenderer.emitLine(bufferBuilder, event.getModelViewStack(), line.first(), line.second(), settings.pathRenderLineWidthPixels.value);
            }
            IRenderer.endLines(bufferBuilder, settings.renderPathIgnoreDepth.value);
        }
        if (!this.blockedLines.isEmpty() && Baritone.settings().elytraRenderRaytraces.value) {
            BufferBuilder bufferBuilder = IRenderer.startLines(Color.BLUE);
            for (Pair<Vec3, Vec3> line : this.blockedLines) {
                IRenderer.emitLine(bufferBuilder, event.getModelViewStack(), line.first(), line.second(), settings.pathRenderLineWidthPixels.value);
            }
            IRenderer.endLines(bufferBuilder, settings.renderPathIgnoreDepth.value);
        }
        if (this.simulationLine != null && Baritone.settings().elytraRenderSimulation.value) {
            BufferBuilder bufferBuilder = IRenderer.startLines(new Color(0x36CCDC));
            final Vec3 offset = ctx.player().getPosition(event.getPartialTicks());
            for (int i = 0; i < this.simulationLine.size() - 1; i++) {
                final Vec3 src = this.simulationLine.get(i).add(offset);
                final Vec3 dst = this.simulationLine.get(i + 1).add(offset);
                IRenderer.emitLine(bufferBuilder, event.getModelViewStack(), src, dst, settings.pathRenderLineWidthPixels.value);
            }
            IRenderer.endLines(bufferBuilder, settings.renderPathIgnoreDepth.value);
        }
    }

    public void onChunkEvent(ChunkEvent event) {
        if (event.isPostPopulate() && this.npfContext != null) {
            final LevelChunk chunk = ctx.world().getChunk(event.getX(), event.getZ());
            npfContext.queueForPacking(chunk);
        }
    }

    public void onBlockChange(BlockChangeEvent event) {
        npfContext.queueBlockUpdate(event);
    }

    public void onReceivePacket(PacketEvent event) {
        if (event.getPacket() instanceof ClientboundPlayerPositionPacket) {
            ctx.minecraft().execute(() -> {
                this.remainingSetBackTicks = Baritone.settings().elytraFireworkSetbackUseDelay.value;
            });
        }
    }

    public void pathTo() {
        // a glide moved by steps takes off where it stands, so it needs no spot to jump off from first
        if (!Baritone.settings().elytraAutoJump.value || ctx.player().isFallFlying() || this.process.glidesBySteps()) {
            this.pathManager.pathToDestination();
        }
    }

    public void destroy() {
        if (this.solver != null) {
            this.solver.cancel(true);
        }
        this.solverExecutor.shutdown();
        try {
            while (!this.solverExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {}
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void onTick() {
        if (npfContext.tryAcquireReadLock()) {
            try {
                this.onTick0();
            } finally {
                npfContext.releaseReadLock();
            }
        }
        final long now = System.currentTimeMillis();
        if ((now - this.timeLastCacheCull) / 1000 > Baritone.settings().elytraTimeBetweenCacheCullSecs.value) {
            npfContext.queueCacheCulling(ctx.player().chunkPosition().x, ctx.player().chunkPosition().z, Baritone.settings().elytraCacheCullDistance.value);
            this.timeLastCacheCull = now;
        }
    }

    private void onTick0() {
        // Fetch the previous solution, regardless of if it's going to be used
        this.pendingSolution = null;
        if (this.solver != null) {
            if (this.solver.isDone()) {
                try {
                    this.pendingSolution = this.solver.get();
                } catch (Exception ignored) {
                    // it doesn't matter if get() fails since the solution can just be recalculated synchronously
                } finally {
                    this.solver = null;
                }
            } else {
                // avoid wasting more cycles on a hard solution, we'll do the work synchronously
                this.solver.cancel(true);
                this.solver = null;
            }
        }

        tickInventoryTransactions();

        // Certified mojang employee incident
        if (this.remainingFireworkTicks > 0) {
            this.remainingFireworkTicks--;
        }
        if (this.remainingSetBackTicks > 0) {
            this.remainingSetBackTicks--;
        }
        if (!this.getAttachedFirework().isPresent()) {
            this.minimumBoostTicks = 0;
        }

        // Reset rendered elements
        this.clearLines.clear();
        this.blockedLines.clear();
        this.visiblePath = null;
        this.simulationLine = null;
        this.aimPos = null;

        final List<BetterBlockPos> path = this.pathManager.getPath();
        if (path.isEmpty()) {
            return;
        } else if (this.destination == null) { // null check why????
            this.pathManager.clear();
            return;
        }

        // ctx AND context???? :DDD
        this.bsi = new BlockStateInterface(ctx);
        this.pathManager.tick();

        final int playerNear = this.pathManager.getNear();
        this.visiblePath = path.subList(
                Math.max(playerNear - 30, 0),
                Math.min(playerNear + 100, path.size())
        );
    }

    /**
     * Called by {@link baritone.process.ElytraProcess#onTick(boolean, boolean)} when the process is in control and the player is flying
     */
    public void tick() {
        if (this.pathManager.getPath().isEmpty()) {
            return;
        }
        trySwapElytra();

        if (ctx.player().horizontalCollision) {
            logVerbose("hbonk");
        }
        if (ctx.player().verticalCollision) {
            logVerbose("vbonk");
        }

        final IGlideControl glideControl = this.process.activeGlideControl();
        if (glideControl != null) {
            this.tickGlideStep(glideControl);
            return;
        }

        final SolverContext solverContext = this.new SolverContext(false);
        this.solveNextTick = true;

        // If there's no previously calculated solution to use, or the context used at the end of last tick doesn't match this tick
        final Solution solution;
        if (this.pendingSolution != null && this.pendingSolution.context.equals(solverContext)) {
            solution = this.pendingSolution;
        } else {
            solution = this.solveAngles(solverContext);
        }

        if (this.deployedFireworkLastTick) {
            this.nextTickBoostCounter[solverContext.boost.isBoosted() ? 1 : 0]++;
            this.deployedFireworkLastTick = false;
        }

        final boolean inLava = ctx.player().isInLava();
        if (inLava) {
            baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
        }

        if (solution == null) {
            logVerbose("no solution");
            return;
        }

        baritone.getLookBehavior().updateTarget(solution.rotation, false);

        if (!solution.solvedPitch) {
            logVerbose("no pitch solution, probably gonna crash in a few ticks LOL!!!");
            return;
        } else {
            this.aimPos = new BetterBlockPos(solution.goingTo.x, solution.goingTo.y, solution.goingTo.z);
        }

        this.tickUseFireworks(
                solution.context.start,
                solution.goingTo,
                solution.context.boost.isBoosted(),
                solution.forceUseFirework || inLava
        );
    }

    /**
     * Works out this tick's {@link #glideStep}: towards the furthest point ahead on the path that the hitbox gets to in
     * a straight line, as far as the control's limits let it go this tick, and short of anything that would cut it or
     * slid along it. With no point ahead in a straight line, towards one of the next few, and after getting nowhere for
     * a while, round by a detour first. The control moves the player by it whole, so there are no physics or fireworks
     * to plan for.
     */
    private void tickGlideStep(final IGlideControl control) {
        final AABB box = this.glideBox(control);
        final Vec3 feet = ctx.player().position();
        final double across = Math.max(0, control.maxHorizontalStep());
        final double vertical = Math.max(0, control.maxVerticalStep());

        Vec3 target;
        Vec3 step;
        if (this.landingMode) {
            final Vec3 column = new Vec3(this.destination.x + 0.5, feet.y, this.destination.z + 0.5);
            final Vec3 toColumn = column.subtract(feet);
            if (toColumn.horizontalDistanceSqr() > LANDING_CENTERED * LANDING_CENTERED) {
                // over the middle of the landing column before going down it
                target = column;
                step = this.glideStepTowards(box, feet, column, across, vertical);
            } else {
                final double probe = Math.max(vertical, LANDING_FALL) + 1;
                final double drop = -this.collideGlideStep(box, new Vec3(0, -probe, 0)).y;
                target = feet.subtract(0, drop, 0);
                if (!this.landingRose && drop > LANDING_FALL) {
                    // down to where, after the rise, the rest of the way is too short a fall to hurt. aimed under that
                    // and not at it, where rounding could leave it a hair over and taking steps of nothing
                    step = new Vec3(toColumn.x, -Math.min(vertical, drop - (LANDING_FALL - LANDING_RISE)), toColumn.z);
                } else if (!this.landingRose) {
                    // the server counts every block of a descent as fall, and a rise starts the count again: a little
                    // way up first, so only the last few blocks count
                    this.landingRose = true;
                    step = this.uncutGlideStep(box, new Vec3(toColumn.x, Math.min(LANDING_RISE, vertical), toColumn.z));
                } else {
                    // down onto the floor. the game only puts the player on the ground when the floor cuts a move down
                    // short: a step that stops on it exactly leaves the glide going, and the next one is a step of nothing
                    step = new Vec3(toColumn.x, -vertical, toColumn.z);
                }
            }
        } else {
            final boolean stalled = this.noteGlidePosition(feet);
            if (this.glideDetour != null && this.glideDetour.distanceToSqr(feet) < GLIDE_DETOUR_REACHED * GLIDE_DETOUR_REACHED) {
                this.glideDetour = null;
            }
            final int tick = ctx.player().tickCount;
            // at the last point there's nowhere further to go: it holds still there on purpose, for the next segment or
            // a landing spot
            final boolean pathAhead = this.pathManager.getNear() < this.pathManager.getPath().size() - 1;
            if (stalled && pathAhead && tick >= this.nextGlideDetourSearch) {
                // getting nowhere, on a detour or not: no straight line on from here works with the game's collisions
                this.nextGlideDetourSearch = tick + GLIDE_STALL_TICKS;
                this.glideDetour = this.findGlideDetour(box, feet);
                // the points left behind may be the way after all
                this.glideFallbackFrom = -1;
                logVerbose(this.glideDetour != null ? "No progress, taking a detour" : "No progress, and no detour found");
            }
            final Vec3 ahead = this.glideDetour != null ? this.glideDetour : this.glideTarget(box, feet);
            if (ahead != null) {
                target = ahead;
                step = this.glideStepTowards(box, feet, target, across, vertical);
            } else {
                // nothing ahead in a straight line: the first of the next few points a step gets a good way towards,
                // else the one it gets furthest towards
                final NetherPath path = this.pathManager.getPath();
                final int near = this.pathManager.getNear();
                final boolean avoidLava = this.glideAvoidsLava();
                final int from = Math.min(Math.max(near, this.glideFallbackFrom), path.size() - 1);
                target = this.glideFeetAt(path, from, box);
                step = Vec3.ZERO;
                int firstAhead = -1;
                for (int i = from; i < Math.min(from + GLIDE_FALLBACK_POINTS, path.size()); i++) {
                    final Vec3 point = this.glideFeetAt(path, i, box);
                    final Vec3 want = withinGlideLimits(point.subtract(feet), across, vertical);
                    if (want.lengthSqr() < GLIDE_AT_SQR) {
                        continue; // there already
                    }
                    if (i + 1 < path.size() && point.subtract(feet).dot(this.glideFeetAt(path, i + 1, box).subtract(point)) < 0) {
                        // past it, on the way to the next: the nearest point stays this one until halfway there, and a
                        // step back to it and on again goes nowhere for ever
                        continue;
                    }
                    if (firstAhead < 0) {
                        firstAhead = i;
                    }
                    final Vec3 toward = this.glideStepTowards(box, feet, point, across, vertical);
                    if (avoidLava && this.glideTouchesLava(box, toward)) {
                        // no ray checked this line, and the game lets a glide into lava
                        continue;
                    }
                    if (toward.lengthSqr() > step.lengthSqr()) {
                        target = point;
                        step = toward;
                    }
                    if (toward.lengthSqr() >= 0.25 * 0.25 * want.lengthSqr()) {
                        break;
                    }
                }
                if (firstAhead >= 0) {
                    this.glideFallbackFrom = firstAhead;
                }
            }
        }

        this.glideStep = step;
        this.aimPos = new BetterBlockPos(target.x, target.y, target.z);
        this.simulationLine = Arrays.asList(Vec3.ZERO, target.subtract(feet));

        // the step's heading, flat
        final Vec3 facing = step.horizontalDistanceSqr() > 1.0E-6 ? step : target.subtract(feet);
        final float yaw = facing.horizontalDistanceSqr() > 1.0E-6
                ? RotationUtils.calcRotationFromVec3d(Vec3.ZERO, new Vec3(facing.x, 0, facing.z), ctx.playerRotations()).getYaw()
                : ctx.playerRotations().getYaw();
        baritone.getLookBehavior().updateTarget(new Rotation(yaw, 0), false);
    }

    /**
     * The hitbox a glide moved by steps has to fit: a standing player's when the server has the player standing for
     * part of the glide, else a gliding one's.
     */
    private AABB glideBox(final IGlideControl control) {
        return this.glideBoxAt(control, ctx.player().position());
    }

    /**
     * {@link #glideBox(IGlideControl)} with its feet at {@code feet}
     */
    public AABB glideBoxAt(final IGlideControl control, final Vec3 feet) {
        final Pose pose = control.standsWhileGliding() ? Pose.STANDING : Pose.FALL_FLYING;
        return ctx.player().getDimensions(pose).makeBoundingBox(feet);
    }

    /**
     * Where the feet go for the box to be at path point {@code i}. Points are the middles of empty cubes, so the box's
     * middle goes there: a standing box with its feet there would have its head in the block over a 2 high cube. The
     * landing spot appended to a path is the block to stand in instead, and when that block is on its floor, the
     * landing comes in a little over the floor, as much as the box has room for.
     */
    private Vec3 glideFeetAt(final NetherPath path, final int i, final AABB box) {
        final BetterBlockPos pos = path.get(i);
        if (this.appendDestination && i == path.size() - 1 && pos.equals(destinationFixed())) {
            final Vec3 stand = Vec3.atBottomCenterOf(pos);
            if (!this.landsOnDestination) {
                return stand;
            }
            final AABB standing = box.move(stand.subtract(ctx.player().position()));
            final double room = this.collideGlideStep(standing, new Vec3(0, LANDING_HOVER, 0)).y;
            return stand.add(0, Math.max(0, room - GLIDE_STEP_MARGIN), 0);
        }
        return new Vec3(pos.x, pos.y - box.getYsize() / 2, pos.z);
    }

    /**
     * @return The furthest of the 20 path points after the nearest (or a point between them) that the box gets to in a
     * straight line, {@code null} if none. Not the nearest itself, nor any spot the box is at: a line of no length is
     * clear, and a target there is a step of nothing. Inside lava every ray is blocked, so the spot between two points
     * that the box's steps had brought it onto exactly was the one target left, and the glide held still on it.
     */
    private Vec3 glideTarget(final AABB box, final Vec3 feet) {
        final NetherPath path = this.pathManager.getPath();
        final int near = this.pathManager.getNear();
        final int furthest = Math.min(near + 20, path.size() - 1);

        // with room to spare around the box first
        final AABB roomy = box.inflate(Baritone.settings().elytraMinimumAvoidance.value);
        for (int i = furthest; i > near; i--) {
            final Vec3 dest = this.glideFeetAt(path, i, box);
            if (this.glidesTo(roomy, feet, dest)) {
                return dest;
            }
        }
        // then without, at the points and every block between them
        for (int i = furthest; i > near; i--) {
            final Vec3 dest = this.glideFeetAt(path, i, box);
            if (this.glidesTo(box, feet, dest)) {
                return dest;
            }
            final Vec3 back = this.glideFeetAt(path, i - 1, box).subtract(dest);
            final Vec3 unit = back.normalize();
            final int blocks = fastFloor(back.length());
            for (int b = 1; b < blocks; b++) {
                final Vec3 between = dest.add(unit.scale(b));
                if (this.glidesTo(box, feet, between)) {
                    return between;
                }
            }
        }
        return null;
    }

    /**
     * Whether {@code dest} is somewhere else than the box, that the box gets to from {@code feet} in a straight line
     */
    private boolean glidesTo(final AABB box, final Vec3 feet, final Vec3 dest) {
        final Vec3 offset = dest.subtract(feet);
        return offset.lengthSqr() >= GLIDE_AT_SQR && this.isGlideClear(box, offset);
    }

    /**
     * The step towards {@code target} within the limits, as far along it as nothing cuts, or slid along what's in the
     * way when that gets further towards the target. The game moves the box one axis at a time, up or down first, so a
     * line that clears a ledge can still be cut at its start by the ledge under or over the box.
     */
    private Vec3 glideStepTowards(final AABB box, final Vec3 feet, final Vec3 target, final double across, final double vertical) {
        final Vec3 want = withinGlideLimits(target.subtract(feet), across, vertical);
        if (!this.cutsGlideStep(box, want)) {
            return want;
        }
        final Vec3 straight = this.uncutGlideStep(box, want);
        final Vec3 slid = this.slidGlideStep(box, want);
        final Vec3 toward = target.subtract(feet);
        if (slid.dot(toward) <= straight.dot(toward)) {
            return straight;
        }
        // a slide leaves the line to the target, which is all a ray checked
        return this.glideAvoidsLava() && this.glideTouchesLava(box, slid) ? straight : slid;
    }

    /**
     * Whether a step has to keep out of lava: not when the player is in it already, where every way out goes through it
     */
    private boolean glideAvoidsLava() {
        return !ctx.player().isInLava();
    }

    /**
     * Whether the box anywhere on its way by {@code step} would be in lava as the game counts it: under the surface of a
     * lava block that its box, a hair smaller, overlaps. The collisions let a glide in, and only the path's rays keep it
     * out, which see lava as solid.
     */
    private boolean glideTouchesLava(final AABB box, final Vec3 step) {
        final AABB swept = box.expandTowards(step).deflate(0.001);
        final int minX = Mth.floor(swept.minX);
        final int maxX = Mth.ceil(swept.maxX);
        final int minY = Mth.floor(swept.minY);
        final int maxY = Mth.ceil(swept.maxY);
        final int minZ = Mth.floor(swept.minZ);
        final int maxZ = Mth.ceil(swept.maxZ);
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    pos.set(x, y, z);
                    final FluidState fluid = ctx.world().getFluidState(pos);
                    if (fluid.is(FluidTags.LAVA) && y + fluid.getHeight(ctx.world(), pos) >= swept.minY) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * {@code want} as the game's collisions slide it along what's in the way, kept {@link #GLIDE_STEP_MARGIN} off what
     * stopped it
     */
    private Vec3 slidGlideStep(final AABB box, final Vec3 want) {
        final Vec3 got = this.collideGlideStep(box, want);
        final Vec3 kept = new Vec3(keptOff(got.x, want.x), keptOff(got.y, want.y), keptOff(got.z, want.z));
        // backing off one axis moves the ones after it in the game's order a little, onto what might cut them
        return this.cutsGlideStep(box, kept) ? this.uncutGlideStep(box, kept) : kept;
    }

    private static double keptOff(final double got, final double want) {
        return Math.abs(got - want) > 1.0E-7 ? got - Math.signum(want) * GLIDE_STEP_MARGIN : got;
    }

    /**
     * Notes where the glide is this tick
     *
     * @return Whether it has gone less than {@link #GLIDE_STALL_DISTANCE} over the last {@link #GLIDE_STALL_TICKS}
     * ticks
     */
    private boolean noteGlidePosition(final Vec3 feet) {
        final int tick = ctx.player().tickCount;
        if (tick != this.glideHistoryTick + 1) {
            // a tick with no step worked out (a pause, or the path still coming) held it still on purpose
            this.glideHistory.clear();
        }
        this.glideHistoryTick = tick;
        final boolean stalled = this.glideHistory.size() >= GLIDE_STALL_TICKS
                && this.glideHistory.peekFirst().distanceToSqr(feet) < GLIDE_STALL_DISTANCE * GLIDE_STALL_DISTANCE;
        this.glideHistory.addLast(feet);
        if (this.glideHistory.size() > GLIDE_STALL_TICKS) {
            this.glideHistory.removeFirst();
        }
        return stalled;
    }

    /**
     * @return The nearest spot within {@link #GLIDE_DETOUR_RANGE} that the box gets to in one straight move the game's
     * collisions don't cut, and from which one of the next {@link #GLIDE_DETOUR_POINTS} points after the nearest is in
     * a straight line, {@code null} if none. A path starts at the nearest empty cube to the player, which can be behind
     * a wall the box has to go over first, and its points are cube middles, which a 1.8 high box can't always go
     * between in a straight line.
     */
    private Vec3 findGlideDetour(final AABB box, final Vec3 feet) {
        final NetherPath path = this.pathManager.getPath();
        final int near = this.pathManager.getNear();
        final int last = Math.min(near + GLIDE_DETOUR_POINTS, path.size() - 1);
        for (final Vec3 offset : GLIDE_DETOURS) {
            if (this.cutsGlideStep(box, offset) || !this.isGlideClear(box, offset)) {
                continue;
            }
            final AABB moved = box.move(offset);
            final Vec3 at = feet.add(offset);
            for (int i = last; i > near; i--) {
                final Vec3 on = this.glideFeetAt(path, i, box).subtract(at);
                if (on.lengthSqr() > 0.25 && this.isGlideClear(moved, on)) {
                    return at;
                }
            }
        }
        return null;
    }

    private static List<Vec3> glideDetours() {
        final int n = fastFloor(GLIDE_DETOUR_RANGE / GLIDE_DETOUR_GRID);
        final List<Vec3> offsets = new ArrayList<>();
        for (int x = -n; x <= n; x++) {
            for (int y = -n; y <= n; y++) {
                for (int z = -n; z <= n; z++) {
                    final Vec3 offset = new Vec3(x, y, z).scale(GLIDE_DETOUR_GRID);
                    if (offset.lengthSqr() > 0 && offset.length() <= GLIDE_DETOUR_RANGE) {
                        offsets.add(offset);
                    }
                }
            }
        }
        offsets.sort(Comparator.comparingDouble(Vec3::lengthSqr));
        return Collections.unmodifiableList(offsets);
    }

    /**
     * The level a path from feet at {@code feetY} in lava starts at: the first block straight over them that is not
     * lava, when that is air within {@code maxRise}; else {@code feetY}. The planner takes lava for rock and starts from
     * the nearest air cube it finds, which deep in a lake can be a sealed pocket beside it that no path leaves, so every
     * ask fails at once. Under rock instead of air, the feet as before.
     */
    static int lavaStartY(final int feetY, final IntPredicate lava, final IntPredicate air, final int maxRise) {
        for (int y = feetY + 1; y <= feetY + maxRise; y++) {
            if (lava.test(y)) {
                continue;
            }
            return air.test(y) ? y : feetY;
        }
        return feetY;
    }

    /**
     * Whether the box moved by {@code offset} in a straight line meets no block. The rays start from a grid over the
     * box less than a block apart, so no block fits between them: a standing box is taller than a block, and a block at
     * its waist would pass between its corners.
     */
    private boolean isGlideClear(final AABB box, final Vec3 offset) {
        if (offset.lengthSqr() < 1.0E-12) {
            return true;
        }
        final int nx = fastFloor(box.getXsize()) + 2;
        final int ny = fastFloor(box.getYsize()) + 2;
        final int nz = fastFloor(box.getZsize()) + 2;
        final int count = nx * ny * nz;
        final double[] src = new double[count * 3];
        final double[] dst = new double[count * 3];
        int k = 0;
        for (int ix = 0; ix < nx; ix++) {
            final double x = Mth.lerp(ix / (double) (nx - 1), box.minX, box.maxX);
            for (int iy = 0; iy < ny; iy++) {
                final double y = Mth.lerp(iy / (double) (ny - 1), box.minY, box.maxY);
                for (int iz = 0; iz < nz; iz++) {
                    final double z = Mth.lerp(iz / (double) (nz - 1), box.minZ, box.maxZ);
                    src[k] = x;
                    src[k + 1] = y;
                    src[k + 2] = z;
                    dst[k] = x + offset.x;
                    dst[k + 1] = y + offset.y;
                    dst[k + 2] = z + offset.z;
                    k += 3;
                }
            }
        }
        return this.raytrace(count, src, dst, NetherPathfinderContext.Visibility.ALL);
    }

    /**
     * {@code offset} shortened along its own line until it is within both limits
     */
    private static Vec3 withinGlideLimits(final Vec3 offset, final double across, final double vertical) {
        final double flat = Math.sqrt(offset.horizontalDistanceSqr());
        double scale = 1;
        if (flat > across) {
            scale = across / flat;
        }
        if (Math.abs(offset.y) * scale > vertical) {
            scale = vertical / Math.abs(offset.y);
        }
        return offset.scale(scale);
    }

    /**
     * {@code want}, or as much of it as nothing cuts, stopped {@link #GLIDE_STEP_MARGIN} short of what would
     */
    private Vec3 uncutGlideStep(final AABB box, final Vec3 want) {
        if (!this.cutsGlideStep(box, want)) {
            return want;
        }
        double lo = 0;
        double hi = 1;
        for (int i = 0; i < 12; i++) {
            final double mid = (lo + hi) / 2;
            if (this.cutsGlideStep(box, want.scale(mid))) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        final double length = want.length();
        final double clear = length * lo - GLIDE_STEP_MARGIN;
        return clear > 0 ? want.scale(clear / length) : Vec3.ZERO;
    }

    private boolean cutsGlideStep(final AABB box, final Vec3 want) {
        final Vec3 got = this.collideGlideStep(box, want);
        return Math.abs(got.x - want.x) > 1.0E-7 || Math.abs(got.y - want.y) > 1.0E-7 || Math.abs(got.z - want.z) > 1.0E-7;
    }

    /**
     * {@code want} as the game's collisions leave it, for the box rather than the player's own
     */
    private Vec3 collideGlideStep(final AABB box, final Vec3 want) {
        final Entity player = ctx.player();
        return Entity.collideBoundingBox(player, want, box, ctx.world(), ctx.world().getEntityCollisions(player, box.expandTowards(want)));
    }

    /**
     * @return Whether the player is at the last point of the path
     */
    public boolean isAtPathEnd() {
        final NetherPath path = this.pathManager.getPath();
        if (path.isEmpty()) {
            return false;
        }
        final IGlideControl control = this.process.activeGlideControl();
        final Vec3 end = control != null
                ? this.glideFeetAt(path, path.size() - 1, this.glideBox(control))
                : path.getLast().getCenter();
        return ctx.player().position().distanceToSqr(end) < 1;
    }

    public void onPostTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.IN && this.solveNextTick) {
            // We're at the end of the tick, the player's position likely updated and the closest path node could've
            // changed. Updating it now will avoid unnecessary recalculation on the main thread.
            this.pathManager.updatePlayerNear();

            final SolverContext context = this.new SolverContext(true);
            this.solver = this.solverExecutor.submit(() -> {
                // a path search holds the read lock for up to 10 seconds, and a chunk waiting to be packed queues for
                // the write lock behind it. lock() won't cut in front of a queued writer, so this used to sit here for
                // the whole search while every tick cancelled it and did the full solve on the main thread instead
                // tryLock does cut in line, and the writer is stuck behind the search either way so it loses nothing
                while (!npfContext.tryAcquireReadLock()) {
                    if (Thread.interrupted()) {
                        return null; // the tick gave up on us
                    }
                    LockSupport.parkNanos(100_000L);
                }
                try {
                    return this.solveAngles(context);
                } finally {
                    npfContext.releaseReadLock();
                }
            });
            this.solveNextTick = false;
        }
    }

    // calls passable which requires a read lock
    private Solution solveAngles(final SolverContext context) {
        final NetherPath path = context.path;
        final int playerNear = landingMode ? path.size() - 1 : context.playerNear;
        final Vec3 start = context.start;
        Solution solution = null;

        for (int relaxation = 0; relaxation < 3; relaxation++) { // try for a strict solution first, then relax more and more (if we're in a corner or near some blocks, it will have to relax its constraints a bit)
            int[] heights = context.boost.isBoosted() ? new int[]{20, 10, 5, 0} : new int[]{0}; // attempt to gain height, if we can, so as not to waste the boost
            int lookahead = relaxation == 0 ? 2 : 3; // ideally this would be expressed as a distance in blocks, rather than a number of voxel steps
            //int minStep = Math.max(0, playerNear - relaxation);
            int minStep = playerNear;

            for (int i = Math.min(playerNear + 20, path.size() - 1); i >= minStep; i--) {
                if (Thread.interrupted()) return null; // cancelled by the game thread
                final List<Pair<Vec3, Integer>> candidates = new ArrayList<>();
                for (int dy : heights) {
                    if (relaxation == 0 || i == minStep) {
                        // no interp
                        candidates.add(new Pair<>(path.getVec(i), dy));
                    } else if (relaxation == 1) {
                        final double[] interps = new double[]{1.0, 0.75, 0.5, 0.25};
                        for (double interp : interps) {
                            final Vec3 dest = interp == 1.0
                                    ? path.getVec(i)
                                    : path.getVec(i).scale(interp).add(path.getVec(i - 1).scale(1.0 - interp));
                            candidates.add(new Pair<>(dest, dy));
                        }
                    } else {
                        // Create a point along the segment every block
                        final Vec3 delta = path.getVec(i).subtract(path.getVec(i - 1));
                        final int steps = fastFloor(delta.length());
                        final Vec3 step = delta.normalize();
                        Vec3 stepped = path.getVec(i);
                        for (int interp = 0; interp < steps; interp++) {
                            candidates.add(new Pair<>(stepped, dy));
                            stepped = stepped.subtract(step);
                        }
                    }
                }

                for (final Pair<Vec3, Integer> candidate : candidates) {
                    final Integer augment = candidate.second();
                    Vec3 dest = candidate.first().add(0, augment, 0);
                    if (landingMode) {
                        dest = dest.add(0.5, 0.5, 0.5);
                    }

                    if (augment != 0) {
                        if (i + lookahead >= path.size()) {
                            continue;
                        }
                        if (start.distanceTo(dest) < 40) {
                            if (!this.clearView(dest, path.getVec(i + lookahead).add(0, augment, 0), false)
                                    || !this.clearView(dest, path.getVec(i + lookahead), false)) {
                                // aka: don't go upwards if doing so would prevent us from being able to see the next position **OR** the modified next position
                                continue;
                            }
                        } else {
                            // but if it's far away, allow gaining altitude if we could lose it again by the time we get there
                            if (!this.clearView(dest, path.getVec(i), false)) {
                                continue;
                            }
                        }
                    }

                    final double minAvoidance = Baritone.settings().elytraMinimumAvoidance.value;
                    final Double growth = relaxation == 2 ? null
                            : relaxation == 0 ? 2 * minAvoidance : minAvoidance;

                    if (this.isHitboxClear(context, dest, growth)) {
                        // Yaw is trivial, just calculate the rotation required to face the destination
                        final float yaw = RotationUtils.calcRotationFromVec3d(start, dest, ctx.playerRotations()).getYaw();

                        final Pair<Float, Boolean> pitch = this.solvePitch(context, dest, relaxation);
                        if (pitch == null) {
                            solution = new Solution(context, new Rotation(yaw, ctx.playerRotations().getPitch()), null, false, false);
                            continue;
                        }

                        // A solution was found with yaw AND pitch, so just immediately return it.
                        return new Solution(context, new Rotation(yaw, pitch.first()), dest, true, pitch.second());
                    }
                }
            }
        }
        return solution;
    }

    private void tickUseFireworks(final Vec3 start, final Vec3 goingTo, final boolean isBoosted, final boolean forceUseFirework) {
        if (this.remainingSetBackTicks > 0) {
            logDebug("waiting for elytraFireworkSetbackUseDelay: " + this.remainingSetBackTicks);
            return;
        }
        if (this.landingMode) {
            return;
        }
        final boolean useOnDescend = !Baritone.settings().elytraConserveFireworks.value || ctx.player().position().y < goingTo.y + 5;
        final double currentSpeed = new Vec3(
                ctx.player().getDeltaMovement().x,
                // ignore y component if we are BOTH below where we want to be AND descending
                ctx.player().position().y < goingTo.y ? Math.max(0, ctx.player().getDeltaMovement().y) : ctx.player().getDeltaMovement().y,
                ctx.player().getDeltaMovement().z
        ).lengthSqr();

        final double elytraFireworkSpeed = Baritone.settings().elytraFireworkSpeed.value;
        if (this.remainingFireworkTicks <= 0 && (forceUseFirework || (!isBoosted
                && useOnDescend
                && (ctx.player().position().y < goingTo.y - 5 || start.distanceTo(new Vec3(goingTo.x + 0.5, ctx.player().position().y, goingTo.z + 0.5)) > 5) // UGH!!!!!!!
                && currentSpeed < elytraFireworkSpeed * elytraFireworkSpeed))
        ) {
            // Prioritize boosting fireworks over regular ones
            // TODO: Take the minimum boost time into account?
            if (!baritone.getInventoryBehavior().throwaway(true, ElytraBehavior::isBoostingFireworks) &&
                    !baritone.getInventoryBehavior().throwaway(true, ElytraBehavior::isFireworks)) {
                logDirect("no fireworks");
                return;
            }
            logVerbose("attempting to use firework" + (forceUseFirework ? " (forced)" : ""));
            ctx.playerController().processRightClick(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND);
            this.minimumBoostTicks = 10 * (1 + getFireworkBoost(ctx.player().getItemInHand(InteractionHand.MAIN_HAND)).orElse(0));
            this.remainingFireworkTicks = 10;
            this.deployedFireworkLastTick = true;
        }
    }

    private final class SolverContext {

        public final NetherPath path;
        public final int playerNear;
        public final Vec3 start;
        public final Vec3 motion;
        public final AABB boundingBox;
        public final boolean ignoreLava;
        public final FireworkBoost boost;
        public final IAimProcessor aimProcessor;

        /**
         * Creates a new SolverContext using the current state of the path, player, and firework boost at the time of
         * construction.
         *
         * @param async Whether the computation is being done asynchronously at the end of a game tick.
         */
        public SolverContext(boolean async) {
            this.path = ElytraBehavior.this.pathManager.getPath();
            this.playerNear = ElytraBehavior.this.pathManager.getNear();

            this.start = ctx.playerFeetAsVec();
            this.motion = ctx.playerMotion();
            this.boundingBox = ctx.player().getBoundingBox();
            this.ignoreLava = ctx.player().isInLava();

            final Integer fireworkTicksExisted;
            if (async && ElytraBehavior.this.deployedFireworkLastTick) {
                final int[] counter = ElytraBehavior.this.nextTickBoostCounter;
                fireworkTicksExisted = counter[1] > counter[0] ? 0 : null;
            } else {
                fireworkTicksExisted = ElytraBehavior.this.getAttachedFirework().map(e -> e.tickCount).orElse(null);
            }
            this.boost = new FireworkBoost(fireworkTicksExisted, ElytraBehavior.this.minimumBoostTicks);

            ITickableAimProcessor aim = ElytraBehavior.this.baritone.getLookBehavior().getAimProcessor().fork();
            if (async) {
                // async computation is done at the end of a tick, advance by 1 to prepare for the next tick
                aim.advance(1);
            }
            this.aimProcessor = aim;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || o.getClass() != SolverContext.class) {
                return false;
            }

            SolverContext other = (SolverContext) o;
            return this.path == other.path  // Contents aren't modified, just compare by reference
                    && this.playerNear == other.playerNear
                    && Objects.equals(this.start, other.start)
                    && Objects.equals(this.motion, other.motion)
                    && Objects.equals(this.boundingBox, other.boundingBox)
                    && this.ignoreLava == other.ignoreLava
                    && Objects.equals(this.boost, other.boost);
        }
    }

    private static final class FireworkBoost {

        private final Integer fireworkTicksExisted;
        private final int minimumBoostTicks;
        private final int maximumBoostTicks;

        /**
         * @param fireworkTicksExisted The ticksExisted of the attached firework entity, or {@code null} if no entity.
         * @param minimumBoostTicks    The minimum number of boost ticks that the attached firework entity, if any, will
         *                             provide.
         */
        public FireworkBoost(final Integer fireworkTicksExisted, final int minimumBoostTicks) {
            this.fireworkTicksExisted = fireworkTicksExisted;

            // this.lifetime = 10 * i + this.rand.nextInt(6) + this.rand.nextInt(7);
            this.minimumBoostTicks = minimumBoostTicks;
            this.maximumBoostTicks = minimumBoostTicks + 11;
        }

        public boolean isBoosted() {
            return this.fireworkTicksExisted != null;
        }

        /**
         * @return The guaranteed number of remaining ticks with boost
         */
        public int getGuaranteedBoostTicks() {
            return this.isBoosted() ? Math.max(0, this.minimumBoostTicks - this.fireworkTicksExisted) : 0;
        }

        /**
         * @return The maximum number of remaining ticks with boost
         */
        public int getMaximumBoostTicks() {
            return this.isBoosted() ? Math.max(0, this.maximumBoostTicks - this.fireworkTicksExisted) : 0;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || o.getClass() != FireworkBoost.class) {
                return false;
            }

            FireworkBoost other = (FireworkBoost) o;
            if (!this.isBoosted() && !other.isBoosted()) {
                return true;
            }

            return Objects.equals(this.fireworkTicksExisted, other.fireworkTicksExisted)
                    && this.minimumBoostTicks == other.minimumBoostTicks
                    && this.maximumBoostTicks == other.maximumBoostTicks;
        }
    }

    private static final class PitchResult {

        public final float pitch;
        public final double dot;
        public final List<Vec3> steps;

        public PitchResult(float pitch, double dot, List<Vec3> steps) {
            this.pitch = pitch;
            this.dot = dot;
            this.steps = steps;
        }
    }

    private static final class Solution {

        public final SolverContext context;
        public final Rotation rotation;
        public final Vec3 goingTo;
        public final boolean solvedPitch;
        public final boolean forceUseFirework;

        public Solution(SolverContext context, Rotation rotation, Vec3 goingTo, boolean solvedPitch, boolean forceUseFirework) {
            this.context = context;
            this.rotation = rotation;
            this.goingTo = goingTo;
            this.solvedPitch = solvedPitch;
            this.forceUseFirework = forceUseFirework;
        }
    }

    public static boolean isFireworks(final ItemStack itemStack) {
        if (itemStack.getItem() != Items.FIREWORK_ROCKET) {
            return false;
        }
        Fireworks fw = itemStack.get(DataComponents.FIREWORKS);
        return fw != null && fw.explosions().isEmpty();
    }

    private static boolean isBoostingFireworks(final ItemStack itemStack) {
        return getFireworkBoost(itemStack).isPresent();
    }

    private static OptionalInt getFireworkBoost(final ItemStack itemStack) {
        Fireworks fw = itemStack.get(DataComponents.FIREWORKS);
        if (fw != null && fw.explosions().isEmpty()) {
            return OptionalInt.of(fw.flightDuration());
        }
        return OptionalInt.empty();
    }

    private Optional<FireworkRocketEntity> getAttachedFirework() {
        return ctx.entitiesStream()
                .filter(x -> x instanceof FireworkRocketEntity)
                .filter(x -> Objects.equals(((IFireworkRocketEntity) x).getBoostedEntity(), ctx.player()))
                .map(x -> (FireworkRocketEntity) x)
                .findFirst();
    }

    private boolean isHitboxClear(final SolverContext context, final Vec3 dest, final Double growAmount) {
        final Vec3 start = context.start;
        final boolean ignoreLava = context.ignoreLava;

        if (!this.clearView(start, dest, ignoreLava)) {
            return false;
        }
        if (growAmount == null) {
            return true;
        }

        final AABB bb = context.boundingBox.inflate(growAmount);

        final double ox = dest.x - start.x;
        final double oy = dest.y - start.y;
        final double oz = dest.z - start.z;

        final double[] src = new double[]{
                bb.minX, bb.minY, bb.minZ,
                bb.minX, bb.minY, bb.maxZ,
                bb.minX, bb.maxY, bb.minZ,
                bb.minX, bb.maxY, bb.maxZ,
                bb.maxX, bb.minY, bb.minZ,
                bb.maxX, bb.minY, bb.maxZ,
                bb.maxX, bb.maxY, bb.minZ,
                bb.maxX, bb.maxY, bb.maxZ,
        };
        final double[] dst = new double[]{
                bb.minX + ox, bb.minY + oy, bb.minZ + oz,
                bb.minX + ox, bb.minY + oy, bb.maxZ + oz,
                bb.minX + ox, bb.maxY + oy, bb.minZ + oz,
                bb.minX + ox, bb.maxY + oy, bb.maxZ + oz,
                bb.maxX + ox, bb.minY + oy, bb.minZ + oz,
                bb.maxX + ox, bb.minY + oy, bb.maxZ + oz,
                bb.maxX + ox, bb.maxY + oy, bb.minZ + oz,
                bb.maxX + ox, bb.maxY + oy, bb.maxZ + oz,
        };

        // Use non-batching method without early failure
        if (Baritone.settings().elytraRenderHitboxRaytraces.value) {
            boolean clear = true;
            for (int i = 0; i < 8; i++) {
                final Vec3 s = new Vec3(src[i * 3], src[i * 3 + 1], src[i * 3 + 2]);
                final Vec3 d = new Vec3(dst[i * 3], dst[i * 3 + 1], dst[i * 3 + 2]);
                // Don't forward ignoreLava since the batch call doesn't care about it
                if (!this.clearView(s, d, false)) {
                    clear = false;
                }
            }
            return clear;
        }


        return raytrace(8, src, dst, NetherPathfinderContext.Visibility.ALL);
    }

    public boolean clearView(Vec3 start, Vec3 dest, boolean ignoreLava) {
        final boolean clear;
        if (!ignoreLava) {
            // if start == dest then the cpp raytracer dies
            clear = start.equals(dest) || raytrace(start, dest);
        } else {
            clear = ctx.world().clip(new ClipContext(start, dest, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.player())).getType() == HitResult.Type.MISS;
        }

        if (Baritone.settings().elytraRenderRaytraces.value) {
            (clear ? this.clearLines : this.blockedLines).add(new Pair<>(start, dest));
        }
        return clear;
    }

    private static FloatArrayList pitchesToSolveFor(final float goodPitch, final boolean desperate) {
        final float minPitch = desperate ? -90 : Math.max(goodPitch - Baritone.settings().elytraPitchRange.value, -89);
        final float maxPitch = desperate ? 90 : Math.min(goodPitch + Baritone.settings().elytraPitchRange.value, 89);

        final FloatArrayList pitchValues = new FloatArrayList(fastCeil(maxPitch - minPitch) + 1);
        for (float pitch = goodPitch; pitch <= maxPitch; pitch++) {
            pitchValues.add(pitch);
        }
        for (float pitch = goodPitch - 1; pitch >= minPitch; pitch--) {
            pitchValues.add(pitch);
        }

        return pitchValues;
    }

    @FunctionalInterface
    private interface IntTriFunction<T> {
        T apply(int first, int second, int third);
    }

    private static final class IntTriple {
        public final int first;
        public final int second;
        public final int third;

        public IntTriple(int first, int second, int third) {
            this.first = first;
            this.second = second;
            this.third = third;
        }
    }

    private Pair<Float, Boolean> solvePitch(final SolverContext context, final Vec3 goal, final int relaxation) {
        final boolean desperate = relaxation == 2;
        final float goodPitch = RotationUtils.calcRotationFromVec3d(context.start, goal, ctx.playerRotations()).getPitch();
        final FloatArrayList pitches = pitchesToSolveFor(goodPitch, desperate);

        final IntTriFunction<PitchResult> solve = (ticks, ticksBoosted, ticksBoostDelay) ->
                this.solvePitch(context, goal, relaxation, pitches.iterator(), ticks, ticksBoosted, ticksBoostDelay);

        final List<IntTriple> tests = new ArrayList<>();

        if (context.boost.isBoosted()) {
            final int guaranteed = context.boost.getGuaranteedBoostTicks();
            if (guaranteed == 0) {
                // uncertain when boost will run out
                final int lookahead = Math.max(4, 10 - context.boost.getMaximumBoostTicks());
                tests.add(new IntTriple(lookahead, 1, 0));
            } else if (guaranteed <= 5) {
                // boost will run out within 5 ticks
                tests.add(new IntTriple(guaranteed + 5, guaranteed, 0));
            } else {
                // there's plenty of guaranteed boost
                tests.add(new IntTriple(guaranteed + 1, guaranteed, 0));
            }
        }

        // Standard test, assume (not) boosted for entire duration
        final int ticks = desperate ? 3 : context.boost.isBoosted() ? Math.max(5, context.boost.getGuaranteedBoostTicks()) : Baritone.settings().elytraSimulationTicks.value;
        tests.add(new IntTriple(ticks, context.boost.isBoosted() ? ticks : 0, 0));

        final Optional<PitchResult> result = tests.stream()
                .map(i -> solve.apply(i.first, i.second, i.third))
                .filter(Objects::nonNull)
                .findFirst();
        if (result.isPresent()) {
            return new Pair<>(result.get().pitch, false);
        }

        // If we used a firework would we be able to get out of the current situation??? perhaps
        if (desperate) {
            final List<IntTriple> testsBoost = new ArrayList<>();
            testsBoost.add(new IntTriple(ticks, 10, 3));
            testsBoost.add(new IntTriple(ticks, 10, 2));
            testsBoost.add(new IntTriple(ticks, 10, 1));

            final Optional<PitchResult> resultBoost = testsBoost.stream()
                    .map(i -> solve.apply(i.first, i.second, i.third))
                    .filter(Objects::nonNull)
                    .findFirst();
            if (resultBoost.isPresent()) {
                return new Pair<>(resultBoost.get().pitch, true);
            }
        }

        return null;
    }

    private PitchResult solvePitch(final SolverContext context, final Vec3 goal, final int relaxation,
                                   final FloatIterator pitches, final int ticks, final int ticksBoosted,
                                   final int ticksBoostDelay) {
        // we are at a certain velocity, but we have a target velocity
        // what pitch would get us closest to our target velocity?
        // yaw is easy so we only care about pitch

        final Vec3 goalDelta = goal.subtract(context.start);
        final Vec3 goalDirection = goalDelta.normalize();

        final Deque<PitchResult> bestResults = new ArrayDeque<>();

        while (pitches.hasNext()) {
            final float pitch = pitches.nextFloat();
            final List<Vec3> displacement = this.simulate(
                    context,
                    goalDelta,
                    pitch,
                    ticks,
                    ticksBoosted,
                    ticksBoostDelay
            );
            if (displacement == null) {
                continue;
            }
            final Vec3 last = displacement.get(displacement.size() - 1);
            double goodness = goalDirection.dot(last.normalize());
            if (landingMode) {
                goodness = -goalDelta.subtract(last).length();
            }
            final PitchResult bestSoFar = bestResults.peek();
            if (bestSoFar == null || goodness > bestSoFar.dot) {
                bestResults.push(new PitchResult(pitch, goodness, displacement));
            }
        }

        outer:
        for (final PitchResult result : bestResults) {
            if (relaxation < 2) {
                // Ensure that the goal is visible along the entire simulated path
                // Reverse order iteration since the last position is most likely to fail
                for (int i = result.steps.size() - 1; i >= 1; i--) {
                    if (!clearView(context.start.add(result.steps.get(i)), goal, context.ignoreLava)) {
                        continue outer;
                    }
                }
            } else {
                // Ensure that the goal is visible from the final position
                if (!clearView(context.start.add(result.steps.get(result.steps.size() - 1)), goal, context.ignoreLava)) {
                    continue;
                }
            }

            this.simulationLine = result.steps;
            return result;
        }
        return null;
    }

    private List<Vec3> simulate(final SolverContext context, final Vec3 goalDelta, final float pitch, final int ticks,
                                final int ticksBoosted, final int ticksBoostDelay) {
        final ITickableAimProcessor aimProcessor = context.aimProcessor.fork();
        Vec3 delta = goalDelta;
        Vec3 motion = context.motion;
        AABB hitbox = context.boundingBox;
        List<Vec3> displacement = new ArrayList<>(ticks + 1);
        displacement.add(Vec3.ZERO);
        int remainingTicksBoosted = ticksBoosted;

        for (int i = 0; i < ticks; i++) {
            final double cx = hitbox.minX + (hitbox.maxX - hitbox.minX) * 0.5D;
            final double cz = hitbox.minZ + (hitbox.maxZ - hitbox.minZ) * 0.5D;
            if (delta.lengthSqr() < 1) {
                break;
            }
            final Rotation rotation = aimProcessor.nextRotation(
                    RotationUtils.calcRotationFromVec3d(Vec3.ZERO, delta, ctx.playerRotations()).withPitch(pitch)
            );
            final Vec3 lookDirection = RotationUtils.calcLookDirectionFromRotation(rotation);

            motion = step(motion, lookDirection, rotation.getPitch());
            delta = delta.subtract(motion);

            // Collision box while the player is in motion, with additional padding for safety
            // Use expandTowards for directional swept volume (fixes #5049)
            // expandTowards handles negative vectors correctly (unlike inflate)
            // and provides full swept volume coverage (unlike move)
            final AABB inMotion = hitbox.expandTowards(motion.x, motion.y, motion.z).inflate(0.01);

            int xmin = fastFloor(inMotion.minX);
            int xmax = fastCeil(inMotion.maxX);
            int ymin = fastFloor(inMotion.minY);
            int ymax = fastCeil(inMotion.maxY);
            int zmin = fastFloor(inMotion.minZ);
            int zmax = fastCeil(inMotion.maxZ);
            for (int x = xmin; x < xmax; x++) {
                for (int y = ymin; y < ymax; y++) {
                    for (int z = zmin; z < zmax; z++) {
                        if (!this.passable(x, y, z, context.ignoreLava)) {
                            return null;
                        }
                    }
                }
            }

            hitbox = hitbox.move(motion);
            displacement.add(displacement.get(displacement.size() - 1).add(motion));

            if (i >= ticksBoostDelay && remainingTicksBoosted-- > 0) {
                // See EntityFireworkRocket
                motion = motion.add(
                        lookDirection.x * 0.1 + (lookDirection.x * 1.5 - motion.x) * 0.5,
                        lookDirection.y * 0.1 + (lookDirection.y * 1.5 - motion.y) * 0.5,
                        lookDirection.z * 0.1 + (lookDirection.z * 1.5 - motion.z) * 0.5
                );
            }
        }

        return displacement;
    }

    private static Vec3 step(final Vec3 motion, final Vec3 lookDirection, final float pitch) {
        double motionX = motion.x;
        double motionY = motion.y;
        double motionZ = motion.z;

        float pitchRadians = pitch * RotationUtils.DEG_TO_RAD_F;
        double pitchBase2 = Math.sqrt(lookDirection.x * lookDirection.x + lookDirection.z * lookDirection.z);
        double flatMotion = Math.sqrt(motionX * motionX + motionZ * motionZ);
        double thisIsAlwaysOne = lookDirection.length();
        float pitchBase3 = Mth.cos(pitchRadians);
        //System.out.println("always the same lol " + -pitchBase + " " + pitchBase3);
        //System.out.println("always the same lol " + Math.abs(pitchBase3) + " " + pitchBase2);
        //System.out.println("always 1 lol " + thisIsAlwaysOne);
        pitchBase3 = (float) ((double) pitchBase3 * (double) pitchBase3 * Math.min(1, thisIsAlwaysOne / 0.4));
        motionY += -0.08 + (double) pitchBase3 * 0.06;
        if (motionY < 0 && pitchBase2 > 0) {
            double speedModifier = motionY * -0.1 * (double) pitchBase3;
            motionY += speedModifier;
            motionX += lookDirection.x * speedModifier / pitchBase2;
            motionZ += lookDirection.z * speedModifier / pitchBase2;
        }
        if (pitchRadians < 0) { // if you are looking down (below level)
            double anotherSpeedModifier = flatMotion * (double) (-Mth.sin(pitchRadians)) * 0.04;
            motionY += anotherSpeedModifier * 3.2;
            motionX -= lookDirection.x * anotherSpeedModifier / pitchBase2;
            motionZ -= lookDirection.z * anotherSpeedModifier / pitchBase2;
        }
        if (pitchBase2 > 0) { // this is always true unless you are looking literally straight up (let's just say the bot will never do that)
            motionX += (lookDirection.x / pitchBase2 * flatMotion - motionX) * 0.1;
            motionZ += (lookDirection.z / pitchBase2 * flatMotion - motionZ) * 0.1;
        }
        motionX *= 0.99f;
        motionY *= 0.98f;
        motionZ *= 0.99f;
        //System.out.println(motionX + " " + motionY + " " + motionZ);

        return new Vec3(motionX, motionY, motionZ);
    }

    // any call to this must be done with the lock held
    private boolean passable(int x, int y, int z, boolean ignoreLava) {
        if (ignoreLava) {
            final BlockState state = this.bsi.get0(x, y, z);
            return state.getBlock() instanceof AirBlock || MovementHelper.isLava(state);
        } else {
            return passable(x, y, z);
        }
    }

    private void tickInventoryTransactions() {
        if (invTickCountdown <= 0) {
            Runnable r = invTransactionQueue.poll();
            if (r != null) {
                r.run();
                invTickCountdown = Baritone.settings().ticksBetweenInventoryMoves.value;
            }
        }
        if (invTickCountdown > 0) invTickCountdown--;
    }

    private void queueWindowClick(int windowId, int slotId, int button, ClickType type) {
        invTransactionQueue.add(() -> ctx.playerController().windowClick(windowId, slotId, button, type, ctx.player()));
    }

    private int findGoodElytra() {
        NonNullList<ItemStack> invy = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < invy.size(); i++) {
            ItemStack slot = invy.get(i);
            if (slot.getItem() == Items.ELYTRA && (slot.getMaxDamage() - slot.getDamageValue()) > Baritone.settings().elytraMinimumDurability.value) {
                return i;
            }
        }
        return -1;
    }

    private void trySwapElytra() {
        if (!Baritone.settings().elytraAutoSwap.value || !invTransactionQueue.isEmpty()) {
            return;
        }

        ItemStack chest = ctx.player().getItemBySlot(EquipmentSlot.CHEST);
        if (chest.getItem() != Items.ELYTRA
                || chest.getMaxDamage() - chest.getDamageValue() > Baritone.settings().elytraMinimumDurability.value) {
            return;
        }

        int goodElytraSlot = findGoodElytra();
        if (goodElytraSlot != -1) {
            final int CHEST_SLOT = 6;
            final int slotId = goodElytraSlot < 9 ? goodElytraSlot + 36 : goodElytraSlot;
            queueWindowClick(ctx.player().inventoryMenu.containerId, slotId, 0, ClickType.PICKUP);
            queueWindowClick(ctx.player().inventoryMenu.containerId, CHEST_SLOT, 0, ClickType.PICKUP);
            queueWindowClick(ctx.player().inventoryMenu.containerId, slotId, 0, ClickType.PICKUP);
        }
    }

    void logVerbose(String message) {
        if (Baritone.settings().elytraChatSpam.value) {
            logDebug(message);
        }
    }

    // so we don't get stuck trying to pathfind through the roof
    private BetterBlockPos fixDestination(BetterBlockPos dst) {
        if (ctx.world().dimension() == Level.NETHER) {
            if (ctx.player().getY() >= 128 && dst.y < 128) {
                return new BetterBlockPos(dst.x, 128, dst.z);
            }
            else if (ctx.player().getY() < 128 && dst.y >= 128) {
                return new BetterBlockPos(dst.x, 64, dst.z);
            }
        }
        return dst;
    }

    private BetterBlockPos destinationFixed() {
        return fixDestination(this.destination);
    }

    public boolean raytrace(double startX, double startY, double startZ, double endX, double endY, double endZ) {
        final int maxHeight = npfContext.getMaxHeight() + ctx.world().getMinY();
        final int minHeight = ctx.world().getMinY();
        final boolean isOOB = startY >= maxHeight || endY >= maxHeight || startY < minHeight || endY < minHeight;
        if (isOOB) {
            Vec3 start = new Vec3(startX, startY, startZ);
            Vec3 end = new Vec3(endX, endY, endZ);
            return ctx.world().clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.player())).getType() == HitResult.Type.MISS;
        }

        return npfContext.raytrace(startX, startY, startZ, endX, endY, endZ);
    }

    public boolean raytrace(Vec3 start, Vec3 end) {
        final int maxHeight = npfContext.getMaxHeight() + ctx.world().getMinY();
        final int minHeight = ctx.world().getMinY();
        final boolean isOOB = start.y >= maxHeight || end.y >= maxHeight || start.y < minHeight || end.y < minHeight;
        if (isOOB) {
            return ctx.world().clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.player())).getType() == HitResult.Type.MISS;
        }
        return npfContext.raytrace(start.x, start.y, start.z, end.x, end.y, end.z);
    }

    public boolean raytrace(int count, double[] src, double[] dst, int visibility) {
        if (src.length != count * 3 || src.length != dst.length) {
            throw new IllegalArgumentException("Expected source and dst to have length of " + (count * 3));
        }
        final int maxHeight = npfContext.getMaxHeight() + ctx.world().getMinY();

        boolean isOOB = false;
        for(int i = 1; i < src.length; i += 3) {
            if (src[i] >= maxHeight || src[i] < ctx.world().getMinY() ||
                    dst[i] >= maxHeight || dst[i] < ctx.world().getMinY()) {
                isOOB = true;
                break;
            }
        }

        if(isOOB) {
            for (int i = 0; i < count; i++) {
                Vec3 start = new Vec3(src[i * 3], src[i * 3 + 1], src[i * 3 + 2]);
                Vec3 end = new Vec3(dst[i * 3], dst[i * 3 + 1], dst[i * 3 + 2]);
                if (ctx.world().clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.player())).getType() != HitResult.Type.MISS) {
                    return false;
                }
            }
            return true;
        }

        return npfContext.raytrace(count, src, dst, visibility);
    }

    public boolean passable(int x, int y, int z) {
        if(y >= ctx.world().getMaxY() || y < ctx.world().getMinY()) {
            return true;
        }
        return npfContext.passable(x, y, z);
    }
}
