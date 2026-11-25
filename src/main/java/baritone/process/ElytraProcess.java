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

package baritone.process;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.event.events.*;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.IElytraProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.behavior.highway.NetherHighwayBuilderBehavior;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.movements.MovementFall;
import baritone.process.elytra.*;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.ReconnectResume;
import baritone.utils.FoodUse;
import baritone.utils.PathingCommandContext;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

public class ElytraProcess extends BaritoneProcessHelper implements IBaritoneProcess, IElytraProcess, AbstractGameEventListener {
    public State state;
    private boolean goingToLandingSpot;
    private BetterBlockPos landingSpot;
    private boolean reachedGoal; // this basically just prevents potential notification spam
    private Goal goal;
    private ElytraBehavior behavior;
    private NetherPathfinderContext npfContext;
    private boolean predictingTerrain;
    private boolean allowTight;
    private boolean allowAboveBuildLimit;
    private boolean allowAboveRoof;
    private final Semaphore npfSema = new Semaphore(1);
    private final GlideSteps glideSteps = new GlideSteps();
    private final GlideDurabilitySaver durabilitySaver = new GlideDurabilitySaver(ctx);
    // the take-off's jump is held every other tick: the elytra opens on a fresh press in the air
    private boolean takeOffJumpHeld;
    // the block a flight to a block goal is to stand in at the end, for elytraLandAtGoal; null for a goal without one
    private BetterBlockPos goalBlock;
    // a landing at the goal has said it is looking, and that there's nowhere to stand: each only once
    private boolean goalLandingSearchSaid;
    private boolean goalLandingRefused;
    private static final int GOAL_LANDING_RADIUS = 4;

    /**
     * What a landing is for: the end of the flight, an emergency, coming down after the server pulled the player down
     * (it takes off again after), or giving up (it says so after, not "Done :)")
     */
    private enum LandingPurpose {
        ARRIVAL,
        EMERGENCY,
        SETTLE,
        GIVE_UP
    }

    private LandingPurpose landingPurpose = LandingPurpose.ARRIVAL;

    // the flight as asked for: where to, and the block to stand in at the end. a landing spot on the way changes the
    // behavior's destination, and coming down after a pull-down is followed by this flight again
    private BlockPos flightDestination;
    private BetterBlockPos flightGoalBlock;
    // the flight's own tick count, for windows of ticks that a new player entity doesn't start again
    private int flightTicks;
    private GlideSetbacks setbacks = new GlideSetbacks(Baritone.settings());
    private GlideRecovery recovery = new GlideRecovery(Baritone.settings(), this.setbacks);
    private final ElytraSafety safety = new ElytraSafety();
    // the server set the player back since the last tick, and the path's nearest point is to be taken again
    private boolean setBackSinceTick;
    // where the player was last tick while gliding, for the distance flown clear of setbacks; null after a setback
    private Vec3 lastGlidePos;
    // the take-off jumped last tick, its path in
    private boolean takeOffJumping;
    // glide steps moved this flight at some point, and stopped with no fireworks to fly on instead, said once
    private boolean glidedBySteps;
    private boolean stepsStoppedSaid;
    private int stepsStoppedTicks;
    private static final int STEPS_STOPPED_TICKS = 10;
    // said once each: the emergency, carrying on in spite of it, and the search for a landing spot
    private boolean emergencySaid;
    private boolean emergencyRefusedSaid;
    private boolean landingSearchSaid;
    // the flight gave up and lands wherever it can
    private boolean forcedLanding;
    // the search for a spot to land on found nothing: when to look again, and since when there's been nothing
    private int landingSearchAgainTick;
    private final LandingHold landingHold = new LandingHold(LANDING_RADIUS, LANDING_HOLD_TICKS);
    private GlideLandingSearch glideLandingSearch;
    // how far across the search for a spot to land on looks: further over lava
    private int glideLandingRadius = LANDING_RADIUS;
    // pulled down over lava with no floor near: the search further for one, spread over ticks
    private GlideLandingSearch settleSearch;
    // the forced landing found nowhere over lava and flies on instead of ending in the air, said once; and it came
    // down in lava, said once a touchdown
    private boolean lavaFlyOnSaid;
    private boolean forcedLavaSaid;
    // fire resistance: when to eat for it, and whether the use held now is that eat's. not of one flight: a flight
    // asked for again mid-eat goes on eating
    private final FireResistanceEat fireResistanceEat = new FireResistanceEat();
    private boolean fireResistanceEatHeld;
    // on fire last tick, so the line about what set it alight is said as it starts
    private boolean wasOnFire;
    // the flight tick the server last set the player back down, and for how long after it the glide doesn't climb
    private int pulledDownTick = Integer.MIN_VALUE / 2;
    private static final int HOLD_LEVEL_TICKS = 40;
    // brought down by the server: the floor it faces, null for the way it goes; and how near the ground nothing is
    // picked
    private BetterBlockPos settleAim;
    private static final int NEAR_GROUND = 8;
    // a fall this far does no harm: the server bringing it down, this near the floor the glide is let go of
    private static final int SAFE_DROP = 3;
    // the lowest the glide has come since the server had it, and when; held for this long with this many setbacks and
    // no lower, it's let go of over a floor within NEAR_GROUND too
    private double lowestY = Double.MAX_VALUE;
    private int lowestTick;
    private static final double DESCENT = 0.5;
    private static final int HELD_TICKS = 60;
    private static final int HELD_SETBACKS = 10;
    private boolean dropSaid;
    // a landing moved by steps comes down on a floor this far across from where it looks from, and this far down
    private static final int LANDING_RADIUS = 24;
    private static final int SETTLE_RADIUS = 16;
    // over lava, where nothing near is a floor and ending the flight drops it in: along the path this far ahead, this
    // far either side of it, then this far around
    private static final int LAVA_AHEAD = 64;
    private static final int LAVA_AHEAD_RADIUS = 8;
    private static final int LAVA_LANDING_RADIUS = 64;
    // where the player was the last tick the flight had control, and when. moved further than this while something else
    // had it for at least this long (a pause, another process), the flight is asked for again from where it is now. a
    // tick or two missed is a hiccup, and a glide goes a few blocks in that
    private int lastControlTick;
    private Vec3 lastControlPos;
    // the glide got nowhere on the path it had: the flight is asked for again from here next tick
    private boolean askedAgain;
    // getting nowhere this long asks for the flight again, and twice as long each time it's asked for again within
    // ASK_AGAIN_FORGET_TICKS of the last: every chunk is packed again for it, and a spot each new path gets stuck at
    // would have that done every few seconds
    private static final int ASK_AGAIN_TICKS = 200;
    private static final int ASK_AGAIN_MOST_TICKS = 1200;
    private static final int ASK_AGAIN_FORGET_TICKS = 1200;
    private int askAgainWait = ASK_AGAIN_TICKS;
    private int askedAgainTick = Integer.MIN_VALUE / 2;
    private static final double MOVED_AWAY = 4;
    private static final int AWAY_TICKS = 20;
    private static final int LANDING_DEPTH = 96;
    private static final int LANDING_SEARCH_AGAIN_TICKS = 40;
    // under a landing spot by this much and this far off its middle, a glide moved by steps can't come down on it
    private static final double LANDING_UNDER = 0.25;
    private static final double LANDING_UNDER_OFF = 0.5;
    // setbacks in place within the window, while landing, that give the spot up: four seconds of them
    private static final int LANDING_HELD_SETBACKS = 20;
    // the flight tick the landing on the spot began, from which those count
    private int landingSinceTick;
    // with nowhere to land at the end of the path, a flight moved by steps holds still this long, then ends
    private static final int LANDING_HOLD_TICKS = 600;

    private static final int SHORT_LANDING_COLUMN_HEIGHT = 15;
    private static final int LONG_LANDING_COLUMN_HEIGHT = 39;
    private static final long LANDING_SEARCH_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(25); // half a tick
    // chunks around the player packed before the first path: its start's search for air can go a dozen blocks out
    private static final int PACK_NOW_RADIUS = 2;
    private int landingColumnHeight = SHORT_LANDING_COLUMN_HEIGHT;
    private Set<BetterBlockPos> badLandingSpots = new HashSet<>();
    private LandingSearchState landingSearchState;

    @Override
    public void onLostControl() {
        this.stopFireResistanceEat();
        this.fireResistanceEat.reset();
        onLostControl(true);
    }

    public void onLostControl(boolean destroyNpf) {
        this.state = State.START_FLYING; // TODO: null state?
        this.goingToLandingSpot = false;
        this.landingSpot = null;
        this.landingSearchState = null;
        this.glideLandingSearch = null;
        this.glideLandingRadius = LANDING_RADIUS;
        this.settleSearch = null;
        this.settleAim = null;
        this.landingPurpose = LandingPurpose.ARRIVAL;
        this.reachedGoal = false;
        this.goal = null;
        this.takeOffJumpHeld = false;
        this.goalLandingSearchSaid = false;
        this.goalLandingRefused = false;
        this.lastControlPos = null;
        destroyBehaviorAsync();
        if (destroyNpf) {
            destroyNpfContextAsync();
        }
    }

    private ElytraProcess(Baritone baritone) {
        super(baritone);
        baritone.getGameEventHandler().registerEventListener(this);
    }

    public static IElytraProcess create(final Baritone baritone) {
        return new ElytraProcess(baritone);
    }

    @Override
    public boolean isActive() {
        return this.behavior != null;
    }

    @Override
    public void resetState() {
        BlockPos destination = this.currentDestination();
        // the same flight: a landing spot it was on its way to keeps the goal it was found for
        BetterBlockPos goalBlock = this.goalBlock;
        this.onLostControl();
        if (destination != null) {
            this.pathTo(destination, goalBlock);
            this.repackChunks();
        }
    }

