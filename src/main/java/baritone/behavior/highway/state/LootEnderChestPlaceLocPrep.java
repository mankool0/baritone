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

package baritone.behavior.highway.state;

import baritone.api.utils.Helper;
import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import baritone.behavior.highway.enums.LocationType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class LootEnderChestPlaceLocPrep extends State {
    public LootEnderChestPlaceLocPrep(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (context.getItemCountInventory(Item.getId(Blocks.ENDER_CHEST.asItem())) == 0) {
            Helper.HELPER.logDirect("No ender chests, pausing");
            context.baritone().getPathingBehavior().cancelEverything();
            context.setPaused(true);
            return;
        }

        Vec3 curPos = new Vec3(context.playerContext().playerFeet().getX() + (7 * -context.highwayDirection().getX()), context.playerContext().playerFeet().getY(), context.playerContext().playerFeet().getZ() + (7 * -context.highwayDirection().getZ())); // Go back a bit just in case
        Vec3 direction = new Vec3(context.highwayDirection().getX(), context.highwayDirection().getY(), context.highwayDirection().getZ());

        context.setPlaceLoc(context.getClosestPoint(new Vec3(context.eChestEmptyShulkOriginVector().x, context.eChestEmptyShulkOriginVector().y, context.eChestEmptyShulkOriginVector().z), direction, curPos, LocationType.SideStorage));

        context.transitionTo(HighwayState.GoingToLootEnderChestPlaceLoc);
    }
}
