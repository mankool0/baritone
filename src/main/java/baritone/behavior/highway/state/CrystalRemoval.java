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

package baritone.behavior.highway.state;

import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.ExplosionExposure;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Breaks an end crystal standing where the highway puts a block. A crystal never moves, so no hole drops it out of the
 * way, and hitting it sets off a power 6 explosion: from the nearest a hit reaches, about 20 HP through Protection IV
 * netherite on Hard. Vanilla scales that by how much of us the blast can see ({@link ExplosionExposure}), and it goes
 * off at the crystal's base, so a block at our feet between us and it hides nearly all of us while we hit it over the
 * top. So: pick a spot in reach of it where a little cover brings that down far enough, walk there, put the cover
 * down, check it from where we actually stand, hit.
 */
public class CrystalRemoval extends State {

    /** The most of us the blast may see: about 2 HP through Protection IV netherite on Hard, from where we hit it. */
    private static final float MAX_EXPOSURE = 0.25f;
    /** No nearer than this to where it goes off, feet to the crystal's base. */
    private static final double MIN_BLAST_DISTANCE = 3.0;
    /** Eye to its hitbox, under the 3 block entity reach. */
    private static final double ATTACK_REACH = 2.8;
    /** How far out from the crystal to look for somewhere to stand. */
    private static final int STAND_RADIUS = 5;
    /** Cover goes this far out from where we stand, at our feet. */
    private static final int COVER_RANGE = 2;
    private static final int MAX_COVERS = 3;
    /** Heights above the crystal's base to aim a hit at, so it goes over cover at our feet. */
    private static final double[] AIM_HEIGHTS = {1.5, 1.85};
    private static final int PATH_TIMEOUT_TICKS = 400;
    private static final int CENTER_TIMEOUT_TICKS = 60;
    private static final int COVER_TIMEOUT_TICKS = 60;
    private static final int ATTACK_TIMEOUT_TICKS = 100;
    /** Spots tried before giving up on the crystal for {@link #IGNORE_TICKS}. */
    private static final int MAX_PLANS = 3;
    private static final int IGNORE_TICKS = 1200;

    private enum Phase {GOING, CENTERING, COVERING, ATTACKING}

    /** Where to stand, what to put down first, and where to hit it, worked out for the middle of that block. */
    private record Plan(BetterBlockPos stand, List<BlockPos> covers, Vec3 aim, float exposure) {}

    private EndCrystal crystal;
    private Plan plan;
    private Phase phase;
    private int phaseTicks;
    private int plans;
    private final Set<BetterBlockPos> badStands = new HashSet<>();

    public CrystalRemoval(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (crystal == null) {
            crystal = findCrystal(context);
        }
        if (crystal == null || crystal.isRemoved()) {
            Helper.HELPER.logDirect(phase == Phase.ATTACKING ? "Broke the end crystal." : "The end crystal is gone.");
            finish(context);
            return;
        }

        if (plan == null) {
            if (plans >= MAX_PLANS) {
                giveUp(context, "tried " + MAX_PLANS + " spots");
                return;
            }
            plans++;
            plan = plan(context);
            if (plan == null) {
                giveUp(context, "nowhere in reach of it where cover keeps the blast off");
                return;
            }
            Helper.HELPER.logDirect(String.format("Breaking the end crystal at %s from %s behind %d block(s) of cover, %.0f%% of us in view of the blast.",
                    crystal.blockPosition().toShortString(), plan.stand().toShortString(), plan.covers().size(), plan.exposure() * 100));
            context.baritone().getPathingBehavior().cancelEverything();
            context.baritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(plan.stand()));
            setPhase(Phase.GOING);
        }

