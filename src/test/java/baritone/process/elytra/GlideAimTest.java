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
import java.util.function.BiPredicate;

import static org.junit.Assert.*;

/**
 * A glide moved by steps heads for the furthest point on the path it gets to in a straight line. It never heads back
 * for one before a spot it got to along the path, nor for one before the target it is still on its way to: each of
 * those would have it going back and forth between two spots indefinitely.
 */
public class GlideAimTest {

    private static final double ACROSS = 3.2;
    private static final double VERTICAL = 3.0;

    @BeforeClass
    public static void bootstrap() {
        HeadlessGame.bootstrap();
    }

    /**
     * Where the box is lets it see what
     */
    private interface Sight {
        boolean clear(Vec3 feet, Vec3 dest, boolean withRoom);
    }

    /**
     * Flies {@code ticks} steps the way the glide does, and the feet each tick
     *
     * @param remember Whether it remembers how far along it got and what it was heading for, as the glide does
     */
    private static List<Vec3> fly(final List<Vec3> points, final Vec3 start, final Sight sight, final int ticks, final boolean remember) {
        final List<Vec3> trail = new ArrayList<>();
        Vec3 feet = start;
        double reached = -1;
        Vec3 lastAt = null;
        double lastProgress = -1;
        for (int t = 0; t < ticks; t++) {
            trail.add(feet);
            final Vec3 at = feet;
            int near = 0;
            for (int i = 1; i < points.size(); i++) {
                if (points.get(i).distanceToSqr(at) < points.get(near).distanceToSqr(at)) {
                    near = i;
                }
            }
            // a line of no length is no target
            final BiPredicate<Vec3, Boolean> glidesTo = (dest, withRoom) -> dest.distanceToSqr(at) >= 1.0E-6 && sight.clear(at, dest, withRoom);
            final ElytraBehavior.Aim found = ElytraBehavior.aimAhead(points::get, near, points.size() - 1, remember ? reached : -1, at, glidesTo);
            final ElytraBehavior.Aim aim = remember ? ElytraBehavior.keepAim(found, lastAt, lastProgress, dest -> glidesTo.test(dest, false)) : found;
            lastAt = aim != null ? aim.at() : null;
            lastProgress = aim != null ? aim.progress() : -1;
            if (aim == null) {
                continue; // held: the fallback's, which isn't this
            }
            final Vec3 step = ElytraBehavior.withinGlideLimits(aim.at().subtract(at), ACROSS, VERTICAL);
            if (at.add(step).distanceToSqr(aim.at()) < 1.0E-6) {
                reached = Math.max(reached, aim.progress());
            }
            feet = at.add(step);
        }
        return trail;
    }

    private static boolean wentBack(final List<Vec3> trail, final Vec3 way) {
        for (int i = 1; i < trail.size(); i++) {
            if (trail.get(i).subtract(trail.get(i - 1)).dot(way) < -1.0E-9) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void atTheFurthestSpotAlongAStretchItDoesntGoBackToThePointBeforeIt() {
        // the box gets no further along the stretch from point 1 to point 2 than x 6, and the point before the box's
        // nearest one is beside it, so point 1 stays a point after the nearest
        final List<Vec3> points = List.of(new Vec3(5, 64, 1), new Vec3(4, 64, -1), new Vec3(12, 64, 3));
        final Sight wall = (feet, dest, withRoom) -> dest.x <= 6;
        final Vec3 start = new Vec3(4, 64, -1);
        final List<Vec3> trail = fly(points, start, wall, 40, true);
        // to the furthest spot along the stretch it sees, and it holds there
        final Vec3 furthest = trail.get(trail.size() - 1);
        assertTrue(furthest.x > 5.5 && furthest.x <= 6);
        for (int i = 2; i < trail.size(); i++) {
            assertEquals("tick " + i, 0, trail.get(i).distanceTo(furthest), 1.0E-9);
        }
        // without remembering: back to point 1 from there, and on to the spot again from it, every tick
        final List<Vec3> before = fly(points, start, wall, 40, false);
        assertTrue(wentBack(before, new Vec3(8, 0, 4)));
        assertEquals(0, before.get(38).distanceTo(before.get(36)), 1.0E-9);
        assertTrue(before.get(38).distanceTo(before.get(37)) > 1);
    }

    @Test
    public void atTheFurthestSpotItSeesBetweenTwoPointsItDoesntStepBackABlock() {
        // a straight stretch along x, and nothing past x 11 in a straight line from anywhere
        final List<Vec3> points = List.of(new Vec3(0, 64, 0), new Vec3(8, 64, 0), new Vec3(16, 64, 0));
        final Sight wall = (feet, dest, withRoom) -> dest.x <= 11;
        final List<Vec3> trail = fly(points, new Vec3(2, 64, 0), wall, 30, true);
        assertFalse(wentBack(trail, new Vec3(1, 0, 0)));
        assertEquals(11, trail.get(trail.size() - 1).x, 1.0E-9);
        // a block back along the stretch is no target even before it has got anywhere along it, remembering or not
        assertFalse(wentBack(fly(points, new Vec3(2, 64, 0), wall, 30, false), new Vec3(1, 0, 0)));
    }

    @Test
    public void besideAWallItKeepsOnForTheTargetItWasHeadingFor() {
        // the path goes on past point 1 and back the other way to point 2. a step from the wall at x 3 the box has room
        // for the line to point 1; by the wall it has no room for that, and point 2, further along, is in a straight
        // line from anywhere
        final Vec3 p1 = new Vec3(6, 64, 0);
        final Vec3 p2 = new Vec3(-6, 64, 0);
        final List<Vec3> points = List.of(new Vec3(-1, 64, 0), p1, p2);
        final Sight sight = (feet, dest, withRoom) -> {
            if (dest.equals(p2)) {
                return !withRoom;
            }
            if (dest.equals(p1)) {
                return !withRoom || feet.x < 3;
            }
            return true;
        };
        final List<Vec3> trail = fly(points, Vec3.ZERO.add(0, 64, 0), sight, 30, true);
        // on to point 2 once it heads for it, and there it holds: the end
        assertEquals(0, trail.get(trail.size() - 1).distanceTo(p2), 1.0E-9);
        // without remembering: towards point 1 with room, then by the wall for point 2, and back again, three blocks
        // each tick
        final List<Vec3> before = fly(points, Vec3.ZERO.add(0, 64, 0), sight, 30, false);
        assertEquals(0, before.get(29).distanceTo(before.get(27)), 1.0E-9);
        assertEquals(ACROSS, before.get(29).distanceTo(before.get(28)), 1.0E-9);
    }
}
