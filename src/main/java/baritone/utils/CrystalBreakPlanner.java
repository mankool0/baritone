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

package baritone.utils;

import baritone.api.utils.BetterBlockPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Where to stand to break an end crystal, and what to put down first. Hitting one sets off a power 6 explosion at its
 * base: from the nearest a hit reaches, about 20 HP through Protection IV netherite on Hard. Vanilla scales that by how
 * much of us the blast can see ({@link ExplosionExposure}), so a block at our feet between us and it hides nearly all
 * of us while we hit it over the top. One sitting under the road needs none: the edge of the floor we stand on hides
 * us from it.
 * <p>
 * Reads blocks only. What stands where, and what counts as somewhere to stand, comes in from outside, so the same plan
 * can be worked out against a world the game isn't running.
 */
public final class CrystalBreakPlanner {

    /** The most of us the blast may see: about 2 HP through Protection IV netherite on Hard, from where we hit it. */
    public static final float MAX_EXPOSURE = 0.25f;
    /**
     * The most it may see of us where it takes the floor around us: on a floor we swapped for obsidian, the one block
     * left, or where we drop into the hole it leaves. The knockback scales with what it sees, and shouldn't carry us off
     * that block or the edge of that hole.
     */
    public static final float MAX_ISLAND_EXPOSURE = 0.1f;
    /** No nearer than this to where it goes off, feet to the crystal's base. */
    public static final double MIN_BLAST_DISTANCE = 3.0;
    /** Eye to its hitbox, under the 3 block entity reach. */
    public static final double ATTACK_REACH = 2.8;
    /** How far out from the crystal to look for somewhere to stand. */
    public static final int STAND_RADIUS = 5;
    /**
     * Feet heights to look at, from its base. Its box reaches two blocks up from where it sits, so a crystal on a
     * pillar or the support row under the road gets in the way of the floor, and the road we stand on is two or
     * three blocks above it.
     */
    public static final int STAND_DY_MIN = -1;
    public static final int STAND_DY_MAX = 3;
    /** Cover goes this far out from where we stand, at our feet. */
    public static final int COVER_RANGE = 2;
    public static final int MAX_COVERS = 3;
    /** Heights above the crystal's base to aim a hit at, so it goes over cover at our feet. */
    private static final double[] AIM_HEIGHTS = {1.5, 1.85};
    /**
     * Failing those, the part of its box nearest us, this far in. Looking down on a crystal below the road, its
     * middle is out of reach past the edge of the floor, but its top edge on our side isn't.
     */
    private static final double AIM_INSET = 0.2;
    /**
     * How far from where it goes off the blast takes out blocks weaker than {@link #BLAST_PROOF}. Its rays reach
     * netherrack further across open air and less far through rock, so this is where the floor under a stand in reach
     * of the crystal is gone for sure, and what's under that is gone more often than not.
     */
    public static final double CRATER_RADIUS = 4.5;
    /** Obsidian, crying obsidian and the like stand up to the blast; netherrack, blackstone and stone don't. */
    private static final float BLAST_PROOF = 600;
    /** The furthest down the first block the blast leaves under us may be, counting the floor itself as one. */
    private static final int MAX_CRATER_DROP = 5;
    /** How much more of us a tank plan lets the blast see once we're there than it was planned for, still in budget. */
    private static final float TANK_EXPOSURE_SLACK = 0.05f;

    /**
     * Taking the blast instead of hiding from it, on an enchanted golden apple: what we wear, what difficulty does to
     * the damage, our Resistance by then, and the most of our health and absorption the hit and any fall may take.
     *
     * @param protection the armour's Protection levels, plus twice its Blast Protection levels
     * @param resistance the Resistance amplifier we'll have, -1 for none
     */
    public record Tank(Difficulty difficulty, float armor, float toughness, float protection, int resistance,
                       float knockbackResistance, float maxDamage) {

        /** Vanilla's explosion damage to us, {@code distance} from where it goes off with {@code seen} of us in view, after everything that takes it down. */
        public float damage(double distance, float seen) {
            double d = distance / 12.0;
            if (d > 1) {
                return 0;
            }
            double e = (1 - d) * seen;
            float amount = (float) ((e * e + e) / 2.0 * 7.0 * 12.0 + 1.0);
            amount = switch (difficulty) { // explosions always scale with difficulty
                case PEACEFUL -> 0;
                case EASY -> Math.min(amount / 2.0F + 1.0F, amount);
                case NORMAL -> amount;
                case HARD -> amount * 3.0F / 2.0F;
            };
            float armorPart = Mth.clamp(armor - amount / (2.0F + toughness / 4.0F), armor * 0.2F, 20.0F) / 25.0F;
            amount *= 1 - armorPart;
            if (resistance >= 0) {
                amount = Math.max(amount * (25 - (resistance + 1) * 5) / 25.0F, 0);
            }
            return amount * (1 - Mth.clamp(protection, 0, 20) / 25.0F);
        }

        /** The shove it gives us standing at {@code feet}, out from where it goes off towards our eyes. */
        public Vec3 knockback(Vec3 blast, Vec3 feet, float eyeHeight, float seen) {
            double d = feet.distanceTo(blast) / 12.0;
            if (d > 1) {
                return Vec3.ZERO;
            }
            return feet.add(0, eyeHeight, 0).subtract(blast).normalize().scale((1 - d) * seen * (1 - knockbackResistance));
        }
    }