        phaseTicks++;
        switch (phase) {
            case GOING -> going(context);
            case CENTERING -> centering(context);
            case COVERING -> covering(context);
            case ATTACKING -> attacking(context);
        }
    }

    @Override
    public void onExit(HighwayContext context) {
        releaseKeys(context);
    }

    private void going(HighwayContext context) {
        IPlayerContext ctx = context.playerContext();
        if (context.baritone().getCustomGoalProcess().isActive() || context.baritone().getPathingBehavior().isPathing()) {
            if (phaseTicks > PATH_TIMEOUT_TICKS) {
                replan(context, "took too long to get to " + plan.stand().toShortString());
            }
            return;
        }
        if (!ctx.playerFeet().equals(plan.stand())) {
            replan(context, "couldn't get to " + plan.stand().toShortString());
            return;
        }
        if (ctx.player().onGround()) {
            setPhase(Phase.CENTERING);
        }
    }

    /** The plan was worked out from the middle of the block, and the cover goes right beside it. */
    private void centering(HighwayContext context) {
        Player player = context.playerContext().player();
        Vec3 target = Vec3.atBottomCenterOf(plan.stand());
        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        if (dx * dx + dz * dz <= 0.01 || phaseTicks > CENTER_TIMEOUT_TICKS) {
            releaseKeys(context);
            setPhase(Phase.COVERING);
            return;
        }
        player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        context.baritone().getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
        context.baritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
    }

    private void covering(HighwayContext context) {
        IPlayerContext ctx = context.playerContext();
        BlockPos next = plan.covers().stream().filter(pos -> ctx.world().getBlockState(pos).canBeReplaced()).findFirst().orElse(null);
        if (next == null) {
            releaseKeys(context);
            float exposure = exposureHere(ctx);
            if (exposure > MAX_EXPOSURE) {
                replan(context, String.format("%.0f%% of us is still in view behind the cover", exposure * 100));
                return;
            }
            setPhase(Phase.ATTACKING);
            return;
        }
        if (phaseTicks > COVER_TIMEOUT_TICKS * plan.covers().size()) {
            replan(context, "couldn't put cover down at " + next.toShortString());
            return;
        }
        int slot = context.putAcceptableThrowawayHotbar();
        if (slot == -1) {
            slot = context.putItemHotbar(Item.getId(Items.OBSIDIAN));
        }
        if (slot == -1) {
            giveUp(context, "nothing to put down as cover");
            return;
        }
        if (slot >= 9) {
            return; // the move onto the hotbar hasn't landed yet
        }
        ctx.player().getInventory().setSelectedSlot(slot);
        context.baritone().getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
        context.place(next, (float) ctx.playerController().getBlockReachDistance(), true, false, InteractionHand.MAIN_HAND);
    }

    private void attacking(HighwayContext context) {
        IPlayerContext ctx = context.playerContext();
        if (phaseTicks > ATTACK_TIMEOUT_TICKS) {
            replan(context, "the hit never landed");
            return;
        }
        // checked every tick: anything that shoves us out from behind the cover makes this a different hit
        float exposure = exposureHere(ctx);
        if (exposure > MAX_EXPOSURE) {
            replan(context, String.format("%.0f%% of us came into view of the blast", exposure * 100));
            return;
        }
        Vec3 eye = ctx.player().getEyePosition();
        if (!clearShot(ctx.world(), eye, plan.aim())) {
            replan(context, "something's in the way of the hit");
            return;
        }
        context.attackEntity(crystal, plan.aim());
    }

    private EndCrystal findCrystal(HighwayContext context) {
        BlockPos cell = context.crystalLocation();
        if (cell == null) {
            return null;
        }
        List<EndCrystal> crystals = context.playerContext().world().getEntitiesOfClass(EndCrystal.class, new AABB(cell), EndCrystal::isAlive);
        return crystals.isEmpty() ? null : crystals.get(0);
    }

    /**
     * The nearest spot in reach of the crystal that's at least {@link #MIN_BLAST_DISTANCE} from it and where no more
     * than {@link #MAX_COVERS} blocks at our feet bring the blast's view of us down to {@link #MAX_EXPOSURE}, each
     * cover the one that hides the most of what's left. Fewer covers win a near tie.
     */
    private Plan plan(HighwayContext context) {
        IPlayerContext ctx = context.playerContext();
        Player player = ctx.player();
        BlockGetter world = ctx.world();
        Vec3 blast = crystal.position();
        BlockPos base = crystal.blockPosition();
        BlockState coverState = Blocks.NETHERRACK.defaultBlockState();
        double eyeHeight = player.getEyeHeight(Pose.STANDING);
        Plan best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -STAND_RADIUS; dx <= STAND_RADIUS; dx++) {
                for (int dz = -STAND_RADIUS; dz <= STAND_RADIUS; dz++) {
                    BetterBlockPos stand = new BetterBlockPos(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
                    Vec3 feet = Vec3.atBottomCenterOf(stand);
                    if (badStands.contains(stand) || feet.distanceTo(blast) < MIN_BLAST_DISTANCE || !standable(ctx, stand)) {
                        continue;
                    }
                    Vec3 eye = feet.add(0, eyeHeight, 0);
                    AABB box = player.getDimensions(Pose.STANDING).makeBoundingBox(feet);
                    Set<BlockPos> covers = new LinkedHashSet<>();
                    Vec3 aim = aimFrom(world, eye);
                    if (aim == null) {
                        continue; // out of reach, or nothing to hit it past
                    }
                    float exposure = ExplosionExposure.seenPercent(world, blast, box);
                    while (exposure > MAX_EXPOSURE && covers.size() < MAX_COVERS) {
                        BlockPos bestCover = null;
                        float bestExposure = exposure;
                        for (BlockPos cover : coverCandidates(context, stand)) {
                            if (!covers.add(cover)) {
                                continue;
                            }
                            BlockGetter covered = ExplosionExposure.withBlocks(world, covers, coverState);
                            if (clearShot(covered, eye, aim)) {
                                float coveredExposure = ExplosionExposure.seenPercent(covered, blast, box);
                                if (coveredExposure < bestExposure) {
                                    bestExposure = coveredExposure;
                                    bestCover = cover;
                                }
                            }
                            covers.remove(cover);
                        }
                        if (bestCover == null) {
                            break;
                        }
                        covers.add(bestCover);
                        exposure = bestExposure;
                    }
                    if (exposure > MAX_EXPOSURE) {
                        continue;
                    }
                    double score = feet.distanceTo(player.position()) + 2 * covers.size();
                    if (score < bestScore) {
                        bestScore = score;
                        best = new Plan(stand, List.copyOf(covers), aim, exposure);
                    }
                }
            }
        }
        return best;
    }

    /** Cells at our feet a block or two out towards the crystal, that a block can go in and nothing stands in. */
    private List<BlockPos> coverCandidates(HighwayContext context, BetterBlockPos stand) {
        IPlayerContext ctx = context.playerContext();
        Vec3 blast = crystal.position();
        double standDist = horizontalDistance(Vec3.atBottomCenterOf(stand), blast);
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -COVER_RANGE; dx <= COVER_RANGE; dx++) {
            for (int dz = -COVER_RANGE; dz <= COVER_RANGE; dz++) {
                BlockPos cell = new BlockPos(stand.x + dx, stand.y, stand.z + dz);
                if ((dx == 0 && dz == 0) || horizontalDistance(Vec3.atBottomCenterOf(cell), blast) >= standDist) {
                    continue;
                }
                if (!ctx.world().getBlockState(cell).canBeReplaced()
                        || ctx.world().getBlockState(cell.below()).getCollisionShape(ctx.world(), cell.below()).isEmpty()
                        || !context.baritone().getBuilderProcess().checkNoEntityCollision(new AABB(cell), ctx.player())) {
                    continue;
                }
                candidates.add(cell);
            }
        }
        return candidates;
    }

    /** The first of {@link #AIM_HEIGHTS} a hit from {@code eye} reaches the crystal at, or null. */
    private Vec3 aimFrom(BlockGetter world, Vec3 eye) {
        for (double height : AIM_HEIGHTS) {
            Vec3 aim = new Vec3(crystal.getX(), crystal.getBoundingBox().minY + height, crystal.getZ());
            if (clearShot(world, eye, aim)) {
                return aim;
            }
        }
        return null;
    }

    /** Whether a hit from {@code eye} towards {@code aim} meets the crystal's box within reach, with no block before it. */
    private boolean clearShot(BlockGetter world, Vec3 eye, Vec3 aim) {
        Vec3 reach = eye.add(aim.subtract(eye).normalize().scale(ATTACK_REACH));
        Optional<Vec3> entry = crystal.getBoundingBox().clip(eye, reach);
        return entry.isPresent() && world.clip(new ClipContext(eye, entry.get(), ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, CollisionContext.empty())).getType() == HitResult.Type.MISS;
    }

    /** How much of us the blast would see, standing up where we are now, the way it'll be when we hit. */
    private float exposureHere(IPlayerContext ctx) {
        AABB box = ctx.player().getDimensions(Pose.STANDING).makeBoundingBox(ctx.player().position());
        return ExplosionExposure.seenPercent(ctx.world(), crystal.position(), box);
    }

    private static boolean standable(IPlayerContext ctx, BetterBlockPos pos) {
        return MovementHelper.canWalkOn(ctx, pos.below()) && MovementHelper.canWalkThrough(ctx, pos) && MovementHelper.canWalkThrough(ctx, pos.above());
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void setPhase(Phase phase) {
        this.phase = phase;
        phaseTicks = 0;
    }

    private void replan(HighwayContext context, String why) {
        Helper.HELPER.logDirect("End crystal: " + why + ", trying another spot.");
        releaseKeys(context);
        context.baritone().getPathingBehavior().cancelEverything();
        badStands.add(plan.stand());
        plan = null;
    }

    private void giveUp(HighwayContext context, String why) {
        Helper.HELPER.logDirect("Can't break the end crystal at " + crystal.blockPosition().toShortString() + ": " + why
                + ". Leaving it for " + IGNORE_TICKS / 20 + " s.");
        context.ignoreCrystal(crystal, IGNORE_TICKS);
        finish(context);
    }

    private void finish(HighwayContext context) {
        releaseKeys(context);
        context.baritone().getPathingBehavior().cancelEverything();
        context.setCrystalLocation(null);
        context.transitionTo(HighwayState.Nothing);
    }

    private static void releaseKeys(HighwayContext context) {
        context.baritone().getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
        context.baritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
    }
}
