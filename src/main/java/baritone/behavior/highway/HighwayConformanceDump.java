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
import baritone.api.schematic.CompositeSchematic;
import baritone.api.schematic.ISchematic;
import baritone.api.schematic.WhiteBlackSchematic;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The conformance dump: every position the highway builder would target, projected onto the
 * coordination server's model (OBSIDIAN, AIR, or nothing for scaffolding), over a matrix of
 * directions, rail configurations and slices at one or more road profiles. The server's own
 * compiler is pinned to this output, so the two implementations of the geometry are diffed rather
 * than trusted.
 */
public final class HighwayConformanceDump {

    public static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /**
     * {highwayRail, highwayRailLow, highwayRailHigh}: the four per-side combos of a railed road (a
     * disabled side asserts its column EMPTY), plus the railless road, the only case that puts
     * nothing in the rail columns.
     */
    public static final boolean[][] RAIL_COMBOS = {{true, true, true}, {true, true, false}, {true, false, true}, {true, false, false}, {false, true, true}};

    /** The two widths in service: stock, and the one the diagonals run at. */
    public static final int[] DEFAULT_WIDTHS = {4, 7};

    /** Slices far enough apart that a t-dependent placement bug cannot hide between them. */
    public static final int[] DEFAULT_TS = {0, 1, 1000, 999999};

    private HighwayConformanceDump() {}

    /**
     * The four integers that shape a road. The schematic never reads mainY (the floor is
     * lowestY + 1 by construction) but the rest of the builder stands on it, so it is recorded to
     * keep the artifact self-describing.
     */
    public record Profile(int lowestY, int mainY, int width, int height) {

        public static Profile of(Settings settings) {
            return new Profile(settings.highwayLowestY.value, settings.highwayMainY.value, settings.highwayWidth.value, settings.highwayHeight.value);
        }

        public Profile withWidth(int newWidth) {
            return new Profile(lowestY, mainY, newWidth, height);
        }

        public String describe() {
            return "width=" + width + " height=" + height + " lowestY=" + lowestY + " mainY=" + mainY;
        }
    }

    public static final class Result {
        public final JsonArray cases = new JsonArray();
        public final List<String> failures = new ArrayList<>();
        public boolean digEmitted;
    }

    /**
     * Run the matrix at each profile in turn. {@code settings} is set to each profile and restored
     * before returning. With {@code emitDig} the dig-mode projection is written as its own case
     * ({@code pave=false}), the shape the live-client dumps had; without it the dig projection is
     * still computed and held equal to pave by the self-check, but only the pave case is written,
     * which halves the artifact for the same guarantee.
     */
    public static Result run(Settings settings, List<Profile> profiles, int[] ts, boolean emitDig) {
        Saved saved = new Saved(settings);
        Result result = new Result();
        result.digEmitted = emitDig;
        try {
            for (Profile profile : profiles) {
                settings.highwayLowestY.value = profile.lowestY();
                settings.highwayMainY.value = profile.mainY();
                settings.highwayWidth.value = profile.width();
                settings.highwayHeight.value = profile.height();

                Map<String, List<int[]>> targetsByCase = new HashMap<>();
                Map<String, int[]> anchorsByCase = new HashMap<>();
                for (int[] dir : DIRECTIONS) {
                    Vec3i direction = new Vec3i(dir[0], 0, dir[1]);
                    boolean groupA = NetherHighwayBuilderBehavior.isGroupA(direction);
                    for (boolean[] combo : RAIL_COMBOS) {
                        settings.highwayRail.value = combo[0];
                        settings.highwayRailLow.value = combo[1];
                        settings.highwayRailHigh.value = combo[2];
                        for (boolean pave : new boolean[]{true, false}) {
                            CompositeSchematic schem = NetherHighwayBuilderBehavior.composeHighwaySchematic(direction, pave, settings);
                            Vec3 origin = NetherHighwayBuilderBehavior.canonicalOriginVector(direction, settings);
                            int ox = (int) Math.round(origin.x);
                            int oz = (int) Math.round(origin.z);
                            int ay = settings.highwayLowestY.value;
                            for (int t : ts) {
                                int ax = ox + t * dir[0];
                                int az = oz + t * dir[1];
                                int[] anchor = {ax, ay, az};
                                List<int[]> targets = projectSlice(schem, ax, ay, az);
                                String key = caseKey(dir, combo, pave, t);
                                targetsByCase.put(key, targets);
                                anchorsByCase.put(key, anchor);
                                if (pave || emitDig) {
                                    result.cases.add(caseJson(profile, dir, groupA, combo, pave, t, anchor, targets));
                                }
                            }
                        }
                    }
                }
                result.failures.addAll(selfCheck(targetsByCase, anchorsByCase, profile, ts));
            }
        } finally {
            saved.restore(settings);
        }
        return result;
    }

