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

import baritone.utils.accessor.ILivingEntity;
import net.minecraft.world.entity.ElytraAnimationState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ElytraAnimationState.class)
public abstract class MixinElytraAnimationState {

    @Shadow
    @Final
    private LivingEntity entity;

    @Shadow
    private float rotX;

    @Shadow
    private float rotY;

    @Shadow
    private float rotZ;

    @Shadow
    private float rotXOld;

    @Shadow
    private float rotYOld;

    @Shadow
    private float rotZOld;

    @Inject(
            method = "tick",
            at = @At("HEAD"),
            cancellable = true
    )
    private void foldWingsWhileStanding(final CallbackInfo ci) {
        // elytraSaveDurability's standing box: the wings fold as on a standing player. the game's own tick with the
        // glide taken as off
        if (!(this.entity instanceof ILivingEntity living) || !living.isGlideHeld()) {
            return;
        }
        this.rotXOld = this.rotX;
        this.rotYOld = this.rotY;
        this.rotZOld = this.rotZ;
        final boolean crouching = this.entity.isCrouching();
        final float x = crouching ? (float) (Math.PI * 2.0 / 9.0) : (float) (Math.PI / 12);
        final float y = crouching ? 0.08726646F : 0.0F;
        final float z = crouching ? (float) (-Math.PI / 4) : (float) (-Math.PI / 12);
        this.rotX = this.rotX + (x - this.rotX) * 0.3F;
        this.rotY = this.rotY + (y - this.rotY) * 0.3F;
        this.rotZ = this.rotZ + (z - this.rotZ) * 0.3F;
        ci.cancel();
    }
}
