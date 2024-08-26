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

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.behavior.INetherHighwayBuilderBehavior;
import baritone.api.event.events.RenderEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.pathing.goals.*;
import baritone.api.schematic.CompositeSchematic;
import baritone.api.schematic.FillSchematic;
import baritone.api.schematic.ISchematic;
import baritone.api.schematic.WhiteBlackSchematic;
import baritone.api.utils.*;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.behavior.Behavior;
import baritone.behavior.highway.enums.HighwayState;
import baritone.behavior.highway.enums.LocationType;
import baritone.behavior.highway.enums.ShulkerType;
import baritone.behavior.highway.state.BuildingHighway;
import baritone.pathing.movement.MovementHelper;
import baritone.process.BuilderProcess;
import baritone.utils.BlockStateInterface;
import baritone.utils.IRenderer;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.awt.*;
import java.util.List;
import java.util.*;

import static baritone.pathing.movement.Movement.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP;

public final class NetherHighwayBuilderBehavior extends Behavior implements INetherHighwayBuilderBehavior, IRenderer {

    private final HighwayContext highwayContext;


    public NetherHighwayBuilderBehavior(Baritone baritone) {
        super(baritone);
        this.highwayContext = new HighwayContext(baritone);
    }

    @Override
    public boolean isBuildingHighwayState() {
        return highwayContext.currentState().getState() == HighwayState.BuildingHighway;
    }