    /** The artifact: the (restored) settings the dump was taken under, and every case. */
    public static JsonObject document(Settings settings, Result result) {
        JsonObject root = new JsonObject();
        root.addProperty("source", "mankool0/baritone @ 1.21.4-highway");
        JsonObject settingsJson = new JsonObject();
        settingsJson.addProperty("lowest_y", settings.highwayLowestY.value);
        settingsJson.addProperty("main_y", settings.highwayMainY.value);
        settingsJson.addProperty("width", settings.highwayWidth.value);
        settingsJson.addProperty("height", settings.highwayHeight.value);
        settingsJson.addProperty("rail", settings.highwayRail.value);
        settingsJson.addProperty("rail_low", settings.highwayRailLow.value);
        settingsJson.addProperty("rail_high", settings.highwayRailHigh.value);
        root.add("settings", settingsJson);
        root.addProperty("dig_cases_emitted", result.digEmitted);
        root.add("cases", result.cases);
        return root;
    }

    /**
     * Project one slice of the composed schematic onto the server's model: every position with a
     * server requirement, as {x, y, z, 1=OBSIDIAN/0=AIR}, sorted ascending by (x, y, z).
     * Scaffolding (blacklist schematics) projects to nothing.
     */
    public static List<int[]> projectSlice(CompositeSchematic schem, int ax, int ay, int az) {
        List<int[]> targets = new ArrayList<>();
        for (int y = 0; y < schem.heightY(); y++) {
            for (int z = 0; z < schem.lengthZ(); z++) {
                for (int x = 0; x < schem.widthX(); x++) {
                    if (!schem.inSchematic(x, y, z, null)) {
                        continue;
                    }
                    ISchematic sub = schem.getSchematic(x, y, z, null).schematic;
                    if (!(sub instanceof WhiteBlackSchematic wb)) {
                        continue;
                    }
                    WhiteBlackSchematic.ServerRequirement req = wb.serverRequirement();
                    if (req == WhiteBlackSchematic.ServerRequirement.IGNORE) {
                        continue;
                    }
                    targets.add(new int[]{ax + x, ay + y, az + z, req == WhiteBlackSchematic.ServerRequirement.OBSIDIAN ? 1 : 0});
                }
            }
        }
        return sorted(targets);
    }

    public static String requirementName(int encoded) {
        return encoded == 1 ? "OBSIDIAN" : "AIR";
    }

    private static JsonObject caseJson(Profile profile, int[] dir, boolean groupA, boolean[] combo, boolean pave, int t, int[] anchor, List<int[]> targets) {
        JsonObject caseJson = new JsonObject();
        JsonArray dirJson = new JsonArray();
        dirJson.add(dir[0]);
        dirJson.add(dir[1]);
        caseJson.add("direction", dirJson);
        caseJson.addProperty("group", groupA ? "A" : "B");
        caseJson.addProperty("rail", combo[0]);
        caseJson.addProperty("rail_low", combo[1]);
        caseJson.addProperty("rail_high", combo[2]);
        caseJson.addProperty("width", profile.width());
        caseJson.addProperty("height", profile.height());
        caseJson.addProperty("lowest_y", profile.lowestY());
        caseJson.addProperty("main_y", profile.mainY());
        caseJson.addProperty("pave", pave);
        caseJson.addProperty("t", t);
        JsonArray anchorJson = new JsonArray();
        anchorJson.add(anchor[0]);
        anchorJson.add(anchor[1]);
        anchorJson.add(anchor[2]);
        caseJson.add("anchor", anchorJson);
        JsonArray targetsJson = new JsonArray();
        for (int[] target : targets) {
            JsonArray row = new JsonArray();
            row.add(target[0]);
            row.add(target[1]);
            row.add(target[2]);
            row.add(requirementName(target[3]));
            targetsJson.add(row);
        }
        caseJson.add("targets", targetsJson);
        return caseJson;
    }