    /**
     * Where to stand, what to put down first, and where to hit it, worked out for the middle of that block, and the
     * most of us the blast may see from there. A {@code swap} is the floor under the stand, which the blast would take,
     * to mine and fill with obsidian first, from {@code swapFrom} next to it; both are null when there's none. A
     * {@code tank} plan takes the blast on an enchanted golden apple, for about {@code damage} health; the others hide
     * from it.
     */
    public record Plan(BetterBlockPos stand, List<BlockPos> covers, Vec3 aim, float exposure, float maxExposure,
                       BlockPos swap, BetterBlockPos swapFrom, boolean tank, float damage) {}

    /** Why a spot can't be the one, or {@link #OK}. In the order they're checked. */
    public enum Verdict {
        /** Tried already and it didn't work out. */
        BAD,
        /** Nearer the blast than {@link #MIN_BLAST_DISTANCE}. */
        TOO_CLOSE,
        /** Nothing to stand on there, or no room. */
        NOT_STANDABLE,
        /**
         * The hole the blast leaves under us drops us into lava, out of the world, or further than
         * {@link #MAX_CRATER_DROP}, and there's no swapping the floor for obsidian.
         */
        FALLS,
        /** No hit reaches it from there, past the blocks in the way. */
        NO_SHOT,
        /** {@link #MAX_COVERS} blocks of cover still leave more than {@link #MAX_EXPOSURE} of us in view. */
        EXPOSED,
        /** Tanking it: the knockback, through the hole the blast leaves, puts us in lava or drops us too far. */
        KNOCKED_OFF,
        /** Tanking it: the hit and the fall after it take more than we can spare. */
        TOO_HURT,
        OK
    }

    /** One spot looked at: why not, or the plan from it. {@code exposure} is the least any cover got it down to. */
    public record Spot(BetterBlockPos stand, Verdict verdict, Plan plan, float exposure) {}

    private final BlockGetter world;
    private final Vec3 blast;
    private final AABB crystalBox;
    private final EntityDimensions standing;
    private final Predicate<BetterBlockPos> standable;
    private final Predicate<BlockPos> coverFree;
    private final boolean canSwap;

    /**
     * @param blast      the crystal's position, where it goes off
     * @param crystalBox its hitbox
     * @param standing   our standing size, eye height included
     * @param standable  whether we can stand with our feet in this block
     * @param coverFree  whether nothing but us stands in this block, so a block can go in it
     * @param canSwap    whether we carry obsidian to put under us in place of a floor the blast would take
     */
    public CrystalBreakPlanner(BlockGetter world, Vec3 blast, AABB crystalBox, EntityDimensions standing,
                               Predicate<BetterBlockPos> standable, Predicate<BlockPos> coverFree, boolean canSwap) {
        this.world = world;
        this.blast = blast;
        this.crystalBox = crystalBox;
        this.standing = standing;
        this.standable = standable;
        this.coverFree = coverFree;
        this.canSwap = canSwap;
    }

    /** A crystal's hitbox for one at {@code pos}, the size vanilla gives every end crystal. */
    public static AABB crystalBox(Vec3 pos) {
        return new AABB(pos.x - 1, pos.y, pos.z - 1, pos.x + 1, pos.y + 2, pos.z + 1);
    }

    /** The stand spots {@link #plan} looks at, nearest the crystal's own block first. */
    public List<BetterBlockPos> spots() {
        BlockPos base = BlockPos.containing(blast);
        List<BetterBlockPos> spots = new ArrayList<>();
        for (int dy = STAND_DY_MIN; dy <= STAND_DY_MAX; dy++) {
            for (int dx = -STAND_RADIUS; dx <= STAND_RADIUS; dx++) {
                for (int dz = -STAND_RADIUS; dz <= STAND_RADIUS; dz++) {
                    spots.add(new BetterBlockPos(base.getX() + dx, base.getY() + dy, base.getZ() + dz));
                }
            }
        }
        return spots;
    }