    private static final String AUTO_JUMP_FAILURE_MSG = "Failed to compute a walking path to a spot to jump off from. Consider starting from a higher location, near an overhang. Or, you can disable elytraAutoJump and just manually begin gliding.";

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        try {
            final long seedSetting = Baritone.settings().elytraNetherSeed.value;
            if (seedSetting != this.behavior.npfContext.getSeed()) {
                logDirect("Nether seed changed, recalculating path");
                this.resetState();
            }
            if (predictingTerrain != Baritone.settings().elytraPredictTerrain.value && ctx.player().level().dimension() == Level.NETHER) {
                logDirect("elytraPredictTerrain setting changed, recalculating path from scratch");
                predictingTerrain = Baritone.settings().elytraPredictTerrain.value;
                this.resetState();
            }
            if (allowTight != Baritone.settings().elytraAllowTightSpaces.value) {
                logDirect("elytraAllowTightSpaces setting changed, recalculating path from scratch");
                allowTight = Baritone.settings().elytraAllowTightSpaces.value;
                this.resetState();
            }
            if (allowAboveBuildLimit != Baritone.settings().elytraAllowAboveBuildLimit.value) {
                logDirect("elytraAllowAboveBuildLimit setting changed, recalculating path from scratch");
                allowAboveBuildLimit = Baritone.settings().elytraAllowAboveBuildLimit.value;
                this.resetState();
            }
            if (allowAboveRoof != Baritone.settings().elytraAllowAboveRoof.value && ctx.player().level().dimension() == Level.NETHER) {
                logDirect("elytraAllowAboveRoof setting changed, recalculating path from scratch");
                allowAboveRoof = Baritone.settings().elytraAllowAboveRoof.value;
                this.resetState();
            }
        } catch (IllegalArgumentException e) {
            logDirect(e.getMessage(), ChatFormatting.RED);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        final double movedAway = this.movedWhileAway();
        if (movedAway > 0) {
            // the path from where it was leads back there: a pause in which the player is flown or walked somewhere
            // else turns it round, back the way it came
            logDirect(String.format(Locale.ROOT, "Moved %.0f blocks while something else had control, finding the way on from here", movedAway));
            this.resumeFlight();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (this.askedAgain) {
            this.askedAgain = false;
            if (!this.forcedLanding) {
                // a new path from here to the goal, every chunk packed again, and the glide's steps from nothing
                this.resumeFlight();
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
        }

        if (!(glidesBySteps() && this.recovery.settled())) {
            // down and waiting to take off again, the path to where it came down has nothing left to do, and looking
            // along it from under an overhang would ask for it again every tick of the wait
            this.behavior.onTick();
        }

        if (calcFailed) {
            onLostControl();
            logDirect(AUTO_JUMP_FAILURE_MSG);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        final int now = ++this.flightTicks;
        final boolean fallFlying = ctx.player().isFallFlying();
        final boolean steps = glidesBySteps();
        final int fireworks = this.countFireworks();
        if (steps) {
            this.glidedBySteps = true;
        }
        if (this.setBackSinceTick) {
            // the player is where the server put it now
            this.setBackSinceTick = false;
            this.behavior.onSetback();
        }
        // what the player is in that burns: it doesn't wait for anything there, and eats for fire resistance
        final String heat = this.heat();
        final boolean onFire = ctx.player().isOnFire();
        if (onFire && !this.wasOnFire && steps) {
            // what did it: the line says what the box went through and whether the pathfinder knew
            final String burnt = this.behavior.burnLine();
            if (burnt != null) {
                logDirect(burnt);
            }
        }
        this.wasOnFire = onFire;
        this.tickFireResistance(now, fallFlying, heat);

        if (ElytraSafety.stepsStopped(this.glidedBySteps, steps, fireworks)) {
            // nothing moves the glide but its own physics, and nothing to push it: steering it, or landing it by
            // fireworks that aren't there, is worse than leaving it be until the steps come back. a tick or two of
            // that is a flicker, and says nothing
            baritone.getInputOverrideHandler().clearAllKeys();
            if (++this.stepsStoppedTicks < STEPS_STOPPED_TICKS) {
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            if (!fallFlying) {
                logDirect("Gave up the flight: elytraFlyBySteps is off and there are no fireworks to fly on");
                this.endFlight();
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            if (!this.stepsStoppedSaid) {
                logDirect("elytraFlyBySteps was turned off mid-flight and there are no fireworks: leaving the glide alone until it is on again");
                this.stepsStoppedSaid = true;
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (this.stepsStoppedTicks > 0) {
            this.stepsStoppedTicks = 0;
            // looked for afresh from wherever the glide went meanwhile
            this.behavior.onSetback();
        }
        if (this.stepsStoppedSaid) {
            this.stepsStoppedSaid = false;
            logDirect("Glide steps are back, carrying on");
        }

        if (steps) {
            this.noteDescent();
            if (fallFlying && this.dropsToGround()) {
                if (!this.dropSaid) {
                    this.dropSaid = true;
                    final int over = this.floorBelow(SAFE_DROP);
                    logDirect(over >= 0
                            ? "Within " + Math.max(1, over) + " blocks of the floor: letting the game's glide bring it down onto it"
                            : "Held where it is by the server for " + HELD_TICKS / 20 + " s, " + this.floorBelow(NEAR_GROUND)
                            + " blocks over the floor: letting the game's glide bring it down onto it");
                }
            } else if (!(this.recovery.settling() || this.recovery.settled() || this.holdsLevel())) {
                // once a descent: it bobs in and out of the safe drop on the way down
                this.dropSaid = false;
            }
            final GlideRecovery.Action action = this.recovery.tick(now, fallFlying, ctx.player().onGround(), this.takeOffJumping, heat);
            this.takeOffJumping = false;
            final String said = this.recovery.line();
            if (said != null) {
                logDirect(said);
            }
            switch (action) {
                case SETTLE:
                    this.settle();
                    break;
                case HOLD:
                    baritone.getInputOverrideHandler().clearAllKeys();
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                case TAKE_OFF:
                    this.resumeFlight();
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                case LET_GO:
                    // no step and no glide held (getGlideStep): the game's own move in the lava, as after a #stop
                    baritone.getInputOverrideHandler().clearAllKeys();
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                case GIVE_UP:
                    this.giveUp();
                    if (this.behavior == null) {
                        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                    }
                    break;
                default:
                    break;
            }
            this.advanceSettleSearch();
            if (fallFlying && !this.recovery.settling()) {
                final Vec3 pos = ctx.player().position();
                if (this.lastGlidePos != null) {
                    final String faster = this.setbacks.flown(Math.sqrt(pos.subtract(this.lastGlidePos).horizontalDistanceSqr()));
                    if (faster != null) {
                        logDirect(faster);
                    }
                }
                this.lastGlidePos = pos;
            } else {
                this.lastGlidePos = null;
            }
        }

        boolean safetyLanding = this.forcedLanding;
        // a tick's flicker of the chest slot or the glide control isn't a reason to land
        final String danger = this.safety.hold(fallFlying ? this.safetyReason(steps, fireworks) : null);
        if (danger == null) {
            this.emergencySaid = false;
            this.emergencyRefusedSaid = false;
        } else if (Baritone.settings().elytraAllowEmergencyLand.value) {
            if (!this.emergencySaid) {
                logDirect("Emergency landing - " + danger);
                this.emergencySaid = true;
            }
            safetyLanding = true;
        } else if (!this.emergencyRefusedSaid) {
            logDirect("almost out of elytra durability or fireworks (" + danger + "), but I'm going to continue since elytraAllowEmergencyLand is false");
            this.emergencyRefusedSaid = true;
        }
        if (fallFlying) {
            this.forcedLavaSaid = false;
        } else if (this.forcedLanding && ctx.player().onGround()) {
            // a firework take-off doesn't start from lava, so only a glide moved by steps goes up again from there
            if (heat == null || !steps) {
                // down before it got to a spot, the server's doing or its own
                logDirect("Landed after giving up the flight");
                this.endFlight();
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            // ended here the flight leaves it in the lava: up again with the take-off below, and down somewhere else
            if (!this.forcedLavaSaid) {
                logDirect("Down in " + heat + " after giving up the flight, taking off again to land somewhere else");
                this.forcedLavaSaid = true;
            }
            this.abandonLanding();
        }
        final boolean settling = steps && this.recovery.settling();
        if (fallFlying && this.state != State.LANDING && (this.behavior.pathManager.isComplete() || safetyLanding)) {
            final BetterBlockPos last = this.behavior.pathManager.path.getLast();
            if ((safetyLanding || (last != null && ctx.player().position().distanceToSqr(last.getCenter()) < (48 * 48))) && (!goingToLandingSpot || (safetyLanding && this.landingSpot == null))) {
                // an emergency comes down wherever is safe nearby, and so does coming down after a pull-down
                final boolean atGoal = !safetyLanding && !settling && landsAtGoal();
                BetterBlockPos landingSpot = null;
                if (atGoal) {
                    if (!this.goalLandingSearchSaid) {
                        logDirect("Path complete, searching for safe landing spot...");
                        this.goalLandingSearchSaid = true;
                    }
                    landingSpot = findGoalLandingSpot();
                } else if (now >= this.landingSearchAgainTick) {
                    if (!this.landingSearchSaid && !settling && !this.forcedLanding) {
                        logDirect("Path complete, searching for safe landing spot...");
                    }
                    this.landingSearchSaid = true;
                    final boolean exhausted;
                    if (steps) {
                        landingSpot = this.advanceGlideLandingSearch();
                        exhausted = landingSpot == null && this.glideLandingSearch == null;
                    } else {
                        landingSpot = findSafeLandingSpot(ctx.playerFeet());
                        exhausted = landingSpot == null && this.landingSearchState == null;
                    }
                    if (exhausted) {
                        // the same search again finds the same nothing: again once chunks may have come in
                        this.landingSearchAgainTick = now + LANDING_SEARCH_AGAIN_TICKS;
                        final PathingCommand ended = this.nowhereToLand(now, steps, safetyLanding, settling);
                        if (ended != null) {
                            return ended;
                        }
                    }
                }
                if (landingSpot != null) {
                    logDirect("Found potential landing spot.");
                    this.landingHold.reset();
                    final LandingPurpose purpose = this.forcedLanding ? LandingPurpose.GIVE_UP
                            : settling ? LandingPurpose.SETTLE
                            : safetyLanding ? LandingPurpose.EMERGENCY
                            : LandingPurpose.ARRIVAL;
                    if (steps && !atGoal) {
                        // a block to stand in, found for a glide moved by steps
                        this.landOn(landingSpot, purpose);
                    } else {
                        this.pathTo0(landingSpot, true, atGoal);
                        this.landingSpot = landingSpot;
                        this.goingToLandingSpot = true;
                        this.landingPurpose = purpose;
                    }
                } else {
                    this.goingToLandingSpot = false;
                }
            }

            if (last != null && this.behavior.isAtPathEnd()) {
                if (Baritone.settings().notificationOnPathComplete.value && !reachedGoal) {
                    logNotification("Pathing complete", false);
                }
                if (Baritone.settings().disconnectOnArrival.value && !reachedGoal && this.landingPurpose == LandingPurpose.ARRIVAL) {
                    // don't be active when the user logs back in
                    this.onLostControl();
                    if (ctx.world() instanceof ClientLevel clientLevel) {
                        baritone.getReconnectResume().disconnectingOnPurpose();
                        clientLevel.disconnect(Component.literal("[Baritone] Arrived at goal!"));
                    }
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                reachedGoal = true;

                // we are goingToLandingSpot and we are in the last node of the path
                if (this.goingToLandingSpot && landingSpot != null) {
                    this.state = State.LANDING;
                    this.landingSinceTick = now;
                    logDirect("Above the landing spot, landing...");
                }
            }
        }

        if (this.state == State.LANDING) {
            final BetterBlockPos endPos = this.landingSpot != null ? this.landingSpot : behavior.pathManager.path.getLast();
            if (ctx.player().isFallFlying() && endPos != null) {
                Vec3 from = ctx.player().position();
                Vec3 to = new Vec3(((double) endPos.x) + 0.5, from.y, ((double) endPos.z) + 0.5);
                Rotation rotation = RotationUtils.calcRotationFromVec3d(from, to, ctx.playerRotations());
                baritone.getLookBehavior().updateTarget(new Rotation(rotation.getYaw(), 0), false); // this will be overwritten, probably, by behavior tick

                final double off = Math.hypot(from.x - (endPos.x + 0.5), from.z - (endPos.z + 0.5));
                if (ctx.player().position().y < endPos.y - this.landingColumnHeight) {
                    logDirect("bad landing spot, trying again...");
                    landingSpotIsBad(endPos);
                } else if (glidesBySteps() && from.y < endPos.y - LANDING_UNDER && off > LANDING_UNDER_OFF) {
                    // put under the spot's floor and beside it, by setbacks: a landing lines up over the spot at its own
                    // height before it comes down, and from under the floor that runs into the side of the block it
                    // stands on. without this it holds still against it indefinitely. somewhere it can come straight down
                    // instead
                    logDirect("Below the landing spot and beside it, looking for somewhere else to come down");
                    landingSpotIsBad(endPos);
                } else if (glidesBySteps() && this.setbacks.heldInPlace(this.flightTicks, this.landingSinceTick) >= LANDING_HELD_SETBACKS) {
                    // whatever has it pinned, five setbacks a second where it is gets it no nearer the floor
                    logDirect("Held where it is by the server for " + LANDING_HELD_SETBACKS / 5 + " s while landing, looking for somewhere else to come down");
                    landingSpotIsBad(endPos);
                }
            }
        }

        if (ctx.player().isFallFlying()) {
            behavior.landingMode = this.state == State.LANDING;
            this.goal = null;
            baritone.getInputOverrideHandler().clearAllKeys();
            if (this.behavior.npfContext.tryAcquireReadLockSoon()) {
                try {
                    behavior.tick();
                } finally {
                    this.behavior.npfContext.releaseReadLock();
                }
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        } else if (this.state == State.LANDING) {
            if (heat != null && steps) {
                // the glide over in lava short of the spot isn't a landing: sneaking there sinks it, and the flight would
                // end in it
                logDirect("Came down in " + heat + " short of the landing spot, taking off again");
                this.abandonLanding();
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            if (ctx.playerMotion().multiply(1, 0, 1).length() > 0.001) {
                logDirect("Landed, but still moving, waiting for velocity to die down... ");
                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            if (this.landingPurpose == LandingPurpose.GIVE_UP) {
                // the flight is over, but it didn't get there: what "Done :)" means to whoever reads it
                logDirect("Landed after giving up the flight");
                baritone.getInputOverrideHandler().clearAllKeys();
                this.endFlight();
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            logDirect("Done :)");
            baritone.getInputOverrideHandler().clearAllKeys();
            this.onLostControl();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (this.state == State.FLYING || this.state == State.START_FLYING) {
            // a glide moved by steps takes off from flat ground, it needs no ledge to jump off
            this.state = ctx.player().onGround() && Baritone.settings().elytraAutoJump.value && !glidesBySteps()
                    ? State.LOCATE_JUMP
                    : State.START_FLYING;
        }

        if (this.state == State.LOCATE_JUMP) {
            if (shouldLandForSafety()) {
                logDirect("Not taking off, because elytra durability or fireworks are so low that I would immediately emergency land anyway.");
                onLostControl();
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            if (this.goal == null) {
                this.goal = new GoalYLevel(31);
            }
            final IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
            if (executor != null && executor.getPath().getGoal() == this.goal) {
                final IMovement fall = executor.getPath().movements().stream()
                        .filter(movement -> movement instanceof MovementFall)
                        .findFirst().orElse(null);

                if (fall != null) {
                    final BetterBlockPos from = new BetterBlockPos(
                            (fall.getSrc().x + fall.getDest().x) / 2,
                            (fall.getSrc().y + fall.getDest().y) / 2,
                            (fall.getSrc().z + fall.getDest().z) / 2
                    );
                    behavior.pathManager.pathToDestination(from).whenComplete((result, ex) -> {
                        if (ex == null) {
                            this.state = State.GET_TO_JUMP;
                            return;
                        }
                        onLostControl();
                    });
                    this.state = State.PAUSE;
                } else {
                    onLostControl();
                    logDirect(AUTO_JUMP_FAILURE_MSG);
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
            }
            return new PathingCommandContext(this.goal, PathingCommandType.SET_GOAL_AND_PAUSE, new WalkOffCalculationContext(baritone));
        }

        // yucky
        if (this.state == State.PAUSE) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (this.state == State.GET_TO_JUMP) {
            final IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
            // TODO 1.21.5: replace `ctx.player().getDeltaMovement().y < -0.377` with `ctx.player().fallDistance > 1.0f`
            final boolean canStartFlying = ctx.player().getDeltaMovement().y < -0.377
                    && !isSafeToCancel
                    && executor != null
                    && executor.getPath().movements().get(executor.getPosition()) instanceof MovementFall;

            if (canStartFlying) {
                this.state = State.START_FLYING;
            } else {
                return new PathingCommand(null, PathingCommandType.SET_GOAL_AND_PATH);
            }
        }

        if (this.state == State.START_FLYING) {
            if (!isSafeToCancel) {
                // owned
                baritone.getPathingBehavior().secretInternalSegmentCancel();
            }
            baritone.getInputOverrideHandler().clearAllKeys();
            if (glidesBySteps()) {
                if (shouldLandForSafety()) {
                    logDirect("Not taking off, because the elytra is missing or its durability is so low that I would immediately emergency land anyway.");
                    onLostControl();
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                // nowhere to fly until the path is in
                if (!this.behavior.pathManager.getPath().isEmpty()) {
                    // a jump, then a fresh press in the air opens the elytra
                    this.takeOffJumpHeld = !this.takeOffJumpHeld;
                    baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, this.takeOffJumpHeld);
                    // timed, and given up after elytraTakeOffTries (GlideRecovery)
                    this.takeOffJumping = true;
                }
            } else if (ctx.player().getDeltaMovement().y < -0.377) {
                // TODO 1.21.5: replace `ctx.player().getDeltaMovement().y < -0.377` with `ctx.player().fallDistance > 1.0f`
                baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
            }
        }
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    public void landingSpotIsBad(BetterBlockPos endPos) {
        badLandingSpots.add(endPos);
        goingToLandingSpot = false;
        this.landingSpot = null;
        this.landingSearchState = null;
        this.state = State.FLYING;
    }

    private void destroyBehaviorAsync() {
        ElytraBehavior behavior = this.behavior;
        if (behavior != null) {
            this.behavior = null;
            Baritone.getExecutor().execute(() -> {
                behavior.destroy();
            });
        }
    }

    @Override
    public double priority() {
        return 0; // higher priority than CustomGoalProcess
    }

    @Override
    public String displayName0() {
        return "Elytra - " + (this.recovery.settled() ? "Waiting to take off again" : this.state.description);
    }

    @Override
    public void repackChunks() {
        this.repackChunks(false);
    }

    /**
     * @param packNearNow Whether to pack the chunks within {@link #PACK_NOW_RADIUS} of the player's here and now, for
     *                    the path about to be asked for. Queued, they race that path, and a chunk it gets to first counts
     *                    as air: it starts where the player is, inside whatever it's in.
     */
    private void repackChunks(final boolean packNearNow) {
        if (this.npfContext == null) return;

        ChunkSource chunkProvider = ctx.world().getChunkSource();
        BetterBlockPos playerPos = ctx.playerFeet();

        int playerChunkX = playerPos.getX() >> 4;
        int playerChunkZ = playerPos.getZ() >> 4;

        if (packNearNow) {
            // before anything is queued. the write thread starts on the queue at once and holds the lock for each chunk
            // it packs, so packed here after the far chunks were queued, these mostly found the lock taken and went in
            // the queue behind them, and the first path was searched with the player's own chunk still air
            for (int x = playerChunkX - PACK_NOW_RADIUS; x <= playerChunkX + PACK_NOW_RADIUS; x++) {
                for (int z = playerChunkZ - PACK_NOW_RADIUS; z <= playerChunkZ + PACK_NOW_RADIUS; z++) {
                    LevelChunk chunk = chunkProvider.getChunk(x, z, false);
                    if (chunk != null && !chunk.isEmpty() && !npfContext.tryPackNow(chunk)) {
                        npfContext.queueForPacking(chunk);
                    }
                }
            }
        }

        int minX = playerChunkX - 40;
        int minZ = playerChunkZ - 40;
        int maxX = playerChunkX + 40;
        int maxZ = playerChunkZ + 40;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (packNearNow && Math.abs(x - playerChunkX) <= PACK_NOW_RADIUS && Math.abs(z - playerChunkZ) <= PACK_NOW_RADIUS) {
                    continue; // done above
                }
                LevelChunk chunk = chunkProvider.getChunk(x, z, false);

                if (chunk != null && !chunk.isEmpty()) {
                    npfContext.queueForPacking(chunk);
                }
            }
        }
    }

    @Override
    public BlockPos currentDestination() {
        return this.behavior != null ? this.behavior.destination : null;
    }

    @Override
    public List<BetterBlockPos> getPath() {
        return this.behavior != null ? behavior.pathManager.getPath() : Collections.emptyList();
    }

    @Override
    public void pathTo(BlockPos destination) {
        this.pathTo(destination, new BetterBlockPos(destination));
    }

    /**
     * @param goalBlock The block to stand in at the end for {@link baritone.api.Settings#elytraLandAtGoal}, or
     *                  {@code null} when the destination isn't one
     */
    private void pathTo(BlockPos destination, BetterBlockPos goalBlock) {
        if (!isSupportedPos(destination)) {
            throw new IllegalArgumentException("The goal must be within bounds to use elytra flight.");
        }

        if (ctx.player() != null && !isSupportedPos(ctx.playerFeet())) {
            throw new IllegalArgumentException("The player must be within bounds to use elytra flight.");
        }

        this.startFlight(destination, goalBlock);
        this.pathTo0(destination, false, false);
        this.goalBlock = goalBlock;
        // the spots a past flight couldn't land on may do now, and a goal landing comes back to the same blocks
        this.badLandingSpots.clear();
    }

    @Override
    public ReconnectResume.Restart restartAfterReconnect() {
        final BlockPos destination = this.flightDestination;
        if (this.behavior == null || destination == null) {
            return null;
        }
        final BetterBlockPos goalBlock = this.flightGoalBlock;
        final String to = goalBlock != null ? destination.toShortString() : destination.getX() + ", " + destination.getZ();
        // the flight as it was asked for, from wherever the player is back: a landing on the way is looked for again.
        // in the air it's still gliding (the server keeps that with the player), and the flying branch takes it from
        // there; else it takes off as from anywhere else
        return new ReconnectResume.Restart("the elytra flight to " + to, () -> this.pathTo(destination, goalBlock));
    }

    /**
     * A new flight: nothing of the last one's setbacks, take-offs or landing searches carries over, but the glide
     * speed it learned does ({@link baritone.api.Settings#elytraGlideSpeed})
     */
    private void startFlight(BlockPos destination, BetterBlockPos goalBlock) {
        this.flightDestination = destination;
        this.flightGoalBlock = goalBlock;
        this.setbacks = new GlideSetbacks(Baritone.settings());
        this.recovery = new GlideRecovery(Baritone.settings(), this.setbacks);
        this.safety.reset();
        this.setBackSinceTick = false;
        this.lastGlidePos = null;
        this.pulledDownTick = Integer.MIN_VALUE / 2;
        this.takeOffJumping = false;
        this.glidedBySteps = false;
        this.stepsStoppedSaid = false;
        this.stepsStoppedTicks = 0;
        this.emergencySaid = false;
        this.emergencyRefusedSaid = false;
        this.landingSearchSaid = false;
        this.forcedLanding = false;
        this.lavaFlyOnSaid = false;
        this.landingSearchAgainTick = 0;
        this.landingHold.reset();
        this.askAgainWait = ASK_AGAIN_TICKS;
        this.askedAgainTick = Integer.MIN_VALUE / 2;
    }

    /**
     * Notes that the flight has control this tick
     *
     * @return How far the player was moved since the flight last had control, when that was {@link #AWAY_TICKS} or
     * more ago and it's further than {@link #MOVED_AWAY}; else 0
     */
    private double movedWhileAway() {
        final int tick = ctx.player().tickCount;
        final Vec3 at = ctx.player().position();
        final double moved = this.lastControlPos != null && tick - this.lastControlTick >= AWAY_TICKS ? at.distanceTo(this.lastControlPos) : 0;
        this.lastControlTick = tick;
        this.lastControlPos = at;
        return moved > MOVED_AWAY ? moved : 0;
    }

    /**
     * Asks for the flight again from here at the start of the next tick, as {@link #resumeFlight()} does: the glide is
     * getting nowhere on the path it has. Not in the middle of the behaviour's own tick, which is the old flight's.
     */
    public void askForFlightAgain() {
        this.askedAgain = true;
        this.askAgainWait = Math.min(ASK_AGAIN_MOST_TICKS, this.askAgainAfterTicks() * 2);
        this.askedAgainTick = this.flightTicks;
    }

    /**
     * @return How long a glide that gets no closer to the goal and nowhere besides waits before it asks for the flight
     * again ({@link #askForFlightAgain()}): {@link #ASK_AGAIN_TICKS}, longer when it was asked for again lately
     */
    public int askAgainAfterTicks() {
        return this.flightTicks - this.askedAgainTick < ASK_AGAIN_FORGET_TICKS ? this.askAgainWait : ASK_AGAIN_TICKS;
    }

    /**
     * The flight as it was asked for again, from here: after coming down from a pull-down, or a take-off that didn't
     * glide. The npf context stays, with the chunks it has packed.
     */
    private void resumeFlight() {
        final BetterBlockPos goalBlock = this.flightGoalBlock;
        this.pathTo0(this.flightDestination, false, false);
        this.goalBlock = goalBlock;
        this.landingSearchSaid = false;
        this.landingHold.reset();
    }

    /**
     * Comes down on {@code spot}, the block to stand in, for {@code purpose}
     */
    private void landOn(BetterBlockPos spot, LandingPurpose purpose) {
        this.pathTo0(spot, true, true);
        this.landingSpot = spot;
        this.goingToLandingSpot = true;
        this.landingPurpose = purpose;
        // the spot stands on its floor: a landing that drops below the floor block has missed it
        this.landingColumnHeight = 1;
    }

    /**
     * The flight is over: the process stops, keeping the npf context and the chunks it has packed for the flight
     * that is likely asked for next from here
     */
    private void endFlight() {
        baritone.getInputOverrideHandler().clearAllKeys();
        this.stopFireResistanceEat();
        this.fireResistanceEat.reset();
        this.onLostControl(false);
    }

    /**
     * The flight gives up, its line said: in the air it comes down wherever it can first, on the ground it ends
     */
    private void giveUp() {
        if (this.behavior != null && ctx.player() != null && ctx.player().isFallFlying()) {
            this.forcedLanding = true;
            this.goingToLandingSpot = false;
            this.landingSpot = null;
            if (this.state == State.LANDING) {
                this.state = State.FLYING;
            }
            return;
        }
        this.endFlight();
    }

    /**
     * The path searches found nothing for as long as they're asked to: says {@code line} and gives up
     */
    public void giveUpOnPath(String line) {
        logDirect(line);
        this.giveUp();
    }

    /**
     * No path gets to {@code spot}, the landing spot the flight was on its way to: another one
     */
    public void landingSpotUnreachable(BetterBlockPos spot, String reason) {
        logDirect("Couldn't find a way to the landing spot (" + reason + "), looking for another");
        final LandingPurpose purpose = this.landingPurpose;
        this.landingSpotIsBad(spot);
        if (purpose == LandingPurpose.SETTLE) {
            this.recovery.noFloor();
        }
        if (purpose == LandingPurpose.ARRIVAL || purpose == LandingPurpose.SETTLE) {
            // nothing else looks for one with no path: the flight again from here, and at its end the search, without
            // this spot. an emergency or a give-up looks again by itself
            this.resumeFlight();
        }
    }

    /**
     * @return Whether {@code behavior} is the flight's, rather than one since replaced
     */
    public boolean flies(ElytraBehavior behavior) {
        return this.behavior == behavior;
    }

    /**
     * @return Whether the flight's goal is a block with a height, rather than a column
     */
    public boolean goalHasHeight() {
        return this.flightGoalBlock != null;
    }

    /**
     * @return Whether the server set the player back down within the last {@link #HOLD_LEVEL_TICKS}: a glide that
     * climbs straight back to the path from where it was put is turned down again, five times a second, and gets
     * nowhere. The server has the glide then ({@link #yields}), and the pull-downs that keep coming are a pull-down
     */
    public boolean holdsLevel() {
        return this.flightTicks - this.pulledDownTick <= HOLD_LEVEL_TICKS;
    }

    /**
     * @return The share of the most a step moves across to fly at
     */
    public double glideSpeed() {
        return this.setbacks.speed();
    }

    private void pathTo0(BlockPos destination, boolean appendDestination, boolean landsOnDestination) {
        if (ctx.player() == null) {
            return;
        }
        this.onLostControl(false);
        this.askedAgain = false;
        this.predictingTerrain = ctx.player().level().dimension() == Level.NETHER && Baritone.settings().elytraPredictTerrain.value;
        this.allowTight = Baritone.settings().elytraAllowTightSpaces.value;
        this.allowAboveBuildLimit = Baritone.settings().elytraAllowAboveBuildLimit.value;
        this.allowAboveRoof = Baritone.settings().elytraAllowAboveRoof.value;
        this.behavior = new ElytraBehavior(this.baritone, this, getNpfContext(), destination, appendDestination, landsOnDestination);

        if (ctx.world() != null) {
            this.repackChunks(true);
        }
        this.behavior.pathTo();
    }

    @Override
    public void pathTo(Goal iGoal) {
        final int x;
        final int y;
        final int z;
        final boolean standsInGoal;
        if (iGoal instanceof GoalXZ) {
            GoalXZ goal = (GoalXZ) iGoal;
            x = goal.getX();
            // ElytraBehavior will automatically change the destination height depending on if we're above or below the roof
            y = 64;
            z = goal.getZ();
            standsInGoal = false;
        } else if (iGoal instanceof GoalBlock) {
            GoalBlock goal = (GoalBlock) iGoal;
            x = goal.x;
            y = goal.y;
            z = goal.z;
            standsInGoal = true;
        } else {
            throw new IllegalArgumentException("The goal must be a GoalXZ or GoalBlock");
        }

        final BlockPos destination = new BlockPos(x, y, z);
        this.pathTo(destination, standsInGoal ? new BetterBlockPos(destination) : null);
    }

    private boolean isSupportedPos(BlockPos pos) {
        final boolean isNether = ctx.world().dimension() == Level.NETHER;
        final int minY = ctx.world().dimensionType().minY();
        final int maxY = (isNether && !Baritone.settings().elytraAllowAboveRoof.value) ? 127 : Math.min(minY + 384, ctx.world().dimensionType().height() + minY);

        final boolean aboveRoof = Baritone.settings().elytraAllowAboveRoof.value;
        final boolean aboveBuild = Baritone.settings().elytraAllowAboveBuildLimit.value;

        final boolean enforceMaxY = isNether ? !(aboveRoof && aboveBuild) : !aboveBuild;

        if (pos.getY() < minY) {
            return false;
        }

        return !enforceMaxY || pos.getY() < maxY;
    }

    private boolean landsAtGoal() {
        return Baritone.settings().elytraLandAtGoal.value && this.goalBlock != null && glidesBySteps();
    }

    /**
     * For {@link baritone.api.Settings#elytraLandAtGoal}: the goal block when it can be stood in, else the nearest
     * block at its height within {@link #GOAL_LANDING_RADIUS} that can, else {@code null}, and the flight holds still
     * at the end of its path. The 15 high column search isn't the fallback: under a roof it finds a floor far below,
     * down whatever hole is nearest.
     */
    private BetterBlockPos findGoalLandingSpot() {
        final BetterBlockPos goal = this.goalBlock;
        if (!isChunkLoaded(goal)) {
            return null; // no telling yet
        }
        final GlideSteps control = glideSteps();
        BetterBlockPos best = null;
        int bestDistSq = Integer.MAX_VALUE;
        for (int dx = -GOAL_LANDING_RADIUS; dx <= GOAL_LANDING_RADIUS; dx++) {
            for (int dz = -GOAL_LANDING_RADIUS; dz <= GOAL_LANDING_RADIUS; dz++) {
                final int distSq = dx * dx + dz * dz;
                if (distSq >= bestDistSq || distSq > GOAL_LANDING_RADIUS * GOAL_LANDING_RADIUS) {
                    continue;
                }
                final BetterBlockPos pos = new BetterBlockPos(goal.x + dx, goal.y, goal.z + dz);
                if (!this.badLandingSpots.contains(pos) && isChunkLoaded(pos) && canStandIn(pos, control)) {
                    best = pos;
                    bestDistSq = distSq;
                }
            }
        }
        if (best == null) {
            if (!this.goalLandingRefused) {
                logDirect("No block at the goal or within " + GOAL_LANDING_RADIUS + " blocks of it at its height can be stood in, holding still");
                this.goalLandingRefused = true;
            }
            return null;
        }
        // the spot stands on its floor: a landing that drops below the floor block has missed it
        this.landingColumnHeight = 1;
        return best;
    }

    /**
     * Whether a landing can stand in {@code pos}: a full top to stand on under it, and room for the landing's hitbox
     * with no fluid or fire in it
     */
    private boolean canStandIn(BlockPos pos, GlideSteps control) {
        final Level world = ctx.world();
        final BlockPos floor = pos.below();
        final BlockState floorState = world.getBlockState(floor);
        if (!floorState.isFaceSturdy(world, floor, Direction.UP) || floorState.is(Blocks.MAGMA_BLOCK)) {
            return false;
        }
        final AABB box = this.behavior.glideBoxAt(control, Vec3.atBottomCenterOf(pos));
        if (!world.noCollision(ctx.player(), box)) {
            return false;
        }
        for (BlockPos cell : BlockPos.betweenClosed(Mth.floor(box.minX), Mth.floor(box.minY), Mth.floor(box.minZ), Mth.floor(box.maxX), Mth.floor(box.maxY), Mth.floor(box.maxZ))) {
            final BlockState state = world.getBlockState(cell);
            if (!state.getFluidState().isEmpty() || state.is(BlockTags.FIRE)) {
                return false;
            }
        }
        return true;
    }

    private boolean shouldLandForSafety() {
        return this.safetyReason(glidesBySteps(), this.countFireworks()) != null;
    }

    /**
     * @return Why the flight should land, {@code null} when it needn't ({@link ElytraSafety#reason})
     */
    private String safetyReason(boolean steps, int fireworks) {
        final ItemStack chest = ctx.player().getItemBySlot(EquipmentSlot.CHEST);
        final boolean elytra = chest.getItem() == Items.ELYTRA;
        // elytrabehavior replaces when durability <= minimumDurability, so if durability < minimumDurability then we
        // can reasonably assume that the elytra will soon be broken without replacement
        return ElytraSafety.reason(elytra, elytra ? chest.getMaxDamage() - chest.getDamageValue() : 0,
                LivingEntity.canGlideUsing(chest, EquipmentSlot.CHEST), steps, fireworks, Baritone.settings());
    }

    private int countFireworks() {
        final NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        int qty = 0;
        for (int i = 0; i < 36; i++) {
            if (ElytraBehavior.isFireworks(inv.get(i))) {
                qty += inv.get(i).getCount();
            }
        }
        return qty;
    }

    /**
     * After a pull-down: the nearest floor below that a landing moved by steps comes straight down onto. Over lava,
     * which the server pulls it into, with none near: one further off, the way the path goes first, while it flies on.
     * With none, the flight carries on, slower.
     */
    private void settle() {
        final GlideSteps control = glideSteps();
        this.settleAim = null;
        if (control == null) {
            this.recovery.noFloor();
            return;
        }
        // the server moves it now, and only where it looks counts: near the ground there's nothing to pick, it comes
        // down where it comes down
        final int below = this.floorBelow(NEAR_GROUND);
        if (below >= 0) {
            logDirect("Near the ground (" + below + " blocks over it): letting the server bring it down wherever it does");
            return;
        }
        final BetterBlockPos spot = this.findGlideLandingNow(control, SETTLE_RADIUS);
        if (spot != null && this.under(spot)) {
            this.settleAim = spot;
            logDirect("Letting the server bring it down, facing a floor " + Math.round(Math.sqrt(ctx.playerFeet().distanceSq(spot))) + " blocks away");
            return;
        }
        if (this.overLava()) {
            this.settleSearch = this.lavaLandingSearch(control);
            logDirect("No floor within " + SETTLE_RADIUS + " blocks, only lava: looking along the path ahead and up to "
                    + LAVA_LANDING_RADIUS + " blocks around, letting the server bring it down meanwhile");
            return;
        }
        logDirect("No floor within " + SETTLE_RADIUS + " blocks to face: letting the server bring it down wherever it does");
    }

    /**
     * The search further for a floor after a pull-down over lava, on for half a tick
     */
    private void advanceSettleSearch() {
        if (this.settleSearch == null) {
            return;
        }
        if (!this.recovery.settling()) {
            this.settleSearch = null;
            return;
        }
        final BetterBlockPos spot = this.settleSearch.advance(LANDING_SEARCH_BUDGET_NANOS);
        if (spot != null && this.under(spot)) {
            this.settleSearch = null;
            this.settleAim = spot;
            logDirect("Found a floor to face " + Math.round(Math.sqrt(ctx.playerFeet().distanceSq(spot))) + " blocks away, letting the server bring it down there if it will");
        } else if (spot != null || this.settleSearch.exhausted()) {
            // one it's under, it can't come down on
            this.settleSearch = null;
            logDirect("No floor under it to face along the path ahead or within " + LAVA_LANDING_RADIUS
                    + " blocks: letting the server bring it down wherever it does");
        }
    }

    /**
     * @return How far over the floor straight under the feet is, within {@code within} blocks, or -1 for none: open
     * all the way down there, or lava
     */
    private int floorBelow(final int within) {
        final BetterBlockPos feet = ctx.playerFeet();
        if (!ctx.world().getFluidState(feet).isEmpty() || ctx.player().isInLava()) {
            // in lava, the bottom of the pool under it is no floor to drop onto: letting go of the glide there leaves it
            // to the game's lava
            return -1;
        }
        for (int d = 1; d <= within; d++) {
            final BlockPos pos = feet.below(d);
            if (!ctx.world().getFluidState(pos).isEmpty()) {
                return -1;
            }
            if (!ctx.world().getBlockState(pos).getCollisionShape(ctx.world(), pos).isEmpty()) {
                return d - 1;
            }
        }
        return -1;
    }

    /**
     * @return Whether block {@code spot}, to stand in, is far enough under the feet for a glide brought down by the
     * server to come down on: one over them, or level, it can only go under, and under one it tries to land on it from
     * below indefinitely
     */
    private boolean under(final BetterBlockPos spot) {
        return spot.y <= ctx.player().getY() - 2;
    }

    /**
     * @return Whether the server has the glide: coming down after a pull-down, or set back down lately. It moves the
     * player by the game's glide then, and the steps follow it, steered only by where they look
     */
    public boolean yields() {
        return this.glidesBySteps() && (this.recovery.settling() || this.holdsLevel());
    }

    /**
     * @return Whether it's coming down after a pull-down
     */
    public boolean settlingAfterPullDown() {
        return this.recovery.settling();
    }

    /**
     * @return The floor to face while the server brings it down, {@code null} to face the way it goes. Dropped once it
     * isn't under the feet any more: missed
     */
    public BetterBlockPos yieldAim() {
        if (this.settleAim != null && (!this.recovery.settling() || !this.under(this.settleAim))) {
            if (this.recovery.settling()) {
                logDirect("Missed the floor it faced: letting the server bring it down wherever it does");
            }
            this.settleAim = null;
        }
        return this.settleAim;
    }

    /**
     * A search over lava for a floor: along the path on from the player first, then far around
     */
    private GlideLandingSearch lavaLandingSearch(GlideSteps control) {
        final List<BetterBlockPos> path = this.behavior.pathManager.getPath();
        final int near = Math.max(0, Math.min(this.behavior.pathManager.getNear(), path.size()));
        return GlideLandingSearch.aheadThenAround(this.glideLandingWorld(control), ctx.playerFeet(),
                new ArrayList<>(path.subList(near, path.size())), LAVA_AHEAD, LAVA_AHEAD_RADIUS, LAVA_LANDING_RADIUS,
                LANDING_DEPTH, ctx.world().getMinY());
    }

    /**
     * A landing the flight was on its way to is off, and the glide goes up again from here: came down in lava short of
     * its spot
     */
    private void abandonLanding() {
        this.goingToLandingSpot = false;
        this.landingSpot = null;
        if (this.state == State.LANDING) {
            this.state = State.START_FLYING;
        }
    }

    /**
     * @return What the player is in that burns, {@code "lava"} or {@code "fire"}, {@code null} when nothing
     */
    private String heat() {
        final LocalPlayer player = ctx.player();
        if (player.isInLava()) {
            return "lava";
        }
        // the box vanilla burns for touching fire
        final AABB box = player.getBoundingBox().deflate(1.0E-5);
        return ctx.world().getBlockStatesIfLoaded(box).anyMatch(state -> state.is(BlockTags.FIRE)) ? "fire" : null;
    }

    /**
     * @return Whether the first thing under the player that a glide can't go through, within {@link #LANDING_DEPTH},
     * is lava: the server pulling it down puts it in
     */
    private boolean overLava() {
        final Level world = ctx.world();
        final BetterBlockPos feet = ctx.playerFeet();
        final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        final int bottom = Math.max(world.getMinY(), feet.y - LANDING_DEPTH);
        for (int y = feet.y; y >= bottom; y--) {
            at.set(feet.x, y, feet.z);
            if (!world.isLoaded(at)) {
                return false;
            }
            final BlockState state = world.getBlockState(at);
            if (state.getFluidState().is(FluidTags.LAVA)) {
                return true;
            }
            if (!state.getCollisionShape(world, at).isEmpty() || !state.getFluidState().isEmpty()) {
                return false;
            }
        }
        return false;
    }

    /**
     * Eats an enchanted golden apple for fire resistance when {@link FireResistanceEat} says to: in lava or fire, on
     * fire, or pulled down over lava
     */
    private void tickFireResistance(int now, boolean fallFlying, String heat) {
        if (!Baritone.settings().elytraEatForFireResistance.value) {
            if (this.fireResistanceEat.eating()) {
                this.stopFireResistanceEat();
                this.fireResistanceEat.reset();
            }
            return;
        }
        final LocalPlayer player = ctx.player();
        final MobEffectInstance effect = player.getEffect(MobEffects.FIRE_RESISTANCE);
        final int left = effect == null ? 0 : effect.isInfiniteDuration() ? Integer.MAX_VALUE : effect.getDuration();
        // an apple gives 6000 ticks: at or over that, every eat would want another
        final int minTicks = Mth.clamp(Baritone.settings().elytraFireResistanceMinSeconds.value, 0, 295) * 20;
        String danger = null;
        if (!this.fireResistanceEat.eating() && left < minTicks) {
            final boolean onFire = player.isOnFire();
            danger = FireResistanceEat.danger(heat, onFire, heat == null && !onFire && fallFlying
                    && this.setbacks.draggedDownLately(now) && this.overLava());
        }
        final FireResistanceEat.Step step = this.fireResistanceEat.tick(now, danger, left, minTicks, FoodUse.count(ctx, Items.ENCHANTED_GOLDEN_APPLE));
        final String said = this.fireResistanceEat.line();
        if (said != null) {
            logDirect(said);
        }
        if (step == FireResistanceEat.Step.EAT) {
            this.holdAppleToEat();
        } else if (step == FireResistanceEat.Step.STOP) {
            this.stopFireResistanceEat();
        }
    }

    /**
     * Eats the apple for this tick: on the hotbar first, then in hand, then use held until it's gone
     * ({@link FoodUse#holdUseToEat})
     */
    private void holdAppleToEat() {
        final LocalPlayer player = ctx.player();
        if (player.hasContainerOpen()) {
            player.closeContainer();
            return;
        }
        if (!(player.isUsingItem() && player.getUseItem().is(Items.ENCHANTED_GOLDEN_APPLE))) {
            final int slot = FoodUse.hotbarSlotOf(ctx, Items.ENCHANTED_GOLDEN_APPLE);
            if (slot == -1) {
                this.fetchApple();
                return;
            }
            if (player.getInventory().getSelectedSlot() != slot) {
                player.getInventory().setSelectedSlot(slot);
            }
        }
        // the use eats, and doesn't open or press whatever block is in the crosshair instead
        NetherHighwayBuilderBehavior.suppressHitResult = true;
        FoodUse.holdUseToEat(ctx, Items.ENCHANTED_GOLDEN_APPLE);
        this.fireResistanceEatHeld = true;
    }

    /**
     * An apple from the main inventory onto the hotbar
     */
    private void fetchApple() {
        final int from = FoodUse.slotOf(ctx, Items.ENCHANTED_GOLDEN_APPLE);
        final net.minecraft.world.entity.player.Input keys = ctx.player().input.keyPresses;
        // Grim drops an inventory click while the last input held a movement key or jump, as a take-off's does every
        // other tick
        if (from < 9 || keys.forward() || keys.backward() || keys.left() || keys.right() || keys.jump()) {
            return;
        }
        final NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        // a firework flight's fireworks stay where it uses them from
        baritone.getInventoryBehavior().attemptToPutOnHotbar(from, slot -> ElytraBehavior.isFireworks(inv.get(slot)));
    }

    /**
     * Lets go of the fire resistance eat, if the use held is its
     */
    private void stopFireResistanceEat() {
        if (!this.fireResistanceEatHeld) {
            return;
        }
        this.fireResistanceEatHeld = false;
        NetherHighwayBuilderBehavior.suppressHitResult = false;
        FoodUse.releaseUseToEat(ctx);
    }

    /**
     * @return Whether the flight is eating for fire resistance: a firework's use would put the apple away
     */
    public boolean eatingForFireResistance() {
        return this.fireResistanceEat.eating();
    }

    /**
     * The search for a spot to land on came to its end and found nothing
     *
     * @return The command to end the flight with, or {@code null} to carry on and look again later
     */
    private PathingCommand nowhereToLand(int now, boolean steps, boolean safetyLanding, boolean settling) {
        if (!steps) {
            // a firework glide circles the end of its path as it always has, and looks again
            return null;
        }
        final int radius = this.glideLandingRadius;
        final boolean overLava = this.overLava();
        if (settling) {
            this.recovery.noFloor();
            logDirect("No floor to come down on within " + radius + " blocks, carrying on at " + this.setbacks.speedPercent() + " glide speed");
            return null;
        }
        if (this.forcedLanding) {
            if (overLava) {
                // ended here the flight falls in: on along the path, looking again as it goes
                if (!this.lavaFlyOnSaid) {
                    logDirect("Found nowhere to land within " + radius + " blocks, and there's lava below: flying on along the path and looking again, not ending the flight over it");
                    this.lavaFlyOnSaid = true;
                }
                return null;
            }
            logDirect("Found nowhere to land within " + radius + " blocks, ending the flight in the air");
            this.endFlight();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (safetyLanding) {
            return null;
        }
        final String line = this.landingHold.nothingFound(now, radius, overLava);
        if (line != null) {
            logDirect(line);
        }
        if (this.landingHold.givenUp()) {
            this.endFlight();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        return null;
    }

    /**
     * {@link GlideLandingSearch} from the feet, on for half a tick
     *
     * @return The block to stand in, {@code null} when none yet, and when the search is done, {@link #glideLandingSearch}
     * is null too
     */
    private BetterBlockPos advanceGlideLandingSearch() {
        final GlideSteps control = glideSteps();
        if (control == null) {
            return null;
        }
        if (this.glideLandingSearch == null) {
            if (this.overLava()) {
                this.glideLandingSearch = this.lavaLandingSearch(control);
                this.glideLandingRadius = LAVA_LANDING_RADIUS;
            } else {
                this.glideLandingSearch = new GlideLandingSearch(this.glideLandingWorld(control), ctx.playerFeet(), LANDING_RADIUS, LANDING_DEPTH, ctx.world().getMinY());
                this.glideLandingRadius = LANDING_RADIUS;
            }
        }
        final BetterBlockPos spot = this.glideLandingSearch.advance(LANDING_SEARCH_BUDGET_NANOS);
        if (spot != null || this.glideLandingSearch.exhausted()) {
            this.glideLandingSearch = null;
        }
        return spot;
    }

    private BetterBlockPos findGlideLandingNow(GlideSteps control, int radius) {
        return new GlideLandingSearch(this.glideLandingWorld(control), ctx.playerFeet(), radius, LANDING_DEPTH, ctx.world().getMinY())
                .advance(LANDING_SEARCH_BUDGET_NANOS);
    }

    /**
     * The world as {@link GlideLandingSearch} reads it: open is what the glide's box goes through without touching it,
     * fluid or fire; standable is {@link #canStandIn} and not a spot that has gone bad
     */
    private GlideLandingSearch.World glideLandingWorld(GlideSteps control) {
        final Level world = ctx.world();
        final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        return new GlideLandingSearch.World() {
            @Override
            public boolean open(int x, int y, int z) {
                at.set(x, y, z);
                final BlockState state = world.getBlockState(at);
                return state.getCollisionShape(world, at).isEmpty() && state.getFluidState().isEmpty() && !state.is(BlockTags.FIRE);
            }

            @Override
            public boolean standable(int x, int y, int z) {
                final BetterBlockPos pos = new BetterBlockPos(x, y, z);
                return !badLandingSpots.contains(pos) && canStandIn(pos, control);
            }

            @Override
            public boolean loaded(int x, int z) {
                return world.getChunkSource().hasChunk(x >> 4, z >> 4);
            }
        };
    }

    @Override
    public boolean isLoaded() {
        return true;
    }

    @Override
    public Vec3 getGlideStep() {
        if (this.behavior == null || !glidesBySteps()) {
            return null;
        }
        if (this.dropsToGround() || this.recovery.lettingGo()) {
            // the game's own glide brings it the rest of the way down, or the glide is let go of before a take-off: the
            // move keeps its own, and the durability saver lets go of the glide with no step to hold it for
            return null;
        }
        // nothing worked out this tick (no path yet, paused, or the lock was taken): hold still
        return this.behavior.glideStep != null ? this.behavior.glideStep : Vec3.ZERO;
    }

    /**
     * @return Whether the steps let go of the glide, for the game's own to bring it down on the floor straight under:
     * the server has it (a pull-down, set down lately, or waiting after one), it's off the ground, and that floor is
     * within {@link #SAFE_DROP}. Held a hair over the floor by steps of nothing, it never comes down, and the server
     * goes on pulling at it the whole wait. Only this near: the game's glide wears the elytra, the steps don't
     */
    public boolean dropsToGround() {
        if (this.behavior == null || ctx.player() == null || ctx.player().onGround()) {
            return false;
        }
        if (!(this.recovery.settling() || this.recovery.settled() || this.holdsLevel())) {
            return false;
        }
        return this.floorBelow(SAFE_DROP) >= 0 || (this.heldOverFloor() && this.floorBelow(NEAR_GROUND) >= 0);
    }

    /**
     * Notes how low the glide has come while the server has it, each tick of the flight
     */
    private void noteDescent() {
        final boolean has = this.recovery.settling() || this.recovery.settled() || this.holdsLevel();
        final double y = ctx.player().getY();
        if (!has || y < this.lowestY - DESCENT) {
            this.lowestY = y;
            this.lowestTick = this.flightTicks;
        }
    }

    /**
     * @return Whether the server has held the glide where it is: it has the glide, has set it back again and again
     * for {@link #HELD_TICKS}, and it came no lower. The game's glide moved by the steps falls, and the server puts it
     * back up each time: let go of, the game's own brings it down
     */
    private boolean heldOverFloor() {
        return this.flightTicks - this.lowestTick >= HELD_TICKS
                && this.setbacks.since(this.flightTicks, this.lowestTick) >= HELD_SETBACKS;
    }

    /**
     * Called by the player's own move before it moves ({@code MixinClientPlayerEntity}): holds or lets go of the glide
     * for {@link baritone.api.Settings#elytraSaveDurability}
     */
    public void beforePlayerMove(LocalPlayer player) {
        this.durabilitySaver.beforeMove(player, Baritone.settings().elytraSaveDurability.value && this.getGlideStep() != null);
    }

    /**
     * @return How the glide is moved by steps ({@link baritone.api.Settings#elytraFlyBySteps}), or {@code null} when
     * the glide's physics move it
     */
    public GlideSteps glideSteps() {
        return Baritone.settings().elytraFlyBySteps.value ? this.glideSteps : null;
    }

    public boolean glidesBySteps() {
        return glideSteps() != null;
    }

    @Override
    public boolean isSafeToCancel() {
        return !this.isActive() || !(this.state == State.FLYING || this.state == State.START_FLYING);
    }

    public enum State {
        LOCATE_JUMP("Finding spot to jump off"),
        PAUSE("Waiting for elytra path"),
        GET_TO_JUMP("Walking to takeoff"),
        START_FLYING("Begin flying"),
        FLYING("Flying"),
        LANDING("Landing");

        public final String description;

        State(String desc) {
            this.description = desc;
        }
    }

    @Override
    public void onRenderPass(RenderEvent event) {
        if (this.behavior != null) this.behavior.onRenderPass(event);
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        if (event.getWorld() != null && event.getState() == EventState.POST) {
            // Exiting the world, just destroy
            destroyBehaviorAsync();
        }
    }

    @Override
    public void onChunkEvent(ChunkEvent event) {
        if (this.behavior != null) this.behavior.onChunkEvent(event);
    }

    @Override
    public void onBlockChange(BlockChangeEvent event) {
        if (this.behavior != null) this.behavior.onBlockChange(event);
    }

    @Override
    public void onReceivePacket(PacketEvent event) {
        if (event.getState() == EventState.PRE) {
            this.durabilitySaver.onReceivePacket(event.getPacket());
        }
        if (this.behavior != null) this.behavior.onReceivePacket(event);
    }

    @Override
    public void onSendPacket(PacketEvent event) {
        if (event.getState() == EventState.PRE) {
            // before the movement packet goes: a start goes first
            this.durabilitySaver.onSendPacket(event.getPacket());
        }
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.IN) {
            this.durabilitySaver.onTick();
        }
    }

    /**
     * Notes where the server is about to put the player, from where it is, and says so. Called on the game thread by
     * the position packet's handler before it moves the player (the mixin's hook): a task the game is given as the
     * packet comes in runs after it, since the game handles its queued packets before its tasks each frame, so it
     * measures nothing and no setback counts towards a pull-down
     */
    public void beforeSetback(ClientboundPlayerPositionPacket packet) {
        if (this.behavior == null || ctx.player() == null) {
            return;
        }
        final PositionMoveRotation from = PositionMoveRotation.of(ctx.player());
        final Vec3 to = PositionMoveRotation.calculateAbsolute(from, packet.change(), packet.relatives()).position();
        final Vec3 moved = to.subtract(from.position());
        final String line = this.setbacks.note(this.flightTicks, moved);
        if (line != null) {
            logDirect(line);
        }
        if (moved.y <= -Baritone.settings().elytraPullDownDrop.value) {
            this.pulledDownTick = this.flightTicks;
        }
        if (GlideSetbacks.inPlace(moved)) {
            // nothing moved: the stall watch and the progress along the path go on as they were. started afresh at each
            // of these, a glide the server holds where it is, five times a second, never looks for a way on
            return;
        }
        this.setBackSinceTick = true;
        this.lastGlidePos = null;
    }

    @Override
    public void onPostTick(TickEvent event) {
        if (this.behavior != null) {
            // a step is for the tick it was worked out in: the player has moved by it now
            this.behavior.glideStep = null;
        }
        IBaritoneProcess procThisTick = baritone.getPathingControlManager().mostRecentInControl().orElse(null);
        if (this.behavior != null && procThisTick == this) this.behavior.onPostTick(event);
    }

    /**
     * Custom calculation context which makes the player fall into lava
     */
    public static final class WalkOffCalculationContext extends CalculationContext {

        public WalkOffCalculationContext(IBaritone baritone) {
            super(baritone, true);
            this.allowFallIntoLava = true;
            this.minFallHeight = 8;
            this.maxFallHeightNoWater = 10000;
        }

        @Override
        public double costOfPlacingAt(int x, int y, int z, BlockState current) {
            return COST_INF;
        }

        @Override
        public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
            return COST_INF;
        }

        @Override
        public double placeBucketCost() {
            return COST_INF;
        }
    }

    private static boolean isInBounds(Level dim, BlockPos pos) {
        DimensionType dimType = dim.dimensionType();
        int minY = dimType.minY();
        int maxY = (dim.dimension() == Level.NETHER && !Baritone.settings().elytraAllowAboveRoof.value) ? 127 : Math.min(minY + 384, dimType.height() + minY);
        return pos.getY() >= minY && pos.getY() < maxY;
    }

    private boolean isSafeBlock(Block block) {
        return block == Blocks.NETHERRACK || block == Blocks.GRAVEL || block == Blocks.SOUL_SAND || block == Blocks.SOUL_SOIL || (block == Blocks.NETHER_BRICKS && Baritone.settings().elytraAllowLandOnNetherFortress.value)
                || block == Blocks.STONE || block == Blocks.DEEPSLATE || block == Blocks.GRASS_BLOCK || block == Blocks.SAND || block == Blocks.RED_SAND || block == Blocks.TERRACOTTA
                || block == Blocks.SNOW || block == Blocks.ICE || block == Blocks.MYCELIUM || block == Blocks.PODZOL
                || block == Blocks.DARK_OAK_LEAVES || block == Blocks.JUNGLE_LEAVES
                || block == Blocks.END_STONE || block == Blocks.BEDROCK
                || block == Blocks.OBSIDIAN || block == Blocks.COBBLESTONE;
    }

    private boolean isSafeBlock(BlockPos pos) {
        return isSafeBlock(ctx.world().getBlockState(pos).getBlock());
    }

    private boolean isAtEdge(BlockPos pos) {
        return !isSafeBlock(pos.north())
                || !isSafeBlock(pos.south())
                || !isSafeBlock(pos.east())
                || !isSafeBlock(pos.west())
                // corners
                || !isSafeBlock(pos.north().west())
                || !isSafeBlock(pos.north().east())
                || !isSafeBlock(pos.south().west())
                || !isSafeBlock(pos.south().east());
    }

    private boolean isColumnAir(BlockPos landingSpot, int minHeight) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos(landingSpot.getX(), landingSpot.getY(), landingSpot.getZ());
        final int maxY = mut.getY() + minHeight;
        for (int y = mut.getY() + 1; y <= maxY; y++) {
            mut.set(mut.getX(), y, mut.getZ());
            if (!(ctx.world().getBlockState(mut).getBlock() instanceof AirBlock)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasAirBubble(BlockPos pos) {
        final int radius = 4; // Half of the full width, rounded down, as we're counting blocks in each direction from the center
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    mut.set(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
                    if (!(ctx.world().getBlockState(mut).getBlock() instanceof AirBlock)) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private BetterBlockPos checkLandingSpot(BlockPos pos, LongOpenHashSet checkedSpots) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos(pos.getX(), pos.getY(), pos.getZ());
        while (mut.getY() >= ctx.world().dimensionType().minY()) {
            if (checkedSpots.contains(mut.asLong())) {
                return null;
            }
            checkedSpots.add(mut.asLong());
            Block block = ctx.world().getBlockState(mut).getBlock();

            if (isSafeBlock(block)) {
                if (!isAtEdge(mut)) {
                    return new BetterBlockPos(mut);
                }
                return null;
            } else if (!(block instanceof AirBlock)) {
                return null;
            }
            mut.set(mut.getX(), mut.getY() - 1, mut.getZ());
        }
        return null; // void
    }

    private BetterBlockPos findSafeLandingSpot(BetterBlockPos start) {
        final boolean useHeightmap = ctx.player().getY() > ctx.world().getHeight(Heightmap.Types.MOTION_BLOCKING, start.getX(), start.getZ());
        if (this.landingSearchState == null || !this.landingSearchState.isCompatible(start, useHeightmap)) {
            this.landingSearchState = new LandingSearchState(start, this.behavior.destination, useHeightmap);
        } else {
            this.landingSearchState.updateStartPosition(start);
        }

        BetterBlockPos landingSpot = this.landingSearchState.advance();
        if (landingSpot != null || this.landingSearchState.exhausted) {
            this.landingSearchState = null;
        }
        return landingSpot;
    }

    private boolean isChunkLoaded(BetterBlockPos pos) {
        return ctx.world().getChunkSource().hasChunk(pos.x >> 4, pos.z >> 4);
    }

    private final class LandingSearchState {
        private final BetterBlockPos origin;
        private final boolean useHeightmap;
        private final Queue<BetterBlockPos> queue;
        private final Set<BetterBlockPos> visited = new HashSet<>();
        private final LongOpenHashSet checkedPositions = new LongOpenHashSet();
        private boolean exhausted;

        private LandingSearchState(BetterBlockPos origin, BetterBlockPos dest, boolean useHeightmap) {
            this.origin = origin;
            this.useHeightmap = useHeightmap;

            final BetterBlockPos target = isChunkLoaded(dest) ? dest : origin;
            this.queue = new PriorityQueue<>(Comparator.<BetterBlockPos>comparingInt(pos -> (pos.x - target.x) * (pos.x - target.x) + (pos.z - target.z) * (pos.z - target.z)).thenComparingInt(pos -> -pos.y));
            this.queue.add(target);
            this.visited.add(target);
            // the player's own air as well: the destination of a goal with no height is a made-up height, often inside
            // rock, and a search from there alone runs out at once, every tick
            if (this.visited.add(origin)) {
                this.queue.add(origin);
            }
        }

        private boolean isCompatible(BetterBlockPos start, boolean useHeightmap) {
            // Restart if we've moved more than a chunk so the priority adjusts and newly loaded chunks get revisited
            return this.useHeightmap == useHeightmap && this.origin.distanceSq(start) <= (16 * 16);
        }

        private void updateStartPosition(BetterBlockPos start) {
            if (this.visited.add(start)) {
                this.queue.add(start);
            }
        }

        private BetterBlockPos advance() {
            final long deadline = System.nanoTime() + LANDING_SEARCH_BUDGET_NANOS;
            while (!this.queue.isEmpty()) {
                if (System.nanoTime() >= deadline) {
                    return null;
                }
                BetterBlockPos qPos = this.queue.poll();
                if (!isChunkLoaded(qPos)) {
                    continue;
                }
                BetterBlockPos landing = this.useHeightmap ? this.advanceHeightmap(qPos) : this.advanceUnderground(qPos);
                if (landing != null) {
                    return landing;
                }
            }
            this.exhausted = true;
            return null;
        }

        private BetterBlockPos advanceUnderground(BetterBlockPos pos) {
            // cave air too, which the nether's caves are carved out of
            if (isInBounds(ctx.world(), pos) && ctx.world().getBlockState(pos).getBlock() instanceof AirBlock) {
                BetterBlockPos actualLandingSpot = checkLandingSpot(pos, this.checkedPositions);
                if (actualLandingSpot != null) {
                    landingColumnHeight = SHORT_LANDING_COLUMN_HEIGHT;
                    if (isColumnAir(actualLandingSpot, landingColumnHeight) && hasAirBubble(actualLandingSpot.above(landingColumnHeight)) && !badLandingSpots.contains(actualLandingSpot.above(landingColumnHeight))) {
                        return actualLandingSpot.above(landingColumnHeight);
                    }
                }
                if (this.visited.add(pos.north())) this.queue.add(pos.north());
                if (this.visited.add(pos.east())) this.queue.add(pos.east());
                if (this.visited.add(pos.south())) this.queue.add(pos.south());
                if (this.visited.add(pos.west())) this.queue.add(pos.west());
                if (this.visited.add(pos.above())) this.queue.add(pos.above());
                if (this.visited.add(pos.below())) this.queue.add(pos.below());
            }
            return null;
        }

        private BetterBlockPos advanceHeightmap(BetterBlockPos qPos) {
            int height = ctx.world().getHeight(Heightmap.Types.MOTION_BLOCKING, qPos.getX(), qPos.getZ());
            BetterBlockPos pos = new BetterBlockPos(qPos.getX(), height + 1, qPos.getZ());
            if (isInBounds(ctx.world(), pos) && ctx.world().getBlockState(pos).getBlock() instanceof AirBlock) {
                BetterBlockPos actualLandingSpot = checkLandingSpot(pos, this.checkedPositions);
                if (actualLandingSpot != null) {
                    landingColumnHeight = ctx.playerFeet().y - actualLandingSpot.y < LONG_LANDING_COLUMN_HEIGHT ? SHORT_LANDING_COLUMN_HEIGHT : LONG_LANDING_COLUMN_HEIGHT;
                    if (hasAirBubble(actualLandingSpot.above(landingColumnHeight)) && !badLandingSpots.contains(actualLandingSpot.above(landingColumnHeight))) {
                        return actualLandingSpot.above(landingColumnHeight);
                    }
                }
                if (this.visited.add(pos.north())) this.queue.add(pos.north());
                if (this.visited.add(pos.east())) this.queue.add(pos.east());
                if (this.visited.add(pos.south())) this.queue.add(pos.south());
                if (this.visited.add(pos.west())) this.queue.add(pos.west());
            }
            return null;
        }
    }

    private NetherPathfinderContext getNpfContext() {
        if(this.npfContext == null) {
            npfSema.acquireUninterruptibly();
            this.npfContext = new NetherPathfinderContext(
                    Baritone.settings().elytraNetherSeed.value,
                    Baritone.settings().elytraUseCache.value ? baritone.getWorldProvider().getCurrentWorld().directory.resolve("cache") : null,
                    ctx.world()
            );
        }
        return this.npfContext;
    }

    private void destroyNpfContextAsync() {
        NetherPathfinderContext npf = this.npfContext;
        if (npf != null) {
            this.npfContext = null;
            Baritone.getExecutor().execute(() -> {
                npf.destroy();
                npfSema.release();
            });
        }
    }
}