    @Override
    public void build(int startX, int startZ, Vec3i direct, boolean selfSolve, boolean pave) {
        highwayContext.setHighwayDirection(direct);
        highwayContext.setPaving(pave);
        highwayContext.setCachedHealth(ctx.player().getHealth());

        if (!highwayContext.paving()) {
            // Only digging so any pickaxe works
            highwayContext.setPicksToUse(ShulkerType.AnyPickaxe);
            highwayContext.setPicksToHave(settings.highwayPicksToHaveDigging.value);
        } else {
            // If paving then we mine echests and need non-silk picks
            highwayContext.setPicksToUse(ShulkerType.NonSilkPickaxe);
            highwayContext.setPicksToHave(settings.highwayPicksToHavePaving.value);
        }

        int highwayWidth = settings.highwayWidth.value;
        int highwayHeight = settings.highwayHeight.value;
        int supportWidth = settings.highwaySupportWidth.value;
        int supportOffset = settings.highwaySupportOffset.value;
        boolean highwayRail = settings.highwayRail.value;
        boolean diag = Math.abs(highwayContext.highwayDirection().getX()) == Math.abs(highwayContext.highwayDirection().getZ()) && Math.abs(highwayContext.highwayDirection().getZ()) == 1;
        int highwayWidthOffset = -((Math.round(highwayWidth / 2.0f)) + 1);
        int highwayWidthLiqOffset = -(Math.round(highwayWidth / 2.0f)) - 1;
        int highwayWidthLiqOffsetRail = -(Math.round(highwayWidth / 2.0f)) - 2;

        ISchematic obsidSchemBot;
        WhiteBlackSchematic topAir;
        WhiteBlackSchematic noLavaBotSides = new WhiteBlackSchematic(1, 1, 1, Collections.singletonList(Blocks.LAVA.defaultBlockState()), Blocks.NETHERRACK.defaultBlockState(), false, true, true);
        WhiteBlackSchematic supportNetherRack;
        WhiteBlackSchematic sideRailSupport;
        ISchematic sideRail;
        if (pave)
            sideRail = new WhiteBlackSchematic(1, 1, 1, Arrays.asList(Blocks.OBSIDIAN.defaultBlockState(), Blocks.CRYING_OBSIDIAN.defaultBlockState()), Blocks.OBSIDIAN.defaultBlockState(), true, false, false);
        else
            sideRail = new WhiteBlackSchematic(1, 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.OBSIDIAN.defaultBlockState(), Blocks.CRYING_OBSIDIAN.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false);

        WhiteBlackSchematic sideRailAir = new WhiteBlackSchematic(1, 2, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false);
        CompositeSchematic fullSchem = new CompositeSchematic(0, 0,0);

        // +X, -X, +X +Z, -X -Z
        if ((highwayContext.highwayDirection().getZ() == 0 && (highwayContext.highwayDirection().getX() == -1 || highwayContext.highwayDirection().getX() == 1)) ||
                (highwayContext.highwayDirection().getX() == 1 && highwayContext.highwayDirection().getZ() == 1) || (highwayContext.highwayDirection().getX() == -1 && highwayContext.highwayDirection().getZ() == -1)) {
            if (selfSolve) {
                highwayContext.setOriginVector(new Vec3(0, 0, highwayWidthOffset));
                if (highwayRail) {
                    highwayContext.setLiqOriginVector(new Vec3(0, 0, highwayWidthLiqOffsetRail));
                } else {
                    highwayContext.setLiqOriginVector(new Vec3(0, 0, highwayWidthLiqOffset));
                }
                highwayContext.setBackPathOriginVector(new Vec3(0, 0, -1));
                highwayContext.seteChestEmptyShulkOriginVector(new Vec3(0, 0, highwayWidthLiqOffsetRail));
            } else {
                highwayContext.setOriginVector(new Vec3(startX, 0, startZ - highwayWidthOffset));
                if (highwayRail) {
                    highwayContext.setLiqOriginVector(new Vec3(startX, 0, startZ - highwayWidthLiqOffsetRail));
                } else {
                    highwayContext.setLiqOriginVector(new Vec3(startX, 0, startZ - highwayWidthLiqOffset));
                }
                highwayContext.setBackPathOriginVector(new Vec3(startX, 0, startZ - highwayWidthLiqOffsetRail + 1));
                highwayContext.seteChestEmptyShulkOriginVector(new Vec3(startX, 0, startZ - highwayWidthLiqOffsetRail));
            }

            topAir = new WhiteBlackSchematic(1, highwayHeight - 1, highwayWidth, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false);
            if (pave) {
                obsidSchemBot = new WhiteBlackSchematic(1, 1, highwayWidth, Arrays.asList(Blocks.OBSIDIAN.defaultBlockState(), Blocks.CRYING_OBSIDIAN.defaultBlockState()), Blocks.OBSIDIAN.defaultBlockState(), true, false, false);
                highwayContext.setLiqOriginVector(highwayContext.liqOriginVector().add(0, 0, 1));
                if (highwayRail) {
                    highwayContext.setLiqCheckSchem(new WhiteBlackSchematic(1, highwayHeight - 1, highwayWidth + 2, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false));
                } else {
                    highwayContext.setLiqCheckSchem(new WhiteBlackSchematic(1, highwayHeight - 1, highwayWidth, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false));
                }

                if (diag && highwayRail) {
                    sideRailSupport = new WhiteBlackSchematic(1, 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.LAVA.defaultBlockState(), Blocks.FIRE.defaultBlockState()), Blocks.NETHERRACK.defaultBlockState(), false, true, true);
                    fullSchem.put(sideRailSupport, 0, 1, 0);
                    fullSchem.put(sideRailSupport, 0, 1, highwayWidth + 1);
                }
            } else {
                obsidSchemBot = new WhiteBlackSchematic(1, 1, highwayWidth, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.OBSIDIAN.defaultBlockState(), Blocks.CRYING_OBSIDIAN.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false); // Allow only air and obsidian
                supportNetherRack = new WhiteBlackSchematic(1, 1, supportWidth, highwayContext.blackListBlocks(), Blocks.NETHERRACK.defaultBlockState(), false, true, true); // Allow everything other than air and lava
                if (highwayRail) {
                    highwayContext.setLiqCheckSchem(new WhiteBlackSchematic(1, highwayHeight + 1, highwayWidth + 4, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false));
                } else {
                    highwayContext.setLiqCheckSchem(new WhiteBlackSchematic(1, highwayHeight + 1, highwayWidth + 2, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false));
                }
                if (supportWidth + 2 <= highwayWidth) {
                    fullSchem.put(noLavaBotSides, 0, 0, 1);
                    fullSchem.put(noLavaBotSides, 0, 0, highwayWidth);
                }
                fullSchem.put(supportNetherRack, 0, 0, supportOffset);
            }

            fullSchem.put(obsidSchemBot, 0, 1, 1);
            if (highwayRail) {
                fullSchem.put(sideRail, 0, 2, 0);
                fullSchem.put(sideRail, 0, 2, highwayWidth + 1);
                fullSchem.put(sideRailAir, 0, 3, 0);
                fullSchem.put(sideRailAir, 0, 3, highwayWidth + 1);
            }
            fullSchem.put(topAir, 0, 2, 1);
        }
        // +Z, -Z, +X -Z, -X +Z
        else if ((highwayContext.highwayDirection().getX() == 0 && (highwayContext.highwayDirection().getZ() == -1 || highwayContext.highwayDirection().getZ() == 1)) ||
                (highwayContext.highwayDirection().getX() == 1 && highwayContext.highwayDirection().getZ() == -1) || (highwayContext.highwayDirection().getX() == -1 && highwayContext.highwayDirection().getZ() == 1)) {
            if (selfSolve) {
                highwayContext.setOriginVector(new Vec3(highwayWidthOffset, 0, 0));
                if (highwayRail) {
                    highwayContext.setLiqOriginVector(new Vec3(highwayWidthLiqOffsetRail, 0, 0));
                } else {
                    highwayContext.setLiqOriginVector(new Vec3(highwayWidthLiqOffset, 0, 0));
                }
                highwayContext.setBackPathOriginVector(new Vec3(-1, 0, 0));
                highwayContext.seteChestEmptyShulkOriginVector(new Vec3(highwayWidthLiqOffsetRail, 0, 0));
            } else {
                highwayContext.setOriginVector(new Vec3(startX - highwayWidthOffset, 0, startZ));
                if (highwayRail) {
                    highwayContext.setLiqOriginVector(new Vec3(startX - highwayWidthLiqOffsetRail, 0, startZ));
                } else {
                    highwayContext.setLiqOriginVector(new Vec3(startX - highwayWidthLiqOffset, 0, startZ));
                }
                highwayContext.setBackPathOriginVector(new Vec3(startX - highwayWidthLiqOffsetRail + 1, 0, startZ));
                highwayContext.seteChestEmptyShulkOriginVector(new Vec3(startX - highwayWidthLiqOffsetRail, 0, startZ));
            }

            topAir = new WhiteBlackSchematic(highwayWidth, highwayHeight - 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false);
            if (pave) {
                obsidSchemBot = new WhiteBlackSchematic(highwayWidth, 1, 1, Arrays.asList(Blocks.OBSIDIAN.defaultBlockState(), Blocks.CRYING_OBSIDIAN.defaultBlockState()), Blocks.OBSIDIAN.defaultBlockState(), true, false, false);
                highwayContext.setLiqOriginVector(highwayContext.liqOriginVector().add(1, 0, 0));
                if (highwayRail) {
                    highwayContext.setLiqCheckSchem(new WhiteBlackSchematic(highwayWidth + 2, highwayHeight - 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false));
                } else {
                    highwayContext.setLiqCheckSchem(new WhiteBlackSchematic(highwayWidth, highwayHeight - 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false));
                }

                if (diag && highwayRail) {
                    sideRailSupport = new WhiteBlackSchematic(1, 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.LAVA.defaultBlockState(), Blocks.FIRE.defaultBlockState()), Blocks.NETHERRACK.defaultBlockState(), false, true, true);
                    fullSchem.put(sideRailSupport, 0, 1, 0);
                    fullSchem.put(sideRailSupport, highwayWidth + 1, 1, 0);
                }
            } else {
                obsidSchemBot = new WhiteBlackSchematic(highwayWidth, 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.OBSIDIAN.defaultBlockState(), Blocks.CRYING_OBSIDIAN.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false); // Allow only air and obsidian
                supportNetherRack = new WhiteBlackSchematic(supportWidth, 1, 1, highwayContext.blackListBlocks(), Blocks.NETHERRACK.defaultBlockState(), false, true, true); // Allow everything other than air and lava
                if (highwayRail) {
                    highwayContext.setLiqCheckSchem(new WhiteBlackSchematic(highwayWidth + 4, highwayHeight + 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false));
                } else {
                    highwayContext.setLiqCheckSchem(new WhiteBlackSchematic(highwayWidth + 2, highwayHeight + 1, 1, Arrays.asList(Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()), Blocks.AIR.defaultBlockState(), true, false, false));
                }
                if (supportWidth + 2 <= highwayWidth) {
                    fullSchem.put(noLavaBotSides, 1, 0, 0);
                    fullSchem.put(noLavaBotSides, highwayWidth, 0, 0);
                }

                fullSchem.put(supportNetherRack, supportOffset, 0, 0);
            }

