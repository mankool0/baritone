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

package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.schematic.CompositeSchematic;
import baritone.api.schematic.ISchematic;
import baritone.api.schematic.WhiteBlackSchematic;
import baritone.behavior.highway.NetherHighwayBuilderBehavior;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class HighwayBuilderCommands {
    Command netherBuildCommand;
    Command netherStopCommand;
    Command netherStatusCommand;
    Command netherDumpCommand;

    public HighwayBuilderCommands(IBaritone baritone) {

        netherBuildCommand = new Command(baritone, "nhwbuild") {
            @Override
            public void execute(String label, IArgConsumer args) throws CommandException {
                args.requireMin(2);

                int xDir = args.getAsOrDefault(Integer.class, -69);
                int zDir = args.getAsOrDefault(Integer.class, -69);
                if (Math.abs(xDir) > 1 || Math.abs(zDir) > 1 || (xDir == 0 && zDir == 0)) {
                    logDirect("Oopsie, screwed up direction");
                    return;
                }
                logDirect("Using direction " + xDir + " " + zDir);


                boolean doPaving = false;
                if (args.hasAny())
                    doPaving = args.getAsOrDefault(Boolean.class, false);
                logDirect("doPaving: " + doPaving);

                boolean selfSolve = true;
                if (args.hasAny())
                    selfSolve = args.getAsOrDefault(Boolean.class, true);
                logDirect("selfSolve: " + selfSolve);

                int origX = -69;
                int origZ = -69;
                if (!selfSolve) {
                    // getAsOrDefault leaves a non-integer token in place, so a missing pair
                    // used to fall through as (-69, -69) and anchor the road there.
                    if (!args.has(2) || isStartKeyword(args.peekString())) {
                        logDirect("selfSolve=false needs <origX> <origZ> before the end coordinates");
                        return;
                    }
                    origX = args.getAs(Integer.class);
                    origZ = args.getAs(Integer.class);
                }
                logDirect("origX: " + origX + " origZ: " + origZ);

                Vec3i endCoords = null;
                if (args.has(2) && !isStartKeyword(args.peekString())) {
                    int endX = args.getAs(Integer.class);
                    int endZ = args.getAs(Integer.class);
                    endCoords = new Vec3i(endX, 0, endZ);
                    logDirect("End: " + endX + ", " + endZ);
                }

                Vec3i startCoords = null;
                if (args.hasAny() && isStartKeyword(args.peekString())) {
                    args.get(); // the keyword
                    int buildStartX = args.getAs(Integer.class);
                    int buildStartZ = args.getAs(Integer.class);
                    startCoords = new Vec3i(buildStartX, 0, buildStartZ);
                    logDirect("Start: " + buildStartX + ", " + buildStartZ);
                }
                if (args.hasAny()) {
                    logDirect("Ignoring leftover arguments (end coords need both <endX> <endZ>)");
                }

                logDirect("Calculating build location");

                baritone.getNetherHighwayBuilderBehavior().build(origX, origZ, new Vec3i(xDir, 0, zDir), selfSolve, doPaving, endCoords, startCoords);
            }

            @Override
            public Stream<String> tabComplete(String label, IArgConsumer args) {
                return Stream.empty();
            }

            @Override
            public String getShortDesc() {
                return "Builds NE Nether Highway";
            }

            @Override
            public List<String> getLongDesc() {
                return Arrays.asList(
                        "Tell Baritone to build a Nether Highway",
                        "",
                        "Usage:",
                        "> nhwbuild - <dirX> <dirZ> - Start building in specified direction. Ex. `1 0` will build +X highway",
                        "> nhwbuild - <dirX> <dirZ> <pave> - Where pave is true if you want to pave with obsidian. Default is false",
                        "> nhwbuild - <dirX> <dirZ> <pave> <selfSolve> <origX> <origZ> - Where selfSolve is false if you want a custom origin. Default is true",
                        "> nhwbuild - <dirX> <dirZ> <pave> <selfSolve> [<origX> <origZ>] <endX> <endZ> - Stop once the highway is built through the end coords",
                        "> nhwbuild - ... start <startX> <startZ> - Begin the build at the named point on the highway line instead of at your feet"
                );
            }
        };

        netherStopCommand = new Command(baritone, "nhwstop") {
            @Override
            public void execute(String label, IArgConsumer args) throws CommandException {
                args.requireMax(0);
                logDirect("Stopping the nether highway builder");
                baritone.getNetherHighwayBuilderBehavior().stop();
            }

            @Override
            public Stream<String> tabComplete(String label, IArgConsumer args) {
                return Stream.empty();
            }

            @Override
            public String getShortDesc() {
                return "Stops the nether highway builder";
            }

            @Override
            public List<String> getLongDesc() {
                return Arrays.asList(
                        "Tell Baritone to stop building the nether highway",
                        "",
                        "Usage:",
                        "> nhwstop - Stop the building"
                );
            }
        };

        netherStatusCommand = new Command(baritone, "nhwstatus") {
            @Override
            public void execute(String label, IArgConsumer args) throws CommandException {
                args.requireMax(0);
                baritone.getNetherHighwayBuilderBehavior().printStatus();
            }

            @Override
            public Stream<String> tabComplete(String label, IArgConsumer args) {
                return Stream.empty();
            }

            @Override
            public String getShortDesc() {
                return "Prints nether highway builder status";
            }

            @Override
            public List<String> getLongDesc() {
                return Arrays.asList(
                        "Print the nether highway builder status",
                        "",
                        "Usage:",
                        "> nhwstatus - Print out the status"
                );
            }
        };

        netherDumpCommand = new Command(baritone, "nhwdump") {
            @Override
            public void execute(String label, IArgConsumer args) throws CommandException {
                Settings settings = BaritoneAPI.getSettings();

                if (args.hasAny()) {
                    args.requireExactly(3);
                    int dirX = args.getAs(Integer.class);
                    int dirZ = args.getAs(Integer.class);
                    int t = args.getAs(Integer.class);
                    Vec3i direction = new Vec3i(dirX, 0, dirZ);
                    if (!NetherHighwayBuilderBehavior.isGroupA(direction) && !NetherHighwayBuilderBehavior.isGroupB(direction)) {
                        logDirect("Not a highway direction: " + dirX + " " + dirZ);
                        return;
                    }
                    // pave and dig project identically (the bulk dump asserts it), so pave is arbitrary here
                    CompositeSchematic schem = NetherHighwayBuilderBehavior.composeHighwaySchematic(direction, true, settings);
                    Vec3 origin = NetherHighwayBuilderBehavior.canonicalOriginVector(direction, settings);
                    int ay = settings.highwayLowestY.value;
                    int ax = (int) Math.round(origin.x) + t * dirX;
                    int az = (int) Math.round(origin.z) + t * dirZ;
                    List<int[]> targets = projectSlice(schem, ax, ay, az);
                    logDirect("Slice t=" + t + " of direction " + dirX + " " + dirZ + " anchored at " + ax + ", " + ay + ", " + az
                            + " (width=" + settings.highwayWidth.value + " rail=" + settings.highwayRail.value
                            + " railLow=" + settings.highwayRailLow.value + " railHigh=" + settings.highwayRailHigh.value + "): " + targets.size() + " targets");
                    for (int[] target : targets) {
                        logDirect("[" + target[0] + ", " + target[1] + ", " + target[2] + ", " + requirementName(target[3]) + "]");
                    }
                    return;
                }

                int savedWidth = settings.highwayWidth.value;
                boolean savedRail = settings.highwayRail.value;
                boolean savedLow = settings.highwayRailLow.value;
                boolean savedHigh = settings.highwayRailHigh.value;
                int height = settings.highwayHeight.value;

                int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
                // {highwayRail, highwayRailLow, highwayRailHigh}: the four per-side combos of a
                // railed road (a disabled side asserts its column EMPTY), plus the railless road,
                // now the only case that puts nothing in the rail columns
                boolean[][] railCombos = {{true, true, true}, {true, true, false}, {true, false, true}, {true, false, false}, {false, true, true}};
                int[] widths = {4, 7};
                int[] ts = {0, 1, 1000, 999999};

                JsonArray casesJson = new JsonArray();
                Map<String, List<int[]>> targetsByCase = new HashMap<>();
                Map<String, int[]> anchorsByCase = new HashMap<>();

                try {
                    for (int width : widths) {
                        settings.highwayWidth.value = width;
                        for (int[] dir : directions) {
                            Vec3i direction = new Vec3i(dir[0], 0, dir[1]);
                            boolean groupA = NetherHighwayBuilderBehavior.isGroupA(direction);
                            for (boolean[] combo : railCombos) {
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
                                        List<int[]> targets = projectSlice(schem, ax, ay, az);
                                        targetsByCase.put(caseKey(width, dir, combo, pave, t), targets);
                                        anchorsByCase.put(caseKey(width, dir, combo, pave, t), new int[]{ax, ay, az});

                                        JsonObject caseJson = new JsonObject();
                                        JsonArray dirJson = new JsonArray();
                                        dirJson.add(dir[0]);
                                        dirJson.add(dir[1]);
                                        caseJson.add("direction", dirJson);
                                        caseJson.addProperty("group", groupA ? "A" : "B");
                                        caseJson.addProperty("rail", combo[0]);
                                        caseJson.addProperty("rail_low", combo[1]);
                                        caseJson.addProperty("rail_high", combo[2]);
                                        caseJson.addProperty("width", width);
                                        caseJson.addProperty("pave", pave);
                                        caseJson.addProperty("t", t);
                                        JsonArray anchorJson = new JsonArray();
                                        anchorJson.add(ax);
                                        anchorJson.add(ay);
                                        anchorJson.add(az);
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
                                        casesJson.add(caseJson);
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    settings.highwayWidth.value = savedWidth;
                    settings.highwayRail.value = savedRail;
                    settings.highwayRailLow.value = savedLow;
                    settings.highwayRailHigh.value = savedHigh;
                }

                List<String> failures = selfCheck(targetsByCase, anchorsByCase, directions, railCombos, widths, ts, height);

                JsonObject root = new JsonObject();
                root.addProperty("source", "mankool0/baritone @ 1.21.4-highway");
                JsonObject settingsJson = new JsonObject();
                settingsJson.addProperty("lowest_y", settings.highwayLowestY.value);
                settingsJson.addProperty("main_y", settings.highwayMainY.value);
                settingsJson.addProperty("width", savedWidth);
                settingsJson.addProperty("height", height);
                settingsJson.addProperty("rail", savedRail);
                settingsJson.addProperty("rail_low", savedLow);
                settingsJson.addProperty("rail_high", savedHigh);
                root.add("settings", settingsJson);
                root.add("cases", casesJson);

                Path dumpPath = ((Baritone) baritone).getDirectory().resolve("nhwdump.json");
                try {
                    Files.writeString(dumpPath, new GsonBuilder().setPrettyPrinting().create().toJson(root));
                } catch (IOException e) {
                    logDirect("Failed to write " + dumpPath + ": " + e);
                    return;
                }

                for (String failure : failures) {
                    logDirect("SELF-CHECK FAILED: " + failure);
                }
                logDirect("Wrote " + casesJson.size() + " cases to " + dumpPath
                        + (failures.isEmpty() ? " (self-checks passed)" : " (" + failures.size() + " SELF-CHECK FAILURES)"));
            }

            @Override
            public Stream<String> tabComplete(String label, IArgConsumer args) {
                return Stream.empty();
            }

            @Override
            public String getShortDesc() {
                return "Dumps the highway schematic the builder targets";
            }

            @Override
            public List<String> getLongDesc() {
                return Arrays.asList(
                        "Dump the positions and blocks the highway builder would target, for conformance-checking",
                        "the coordination server's independent implementation of the same geometry",
                        "",
                        "Usage:",
                        "> nhwdump - Write the full case matrix to baritone/nhwdump.json (the CI artifact)",
                        "> nhwdump <dirX> <dirZ> <t> - Print slice t to chat using the current settings"
                );
            }
        };
    }

    private static boolean isStartKeyword(String arg) {
        return arg.equalsIgnoreCase("start") || arg.equalsIgnoreCase("--start");
    }

    private static String requirementName(int encoded) {
        return encoded == 1 ? "OBSIDIAN" : "AIR";
    }

    private static String caseKey(int width, int[] dir, boolean[] combo, boolean pave, int t) {
        return width + "|" + dir[0] + "|" + dir[1] + "|" + combo[0] + "|" + combo[1] + "|" + combo[2] + "|" + pave + "|" + t;
    }

    /**
     * Project one slice of the composed schematic onto the server's model: every position with a
     * server requirement, as {x, y, z, 1=OBSIDIAN/0=AIR}, sorted ascending by (x, y, z).
     * Scaffolding (blacklist schematics) projects to nothing.
     */
    private static List<int[]> projectSlice(CompositeSchematic schem, int ax, int ay, int az) {
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
        targets.sort(Comparator.comparingInt((int[] a) -> a[0]).thenComparingInt(a -> a[1]).thenComparingInt(a -> a[2]));
        return targets;
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

    private static List<String> selfCheck(Map<String, List<int[]>> targetsByCase, Map<String, int[]> anchorsByCase,
                                          int[][] directions, boolean[][] railCombos, int[] widths, int[] ts, int height) {
        List<String> failures = new ArrayList<>();
        for (int width : widths) {
            for (int[] dir : directions) {
                boolean groupA = NetherHighwayBuilderBehavior.isGroupA(new Vec3i(dir[0], 0, dir[1]));
                for (boolean[] combo : railCombos) {
                    boolean rails = combo[0];
                    int nRails = rails ? (combo[1] ? 1 : 0) + (combo[2] ? 1 : 0) : 0;
                    for (int t : ts) {
                        String where = "dir " + dir[0] + " " + dir[1] + " rail=" + combo[0] + " railLow=" + combo[1] + " railHigh=" + combo[2] + " width=" + width + " t=" + t;
                        List<int[]> paveTargets = targetsByCase.get(caseKey(width, dir, combo, true, t));
                        List<int[]> digTargets = targetsByCase.get(caseKey(width, dir, combo, false, t));

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
                            int[] anchor = anchorsByCase.get(caseKey(width, dir, combo, true, t));
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
                            List<int[]> mirrorCase = targetsByCase.get(caseKey(width, dir, new boolean[]{true, false, true}, true, t));
                            int[] anchor = anchorsByCase.get(caseKey(width, dir, combo, true, t));
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
                    }
                }
            }
        }

        // 3. group A and group B are transposes: (1,0) and (0,1) give the same shape with X and Z swapped
        for (int width : widths) {
            for (boolean[] combo : railCombos) {
                for (int t : ts) {
                    List<int[]> alongX = targetsByCase.get(caseKey(width, new int[]{1, 0}, combo, true, t));
                    List<int[]> alongZ = targetsByCase.get(caseKey(width, new int[]{0, 1}, combo, true, t));
                    List<int[]> transposed = new ArrayList<>();
                    for (int[] target : alongX) {
                        transposed.add(new int[]{target[2], target[1], target[0], target[3]});
                    }
                    if (!targetsEqual(sorted(transposed), alongZ)) {
                        failures.add("(1,0) transposed is not (0,1) at rail=" + combo[0] + " railLow=" + combo[1] + " railHigh=" + combo[2] + " width=" + width + " t=" + t);
                    }
                }
            }
        }
        return failures;
    }
}