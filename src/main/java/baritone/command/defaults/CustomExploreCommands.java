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

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.RelativeGoalXZ;
import baritone.api.command.exception.CommandException;
import baritone.api.pathing.goals.GoalXZ;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class CustomExploreCommands {
    Command customExploreStart;
    Command customExploreStop;

    public CustomExploreCommands(IBaritone baritone) {

        customExploreStart = new Command(baritone, "customexplore") {
            @Override
            public void execute(String label, IArgConsumer args) throws CommandException {
                args.requireExactly(5);

                int pointsDist = args.getAsOrDefault(Integer.class, 16);

                GoalXZ start = args.hasAny()
                        ? args.getDatatypePost(RelativeGoalXZ.INSTANCE, ctx.playerFeet())
                        : new GoalXZ(ctx.playerFeet());

                GoalXZ end = args.hasAny()
                        ? args.getDatatypePost(RelativeGoalXZ.INSTANCE, ctx.playerFeet())
                        : new GoalXZ(ctx.playerFeet());


                baritone.getCustomExploreBehavior().explore(pointsDist, start.getX(), start.getZ(), end.getX(), end.getZ());
                logDirect(String.format("Exploring from %s to %s", start, end));
            }

            @Override
            public Stream<String> tabComplete(String label, IArgConsumer args) {
                return Stream.empty();
            }

            @Override
            public String getShortDesc() {
                return "Explore a square";
            }

            @Override
            public List<String> getLongDesc() {
                return Arrays.asList(
                        "Tell Baritone to explore a big ass square.",
                        "",
                        "Usage:",
                        "> customexplore <pointsDist> <x1> <z1> <x2> <z2> - Explore from the specified X and Z position to specified X and Z position where pointsDist is distance between explore points"
                );
            }
        };

        customExploreStop = new Command(baritone, "customexplorestop") {
            @Override
            public void execute(String label, IArgConsumer args) throws CommandException {
                args.requireMax(0);
                logDirect("Stopping custom explore");
                baritone.getCustomExploreBehavior().stop();
            }

            @Override
            public Stream<String> tabComplete(String label, IArgConsumer args) {
                return Stream.empty();
            }

            @Override
            public String getShortDesc() {
                return "Stops the custom explorer";
            }

            @Override
            public List<String> getLongDesc() {
                return Arrays.asList(
                        "Tell Baritone to stop exploring",
                        "",
                        "Usage:",
                        "> customexplorestop - Stop the exploring"
                );
            }
        };
    }
}