    /**
     * The nearest spot to {@code from} in reach of the crystal that's at least {@link #MIN_BLAST_DISTANCE} from it and
     * where no more than {@link #MAX_COVERS} blocks at our feet bring the blast's view of us down to
     * {@link #MAX_EXPOSURE}, each cover the one that hides the most of what's left. Fewer covers and no floor to swap
     * win a near tie.
     */
    public Plan plan(Vec3 from, Set<BetterBlockPos> badStands) {
        return best(from, badStands, null);
    }

    /**
     * For when nowhere hides us well enough: the spot in reach where, with whatever cover helps and an enchanted golden
     * apple's Resistance, the hit and the fall after it cost the least health within {@code tank}'s budget, and the
     * knockback leaves us somewhere safe.
     */
    public Plan tankPlan(Vec3 from, Set<BetterBlockPos> badStands, Tank tank) {
        return best(from, badStands, tank);
    }

    private Plan best(Vec3 from, Set<BetterBlockPos> badStands, Tank tank) {
        Plan best = null;
        double bestScore = Double.MAX_VALUE;
        for (BetterBlockPos stand : spots()) {
            Spot spot = evaluate(stand, badStands, from, tank);
            if (spot.verdict() != Verdict.OK) {
                continue;
            }
            double score = Vec3.atBottomCenterOf(stand).distanceTo(from) + 2 * spot.plan().covers().size() + (spot.plan().swap() != null ? 2 : 0)
                    + spot.plan().damage();
            if (score < bestScore) {
                bestScore = score;
                best = spot.plan();
            }
        }
        return best;
    }

    /** Whether {@code stand} works, and the cover it takes. */
    public Spot evaluate(BetterBlockPos stand, Set<BetterBlockPos> badStands) {
        return evaluate(stand, badStands, null);
    }

    /** The same, swapping the floor from the side nearest {@code from} when it needs swapping. */
    public Spot evaluate(BetterBlockPos stand, Set<BetterBlockPos> badStands, Vec3 from) {
        return evaluate(stand, badStands, from, null);
    }

