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

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class MapBuilderCommand {

    Command startCommand;
    Command stopCommand;

    public MapBuilderCommand(IBaritone baritone) {
        startCommand = new Command(baritone, "mapbuild") {
            @Override
            public void execute(String label, IArgConsumer args) {
                baritone.getMapBuilderBehavior().build();
            }

            @Override
            public Stream<String> tabComplete(String label, IArgConsumer args) {
                return Stream.empty();
            }

            @Override
            public String getShortDesc() {
                return "Auto builds your maps for you :)";
            }

            @Override
            public List<String> getLongDesc() {
                return Arrays.asList(
                        "Auto builds your maps for you :)",
                        "",
                        "Usage:",
                        "> mapbuild - Start the auto building"
                );
            }
        };

        stopCommand = new Command(baritone, "mapStop") {
            @Override
            public void execute(String label, IArgConsumer args) {
                baritone.getMapBuilderBehavior().stop();
            }

            @Override
            public Stream<String> tabComplete(String label, IArgConsumer args) {
                return Stream.empty();
            }

            @Override
            public String getShortDesc() {
                return "Stops building";
            }

            @Override
            public List<String> getLongDesc() {
                return Arrays.asList(
                        "Stops building",
                        "",
                        "Usage:",
                        "> mapStop - Stop the auto building"
                );
            }
        };
    }
}