            fullSchem.put(obsidSchemBot, 1, 1, 0);
            if (highwayRail) {
                fullSchem.put(sideRail, 0, 2, 0);
                fullSchem.put(sideRail, highwayWidth + 1, 2, 0);
                fullSchem.put(sideRailAir, 0, 3, 0);
                fullSchem.put(sideRailAir, highwayWidth + 1, 3, 0);
            }

            fullSchem.put(topAir, 1, 2, 0);
        }

        highwayContext.setSchematic(fullSchem);

        Vec3 origin = new Vec3(highwayContext.originVector().x, highwayContext.originVector().y, highwayContext.originVector().z);
        Vec3 direction = new Vec3(highwayContext.highwayDirection().getX(), highwayContext.highwayDirection().getY(), highwayContext.highwayDirection().getZ());
        Vec3 curPos = new Vec3(ctx.playerFeet().getX(), ctx.playerFeet().getY(), ctx.playerFeet().getZ());
        highwayContext.setOriginBuild(highwayContext.getClosestPoint(origin, direction, curPos, LocationType.HighwayBuild));

        highwayContext.setFirstStartingPos(new BetterBlockPos(highwayContext.originBuild()));

        Helper.HELPER.logDirect("Building from " + highwayContext.originBuild().toString());
        settings.buildRepeat.value = new Vec3i(highwayContext.highwayDirection().getX(), 0, highwayContext.highwayDirection().getZ());
        baritone.getPathingBehavior().cancelEverything();


        highwayContext.setEnderChestHasPickShulks(true);
        highwayContext.setEnderChestHasEnderShulks(true);
        highwayContext.setRepeatCheck(false);
        highwayContext.setStartShulkerCount(highwayContext.getShulkerCountInventory(ShulkerType.Any));
        highwayContext.setPaused(false);
        highwayContext.transitionTo(HighwayState.Nothing);
    }

    @Override
    public void stop() {
        Helper.HELPER.logDirect("STOPPING NETHERHIGHWAYBUILDER");
        Helper.HELPER.logDirect("Was at state " + highwayContext.currentState().getState() + " before termination");

        highwayContext.setPaused(true);
        highwayContext.setRepeatCheck(false);
        highwayContext.transitionTo(HighwayState.Nothing);
        baritone.getPathingBehavior().cancelEverything();
        highwayContext.setFirstStartingPos(null);
        highwayContext.setOriginBuild(null);
    }

    @Override
    public void printStatus() {
        Helper.HELPER.logDirect("State: " + highwayContext.currentState().getState());
        Helper.HELPER.logDirect("Paused: " + highwayContext.paused());
        Helper.HELPER.logDirect("Timer: " + highwayContext.timer());
        Helper.HELPER.logDirect("startShulkerCount: " + highwayContext.startShulkerCount());
    }

    @Override
    public void onTick(TickEvent event) {
        if (highwayContext.paused() || highwayContext.schematic() == null || ctx.player() == null || ctx.player().getInventory().isEmpty() || event.getType() == TickEvent.Type.OUT) {
            return;
        }

        highwayContext.incrementTimers();

        if (highwayContext.autoTotem() || highwayContext.clearCursorItem() ||
                (highwayContext.repeatCheck() && highwayContext.timer() <= 120) ||
                highwayContext.stuckCheck() || highwayContext.healthCheck()) {
            return;
        }

        // Handle distance to keep from end of highway
        if (settings.highwayEndDistance.value != -1) {
            ctx.minecraft().options.keyUp.setDown(highwayContext.getHighwayLengthFront() >= settings.highwayEndDistance.value && highwayContext.currentState().getState() == HighwayState.BuildingHighway);
        }

        highwayContext.handle();
    }

    @Override
    public void onRenderPass(RenderEvent event) {
        Entity player = ctx.minecraft().getCameraEntity();
        if (player == null) {
            return;
        }

        if (settings.highwayRenderLiquidScanArea.value) {
            highwayContext.renderLockLiquid().lock();
            //PathRenderer.drawManySelectionBoxes(ctx.minecraft().getRenderViewEntity(), renderBlocks, Color.CYAN);
            BufferBuilder bufferBuilder = IRenderer.startLines(Color.BLUE, 2, settings.renderSelectionBoxesIgnoreDepth.value);

            //BlockPos blockpos = movingObjectPositionIn.getBlockPos();
            BlockStateInterface bsi = new BlockStateInterface(BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext()); // TODO this assumes same dimension between primary baritone and render view? is this safe?

            highwayContext.renderBlocksLiquid().forEach(pos -> {
                BlockState state = bsi.get0(pos);
                VoxelShape shape;
                AABB toDraw;

                if (state.getBlock() instanceof AirBlock) {
                    shape = Blocks.DIRT.defaultBlockState().getShape(player.level(), pos);
                } else {
                    shape = state.getShape(player.level(), pos);
                }
                if (!shape.isEmpty()) {
                    AABB bounds = shape.bounds();
                    toDraw = new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + bounds.getXsize(), pos.getY() + bounds.getYsize(), pos.getZ() + bounds.getZsize());
                    IRenderer.emitAABB(bufferBuilder, event.getModelViewStack(), toDraw, .002D);
                }
            });

            IRenderer.endLines(bufferBuilder, settings.renderSelectionBoxesIgnoreDepth.value);

            highwayContext.renderLockLiquid().unlock();
        }

        if (settings.highwayRenderBuildingArea.value) {
            highwayContext.renderLockBuilding().lock();
            BlockStateInterface bsi = new BlockStateInterface(BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext());
            highwayContext.renderBlocksBuilding().forEach((pos, color) -> {
                BufferBuilder bufferBuilder = IRenderer.startLines(color, 2, settings.renderSelectionBoxesIgnoreDepth.value);

                BlockState state = bsi.get0(pos);
                VoxelShape shape;
                AABB toDraw;

                if (state.getBlock() instanceof AirBlock) {
                    shape = Blocks.DIRT.defaultBlockState().getShape(player.level(), pos);
                } else {
                    shape = state.getShape(player.level(), pos);
                }
                if (!shape.isEmpty()) {
                    AABB bounds = shape.bounds();
                    toDraw = new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + bounds.getXsize(), pos.getY() + bounds.getYsize(), pos.getZ() + bounds.getZsize());
                    IRenderer.emitAABB(bufferBuilder, event.getModelViewStack(), toDraw, .002D);

                    IRenderer.endLines(bufferBuilder, settings.renderSelectionBoxesIgnoreDepth.value);
                }
            });
        }
    }





}