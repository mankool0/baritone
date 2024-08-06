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

package baritone.launch.mixins;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.event.events.PlayerUpdateEvent;
import baritone.api.event.events.SprintStateEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.utils.accessor.IClientPlayerEntity;
import baritone.behavior.LookBehavior;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * @author Brady
 * @since 8/1/2018
 */
@Mixin(LocalPlayer.class)
public abstract class MixinClientPlayerEntity implements IClientPlayerEntity {

    @Inject(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "net/minecraft/client/player/AbstractClientPlayer.tick()V",
                    shift = At.Shift.AFTER
            )
    )
    private void onPreUpdate(CallbackInfo ci) {
        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer((LocalPlayer) (Object) this);
        if (baritone != null) {
            baritone.getGameEventHandler().onPlayerUpdate(new PlayerUpdateEvent(EventState.PRE));
        }
    }

    @Redirect(
            method = "aiStep",
            at = @At(
                    value = "FIELD",
                    target = "net/minecraft/world/entity/player/Abilities.mayfly:Z"
            )
    )
    private boolean isAllowFlying(Abilities capabilities) {
        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer((LocalPlayer) (Object) this);
        if (baritone == null) {
            return capabilities.mayfly;
        }
        return !baritone.getPathingBehavior().isPathing() && capabilities.mayfly;
    }

    @Redirect(
            method = "aiStep",
            at = @At(
                    value = "INVOKE",
                    target = "net/minecraft/client/KeyMapping.isDown()Z"
            )
    )
    private boolean isKeyDown(KeyMapping keyBinding) {
        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer((LocalPlayer) (Object) this);
        if (baritone == null) {
            return keyBinding.isDown();
        }
        SprintStateEvent event = new SprintStateEvent();
        baritone.getGameEventHandler().onPlayerSprintState(event);
        if (event.getState() != null) {
            return event.getState();
        }
        if (baritone != BaritoneAPI.getProvider().getPrimaryBaritone()) {
            // hitting control shouldn't make all bots sprint
            return false;
        }
        return keyBinding.isDown();
    }

    @Inject(
            method = "rideTick",
            at = @At(
                    value = "HEAD"
            )
    )
    private void updateRidden(CallbackInfo cb) {
        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer((LocalPlayer) (Object) this);
        if (baritone != null) {
            ((LookBehavior) baritone.getLookBehavior()).pig();
        }
    }

    @Redirect(
            method = "aiStep",
            at = @At(
                    value = "INVOKE",
                    target = "net/minecraft/world/item/ElytraItem.isFlyEnabled(Lnet/minecraft/world/item/ItemStack;)Z"
            )
    )
    private boolean isFlyEnabled(ItemStack stack) {
        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer((LocalPlayer) (Object) this);
        if (baritone != null && baritone.getPathingBehavior().isPathing()) {
            return false;
        }
        return ElytraItem.isFlyEnabled(stack);
    }

    @Accessor("xLast")
    @Override
    public abstract double getXLast();

    @Accessor("yLast1")
    @Override
    public abstract double getYLast();

    @Accessor("zLast")
    @Override
    public abstract double getZLast();
}