    private static String caseKey(int[] dir, boolean[] combo, boolean pave, int t) {
        return dir[0] + "|" + dir[1] + "|" + combo[0] + "|" + combo[1] + "|" + combo[2] + "|" + pave + "|" + t;
    }

    private static boolean targetsEqual(List<int[]> a, List<int[]> b) {
        if (a == null || b == null || a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!Arrays.equals(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static List<int[]> sorted(List<int[]> targets) {
        List<int[]> copy = new ArrayList<>(targets);
        copy.sort(Comparator.comparingInt((int[] a) -> a[0]).thenComparingInt(a -> a[1]).thenComparingInt(a -> a[2]));
        return copy;
    }

    /**
     * Properties of the geometry that hold at every profile, checked against the dump itself so a
     * broken projection cannot produce a plausible-looking artifact.
     */
    private static List<String> selfCheck(Map<String, List<int[]>> targetsByCase, Map<String, int[]> anchorsByCase, Profile profile, int[] ts) {
        List<String> failures = new ArrayList<>();
        int width = profile.width();
        int height = profile.height();
        for (int[] dir : DIRECTIONS) {
            boolean groupA = NetherHighwayBuilderBehavior.isGroupA(new Vec3i(dir[0], 0, dir[1]));
            for (boolean[] combo : RAIL_COMBOS) {
                boolean rails = combo[0];
                int nRails = rails ? (combo[1] ? 1 : 0) + (combo[2] ? 1 : 0) : 0;
                for (int t : ts) {
                    String where = "dir " + dir[0] + " " + dir[1] + " rail=" + combo[0] + " railLow=" + combo[1] + " railHigh=" + combo[2] + " " + profile.describe() + " t=" + t;
                    List<int[]> paveTargets = targetsByCase.get(caseKey(dir, combo, true, t));
                    List<int[]> digTargets = targetsByCase.get(caseKey(dir, combo, false, t));
                    int[] anchor = anchorsByCase.get(caseKey(dir, combo, true, t));

                    // 1. the geometry is mode-independent: only the allowed sets differ between
                    // pave and dig, and the projection collapses that difference
                    if (!targetsEqual(paveTargets, digTargets)) {
                        failures.add("pave and dig disagree at " + where);
                    }

                    // 2. cell counts. Every railed combo covers the SAME cells - a disabled
                    // side requires its rail column empty rather than dropping it from the
                    // model - so the total is constant and only the two rail cells flip
                    // between OBSIDIAN and AIR. This fails if the clear went to the wrong sy,
                    // is the wrong height, or was gated on the wrong put argument. A railless
                    // road doesn't model the columns at all.
                    long obsidian = paveTargets.stream().filter(target -> target[3] == 1).count();
                    long air = paveTargets.size() - obsidian;
                    long expectedTotal = (long) height * width + (rails ? 6 : 0);
                    long expectedObsidian = width + nRails;
                    if (paveTargets.size() != expectedTotal || obsidian != expectedObsidian) {
                        failures.add("cell counts at " + where + ": got " + obsidian + " obsidian + " + air + " air, expected "
                                + expectedObsidian + " + " + (expectedTotal - expectedObsidian));
                    }

                    // 3. rail=false must emit nothing in either rail column (cross offsets 0
                    // and width+1) - after the empty-column change it is the only case that
                    // leaves them out of the model
                    if (!rails) {
                        for (int[] target : paveTargets) {
                            int cross = groupA ? target[2] - anchor[2] : target[0] - anchor[0];
                            if (cross == 0 || cross == width + 1) {
                                failures.add("rail=false emits a rail-column cell at " + where);
                                break;
                            }
                        }
                    }

                    // 4. (T,F) and (F,T) must be mirror images across the corridor centre, and differ
                    if (combo[0] && combo[1] && !combo[2]) {
                        List<int[]> mirrorCase = targetsByCase.get(caseKey(dir, new boolean[]{true, false, true}, true, t));
                        if (targetsEqual(paveTargets, mirrorCase)) {
                            failures.add("(T,F) and (F,T) are identical at " + where + " - a side gate is not being applied");
                        }
                        List<int[]> mirrored = new ArrayList<>();
                        for (int[] target : paveTargets) {
                            if (groupA) {
                                mirrored.add(new int[]{target[0], target[1], 2 * anchor[2] + width + 1 - target[2], target[3]});
                            } else {
                                mirrored.add(new int[]{2 * anchor[0] + width + 1 - target[0], target[1], target[2], target[3]});
                            }
                        }
                        if (!targetsEqual(sorted(mirrored), mirrorCase)) {
                            failures.add("(T,F) mirrored is not (F,T) at " + where + " - a side gate is on the wrong put argument");
                        }
                    }

                    // 5. the Y placement the rest of the builder stands on: the floor is one
                    // above lowestY (highwayFloorY, computeRecoveryTarget) and a rail one above
                    // that. A profile whose mainY is not lowestY + 1 builds one road and walks
                    // another, which is why the server refuses such a profile outright.
                    for (int[] target : paveTargets) {
                        if (target[3] != 1) {
                            continue;
                        }
                        int cross = groupA ? target[2] - anchor[2] : target[0] - anchor[0];
                        int expectedY = profile.lowestY() + (cross == 0 || cross == width + 1 ? 2 : 1);
                        if (target[1] != expectedY) {
                            failures.add("obsidian at y=" + target[1] + " rather than " + expectedY + " at " + where);
                            break;
                        }
                    }
                }
            }
        }

        // 6. group A and group B are transposes: (1,0) and (0,1) give the same shape with X and Z swapped
        for (boolean[] combo : RAIL_COMBOS) {
            for (int t : ts) {
                List<int[]> alongX = targetsByCase.get(caseKey(new int[]{1, 0}, combo, true, t));
                List<int[]> alongZ = targetsByCase.get(caseKey(new int[]{0, 1}, combo, true, t));
                List<int[]> transposed = new ArrayList<>();
                for (int[] target : alongX) {
                    transposed.add(new int[]{target[2], target[1], target[0], target[3]});
                }
                if (!targetsEqual(sorted(transposed), alongZ)) {
                    failures.add("(1,0) transposed is not (0,1) at rail=" + combo[0] + " railLow=" + combo[1] + " railHigh=" + combo[2] + " " + profile.describe() + " t=" + t);
                }
            }
        }
        return failures;
    }

    /** The settings the matrix overwrites, so the caller gets them back whatever happens. */
    private static final class Saved {
        private final int lowestY, mainY, width, height;
        private final boolean rail, railLow, railHigh;

        Saved(Settings s) {
            lowestY = s.highwayLowestY.value;
            mainY = s.highwayMainY.value;
            width = s.highwayWidth.value;
            height = s.highwayHeight.value;
            rail = s.highwayRail.value;
            railLow = s.highwayRailLow.value;
            railHigh = s.highwayRailHigh.value;
        }

        void restore(Settings s) {
            s.highwayLowestY.value = lowestY;
            s.highwayMainY.value = mainY;
            s.highwayWidth.value = width;
            s.highwayHeight.value = height;
            s.highwayRail.value = rail;
            s.highwayRailLow.value = railLow;
            s.highwayRailHigh.value = railHigh;
        }
    }
}
