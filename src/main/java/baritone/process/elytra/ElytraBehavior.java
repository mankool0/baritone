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
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
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
import java.util.function.BiPredicate;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
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
     * The step worked out this tick to move the glide by ({@link GlideSteps}), {@code null} when there is none.
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
    // the glide's steps over the last second, oldest first, for the line when it catches fire: the game says so a few
    // ticks after the move that did it
    private final ArrayDeque<GlideMove> recentMoves = new ArrayDeque<>();
    private static final int RECENT_MOVES = 100;
    // how far a step keeps the box from lava and fire, every way: the server sets it alight within 0.3 of it. paths keep
    // a block off them (elytraKeepOffLava), so only a step's corners and a box already nearer come this close
    private static final double BURN_CLEARANCE = 0.4;
    // this tick's step slid along what was in its way, off the line to its target
    private boolean stepSlid;

    private static final class GlideMove {
        final Vec3 feet;
        final String kind;
        final int tick;
        final double length;

        GlideMove(final Vec3 feet, final String kind, final int tick, final double length) {
            this.feet = feet;
            this.kind = kind;
            this.tick = tick;
            this.length = length;
        }
    }

    // the tick the server last moved the player back, and how the glide's speed comes back after it: from RAMP_FROM of
    // it to all of it over RAMP_TICKS
    private int rampFromTick = Integer.MIN_VALUE / 2;
    // the sink the server pulls it down by, as a level glide's velocity, from a standstill at each setback
    private Vec3 pullVelocity = Vec3.ZERO;
    private static final int RAMP_TICKS = 20;
    private static final double RAMP_FROM = 0.25;

    // the nearest the glide has come to the goal, and when: no closer for STUCK_TICKS, and a line says what holds it
    private double closestToGoal = Double.MAX_VALUE;
    private int closestToGoalTick;
    private int stuckSaidTick = Integer.MIN_VALUE / 2;
    private static final int STUCK_TICKS = 300;
    // no closer for ElytraProcess#askAgainAfterTicks, and getting nowhere besides: the flight is asked for again from
    // here, once. a glide held on a path whose start goes nowhere flies on at once when #elytra is asked for again,
    // while the flight's own re-paths, which rejoin the path it has, find the same path every time
    private static final int STUCK_MOVES = 20;
    private static final double STUCK_NET = 1.0;
    private boolean restartAsked;
    private int glideHistoryTick;

    /**
     * Where a glide moved by steps that got nowhere goes first, to see on along the path from there; {@code null} when
     * it isn't going round anything
     */
    private Vec3 glideDetour;
    private int nextGlideDetourSearch;
    // when to look again for a way out of blocks the pathfinder has as solid that the box is partly in
    private int nextEscapeSearch;

    /**
     * The first path point the fallback of a glide moved by steps last headed for, the ones before it found behind the
     * box or under it; -1 for none. They stay behind: looked at afresh, a point the path turns back at can be behind
     * the box at it and ahead of the box one step on from it, and the fallback went back and forth between the two
     */
    private int glideFallbackFrom = -1;

    /**
     * The target in a straight line a glide moved by steps last headed for and how far along the path it is
     * ({@link Aim#progress}), and the furthest along the path a step has got the box to one; -1 for none. No target is
     * one before either. Near a wall the furthest point in a straight line changes with each step, and from the
     * furthest spot along a stretch that the box can get to the only one left is a step back: without these the glide
     * goes back and forth between two spots indefinitely, and looks to be flying at the rock.
     */
    private Vec3 glideTargetAt;
    private double glideTargetProgress = -1;
    private double glideReachedProgress = -1;

    /**
     * A target in a straight line: {@code at}, the feet's spot, and how far along the path it is, the index of a point,
     * or for a spot between two points the second's less the share of the way back to the first
     */
    record Aim(Vec3 at, double progress) {}

    /**
     * Whether this tick's step was held back at the edge of the chunks the client has, and the tick that was last said
     */
    private boolean waitingForChunks;
    private int chunkWaitSaidTick = Integer.MIN_VALUE / 2;

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
    // the path points ahead checked against the game's blocks each tick, and how long before a chunk packed again for
    // it is checked again
    private static final int PATH_CHECK_POINTS = 20;
    private static final int REPACK_AGAIN_TICKS = 100;
    private final Long2IntOpenHashMap repackAgainTick = new Long2IntOpenHashMap();
    private final LongOpenHashSet repackSaid = new LongOpenHashSet();
    private static final double GLIDE_STALL_DISTANCE = 0.5;
    // with nothing ahead in a straight line, a glide moved by steps heads for one of this many points from the nearest
    private static final int GLIDE_FALLBACK_POINTS = 3;
    // a detour is a spot this close that the box gets to in one straight move, looked for on a grid this fine, nearest
    // first, from which one of the next GLIDE_DETOUR_POINTS points is in a straight line
    private static final double GLIDE_DETOUR_RANGE = 2.5;
    private static final double GLIDE_DETOUR_GRID = 0.5;
    // out of lava and the block round it from a pool under a lavafall takes further than a detour goes
    private static final List<Vec3> LAVA_ESCAPES = offsetsWithin(4.0);
    private static final int GLIDE_DETOUR_POINTS = 5;
    private static final double GLIDE_DETOUR_REACHED = 0.05;
    // a glide moved by steps is at a spot less than this squared distance from it, and a step there is a step of nothing
    private static final double GLIDE_AT_SQR = 1.0E-6;
    private static final List<Vec3> GLIDE_DETOURS = glideDetours();
    // a path from a player in lava starts at most this far straight up, out of it (lavaStartY)
    static final int LAVA_START_RISE = 64;
    // waiting at the edge of the chunks the client has is said at most this often
    private static final int CHUNK_WAIT_SAY_TICKS = 200;
    // a path search that found nothing starts again from the player (feet, over the head, higher, back along the path,
    // bigger cubes), or from the end of the path (its end, higher, further back, bigger cubes)
    private static final int PLAYER_STARTS = 5;
    private static final int ONWARD_STARTS = 4;
    private static final int HEAD_RISE = 2;
    private static final int HIGHER_RISE = 5;
    private static final int PASSED_POINTS = 3;
    private static final int ONWARD_RISE = 3;
    private static final int ONWARD_BACK_POINTS = 8;
    // the end of a path this close to the goal (across, for a goal with no height) is as close as it gets when the way
    // on can't be found
    private static final int CLOSE_TO_GOAL = 16;
    private static final int CLOSE_TO_GOAL_ACROSS = 32;
    // giving up on the way on waits for the player to be this close to the end of the path it has
    private static final int NEAR_PATH_END = 32;

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

    /**
     * Where a path search starts: a block, whether it goes through 4 block cubes of air only, and for the way on from
     * the end of a path, the last point of the path that goes before it
     */
    private static final class Start {
        final BlockPos pos;
        final boolean atLeastX4;
        final int after;

        Start(BlockPos pos, boolean atLeastX4, int after) {
            this.pos = pos;
            this.atLeastX4 = atLeastX4;
            this.after = after;
        }
    }

    /**
     * What a path search was for: the flight's first path, a path again from the player, or the way on from the end
     */
    private enum Search {
        FIRST,
        AGAIN,
        ONWARD
    }

    public final class PathManager {

        public NetherPath path;
        private boolean completePath;
        private boolean recalculating;

        private int maxPlayerNear;
        private int ticksNearUnchanged;
        // ticks the nearest point has been in a block, and how many before the path is asked for again from here
        private int inWallTicks;
        private static final int IN_WALL_TICKS = 20;
        private int playerNear;

        // searches that found nothing, from the player and on from the end of the path, and when to ask again
        private final PathRetry fromPlayer = new PathRetry(PLAYER_STARTS);
        private final PathRetry onward = new PathRetry(ONWARD_STARTS);
        // the first path found nothing, and is asked for again from other starts
        private boolean firstFailed;
        // the way on from the end ran out of air at the edge of the chunks the game has sent, where the seed has rock (a
        // dug tunnel): asked again once more chunks are packed, besides the retries' own times. along a highway tunnel
        // that's most searches, and each new chunk ahead lets the next one through, so a 2 s wait after every failure
        // leaves the end of the path that far behind the chunks that have come
        private boolean onwardWaitsForChunks;
        private int onwardFailedTick;
        // npfContext.chunksPacked() when the last onward search was asked for, and whether that was because more had
        // come, ahead of its turn
        private int onwardChunksPacked;
        private boolean onwardForChunks;
        // said once a flight: along a tunnel it would be every other search
        private boolean saidChunkEdge;
        private static final int CHUNK_RETRY_TICKS = 10;

        public PathManager() {
            // lol imagine initializing fields normally
            this.clear();
        }

        public void tick() {
            // Recalculate closest path node
            this.updatePlayerNear();
            ElytraBehavior.this.checkPathAhead();
            final int prevMaxNear = this.maxPlayerNear;
            this.maxPlayerNear = Math.max(this.maxPlayerNear, this.playerNear);

            // held at the edge of the chunks the client has isn't getting nowhere: a path again goes the same way
            if (this.maxPlayerNear == prevMaxNear && ctx.player().isFallFlying() && !ElytraBehavior.this.waitingForChunks) {
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
            return this.pathToDestination(this.playerStarts()[0], 0);
        }

        /**
         * With no path yet because the first search found nothing: the next start, when its turn has come
         */
        void retryFirst() {
            if (!this.firstFailed || this.recalculating || !this.fromPlayer.mayAsk(ElytraBehavior.this.gameTick())) {
                return;
            }
            final Start[] starts = this.playerStarts();
            final int kind = this.fromPlayer.kind(k -> starts[k] != null);
            this.pathToDestination(starts[kind], kind);
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
            return this.pathToDestination(new Start(from, false, -1), 0);
        }

        private CompletableFuture<Void> pathToDestination(final Start from, final int kind) {
            final long start = System.nanoTime();
            this.recalculating = true;
            return this.path0(from, destinationFixed(), UnaryOperator.identity())
                    .thenRun(() -> {
                        final double distance = this.path.get(0).distanceTo(this.path.get(this.path.size() - 1));
                        if (this.completePath) {
                            logVerbose(String.format("Computed path (%.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
                        } else {
                            logVerbose(String.format("Computed segment (Next %.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
                        }
                    })
                    .whenComplete((result, ex) -> this.searched(Search.FIRST, from, kind, ex));
        }

        public CompletableFuture<Void> pathRecalcSegment(final OptionalInt upToIncl) {
            if (this.recalculating) {
                throw new IllegalStateException("already recalculating");
            }

            this.recalculating = true;
            final List<BetterBlockPos> after = upToIncl.isPresent() ? this.path.subList(upToIncl.getAsInt() + 1, this.path.size()) : Collections.emptyList();
            final boolean complete = this.completePath;
            // the same start again finds the same nothing
            final Start[] starts = this.playerStarts();
            final int kind = this.fromPlayer.kind(k -> starts[k] != null);
            final Start from = starts[kind];

            return this.path0(from, upToIncl.isPresent() ? fixDestination(this.path.get(upToIncl.getAsInt())) : destinationFixed(), segment -> segment.append(after.stream(), complete || (segment.isFinished() && !upToIncl.isPresent())))
                    .whenComplete((result, ex) -> this.searched(Search.AGAIN, from, kind, ex));
        }

        public void pathNextSegment(final int afterIncl) {
            if (this.recalculating) {
                return;
            }

            this.recalculating = true;
            this.onwardChunksPacked = npfContext.chunksPacked();
            final Start[] starts = this.onwardStarts(afterIncl);
            final int kind = this.onward.kind(k -> starts[k] != null);
            final Start from = starts[kind];
            final List<BetterBlockPos> before = this.path.subList(0, from.after + 1);
            final long start = System.nanoTime();

            this.path0(from, destinationFixed(), segment -> segment.prepend(before.stream()))
                    .thenRun(() -> {
                        final int recompute = this.path.size() - before.size() - 1;
                        final double distance = recompute > 0 ? this.path.get(0).distanceTo(this.path.get(recompute)) : 0;

                        if (this.completePath) {
                            logVerbose(String.format("Computed path (%.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
                        } else {
                            logVerbose(String.format("Computed segment (Next %.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
                        }
                    })
                    .whenComplete((result, ex) -> this.searched(Search.ONWARD, from, kind, ex));
        }

        /**
         * After a search: one that found a path starts the count of failures again, and one that found nothing waits
         * its turn to be asked again from somewhere else, or gives up. The game thread does the counting, so a search
         * isn't asked for again before the failure is counted.
         */
        private void searched(final Search search, final Start from, final int kind, final Throwable ex) {
            if (ex == null) {
                // setPath ran on the game thread, and this after it
                this.recalculating = false;
                this.retry(search).succeeded();
                if (search == Search.FIRST) {
                    this.firstFailed = false;
                } else if (search == Search.ONWARD) {
                    this.onwardWaitsForChunks = false;
                }
                return;
            }
            ctx.minecraft().execute(() -> {
                this.recalculating = false;
                if (!ElytraBehavior.this.process.flies(ElytraBehavior.this)) {
                    return; // a flight since replaced
                }
                final Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
                if (!(cause instanceof PathCalculationException)) {
                    logUnhandledException(cause);
                }
                final PathCalculationException failure = cause instanceof PathCalculationException ? (PathCalculationException) cause : null;
                this.failed(search, from, kind, failure != null ? failure.reason : null, failure == null || failure.startMatters(),
                        failure != null && failure.waitsForChunks());
            });
        }

        private void failed(final Search search, final Start from, final int kind, final String why, final boolean startMatters, final boolean waitsForChunks) {
            final String reason = why != null ? why : "no path found";
            final PathRetry retry = this.retry(search);
            final int now = ElytraBehavior.this.gameTick();
            final boolean chunkEdge = search == Search.ONWARD && waitsForChunks;
            if (search == Search.ONWARD) {
                this.onwardWaitsForChunks = chunkEdge;
                this.onwardFailedTick = now;
            }
            if (chunkEdge) {
                if (!this.saidChunkEdge) {
                    this.saidChunkEdge = true;
                    logDirect(PathRetry.chunkEdgeLine());
                }
                if (this.onwardForChunks) {
                    // an extra try because more chunks came, ahead of its turn: the retries' count and times stand, so
                    // a path that really ends there gives up as it did
                    return;
                }
            }
            final Start[] starts = search == Search.ONWARD
                    ? (this.path.isEmpty() ? new Start[ONWARD_STARTS] : this.onwardStarts(this.path.size() - 1))
                    : this.playerStarts();
            // the first path is all the flight has: one round of starts, then it says so. when the goal is the trouble
            // the start doesn't matter, and each try can be a search that ran to its timeout: a round is one try, and
            // there are as many of them as there are kinds of start in the rounds for a start shut in
            final int setting = Math.max(1, Baritone.settings().elytraPathRetries.value);
            final int retries = search == Search.FIRST ? PLAYER_STARTS : startMatters ? setting : Math.max(1, setting / PLAYER_STARTS);
            final boolean last = retry.failed(now, retries, kind, k -> starts[k] != null, startMatters)
                    || (search == Search.FIRST && retry.rounds() > 0);
            if (!last) {
                if (search == Search.FIRST) {
                    this.firstFailed = true;
                }
                if (!chunkEdge && retry.sayReason()) {
                    logDirect(PathRetry.retryingLine(search == Search.ONWARD ? "the end of the path" : "here", reason));
                }
                return;
            }
            final int tries = retry.failures();
            if (ElytraBehavior.this.appendDestination) {
                // a flight to a landing spot: another spot, not the end of the flight
                ElytraBehavior.this.process.landingSpotUnreachable(ElytraBehavior.this.destination, reason);
                return;
            }
            switch (search) {
                case FIRST:
                    ElytraBehavior.this.process.giveUpOnPath(PathRetry.firstGivesUpLine(reason));
                    return;
                case AGAIN:
                    ElytraBehavior.this.process.giveUpOnPath(PathRetry.againGivesUpLine(tries, reason));
                    return;
                default: // ONWARD
                    if (this.path.isEmpty()) {
                        return;
                    }
                    final BlockPos end = this.path.get(Math.min(from.after, this.path.size() - 1));
                    if (ElytraBehavior.this.closeToGoal(end)) {
                        logDirect("The path gets no closer to the goal than this (" + reason + "), landing near its end");
                        this.completePath = true;
                    } else if (ctx.player().distanceToSqr(Vec3.atCenterOf(end)) < NEAR_PATH_END * NEAR_PATH_END) {
                        ElytraBehavior.this.process.giveUpOnPath(PathRetry.onwardGivesUpLine(tries, reason));
                    } else {
                        // the player is still a way off it: the chunks between may change what's found
                        retry.keepTrying(now);
                    }
            }
        }

        private PathRetry retry(final Search search) {
            return search == Search.ONWARD ? this.onward : this.fromPlayer;
        }

        /**
         * The starts of a search from the player, by kind, {@code null} for a kind that isn't one here: the feet
         * ({@link #pathStart()}), over the head, higher up, the path a few points back, the feet in bigger cubes. The
         * planner starts from the nearest cube of air to the block it is given, which can be a sealed pocket.
         */
        private Start[] playerStarts() {
            final BetterBlockPos feet = this.pathStart();
            final Start[] starts = new Start[PLAYER_STARTS];
            starts[0] = new Start(feet, false, -1);
            starts[1] = ElytraBehavior.this.airStart(feet.above(HEAD_RISE), -1);
            starts[2] = ElytraBehavior.this.airStart(feet.above(HIGHER_RISE), -1);
            starts[3] = !this.path.isEmpty() && this.playerNear > 0
                    ? new Start(this.path.get(Math.max(0, this.playerNear - PASSED_POINTS)), false, -1)
                    : null;
            starts[4] = ElytraBehavior.this.biggerCubes() ? new Start(feet, true, -1) : null;
            return starts;
        }

        /**
         * The starts of the way on from point {@code afterIncl}, by kind, {@code null} for a kind that isn't one here:
         * the point, higher, further back along the path, the point in bigger cubes
         */
        private Start[] onwardStarts(final int afterIncl) {
            final BetterBlockPos end = this.path.get(afterIncl);
            final int back = afterIncl - ONWARD_BACK_POINTS;
            final Start[] starts = new Start[ONWARD_STARTS];
            starts[0] = new Start(end, false, afterIncl);
            starts[1] = ElytraBehavior.this.airStart(end.above(ONWARD_RISE), afterIncl);
            starts[2] = back > 0 ? new Start(this.path.get(back), false, back) : null;
            starts[3] = ElytraBehavior.this.biggerCubes() ? new Start(end, true, afterIncl) : null;
            return starts;
        }

        public void clear() {
            this.path = NetherPath.emptyPath();
            this.completePath = true;
            this.recalculating = false;
            this.playerNear = 0;
            this.ticksNearUnchanged = 0;
            this.maxPlayerNear = 0;
            this.onwardWaitsForChunks = false;
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
            final NetherPath old = this.path;
            this.path = new NetherPath(path);
            this.completePath = segment.isFinished();
            // a detour was to see on along the old path, and the points behind were the old path's
            ElytraBehavior.this.glideDetour = null;
            ElytraBehavior.this.glideFallbackFrom = -1;
            // how far along it the glide has got stands for the same points, when the path only goes on further
            final int along = (int) Math.ceil(Math.max(ElytraBehavior.this.glideReachedProgress, ElytraBehavior.this.glideTargetProgress));
            if (along < 0 || along >= old.size() || along >= path.size() || !old.subList(0, along + 1).equals(path.subList(0, along + 1))) {
                ElytraBehavior.this.forgetGlideTarget();
            }
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
        private CompletableFuture<Void> path0(Start src, BlockPos dst, UnaryOperator<UnpackedSegment> operator) {
            final CompletableFuture<UnpackedSegment> found = src.atLeastX4
                    ? npfContext.pathFindAsync(src.pos, dst, true)
                    : ElytraBehavior.this.pathFinder.pathFindAsync(src.pos, dst);
            return found
                    .thenApply(operator)
                    .thenAcceptAsync(this::setPath, ctx.minecraft()::execute);
        }

        /**
         * After a setback: the nearest point again from the whole path, which the server may have put the player
         * anywhere along, and progress counted from there
         */
        void retakeNearest() {
            if (this.path.isEmpty()) {
                return;
            }
            final ToDoubleFunction<BetterBlockPos> distance = this.nearDistance();
            int best = 0;
            double bestDistance = Double.MAX_VALUE;
            for (int i = 0; i < this.path.size(); i++) {
                final double d = distance.applyAsDouble(this.path.get(i));
                if (d < bestDistance) {
                    best = i;
                    bestDistance = d;
                }
            }
            this.playerNear = best;
            this.maxPlayerNear = best;
            this.ticksNearUnchanged = 0;
        }

        // required read lock to be held
        private void pathfindAroundObstacles() {
            if (this.recalculating || !this.fromPlayer.mayAsk(ElytraBehavior.this.gameTick())) {
                return;
            }

            int rangeStartIncl = playerNear;
            int rangeEndExcl = playerNear;
            while (rangeEndExcl < path.size() && npfContext.hasChunk(new ChunkPos(path.get(rangeEndExcl)))) {
                rangeEndExcl++;
            }
            // rangeEndExcl now represents an index either not in the path, or just outside render distance
            // before the returns below, the one for the nearest point's chunk not packed yet as well: a path found while
            // the player's chunk isn't packed yet runs through blocks that were air to it, lava as well, and once
            // they're packed the nearest point is in one for good. nothing else here re-paths then, and the flight sits
            // still until the whole flight is asked for again
            if (ElytraBehavior.this.process.state != ElytraProcess.State.LANDING && this.ticksNearUnchanged > 100) {
                this.pathRecalcSegment(rangeStartIncl < rangeEndExcl ? OptionalInt.of(rangeEndExcl - 1) : OptionalInt.empty())
                        .thenRun(() -> {
                            logVerbose("Recalculating segment, no progress in last 100 ticks");
                        });
                this.ticksNearUnchanged = 0;
                return;
            }
            if (rangeStartIncl >= rangeEndExcl) {
                // not loaded yet?
                return;
            }

            final BetterBlockPos rangeStart = path.get(rangeStartIncl);
            if (!ElytraBehavior.this.passable(rangeStart.x, rangeStart.y, rangeStart.z, false)) {
                // the nearest point is in a block: the path was found before that block was packed. nothing on from
                // here is looked along, so nothing asks for another way, and the glide flies at the rock indefinitely
                if (++this.inWallTicks >= IN_WALL_TICKS && ElytraBehavior.this.process.state != ElytraProcess.State.LANDING) {
                    this.inWallTicks = 0;
                    this.pathRecalcSegment(OptionalInt.empty())
                            .thenRun(() -> logVerbose("Recalculated the path, its nearest point was in a block"));
                }
                return;
            }
            this.inWallTicks = 0;

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
            final int now = ElytraBehavior.this.gameTick();
            final boolean due = this.onward.mayAsk(now);
            final boolean chunksCame = !due && this.onwardWaitsForChunks
                    && npfContext.chunksPacked() != this.onwardChunksPacked
                    && now - this.onwardFailedTick >= CHUNK_RETRY_TICKS;
            if (!due && !chunksCame) {
                return;
            }

            final int last = this.path.size() - 1;
            final BetterBlockPos lastPos = this.path.get(this.path.size() - 1);
            // `ctx.world().isLoaded` cannot be used here as it returns false is the y-value is beyond the build limits.
            if (!this.completePath && ctx.world().getChunkSource().hasChunk(lastPos.x >> 4,lastPos.z >> 4)) {
                this.onwardForChunks = chunksCame;
                this.pathNextSegment(last);
            }
        }

        /**
         * How far a point is from the player, squared, for which is nearest
         */
        private ToDoubleFunction<BetterBlockPos> nearDistance() {
            final GlideSteps control = ElytraBehavior.this.process.glideSteps();
            if (control != null) {
                // a glide moved by steps puts the box's middle on the points. measured from the feet block, a point
                // the box is on ties with the one below it, and the nearest stays a point behind
                final Vec3 middle = ElytraBehavior.this.glideBox(control).getCenter();
                return p -> middle.distanceToSqr(p.x, p.y, p.z);
            }
            final BetterBlockPos pos = ctx.playerFeet();
            return p -> p.distanceSq(pos);
        }

        public void updatePlayerNear() {
            if (this.path.isEmpty()) {
                return;
            }

            final ToDoubleFunction<BetterBlockPos> distance = this.nearDistance();
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
            // not getChunk(x, z): for a chunk the game doesn't keep that's its one empty chunk, which says it's at 0, 0,
            // and packing it puts a chunk of air there
            final LevelChunk chunk = ctx.world().getChunkSource().getChunk(event.getX(), event.getZ(), false);
            if (chunk != null && !chunk.isEmpty()) {
                npfContext.queueForPacking(chunk);
            }
        }
    }

    public void onBlockChange(BlockChangeEvent event) {
        // each changed block, and the ones next to it: a fence's top is in the block over the fence, and the blocks
        // next to lava and fire are kept off
        final List<Pair<BlockPos, Boolean>> writes = new ArrayList<>(event.getBlocks().size() * 7);
        for (final Pair<BlockPos, BlockState> change : event.getBlocks()) {
            final BlockPos pos = change.first();
            writes.add(new Pair<>(pos, NetherPathfinderContext.solidAt(ctx.world(), pos, change.second())));
            for (final Direction direction : Direction.values()) {
                final BlockPos next = pos.relative(direction);
                writes.add(new Pair<>(next, NetherPathfinderContext.solidAt(ctx.world(), next, ctx.world().getBlockState(next))));
            }
        }
        npfContext.queueBlockWrites(writes);
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
        if (npfContext.tryAcquireReadLockSoon()) {
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
            this.pathManager.retryFirst();
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
     * The path ahead against the blocks the game has. Its points are the middles of cubes the pathfinder had as air, so
     * a block there that a glide can't go through, or a chunk there that the pathfinder never got from the game, is a
     * path through rock. A chunk missing from its table is air to a search but solid to its rays, so the path goes
     * straight through it, the look along the path stops short of it, and the glide bounces off the rock with
     * nothing asking for another way. The chunk is packed again from the game's, the look along the path then finds
     * the way blocked and asks for one round it, and the line says which it was.
     */
    private void checkPathAhead() {
        final NetherPath path = this.pathManager.getPath();
        final int near = this.pathManager.getNear();
        final int last = Math.min(near + PATH_CHECK_POINTS, path.size() - 1);
        final int tick = this.gameTick();
        for (int i = near; i <= last; i++) {
            final BetterBlockPos point = path.get(i);
            final long key = ChunkPos.asLong(point.x >> 4, point.z >> 4);
            if (this.repackAgainTick.get(key) > tick || npfContext.packQueued(point.x >> 4, point.z >> 4)) {
                // packed again lately, or the game has just given it and it's on its way in
                continue;
            }
            final LevelChunk chunk = ctx.world().getChunkSource().getChunk(point.x >> 4, point.z >> 4, false);
            if (chunk == null || chunk.isEmpty()) {
                continue; // the game hasn't got it either
            }
            final String line;
            switch (npfContext.held(point.x, point.y, point.z)) {
                case NOTHING:
                    line = "The path goes into a chunk the pathfinder never got from the game, so it took it for air: packing it now";
                    break;
                case PREDICTED:
                    line = "The path goes into a chunk the pathfinder had only the seed's guess at: packing the game's now";
                    break;
                case AIR: {
                    final int section = chunk.getSectionIndex(point.y);
                    final LevelChunkSection held = section >= 0 && section < chunk.getSections().length ? chunk.getSection(section) : null;
                    // the game's getBlockState has a section the server sent as empty all air, whatever is in it, so
                    // read through it this never sees a block there: the block is read from the section itself
                    final boolean sentEmpty = NetherPathfinderContext.sentEmpty(held);
                    final BlockState state = sentEmpty ? held.getBlockState(point.x & 15, point.y & 15, point.z & 15) : chunk.getBlockState(point);
                    if (!NetherPathfinderContext.solidAt(ctx.world(), point, state)) {
                        continue;
                    }
                    // the block itself, the top of a fence under it, or air next to lava or fire
                    final String what = !NetherPathfinderContext.glidesThrough(state) ? BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath()
                            : NetherPathfinderContext.reachesAbove(ctx.world().getBlockState(point.below()))
                            ? "the top of " + BuiltInRegistries.BLOCK.getKey(ctx.world().getBlockState(point.below()).getBlock()).getPath()
                            : "the air next to lava or fire";
                    line = "The path goes through " + what + " the pathfinder had as air"
                            + (sentEmpty ? " (the server sent that part of the chunk as empty)" : "") + ": packing that chunk again";
                    break;
                }
                default:
                    continue;
            }
            this.repackAgainTick.put(key, tick + REPACK_AGAIN_TICKS);
            npfContext.queueForPacking(chunk);
            if (this.repackSaid.add(key)) {
                logDirect(line);
            }
        }
    }

    /**
     * The server has put the player somewhere else: the stall watch and any detour were for where it was, and the
     * nearest point is looked for afresh. A path is asked for again only if no point can be seen from here, which the
     * next tick's look along the path finds.
     */
    public void onSetback() {
        this.glideHistory.clear();
        this.recentMoves.clear();
        // put back where the server has it, from a standstill
        this.pullVelocity = Vec3.ZERO;
        this.rampFromTick = this.gameTick();
        this.glideDetour = null;
        this.glideFallbackFrom = -1;
        this.forgetGlideTarget();
        this.waitingForChunks = false;
        this.pathManager.retakeNearest();
    }

    /**
     * The target in a straight line, and how far along the path the glide has got, start afresh: a new way, or the
     * player put somewhere else
     */
    private void forgetGlideTarget() {
        this.glideTargetAt = null;
        this.glideTargetProgress = -1;
        this.glideReachedProgress = -1;
    }

    int gameTick() {
        return ctx.player().tickCount;
    }


    /**
     * @return A search start at {@code pos} when the planner can start there, inside its height and in air, else
     * {@code null}
     */
    private Start airStart(final BlockPos pos, final int after) {
        final int minY = ctx.world().getMinY();
        if (pos.getY() < minY || pos.getY() >= minY + npfContext.getMaxHeight()) {
            return null;
        }
        return ctx.world().getBlockState(pos).isAir() ? new Start(pos, false, after) : null;
    }

    /**
     * @return Whether a search can be asked to go through bigger cubes of air than it does already
     */
    private boolean biggerCubes() {
        return this.pathFinder == this.npfContext && Baritone.settings().elytraAllowTightSpaces.value;
    }

    /**
     * @return Whether {@code end} is as close to the goal as a path needs to come: within the search's own goal radius,
     * or for a goal with no height, that far across
     */
    private boolean closeToGoal(final BlockPos end) {
        final BetterBlockPos goal = this.destinationFixed();
        if (this.appendDestination || this.process.goalHasHeight()) {
            return end.distSqr(goal) <= CLOSE_TO_GOAL * CLOSE_TO_GOAL;
        }
        final double dx = end.getX() - goal.x;
        final double dz = end.getZ() - goal.z;
        return dx * dx + dz * dz <= CLOSE_TO_GOAL_ACROSS * CLOSE_TO_GOAL_ACROSS;
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

        final GlideSteps glideSteps = this.process.glideSteps();
        if (glideSteps != null) {
            this.tickGlideStep(glideSteps);
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
     * a straight line, as far as the step limits let it go this tick, and short of anything that would cut it or
     * slid along it. With no point ahead in a straight line, towards one of the next few, and after getting nowhere for
     * a while, round by a detour first. The player's move takes it whole, so there are no physics or fireworks to plan
     * for.
     */
    private void tickGlideStep(final GlideSteps control) {
        final AABB box = this.glideBox(control);
        final Vec3 feet = ctx.player().position();
        this.stepSlid = false;
        String kind;
        // brought down by the server: the look it steers the game's glide by
        Rotation yieldLook = null;
        // slowed by the pull-downs so far (elytraGlideSpeed), and back up to it over a second after a setback
        final double across = Math.max(0, control.maxHorizontalStep()) * this.process.glideSpeed() * this.rampAfterSetback();
        // and up and down: the server turns a settle's drops down too after a setback
        final double vertical = Math.max(0, control.maxVerticalStep()) * this.rampAfterSetback();

        Vec3 target;
        Vec3 step;
        // the target in a straight line, when that's what the step is towards
        Aim aimed = null;
        // coming down after a pull-down, the server has it whatever it was doing: a landing's steps are turned down too
        // in lava it gets out of it slowly, below, rather than being brought down
        final boolean yields = this.process.yields() && (!this.landingMode || this.process.settlingAfterPullDown())
                && !ctx.player().isInLava();
        if (!yields) {
            this.pullVelocity = Vec3.ZERO;
        }
        if (this.landingMode && !yields) {
            kind = "landing";
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
        } else if (yields) {
            // pulled down: the server sinks the player at the game's glide speed, nearly straight down, a little the way
            // it faces, and turns down every move of the step's making, up, down or across. all that counts is where it
            // faces: it faces where it would come down, and sinks as the server sinks it
            final Vec3 velocity = ctx.player().getDeltaMovement();
            final BetterBlockPos floor = this.process.yieldAim();
            final Vec3 aim;
            if (floor != null) {
                aim = Vec3.atCenterOf(floor);
                kind = "brought down by the server, facing the floor it picked";
            } else if (this.process.settlingAfterPullDown()) {
                aim = velocity.lengthSqr() > 1.0E-6 ? ctx.playerHead().add(velocity) : null;
                kind = "brought down by the server, facing the way it goes";
            } else {
                final Vec3 ahead = this.glideTarget(box, feet);
                aim = ahead != null ? ahead.add(0, box.getYsize() / 2, 0) : velocity.lengthSqr() > 1.0E-6 ? ctx.playerHead().add(velocity) : null;
                kind = "set back down lately, facing the way on";
            }
            final Rotation want = aim != null
                    ? RotationUtils.calcRotationFromVec3d(ctx.playerHead(), aim, ctx.playerRotations())
                    : ctx.playerRotations();
            // level: looking down at the floor dives, faster than the server's pull, and it puts the glide back up
            yieldLook = new Rotation(want.getYaw(), 0);
            // the way it faces this tick, as the aim processor has it: the little it drifts goes that way
            final Rotation actual = baritone.getLookBehavior().getAimProcessor().peekRotation(yieldLook);
            step = pullStep(this.pullVelocity, actual.getYaw());
            this.pullVelocity = step;
            target = aim != null ? aim : feet.add(step);
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
            if (this.glideDetour == null && tick >= this.nextEscapeSearch && this.inPackedSolid(box)) {
                // the box is partly in a block the pathfinder has as solid, a fence's block beside its thin post, say,
                // where it came down. every ray from it starts in that block, so none finds a line anywhere and the
                // glide slides along the fence back and forth: out of it first, by the game's collisions
                this.nextEscapeSearch = tick + GLIDE_STALL_TICKS / 2;
                this.glideDetour = this.findSolidEscape(box, feet);
                logVerbose(this.glideDetour != null ? "In a block the pathfinder has as solid, getting out of it" : "In a block the pathfinder has as solid, and nowhere near to get out of it to");
            }
            if (this.glideDetour == null) {
                aimed = keepAim(this.aimAhead(box, feet), this.glideTargetAt, this.glideTargetProgress,
                        at -> this.glidesTo(box, feet, at));
                this.glideTargetAt = aimed != null ? aimed.at() : null;
                this.glideTargetProgress = aimed != null ? aimed.progress() : -1;
            }
            final Vec3 ahead = this.glideDetour != null ? this.glideDetour : aimed != null ? aimed.at() : null;
            if (ahead != null) {
                kind = this.glideDetour != null ? "detour" : "ray-checked";
                target = ahead;
                step = this.glideStepTowards(box, feet, target, across, vertical);
            } else {
                kind = "fallback (no ray-checked line)";
                // nothing ahead in a straight line: the first of the next few points a step gets a good way towards,
                // else the one it gets furthest towards
                final NetherPath path = this.pathManager.getPath();
                final int near = this.pathManager.getNear();
                final boolean avoidLava = this.glideAvoidsLava();
                final int from = Math.min(Math.max(near, this.glideFallbackFrom), path.size() - 1);
                target = this.glideFeetAt(path, from, box);
                step = Vec3.ZERO;
                boolean slid = false;
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
                    this.stepSlid = false;
                    final Vec3 toward = this.glideStepTowards(box, feet, point, across, vertical);
                    if (avoidLava && this.glideTouchesLava(box, toward)) {
                        // no ray checked this line, and the game lets a glide into lava and fire
                        continue;
                    }
                    if (toward.lengthSqr() > step.lengthSqr()) {
                        target = point;
                        step = toward;
                        slid = this.stepSlid;
                    }
                    if (toward.lengthSqr() >= 0.25 * 0.25 * want.lengthSqr()) {
                        break;
                    }
                }
                if (firstAhead >= 0) {
                    this.glideFallbackFrom = firstAhead;
                }
                this.stepSlid = slid;
            }
        }

        if (yieldLook == null && this.glideAvoidsLava() && step.lengthSqr() > 1.0E-12) {
            // whatever kind of step it is: the rays looked along a straight line, and the game doesn't move along one
            final Vec3 unburnt = this.unburntGlideStep(box, step);
            if (unburnt != step) {
                kind += unburnt == null ? ", held: any of it would burn" : ", shortened or slid to keep off lava and fire";
                step = unburnt == null ? Vec3.ZERO : unburnt;
            }
        }

        if (ctx.player().isInLava()) {
            // the server turns down a fast move out of lava, and puts it back in: slow until the box is out
            final double most = Math.max(0.01, Baritone.settings().elytraInLavaSpeed.value);
            if (step.lengthSqr() > most * most) {
                step = step.normalize().scale(most);
                kind += ", slowed in lava";
            }
        }

        this.waitingForChunks = false;
        if (!this.landingMode && step.horizontalDistanceSqr() > 1.0E-12 && !this.clientHasChunkAt(feet.add(step))) {
            // not into a chunk the client hasn't got, which the server sends slowly when it's busy: it has no blocks to
            // collide with, and may be anything. held here on purpose, so the stall watch doesn't take it for being
            // stuck and look for a detour, nor the flight for a path again
            step = Vec3.ZERO;
            this.waitingForChunks = true;
            this.glideHistory.clear();
            final int tick = this.gameTick();
            if (tick - this.chunkWaitSaidTick >= CHUNK_WAIT_SAY_TICKS) {
                this.chunkWaitSaidTick = tick;
                logDirect("Waiting for the server to send the chunks ahead");
            }
        }

        if (aimed != null && feet.add(step).distanceToSqr(aimed.at()) < GLIDE_AT_SQR) {
            // there: nothing up to here along the path is a target again
            this.glideReachedProgress = Math.max(this.glideReachedProgress, aimed.progress());
        }
        this.glideStep = step;
        // before the progress check, whose line says where it aims
        this.aimPos = new BetterBlockPos(target.x, target.y, target.z);
        this.simulationLine = Arrays.asList(Vec3.ZERO, target.subtract(feet));
        this.noteGlideMove(feet, kind);
        if (!this.landingMode && !this.waitingForChunks && this.pathManager.getNear() < this.pathManager.getPath().size() - 1) {
            this.noteProgress(feet);
        }

        // the step's heading, flat
        final Vec3 facing = step.horizontalDistanceSqr() > 1.0E-6 ? step : target.subtract(feet);
        final float yaw = facing.horizontalDistanceSqr() > 1.0E-6
                ? RotationUtils.calcRotationFromVec3d(Vec3.ZERO, new Vec3(facing.x, 0, facing.z), ctx.playerRotations()).getYaw()
                : ctx.playerRotations().getYaw();
        baritone.getLookBehavior().updateTarget(yieldLook != null ? yieldLook : new Rotation(yaw, 0), false);
    }

    /**
     * @return What a pull-down moves the player by this tick from {@code velocity}, facing {@code yaw}, which is also
     * the velocity next tick: it sinks as the game's glide does looking level ({@code
     * LivingEntity.updateFallFlyingMovement} at pitch 0), to about 0.15 a tick from a standstill, and goes across only
     * the lift that turns a tenth of this tick's fall the way it faces, nothing carried over. The server's pull is that
     * slow and that nearly straight down: neither a fall nor a glide, which picks up speed across
     */
    static Vec3 pullStep(final Vec3 velocity, final float yaw) {
        // Entity.calculateViewVector at pitch 0: level, length 1
        final float negYawRadians = -yaw * (float) (Math.PI / 180.0);
        final double lookX = Mth.sin(negYawRadians);
        final double lookZ = Mth.cos(negYawRadians);
        // gravity 0.08, three quarters of it held up looking level
        double y = velocity.y + 0.08 * (-1.0 + 0.75);
        double lift = 0;
        if (y < 0.0) {
            lift = y * -0.1;
            y += lift;
        }
        return new Vec3(lookX * lift * 0.99F, y * 0.98F, lookZ * lift * 0.99F);
    }

    private boolean clientHasChunkAt(final Vec3 pos) {
        return ctx.world().getChunkSource().hasChunk(Mth.floor(pos.x) >> 4, Mth.floor(pos.z) >> 4);
    }

    /**
     * The hitbox a glide moved by steps has to fit: a standing player's when the server has the player standing for
     * part of the glide, else a gliding one's.
     */
    private AABB glideBox(final GlideSteps control) {
        return this.glideBoxAt(control, ctx.player().position());
    }

    /**
     * {@link #glideBox(GlideSteps)} with its feet at {@code feet}
     */
    public AABB glideBoxAt(final GlideSteps control, final Vec3 feet) {
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
        final Aim aim = this.aimAhead(box, feet);
        return aim != null ? aim.at() : null;
    }

    /**
     * {@link #glideTarget}, and how far along the path it is. Nothing the box has already got to along the path
     * ({@link #glideReachedProgress}) or before it, and no spot between two points that is behind the box on the way
     * from the first to the second: at the furthest spot along a stretch that the box gets to in a straight line, those
     * are all that is left, a block or a step back, and from there that spot is in a straight line again.
     */
    private Aim aimAhead(final AABB box, final Vec3 feet) {
        final NetherPath path = this.pathManager.getPath();
        final int near = this.pathManager.getNear();
        final AABB roomy = box.inflate(Baritone.settings().elytraMinimumAvoidance.value);
        return aimAhead(i -> this.glideFeetAt(path, i, box), near, Math.min(near + 20, path.size() - 1),
                this.glideReachedProgress, feet, (dest, withRoom) -> this.glidesTo(withRoom ? roomy : box, feet, dest));
    }

    /**
     * {@link #aimAhead(AABB, Vec3)} with what it looks at given
     *
     * @param pointFeet Where the feet go for the box to be at each point
     * @param near      The nearest point, which is never a target
     * @param furthest  The furthest point looked at
     * @param reached   How far along the path the box has got to a target ({@link #glideReachedProgress}), -1 for not
     * @param feet      Where the feet are
     * @param glidesTo  Whether the box gets to a spot in a straight line: with room to spare round it, or just
     */
    static Aim aimAhead(final IntFunction<Vec3> pointFeet, final int near, final int furthest, final double reached,
                        final Vec3 feet, final BiPredicate<Vec3, Boolean> glidesTo) {
        // with room to spare around the box first
        for (int i = furthest; i > near && i > reached; i--) {
            final Vec3 dest = pointFeet.apply(i);
            if (glidesTo.test(dest, true)) {
                return new Aim(dest, i);
            }
        }
        // then without, at the points and every block between them
        for (int i = furthest; i > near && i > reached; i--) {
            final Vec3 dest = pointFeet.apply(i);
            if (glidesTo.test(dest, false)) {
                return new Aim(dest, i);
            }
            final Vec3 back = pointFeet.apply(i - 1).subtract(dest);
            final double length = back.length();
            final Vec3 unit = back.normalize();
            final int blocks = fastFloor(length);
            for (int b = 1; b < blocks; b++) {
                final double progress = i - b / length;
                final Vec3 between = dest.add(unit.scale(b));
                if (progress <= reached || between.subtract(feet).dot(back) >= 0) {
                    // got to already, or back along the stretch from the box: the spots on from here are further back
                    break;
                }
                if (glidesTo.test(between, false)) {
                    return new Aim(between, progress);
                }
            }
        }
        return null;
    }

    /**
     * @return {@code found}, or the target it was heading for when that is further along the path and still in a
     * straight line ({@code stillClear}): one step from a wall the box has room for a line to a nearer point, and at
     * the wall only the line on is clear, so it would go back and forth between the two
     */
    static Aim keepAim(final Aim found, final Vec3 lastAt, final double lastProgress, final Predicate<Vec3> stillClear) {
        if (found != null && lastAt != null && found.progress() < lastProgress && stillClear.test(lastAt)) {
            return new Aim(lastAt, lastProgress);
        }
        return found;
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
        if (this.glideAvoidsLava() && this.glideTouchesLava(box, slid)) {
            return straight;
        }
        this.stepSlid = true;
        return slid;
    }

    /**
     * Whether a step has to keep out of lava: not when the player is in it already, where every way out goes through it
     */
    private boolean glideAvoidsLava() {
        return !ctx.player().isInLava();
    }

    /**
     * Whether the box anywhere on its way by {@code step} would catch fire: see {@link #burnsOnTheWay}. The collisions let
     * a glide into lava and fire, and only the path's rays keep it out, which see both as solid.
     */
    private boolean glideTouchesLava(final AABB box, final Vec3 step) {
        return this.burnsOnTheWay(this.burnBox(box), step) != null;
    }

    /**
     * @return The box to keep clear of lava and fire: with {@link #BURN_CLEARANCE} round it, unless it's that near one
     * already. Then the box itself: kept that near it, it would hold still beside the lava with every step refused
     */
    private AABB burnBox(final AABB box) {
        final AABB roomy = box.inflate(BURN_CLEARANCE);
        return this.burnsOnTheWay(roomy, Vec3.ZERO) == null ? roomy : box;
    }

    /**
     * @return The first block on the box's way by {@code step} that sets the player on fire, {@code null} if none: lava
     * over its fluid's surface, or fire. The game works out what a move went through one axis at a time, the way its
     * collisions move the box: up or down first, then the longer way across, then the shorter. A diagonal step's path
     * is an L, not the straight line the rays looked along, and its corner swings off that line by as much as the step
     * goes across: a step of two blocks past a lavafall a block off the line sets the player on fire
     */
    private BlockPos burnsOnTheWay(final AABB box, final Vec3 step) {
        return burnsOnTheWay(box, step, (x, y, z, minY) -> {
            final BlockPos pos = new BlockPos(x, y, z);
            final FluidState fluid = ctx.world().getFluidState(pos);
            if (fluid.is(FluidTags.LAVA) && y + fluid.getHeight(ctx.world(), pos) >= minY) {
                return true;
            }
            return ctx.world().getBlockState(pos).getBlock() instanceof BaseFireBlock;
        });
    }

    /**
     * What sets the player on fire in a block, for {@link #burnsOnTheWay(AABB, Vec3, Burns)}
     */
    @FunctionalInterface
    interface Burns {
        /**
         * @return Whether a box with its bottom at {@code minY} that reaches into block ({@code x}, {@code y},
         * {@code z}) is set on fire there
         */
        boolean at(int x, int y, int z, double minY);
    }

    /**
     * {@link #burnsOnTheWay(AABB, Vec3)} with what burns given
     */
    static BlockPos burnsOnTheWay(final AABB box, final Vec3 step, final Burns burns) {
        AABB at = box;
        boolean moved = false;
        for (final Direction.Axis axis : Direction.axisStepOrder(step)) {
            final double d = step.get(axis);
            if (d == 0) {
                continue;
            }
            final Vec3 part = axis == Direction.Axis.Y ? new Vec3(0, d, 0) : axis == Direction.Axis.X ? new Vec3(d, 0, 0) : new Vec3(0, 0, d);
            final BlockPos hit = burnsAlong(at, part, burns);
            if (hit != null) {
                return hit;
            }
            at = at.move(part);
            moved = true;
        }
        return moved ? null : burnsAlong(box, Vec3.ZERO, burns);
    }

    /**
     * @return The first block that sets the player on fire in the box swept by {@code part}, a move along one axis, so
     * that its sweep is a box too, a hair smaller as the game's own is. {@code null} if none
     */
    private static BlockPos burnsAlong(final AABB box, final Vec3 part, final Burns burns) {
        final AABB swept = box.expandTowards(part).deflate(1.0E-5);
        final int minX = Mth.floor(swept.minX);
        final int maxX = Mth.ceil(swept.maxX);
        final int minY = Mth.floor(swept.minY);
        final int maxY = Mth.ceil(swept.maxY);
        final int minZ = Mth.floor(swept.minZ);
        final int maxZ = Mth.ceil(swept.maxZ);
        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    if (burns.at(x, y, z, swept.minY)) {
                        return new BlockPos(x, y, z);
                    }
                }
            }
        }
        return null;
    }

    /**
     * @return {@code step}, or as much of it as gets the box nowhere that burns on its way as the game counts it (see
     * {@link #burnsOnTheWay}): a shorter step's path swings less far off its line. {@code null} when even the start of
     * it burns, or the box is in something that burns already
     */
    private Vec3 unburntGlideStep(final AABB box, final Vec3 step) {
        return unburntSlidStep(this.burnBox(box), step, (b, st) -> this.burnsOnTheWay(b, st) != null);
    }

    /**
     * {@link #unburntStep}, and of the step without each axis in turn, and of each axis alone, the longest: what
     * burns is slid along like a wall. Only shortened, a step with any of it into a fire's face, pressed up against
     * it, burns however short it gets, and the glide holds still beside the fire. {@code null} when none
     * of them gets anywhere
     */
    static Vec3 unburntSlidStep(final AABB box, final Vec3 step, final java.util.function.BiPredicate<AABB, Vec3> burns) {
        if (!burns.test(box, step)) {
            return step;
        }
        final Vec3[] candidates = {
                step,
                new Vec3(0, step.y, step.z), new Vec3(step.x, 0, step.z), new Vec3(step.x, step.y, 0),
                new Vec3(step.x, 0, 0), new Vec3(0, step.y, 0), new Vec3(0, 0, step.z)
        };
        Vec3 best = null;
        double bestLength = 0;
        for (final Vec3 candidate : candidates) {
            if (candidate.lengthSqr() < 1.0E-12) {
                continue;
            }
            final Vec3 kept = unburntStep(box, candidate, burns);
            if (kept != null && kept.lengthSqr() > bestLength) {
                best = kept;
                bestLength = kept.lengthSqr();
            }
        }
        return best;
    }

    /**
     * {@link #unburntGlideStep} with what burns given: whether the box's way by a step burns
     */
    static Vec3 unburntStep(final AABB box, final Vec3 step, final java.util.function.BiPredicate<AABB, Vec3> burns) {
        if (!burns.test(box, step)) {
            return step;
        }
        double lo = 0;
        double hi = 1;
        for (int i = 0; i < 10; i++) {
            final double mid = (lo + hi) / 2;
            if (!burns.test(box, step.scale(mid))) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        final Vec3 kept = step.scale(lo);
        return lo > 0 && !burns.test(box, kept) ? kept : null;
    }


    /**
     * @return The share of the glide's speed it may go at this tick: after a setback it starts slow and comes back up.
     * The server puts the player back with the speed it had worked out there, and a glide that sets off again at full
     * speed from a standstill is put back again and again
     */
    private double rampAfterSetback() {
        final int since = this.gameTick() - this.rampFromTick;
        return since >= 0 && since < RAMP_TICKS ? RAMP_FROM + (1 - RAMP_FROM) * since / RAMP_TICKS : 1;
    }

    /**
     * Notes how near the glide has come to the goal, and says what holds it when it has come no nearer for
     * {@link #STUCK_TICKS}: without it, flying at rock the pathfinder has as air says nothing at all
     */
    private void noteProgress(final Vec3 feet) {
        final BetterBlockPos goal = this.destinationFixed();
        final double dx = feet.x - goal.x;
        final double dz = feet.z - goal.z;
        final double dy = this.process.goalHasHeight() ? feet.y - goal.y : 0;
        final double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        final int tick = this.gameTick();
        if (distance < this.closestToGoal - 2 || tick - this.closestToGoalTick < 0) {
            this.closestToGoal = distance;
            this.closestToGoalTick = tick;
            return;
        }
        final int still = tick - this.closestToGoalTick;
        if (still >= STUCK_TICKS && tick - this.stuckSaidTick >= STUCK_TICKS) {
            this.stuckSaidTick = tick;
            logDirect(this.stuckLine(still / 20));
        }
        // a detour away from the goal gets no closer either, but it goes somewhere; a glide the server is bringing down
        // is the recovery's to finish; and a flight to a landing spot isn't the one to ask for again
        if (still >= this.process.askAgainAfterTicks() && !this.restartAsked && !this.appendDestination
                && !this.process.yields() && this.goesNowhere()) {
            this.restartAsked = true;
            if (this.stuckSaidTick < this.closestToGoalTick) {
                // what held it, before it's asked for again: said once for this hold
                this.stuckSaidTick = tick;
                logDirect(this.stuckLine(still / 20));
            }
            logDirect(String.format(Locale.ROOT, "No closer to the goal in %d s and getting nowhere: asking for the flight again from here, as a fresh #elytra would",
                    still / 20));
            this.process.askForFlightAgain();
        }
    }

    /**
     * @return Whether the last {@link #STUCK_MOVES} steps left the feet within {@link #STUCK_NET} of where they began:
     * held where it is, or going back and forth
     */
    private boolean goesNowhere() {
        if (this.recentMoves.size() <= STUCK_MOVES) {
            return false;
        }
        final Iterator<GlideMove> moves = this.recentMoves.descendingIterator();
        final Vec3 now = moves.next().feet;
        Vec3 then = now;
        for (int i = 0; i < STUCK_MOVES && moves.hasNext(); i++) {
            then = moves.next().feet;
        }
        return now.distanceToSqr(then) < STUCK_NET * STUCK_NET;
    }

    private String stuckLine(final int seconds) {
        final NetherPath path = this.pathManager.getPath();
        final int near = this.pathManager.getNear();
        final BetterBlockPos point = path.get(near);
        final BetterBlockPos feet = ctx.playerFeet();
        final GlideMove last = this.recentMoves.peekLast();
        final String search = this.pathManager.recalculating ? "a search running"
                : !this.pathManager.fromPlayer.mayAsk(this.gameTick()) ? "the next search waiting its turn after one found nothing"
                : "no search running";
        final List<Vec3> at = new ArrayList<>();
        final List<Double> asked = new ArrayList<>();
        for (final GlideMove move : this.recentMoves) {
            at.add(move.feet);
            asked.add(move.length);
        }
        final BlockPos aim = this.aimPos;
        final String aiming = aim == null ? "nothing"
                : this.glideTargetAt != null && this.glideTargetProgress >= 0
                ? String.format(Locale.ROOT, "%s (point %.1f of the path)", blockAt(aim), this.glideTargetProgress + 1)
                : blockAt(aim);
        return String.format(Locale.ROOT, "No closer to the goal in %d s: %s steps of %.1f blocks; %s; at %s, aiming at %s; the path's nearest point (%d of %d) is %s to the game and %s to the pathfinder, where the player is %s to it; %d chunks waiting to be packed, %s",
                seconds, last != null ? last.kind : "no", last != null ? last.length : 0, lastStepsMoved(at, asked, 20),
                blockAt(feet), aiming, near + 1, path.size(),
                this.gameBlockName(point),
                heldName(npfContext.held(point.x, point.y, point.z)), heldName(npfContext.held(feet.x, feet.y, feet.z)),
                npfContext.packsQueued(), search);
    }

    /**
     * @return The block at {@code pos} as the game has it, and when the server sent its section as empty, which the
     * game takes for air, what is in it all the same
     */
    private String gameBlockName(final BlockPos pos) {
        final BlockState state = ctx.world().getBlockState(pos);
        final String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        final LevelChunk chunk = ctx.world().getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, false);
        if (chunk == null) {
            return name;
        }
        final int index = chunk.getSectionIndex(pos.getY());
        final LevelChunkSection section = index >= 0 && index < chunk.getSections().length ? chunk.getSection(index) : null;
        if (!NetherPathfinderContext.sentEmpty(section)) {
            return name;
        }
        final BlockState inIt = section.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        return inIt.isAir() ? name : name + " (" + BuiltInRegistries.BLOCK.getKey(inIt.getBlock()).getPath() + " in a section the server sent as empty)";
    }

    private static String blockAt(final BlockPos pos) {
        return SettingsUtil.maybeCensor(pos.getX()) + " " + SettingsUtil.maybeCensor(pos.getY()) + " " + SettingsUtil.maybeCensor(pos.getZ());
    }

    /**
     * How the last {@code ticks} steps went: how far they asked to go, how far the player went, and how far it got. A
     * glide held where it is and one going back and forth both get no closer, and only this tells them apart: a step
     * the move never made, or steps that undo each other.
     *
     * @param feet  Where the feet were each tick, oldest first; the last is this tick's, its step not made yet
     * @param asked The length of the step worked out each tick, with {@code feet}
     */
    static String lastStepsMoved(final List<Vec3> feet, final List<Double> asked, final int ticks) {
        final int n = feet.size();
        if (n < 2) {
            return "no steps made yet to measure";
        }
        final int from = Math.max(0, n - 1 - ticks);
        double want = 0;
        double went = 0;
        for (int i = from; i < n - 1; i++) {
            want += asked.get(i);
            went += feet.get(i + 1).distanceTo(feet.get(i));
        }
        return String.format(Locale.ROOT, "the last %d steps asked for %.1f blocks and it went %.1f (%.1f from where they began)",
                n - 1 - from, want, went, feet.get(n - 1).distanceTo(feet.get(from)));
    }

    private static String heldName(final NetherPathfinderContext.Held held) {
        switch (held) {
            case NOTHING:
                return "in a chunk it hasn't got";
            case PREDICTED:
                return "the seed's guess";
            case AIR:
                return "air";
            default:
                return "solid";
        }
    }

    /**
     * Notes the step worked out this tick, from where the feet are, for {@link #burnLine}
     */
    private void noteGlideMove(final Vec3 feet, final String kind) {
        this.recentMoves.addLast(new GlideMove(feet, this.stepSlid ? kind + ", slid along what was in the way," : kind, this.gameTick(),
                this.glideStep != null ? this.glideStep.length() : 0));
        while (this.recentMoves.size() > RECENT_MOVES) {
            this.recentMoves.removeFirst();
        }
    }

    /**
     * Why the player has just caught fire, from the glide's last moves, newest first: what its box went through, on what
     * kind of step, and what the pathfinder had there. Steps the rays checked went through what the pathfinder had as
     * air, the others went where no ray looked.
     */
    public String burnLine() {
        final GlideSteps control = this.process.glideSteps();
        if (control == null || this.recentMoves.isEmpty()) {
            return null;
        }
        final List<GlideMove> moves = new ArrayList<>(this.recentMoves);
        final int now = this.gameTick();
        for (int i = moves.size() - 1; i >= 0; i--) {
            final GlideMove move = moves.get(i);
            final Vec3 end = i + 1 < moves.size() ? moves.get(i + 1).feet : ctx.player().position();
            final Vec3 way = end.subtract(move.feet);
            String box = control.standsWhileGliding() ? "standing box" : "gliding box";
            BlockPos hit = this.burnsOnTheWay(this.glideBoxAt(control, move.feet), way);
            if (hit == null && !control.standsWhileGliding()) {
                // the server has the player standing whenever it isn't gliding
                hit = this.burnsOnTheWay(ctx.player().getDimensions(Pose.STANDING).makeBoundingBox(move.feet), way);
                box = "standing box (the one the server has when it isn't gliding)";
            }
            if (hit == null) {
                continue;
            }
            final String what = ctx.world().getFluidState(hit).is(FluidTags.LAVA) ? "lava" : "fire";
            final String had;
            switch (npfContext.held(hit.getX(), hit.getY(), hit.getZ())) {
                case NOTHING:
                    had = "in a chunk it never got from the game";
                    break;
                case PREDICTED:
                    had = "only as the seed's guess";
                    break;
                case AIR:
                    had = "as air";
                    break;
                default:
                    had = "as solid";
            }
            return "Caught fire: " + (now - move.tick) + " ticks before, the " + box + " went through " + what + " on a "
                    + move.kind + " step, and the pathfinder had that block " + had;
        }
        // how near it came, which says whether the server can have had it in there
        for (final double margin : new double[]{0.05, 0.15, 0.3, 0.6, 1, 2}) {
            for (int i = moves.size() - 1; i >= 0; i--) {
                final GlideMove move = moves.get(i);
                final Vec3 end = i + 1 < moves.size() ? moves.get(i + 1).feet : ctx.player().position();
                if (this.burnsOnTheWay(this.glideBoxAt(control, move.feet).inflate(margin), end.subtract(move.feet)) != null) {
                    return "Caught fire with no lava or fire on the glide's way in the last " + moves.size()
                            + " ticks; the nearest it came was within " + margin + " blocks, " + (now - move.tick)
                            + " ticks before, on a " + move.kind + " step";
                }
            }
        }
        return "Caught fire with no lava or fire within 2 blocks of the glide's way in the last " + moves.size()
                + " ticks: something else set it alight, or the server had the player somewhere else";
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
    /**
     * @return Whether the box is partly in a block the pathfinder has as solid: one with a box of its own smaller than
     * the block, that the game lets it be in beside
     */
    private boolean inPackedSolid(final AABB box) {
        final AABB in = box.deflate(1.0E-5);
        for (int x = Mth.floor(in.minX); x < Mth.ceil(in.maxX); x++) {
            for (int y = Mth.floor(in.minY); y < Mth.ceil(in.maxY); y++) {
                for (int z = Mth.floor(in.minZ); z < Mth.ceil(in.maxZ); z++) {
                    if (!this.passable(x, y, z)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * @return The nearest spot within {@link #GLIDE_DETOUR_RANGE} that the box gets to in one straight move the game's
     * collisions don't cut, through nothing that burns, and where it's in nothing the pathfinder has as solid, so the
     * rays work from there; {@code null} if none
     */
    private Vec3 findSolidEscape(final AABB box, final Vec3 feet) {
        // in lava every way out goes through it: out by the shortest of them, not up along the path. a take-off from a
        // pool straight up the path goes up through lava the whole way, and the server puts it back in it every time
        final boolean inLava = !this.glideAvoidsLava();
        for (final Vec3 offset : inLava ? LAVA_ESCAPES : GLIDE_DETOURS) {
            final AABB moved = box.move(offset);
            if (this.inPackedSolid(moved) || this.cutsGlideStep(box, offset) || (!inLava && this.glideTouchesLava(box, offset))) {
                continue;
            }
            return feet.add(offset);
        }
        return null;
    }

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
        return offsetsWithin(GLIDE_DETOUR_RANGE);
    }

    private static List<Vec3> offsetsWithin(final double range) {
        final int n = fastFloor(range / GLIDE_DETOUR_GRID);
        final List<Vec3> offsets = new ArrayList<>();
        for (int x = -n; x <= n; x++) {
            for (int y = -n; y <= n; y++) {
                for (int z = -n; z <= n; z++) {
                    final Vec3 offset = new Vec3(x, y, z).scale(GLIDE_DETOUR_GRID);
                    if (offset.lengthSqr() > 0 && offset.length() <= range) {
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
    static Vec3 withinGlideLimits(final Vec3 offset, final double across, final double vertical) {
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
        final GlideSteps control = this.process.glideSteps();
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
        if (this.process.eatingForFireResistance()) {
            // a firework in hand would put the apple away: 1.6 s without a boost, rather than in lava without the eat
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
