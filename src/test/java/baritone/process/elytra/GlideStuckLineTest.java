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

import baritone.test.HeadlessGame;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * The line said when a glide gets no closer to the goal tells a step the move never made from steps that undo each
 * other: both get nowhere, and the step's own length looks the same in both.
 */
public class GlideStuckLineTest {

    @BeforeClass
    public static void bootstrap() {
        HeadlessGame.bootstrap();
    }

    private static String moved(final List<Vec3> feet, final double step) {
        final List<Double> asked = new ArrayList<>();
        for (int i = 0; i < feet.size(); i++) {
            asked.add(step);
        }
        return ElytraBehavior.lastStepsMoved(feet, asked, 20);
    }

    @Test
    public void heldWhereItIs() {
        final List<Vec3> feet = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            feet.add(new Vec3(10.5, 60, 10.5));
        }
        assertEquals("the last 20 steps asked for 64.0 blocks and it went 0.0 (0.0 from where they began)", moved(feet, 3.2));
    }

    @Test
    public void backAndForth() {
        final List<Vec3> feet = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            feet.add(new Vec3(10.5 + (i % 2) * 3.2, 60, 10.5));
        }
        assertEquals("the last 20 steps asked for 64.0 blocks and it went 64.0 (0.0 from where they began)", moved(feet, 3.2));
    }

    @Test
    public void onItsWay() {
        final List<Vec3> feet = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            feet.add(new Vec3(10.5 + i * 2, 60, 10.5));
        }
        assertEquals("the last 5 steps asked for 10.0 blocks and it went 10.0 (10.0 from where they began)", moved(feet, 2));
    }

    @Test
    public void nothingToMeasureYet() {
        final List<Vec3> feet = new ArrayList<>();
        feet.add(new Vec3(0, 60, 0));
        assertEquals("no steps made yet to measure", moved(feet, 1));
    }
}