    /** The same, tanking the blast on {@code tank} instead of hiding from it, when that isn't null. */
    public Spot evaluate(BetterBlockPos stand, Set<BetterBlockPos> badStands, Vec3 from, Tank tank) {
        Vec3 feet = Vec3.atBottomCenterOf(stand);
        if (badStands.contains(stand)) {
            return new Spot(stand, Verdict.BAD, null, 1);
        }
        if (feet.distanceTo(blast) < MIN_BLAST_DISTANCE) {
            return new Spot(stand, Verdict.TOO_CLOSE, null, 1);
        }
        if (!standable.test(stand)) {
            return new Spot(stand, Verdict.NOT_STANDABLE, null, 1);
        }
        boolean holds = floorHolds(stand);
        boolean lands = landsSafely(stand);
        BlockPos swap = null;
        BetterBlockPos swapFrom = null;
        if (!holds || !lands) {
            // better than dropping into the hole even when that's safe: we'd be in it, among whatever lava it opened
            swapFrom = swapFrom(stand, from);
            if (swapFrom != null) {
                swap = stand.below();
            } else if (!lands) {
                return new Spot(stand, Verdict.FALLS, null, 1);
            }
        }
        Vec3 eye = feet.add(0, standing.eyeHeight(), 0);
        Vec3 aim = aimFrom(world, eye);
        if (aim == null) {
            return new Spot(stand, Verdict.NO_SHOT, null, 1); // out of reach, or nothing to hit it past
        }
        AABB box = standing.makeBoundingBox(feet);
        BlockState coverState = Blocks.NETHERRACK.defaultBlockState();
        Set<BlockPos> covers = new LinkedHashSet<>();
        // tanking it, every bit of cover still takes damage and knockback off the hit
        float allowed = tank != null ? 0 : swap != null || !holds ? MAX_ISLAND_EXPOSURE : MAX_EXPOSURE;
        float exposure = ExplosionExposure.seenPercent(world, blast, box);
        while (exposure > allowed && covers.size() < MAX_COVERS) {
            BlockPos bestCover = null;
            float bestExposure = exposure;
            for (BlockPos cover : coverCandidates(stand)) {
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
        if (tank != null) {
            return tankSpot(stand, List.copyOf(covers), aim, exposure, swap, swapFrom, tank);
        }
        if (exposure > allowed) {
            return new Spot(stand, Verdict.EXPOSED, null, exposure);
        }
        return new Spot(stand, Verdict.OK, new Plan(stand, List.copyOf(covers), aim, exposure, allowed, swap, swapFrom, false, 0), exposure);
    }

    /** Whether taking the blast at {@code stand} behind these covers stays in {@code tank}'s budget and leaves us somewhere safe. */
    private Spot tankSpot(BetterBlockPos stand, List<BlockPos> covers, Vec3 aim, float exposure, BlockPos swap, BetterBlockPos swapFrom, Tank tank) {
        Vec3 feet = Vec3.atBottomCenterOf(stand);
        float damage = tank.damage(feet.distanceTo(blast), exposure);
        BlockGetter after = afterBlast(swap != null ? ExplosionExposure.withBlocks(world, Set.of(swap), Blocks.OBSIDIAN.defaultBlockState()) : world);
        KnockbackLanding.Landing landing = KnockbackLanding.land(after, feet, tank.knockback(blast, feet, standing.eyeHeight(), exposure), standing, MAX_CRATER_DROP - 1);
        if (landing == null) {
            return new Spot(stand, Verdict.KNOCKED_OFF, null, exposure);
        }
        if (damage + landing.fallDamage() > tank.maxDamage()) {
            return new Spot(stand, Verdict.TOO_HURT, null, exposure);
        }
        // a little more of us in view once we're there still goes, when the budget and the landing have room for it
        float slack = Math.min(1, exposure + TANK_EXPOSURE_SLACK);
        KnockbackLanding.Landing slackLanding = KnockbackLanding.land(after, feet, tank.knockback(blast, feet, standing.eyeHeight(), slack), standing, MAX_CRATER_DROP - 1);
        float maxExposure = slackLanding != null && tank.damage(feet.distanceTo(blast), slack) + slackLanding.fallDamage() <= tank.maxDamage() ? slack : exposure;
        return new Spot(stand, Verdict.OK, new Plan(stand, covers, aim, exposure, maxExposure, swap, swapFrom, true, damage + landing.fallDamage()), exposure);
    }

    /**
     * The world once the blast has gone through it: every block weaker than {@link #BLAST_PROOF} within
     * {@link #CRATER_RADIUS} gone. Lava stays where it is, and so does what the blast doesn't reach.
     */
    private BlockGetter afterBlast(BlockGetter base) {
        return new BlockGetter() {
            @Override
            public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
                return gone(pos) ? null : base.getBlockEntity(pos);
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                return gone(pos) ? Blocks.AIR.defaultBlockState() : base.getBlockState(pos);
            }

            @Override
            public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {
                return getBlockState(pos).getFluidState();
            }

            @Override
            public int getHeight() {
                return base.getHeight();
            }

            @Override
            public int getMinY() {
                return base.getMinY();
            }

            private boolean gone(BlockPos pos) {
                BlockState state = base.getBlockState(pos);
                return !state.isAir() && state.getFluidState().isEmpty() && !blastProof(state)
                        && Vec3.atCenterOf(pos).distanceTo(blast) <= CRATER_RADIUS;
            }
        };
    }

    /** Whether the blast leaves this block standing however near it is. */
    public static boolean blastProof(BlockState state) {
        return state.getBlock().getExplosionResistance() >= BLAST_PROOF;
    }

    /** Whether the floor under {@code stand} is still there after the blast: tough enough, or out of its reach. */
    private boolean floorHolds(BetterBlockPos stand) {
        BlockPos floor = stand.below();
        return blastProof(world.getBlockState(floor)) || Vec3.atCenterOf(floor).distanceTo(blast) > CRATER_RADIUS;
    }

    /**
     * Where to stand while mining the floor under {@code stand} and putting obsidian in its place, the side nearest
     * {@code from}, or null when it can't be done: we carry none, it isn't something to mine, lava beside it would run
     * into the gap (lava under it stays put), there's nothing to put the obsidian against, or lava lies beside the
     * stand, which no floor keeps out.
     */
    private BetterBlockPos swapFrom(BetterBlockPos stand, Vec3 from) {
        BlockPos floor = stand.below();
        BlockState state = world.getBlockState(floor);
        if (!canSwap || state.getCollisionShape(world, floor).isEmpty() || state.getDestroySpeed(world, floor) < 0
                || world.getBlockEntity(floor) != null || !coverFree.test(floor) || lavaBeside(floor) || lavaBeside(stand)) {
            return null;
        }
        BetterBlockPos best = null;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BetterBlockPos at = stand.relative(side);
            if (!standable.test(at)) {
                continue;
            }
            // what the obsidian goes against: below the gap, or a side of it facing us, never the block we stand on
            boolean backing = !world.getBlockState(floor.below()).getCollisionShape(world, floor.below()).isEmpty();
            for (Direction other : Direction.Plane.HORIZONTAL) {
                BlockPos beside = floor.relative(other);
                backing |= other != side && !world.getBlockState(beside).getCollisionShape(world, beside).isEmpty();
            }
            if (backing && (best == null || (from != null && Vec3.atBottomCenterOf(at).distanceTo(from) < Vec3.atBottomCenterOf(best).distanceTo(from)))) {
                best = at;
            }
        }
        return best;
    }

