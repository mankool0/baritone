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
import baritone.test.HeadlessGame;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A road along Z, nine wide around x = 0, with an end crystal in the middle of it at z = 0 whose box keeps the floor
 * cells around it from being placed. We come at it from {@link #FROM}, down the road.
 */
public class CrystalBreakPlannerTest {

    private static final int FLOOR = 64;
    private static final Vec3 FROM = new Vec3(0.5, FLOOR + 1, 7.5);
    private static final EntityDimensions PLAYER = EntityDimensions.scalable(0.6F, 1.8F).withEyeHeight(1.62F);

    private final Map<BlockPos, BlockState> blocks = new HashMap<>();
    private final BlockGetter world = new BlockGetter() {
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinY() {
            return -64;
        }
    };

    private Vec3 crystal;

    @BeforeClass
    public static void bootstrap() {
        HeadlessGame.bootstrap();
    }

    @Before
    public void clear() {
        blocks.clear();
    }

    /** A floor of {@code state} at {@code y}, with {@code under} filling the {@code depth} rows below it. */
    private void road(int y, BlockState state, BlockState under, int depth) {
        for (int x = -4; x <= 4; x++) {
            for (int z = -8; z <= 8; z++) {
                blocks.put(new BlockPos(x, y, z), state);
                for (int d = 1; d <= depth; d++) {
                    blocks.put(new BlockPos(x, y - d, z), under);
                }
            }
        }
    }

    /** A crystal sitting on obsidian at {@code baseY}, the cells its box takes up emptied the way the builder leaves them. */
    private void crystalOn(int baseY) {
        crystal = new Vec3(0.5, baseY + 1, 0.5);
        blocks.put(new BlockPos(0, baseY, 0), Blocks.OBSIDIAN.defaultBlockState());
        AABB box = CrystalBreakPlanner.crystalBox(crystal);
        for (int x = -1; x <= 1; x++) {
            for (int y = baseY + 1; y <= baseY + 3; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (box.intersects(new AABB(new BlockPos(x, y, z)))) {
                        blocks.remove(new BlockPos(x, y, z));
                    }
                }
            }
        }
    }

    private CrystalBreakPlanner planner(boolean canSwap) {
        AABB box = CrystalBreakPlanner.crystalBox(crystal);
        return new CrystalBreakPlanner(world, crystal, box, PLAYER,
                pos -> !world.getBlockState(pos.below()).getCollisionShape(world, pos.below()).isEmpty()
                        && world.getBlockState(pos).isAir() && world.getBlockState(pos.above()).isAir(),
                cell -> !box.intersects(new AABB(cell)), canSwap);
    }

    @Test
    public void obsidianRoadNeedsNoSwap() {
        road(FLOOR, Blocks.OBSIDIAN.defaultBlockState(), Blocks.AIR.defaultBlockState(), 0);
        crystalOn(FLOOR - 1); // on the support row, its box up through the floor
        CrystalBreakPlanner.Plan plan = planner(true).plan(FROM, Set.of());
        assertNotNull(plan);
        assertNull("the obsidian under us holds", plan.swap());
        assertEquals(FLOOR + 1, plan.stand().y);
        assertTrue(plan.exposure() <= CrystalBreakPlanner.MAX_EXPOSURE);
    }

    @Test
    public void netherrackOverLavaGetsItsFloorSwapped() {
        BlockState lava = Blocks.LAVA.defaultBlockState();
        road(FLOOR, Blocks.NETHERRACK.defaultBlockState(), lava, 3);
        crystalOn(FLOOR - 1);
        CrystalBreakPlanner.Plan plan = planner(true).plan(FROM, Set.of());
        assertNotNull(plan);
        assertEquals("the blast would drop us into the lava, so the floor goes first", plan.stand().below(), plan.swap());
        BetterBlockPos from = plan.swapFrom();
        assertNotNull(from);
        assertEquals(1, Math.abs(from.x - plan.stand().x) + Math.abs(from.z - plan.stand().z));
        assertEquals(plan.stand().y, from.y);
        assertTrue("on one block of obsidian, nothing may push us off it", plan.exposure() <= CrystalBreakPlanner.MAX_ISLAND_EXPOSURE);
    }

    @Test
    public void netherrackOverLavaWithoutObsidianHasNowhere() {
        road(FLOOR, Blocks.NETHERRACK.defaultBlockState(), Blocks.LAVA.defaultBlockState(), 3);
        crystalOn(FLOOR - 1);
        CrystalBreakPlanner planner = planner(false);
        assertNull(planner.plan(FROM, Set.of()));
        BetterBlockPos nearest = new BetterBlockPos(0, FLOOR + 1, 3);
        assertEquals(CrystalBreakPlanner.Verdict.FALLS, planner.evaluate(nearest, Set.of()).verdict());
    }

    @Test
    public void netherrackOverAShallowDryHoleNeedsNoObsidian() {
        // the blast takes the netherrack, and we land on the obsidian two blocks down
        road(FLOOR, Blocks.NETHERRACK.defaultBlockState(), Blocks.AIR.defaultBlockState(), 1);
        for (int x = -4; x <= 4; x++) {
            for (int z = -8; z <= 8; z++) {
                blocks.put(new BlockPos(x, FLOOR - 2, z), Blocks.OBSIDIAN.defaultBlockState());
            }
        }
        crystalOn(FLOOR - 1);
        CrystalBreakPlanner.Plan plan = planner(false).plan(FROM, Set.of());
        assertNotNull(plan);
        assertNull(plan.swap());
        assertTrue("where the floor goes, nothing may push us off the edge", plan.exposure() <= plan.maxExposure());
    }

    /** Full Protection IV netherite on Hard. */
    private static CrystalBreakPlanner.Tank netherite(int resistance, float maxDamage) {
        return new CrystalBreakPlanner.Tank(Difficulty.HARD, 20, 12, 16, resistance, 0, maxDamage);
    }

    @Test
    public void tankDamageMatchesVanilla() {
        // worked through by hand from ServerExplosion, CombatRules and LivingEntity for 3.2 blocks out
        assertEquals(24.7, netherite(-1, 0).damage(3.2, 1), 0.1);
        assertEquals(19.7, netherite(0, 0).damage(3.2, 1), 0.1);
        assertEquals(0.11, netherite(-1, 0).damage(3.2, 0), 0.01);
        assertEquals(0, netherite(-1, 0).damage(12.5, 1), 0);
    }

    @Test
    public void tankTakesWhatCoverCantHide() {
        // over a wide obsidian floor, a crystal floating two blocks up goes off above the cover at our feet
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                blocks.put(new BlockPos(x, FLOOR, z), Blocks.OBSIDIAN.defaultBlockState());
            }
        }
        crystal = new Vec3(0.5, FLOOR + 2, 0.5);
        CrystalBreakPlanner planner = planner(true);
        assertNull("nothing hides us from it", planner.plan(FROM, Set.of()));
        CrystalBreakPlanner.Tank tank = netherite(0, 26);
        CrystalBreakPlanner.Plan plan = planner.tankPlan(FROM, Set.of(), tank);
        assertNotNull(plan);
        assertTrue(plan.tank());
        assertTrue(plan.damage() <= tank.maxDamage());
        assertTrue(plan.exposure() <= plan.maxExposure());
    }

    @Test
    public void tankWontStandWhereTheKnockbackThrowsUsOff() {
        // the same crystal, but the floor is one strip with nothing on either side of it
        for (int z = -12; z <= 12; z++) {
            blocks.put(new BlockPos(0, FLOOR, z), Blocks.OBSIDIAN.defaultBlockState());
        }
        crystal = new Vec3(0.5, FLOOR + 2, 0.5);
        CrystalBreakPlanner planner = planner(true);
        CrystalBreakPlanner.Plan plan = planner.tankPlan(FROM, Set.of(), netherite(0, 26));
        assertNotNull("somewhere along the strip the shove keeps us on it", plan);
        // whatever it picks, the shove must not carry us off the strip
        Vec3 feet = Vec3.atBottomCenterOf(plan.stand());
        Vec3 push = netherite(0, 26).knockback(crystal, feet, PLAYER.eyeHeight(), plan.maxExposure());
        assertNotNull(KnockbackLanding.land(world, feet, push, PLAYER, 4));
    }
}
