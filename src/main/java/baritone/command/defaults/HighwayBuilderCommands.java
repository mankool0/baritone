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
import baritone.behavior.highway.NetherHighwayBuilderBehavior;
import net.minecraft.core.Vec3i;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class HighwayBuilderCommands {
    Command netherBuildCommand;
    Command netherStopCommand;
    Command netherStatusCommand;

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
    }

    private static boolean isStartKeyword(String arg) {
        return arg.equalsIgnoreCase("start") || arg.equalsIgnoreCase("--start");
    }
}