    /** Lava next to {@code pos} that would run into it: on any side but underneath. */
    private boolean lavaBeside(BlockPos pos) {
        for (Direction side : Direction.values()) {
            if (side != Direction.DOWN && world.getBlockState(pos.relative(side)).is(Blocks.LAVA)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether standing at {@code stand} we still land somewhere fine after the blast: the damage is dealt before it
     * breaks any block, so it takes the floor from under us afterwards. On an obsidian floor we stay put. On netherrack
     * we drop to the first block it leaves, which has to be near enough, with no lava beside where we stand, beside any
     * block we fall past, or where we land: the knockback drifts us sideways as we go, and the hole lets lava in.
     */
    private boolean landsSafely(BetterBlockPos stand) {
        if (lavaBeside(stand)) {
            return false;
        }
        for (int drop = 1; drop <= MAX_CRATER_DROP; drop++) {
            BlockPos cell = stand.below(drop);
            BlockState state = world.getBlockState(cell);
            if (state.is(Blocks.LAVA)) {
                return false;
            }
            if (state.getCollisionShape(world, cell).isEmpty()
                    || (!blastProof(state) && Vec3.atCenterOf(cell).distanceTo(blast) <= CRATER_RADIUS)) {
                if (lavaBeside(cell)) {
                    return false;
                }
                continue; // nothing there, or nothing there once it goes off
            }
            return true;
        }
        return false;
    }

    /** Cells at our feet a block or two out towards the crystal, that a block can go in and nothing stands in. */
    private List<BlockPos> coverCandidates(BetterBlockPos stand) {
        double standDist = horizontalDistance(Vec3.atBottomCenterOf(stand), blast);
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -COVER_RANGE; dx <= COVER_RANGE; dx++) {
            for (int dz = -COVER_RANGE; dz <= COVER_RANGE; dz++) {
                BlockPos cell = new BlockPos(stand.x + dx, stand.y, stand.z + dz);
                if ((dx == 0 && dz == 0) || horizontalDistance(Vec3.atBottomCenterOf(cell), blast) >= standDist) {
                    continue;
                }
                if (!world.getBlockState(cell).canBeReplaced()
                        || world.getBlockState(cell.below()).getCollisionShape(world, cell.below()).isEmpty()
                        || !coverFree.test(cell)) {
                    continue;
                }
                candidates.add(cell);
            }
        }
        return candidates;
    }

    /** The first of {@link #AIM_HEIGHTS}, then the nearest part of its box, that a hit from {@code eye} reaches the crystal at, or null. */
    public Vec3 aimFrom(BlockGetter world, Vec3 eye) {
        for (double height : AIM_HEIGHTS) {
            Vec3 aim = new Vec3(blast.x, crystalBox.minY + height, blast.z);
            if (clearShot(world, eye, aim)) {
                return aim;
            }
        }
        AABB inner = crystalBox.deflate(AIM_INSET);
        Vec3 nearest = new Vec3(Mth.clamp(eye.x, inner.minX, inner.maxX), Mth.clamp(eye.y, inner.minY, inner.maxY), Mth.clamp(eye.z, inner.minZ, inner.maxZ));
        return clearShot(world, eye, nearest) ? nearest : null;
    }

    /** Whether a hit from {@code eye} towards {@code aim} meets the crystal's box within reach, with no block before it. */
    public boolean clearShot(BlockGetter world, Vec3 eye, Vec3 aim) {
        Vec3 reach = eye.add(aim.subtract(eye).normalize().scale(ATTACK_REACH));
        Optional<Vec3> entry = crystalBox.clip(eye, reach);
        return entry.isPresent() && world.clip(new ClipContext(eye, entry.get(), ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, CollisionContext.empty())).getType() == HitResult.Type.MISS;
    }

    /** How much of a standing player with feet at {@code feet} the blast would see. */
    public float exposureAt(BlockGetter world, Vec3 feet) {
        return ExplosionExposure.seenPercent(world, blast, standing.makeBoundingBox(feet));
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
