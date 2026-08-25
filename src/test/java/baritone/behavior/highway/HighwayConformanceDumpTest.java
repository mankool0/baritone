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

package baritone.behavior.highway;

import baritone.api.Settings;
import baritone.api.TestSettings;
import baritone.api.schematic.CompositeSchematic;
import baritone.behavior.highway.HighwayConformanceDump.Profile;
import baritone.behavior.highway.HighwayConformanceDump.Result;
import baritone.test.HeadlessGame;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.Vec3i;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Runs the conformance dump without a game and writes its artifacts under {@code build/} for the
 * coordination server to pin its compiler to:
 * <ul>
 *   <li>{@code nhwdump.json} - the two widths in service at stock settings, in the shape the
 *   retired in-game command wrote (dig cases included), so it diffs against the dumps live clients
 *   took;</li>
 *   <li>{@code nhwdump-profiles.json} - the same geometry at every width, height and deck the
 *   server might be asked to build, which no live client would ever be set to one by one.</li>
 * </ul>
 * The self-checks inside the dump are the assertions; a dump that fails them is not written as
 * passing, and a dump that cannot be composed at some profile fails here rather than in a bot.
 * <p>
 * Class initialisation is the headless hazard: {@link NetherHighwayBuilderBehavior} implements
 * {@code IRenderer}, whose fields touch the client, and the JVM leaves that interface alone only
 * because it declares no default methods. A default method added there would make this test die
 * in {@code <clinit>}; the fix is to move the static geometry out of the behavior, not to run a
 * client.
 */
public class HighwayConformanceDumpTest {

    private static final Gson COMPACT = new Gson();
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

    @BeforeClass
    public static void bootstrap() {
        HeadlessGame.bootstrap();
    }

    /** The slice the nhwdump handoff spells out cell by cell: +X, t = 0, stock settings. */
    @Test
    public void theStockSliceIsTheOneInTheHandoff() {
        Settings settings = TestSettings.fresh();
        CompositeSchematic schem = NetherHighwayBuilderBehavior.composeHighwaySchematic(new Vec3i(1, 0, 0), true, settings);
        List<int[]> targets = HighwayConformanceDump.projectSlice(schem, 0, 118, -3);
        assertEquals(22, targets.size());
        for (int z = -2; z <= 1; z++) {
            assertTrue("floor at z=" + z, contains(targets, 0, 119, z, 1));
        }
        assertTrue("low rail", contains(targets, 0, 120, -3, 1));
        assertTrue("high rail", contains(targets, 0, 120, 2, 1));
        assertEquals(6, targets.stream().filter(t -> t[3] == 1).count());
        for (int[] t : targets) {
            assertTrue("nothing below the floor: " + Arrays.toString(t), t[1] >= 119);
            assertTrue("nothing above the tunnel: " + Arrays.toString(t), t[1] <= 122);
        }
    }

    @Test
    public void theInGameMatrixPassesItsSelfChecks() throws IOException {
        Settings settings = TestSettings.fresh();
        Profile stock = Profile.of(settings);
        List<Profile> profiles = new ArrayList<>();
        for (int width : HighwayConformanceDump.DEFAULT_WIDTHS) {
            profiles.add(stock.withWidth(width));
        }
        Result result = HighwayConformanceDump.run(settings, profiles, HighwayConformanceDump.DEFAULT_TS, true);
        assertEquals(Collections.emptyList(), result.failures);
        assertEquals("2 widths x 8 directions x 5 rail combos x pave/dig x 4 slices", 2 * 8 * 5 * 2 * 4, result.cases.size());
        assertEquals("the run leaves the settings as it found them", stock, Profile.of(settings));
        assertTrue(settings.highwayRail.value && settings.highwayRailLow.value && settings.highwayRailHigh.value);
        write("nhwdump.json", PRETTY.toJson(HighwayConformanceDump.document(settings, result)));
    }

    /**
     * Every profile the server accepts is a size the builder must agree on. The sweep varies one
     * dimension at a time around stock, then a handful of corners, then other decks: the geometry
     * is a translation in Y, which is exactly the kind of thing a port gets wrong once and keeps.
     */
    @Test
    public void theProfileSweepPassesItsSelfChecks() throws IOException {
        List<Profile> profiles = sweep();
        for (Profile p : profiles) {
            assertEquals("the builder stands on mainY == lowestY + 1 (HighwayContext.highwayFloorY); " + p.describe(),
                    p.lowestY() + 1, p.mainY());
        }
        Settings settings = TestSettings.fresh();
        Profile before = Profile.of(settings);
        int[] ts = {0, 999999};
        Result result = HighwayConformanceDump.run(settings, profiles, ts, false);
        assertEquals(Collections.emptyList(), result.failures);
        assertEquals(profiles.size() * 8 * 5 * ts.length, result.cases.size());
        assertEquals(before, Profile.of(settings));
        write("nhwdump-profiles.json", COMPACT.toJson(HighwayConformanceDump.document(settings, result)));
    }

    static List<Profile> sweep() {
        List<Profile> profiles = new ArrayList<>();
        for (int width : new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 16}) {
            profiles.add(new Profile(118, 119, width, 4));
        }
        for (int height : new int[]{1, 2, 3, 5, 6, 7, 8, 10}) {
            profiles.add(new Profile(118, 119, 4, height));
        }
        for (int[] wh : new int[][]{{1, 1}, {2, 3}, {3, 2}, {5, 2}, {6, 6}, {7, 5}, {9, 3}, {12, 8}, {16, 10}}) {
            profiles.add(new Profile(118, 119, wh[0], wh[1]));
        }
        profiles.add(new Profile(40, 41, 4, 4));
        profiles.add(new Profile(40, 41, 7, 5));
        profiles.add(new Profile(40, 41, 5, 3));
        profiles.add(new Profile(240, 241, 4, 4));
        profiles.add(new Profile(240, 241, 3, 6));
        return profiles;
    }

    private static boolean contains(List<int[]> targets, int x, int y, int z, int req) {
        return targets.stream().anyMatch(t -> Arrays.equals(t, new int[]{x, y, z, req}));
    }

    private static void write(String name, String json) throws IOException {
        Path dir = Path.of(System.getProperty("nhwdump.dir", "build"));
        Files.createDirectories(dir);
        Path out = dir.resolve(name);
        Files.writeString(out, json);
        System.out.println("wrote " + out.toAbsolutePath());
    }
}
