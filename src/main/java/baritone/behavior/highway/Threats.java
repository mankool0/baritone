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

import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.behavior.highway.enums.HighwayState;
import baritone.behavior.highway.enums.LocationType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Mobs to fight, the mobs and end crystals we gave up on for a while, and piglins that walked off
 * with a shulker box.
 */
public class Threats extends ContextComponent {
    private Entity currentMobTarget = null;
    private final Map<java.util.UUID, Long> ignoredMobs = new HashMap<>(); // mobs a stalled chase gave up on, to the game time they stay ignored until
    private final Map<java.util.UUID, Long> ignoredCrystals = new HashMap<>(); // end crystals CrystalRemoval gave up on, likewise
    // MobCombat's chase-stall clock, kept here because every engagement starts a new MobCombat, see chaseStalled
    private final Map<java.util.UUID, Double> chaseClosestReach = new HashMap<>();
    private int chaseStallTicks = 0;
    private long lastChaseTick = 0;
    private BetterBlockPos combatReturnPos = null;
    private Entity thiefTarget = null;
    private HighwayState thiefHuntReturnState = null;
    private BetterBlockPos thiefHuntStartPos = null;
    private int thiefHuntFailCooldown = 0;
    private java.util.UUID thiefHuntIgnoredThief = null; // the one thief a failed hunt gave up on; only it is ignored while the cooldown runs
    private double thiefHuntBestDist = Double.MAX_VALUE; // closest we've ever been to the current hunt target

    Threats(HighwayContext ctx) {
        super(ctx);
    }

    public Entity currentMobTarget() {
        return currentMobTarget;
    }

    public void setCurrentMobTarget(Entity entity) {
        this.currentMobTarget = entity;
    }

    public void ignoreMob(Entity mob, int ticks) {
        long now = playerContext.world().getGameTime();
        ignoredMobs.values().removeIf(until -> until <= now);
        ignoredMobs.put(mob.getUUID(), now + ticks);
    }

    private boolean isMobIgnored(Entity mob) {
        Long until = ignoredMobs.get(mob.getUUID());
        return until != null && until > playerContext.world().getGameTime();
    }

    public void clearIgnoredMobs() {
        ignoredMobs.clear();
        ignoredCrystals.clear();
    }

    /** Leaves an end crystal out of {@link HighwayScanner#isHighwayCorrect} for a while, so a crystal we can't break doesn't send us straight back. */
    public void ignoreCrystal(Entity crystal, int ticks) {
        long now = playerContext.world().getGameTime();
        ignoredCrystals.values().removeIf(until -> until <= now);
        ignoredCrystals.put(crystal.getUUID(), now + ticks);
    }

    boolean isCrystalIgnored(Entity crystal) {
        Long until = ignoredCrystals.get(crystal.getUUID());
        return until != null && until > playerContext.world().getGameTime();
    }

    /**
     * Counts a tick of chasing {@code mob} from {@code reach} away and returns true, restarting the
     * clock, once the chase has gone {@code timeoutTicks} without getting half a block closer to any
     * mob than we'd already been. The closest reach is kept per mob, so switching between several
     * out-of-reach mobs as each wanders nearest can't keep resetting the clock. Fights that follow
     * each other within the timeout share both: a mob with no way to us calms down whenever we stop
     * short of it and turns angry again as we walk back, starting a new fight every few seconds.
     */
    public boolean chaseStalled(Entity mob, double reach, int timeoutTicks) {
        long now = playerContext.world().getGameTime();
        if (now - lastChaseTick > timeoutTicks) {
            chaseClosestReach.clear();
            chaseStallTicks = 0;
        }
        lastChaseTick = now;
        Double closest = chaseClosestReach.get(mob.getUUID());
        if (closest == null || reach < closest - 0.5) {
            chaseClosestReach.put(mob.getUUID(), reach);
            chaseStallTicks = 0;
            return false;
        }
        if (++chaseStallTicks > timeoutTicks) {
            chaseStallTicks = 0;
            return true;
        }
        return false;
    }

    public void resetChaseStall() {
        chaseStallTicks = 0;
    }

    /**
     * Distance to the nearest point of the mob's hitbox from whichever of our feet and eyes is
     * farther from it. The killaura checks its range from our feet, the server and anticheat check
     * reach from our eyes, and a hit has to pass both.
     */
    public double mobReachDistance(Entity mob) {
        net.minecraft.world.entity.player.Player player = playerContext.player();
        AABB hitbox = mob.getBoundingBox();
        return Math.sqrt(Math.max(hitbox.distanceToSqr(player.position()),
                hitbox.distanceToSqr(player.getEyePosition())));
    }

    public Entity thiefTarget() {
        return thiefTarget;
    }

    public void setThiefTarget(Entity entity) {
        this.thiefTarget = entity;
    }

    public HighwayState thiefHuntReturnState() {
        return thiefHuntReturnState;
    }

    public BetterBlockPos thiefHuntStartPos() {
        return thiefHuntStartPos;
    }

    public void ignoreThief(Entity thief, int ticks) {
        this.thiefHuntIgnoredThief = thief.getUUID();
        this.thiefHuntFailCooldown = ticks;
    }

    /** Counts down the cooldown {@link #ignoreThief} set, forgetting the thief once it runs out. */
    void tickThiefHuntCooldown() {
        if (thiefHuntFailCooldown > 0) {
            thiefHuntFailCooldown--;
            if (thiefHuntFailCooldown == 0) {
                thiefHuntIgnoredThief = null;
            }
        }
    }

    public double thiefHuntBestDist() {
        return thiefHuntBestDist;
    }

    public void setThiefHuntBestDist(double dist) {
        this.thiefHuntBestDist = dist;
    }

    public void resetThiefHunt() {
        thiefTarget = null;
        thiefHuntReturnState = null;
        thiefHuntStartPos = null;
        thiefHuntFailCooldown = 0;
        thiefHuntIgnoredThief = null;
        thiefHuntBestDist = Double.MAX_VALUE;
        ctx.inventory().setPreMineShulkerCount(-1);
    }

    public BetterBlockPos combatReturnPos() {
        return combatReturnPos;
    }

    public void setCombatReturnPos(BetterBlockPos pos) {
        this.combatReturnPos = pos;
    }

    private boolean isPlayerWearingGoldArmor(net.minecraft.world.entity.player.Player player) {
        return java.util.stream.Stream.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET).map(player::getItemBySlot).anyMatch(stack ->
                stack.is(net.minecraft.world.item.Items.GOLDEN_HELMET)
                || stack.is(net.minecraft.world.item.Items.GOLDEN_CHESTPLATE)
                || stack.is(net.minecraft.world.item.Items.GOLDEN_LEGGINGS)
                || stack.is(net.minecraft.world.item.Items.GOLDEN_BOOTS));
    }

    private boolean isEntityInHighwayCorridor(Entity entity) {
        if (layout.schematic() == null) return false;
        Vec3 dir = new Vec3(layout.highwayDirection().getX(), layout.highwayDirection().getY(), layout.highwayDirection().getZ());
        if (dir.lengthSqr() == 0) return true;
        BetterBlockPos closest = layout.getClosestPoint(layout.originVector(), dir, entity.position(), LocationType.HighwayBuild);
        BlockPos ep = entity.blockPosition();
        // widthX/lengthZ == 1 means that's the along-highway axis; getClosestPoint rounds to the nearest block
        // so we skip the size-1 dimension to avoid off-by-one failures at block boundaries
        boolean xOk = layout.schematic().widthX() == 1 || (ep.getX() >= closest.getX() && ep.getX() < closest.getX() + layout.schematic().widthX());
        boolean zOk = layout.schematic().lengthZ() == 1 || (ep.getZ() >= closest.getZ() && ep.getZ() < closest.getZ() + layout.schematic().lengthZ());
        boolean yOk = ep.getY() >= closest.getY() && ep.getY() < closest.getY() + layout.schematic().heightY();
        return xOk && yOk && zOk;
    }

    /**
     * Height of the floor an entity stands on. A jump lifts its feet about 1.25 blocks for a few
     * ticks, which would otherwise flicker a mob below us in and out of reach.
     */
    private double standingY(Entity entity) {
        if (entity.onGround()) {
            return entity.getY();
        }
        BlockPos.MutableBlockPos pos = BlockPos.containing(entity.position()).mutable();
        for (int i = 0; i < 3; i++, pos.move(Direction.DOWN)) {
            VoxelShape shape = playerContext.world().getBlockState(pos).getCollisionShape(playerContext.world(), pos);
            if (!shape.isEmpty()) {
                return Math.min(pos.getY() + shape.max(Direction.Axis.Y), entity.getY());
            }
        }
        return entity.getY();
    }

    /**
     * A walking mob standing more than a jump below us can neither hit us nor step up to us from
     * where it is. It has to find its own way up, and if it does it's back at our level and gets
     * engaged then. Going after it instead walks us off the highway, or digs us down through it,
     * after a mob that was never coming: an angry mob with no way up just paths to the spot under
     * us and waits there. Shooters below still count while they can see us.
     */
    private boolean stuckBelowUs(Entity mob, double ourStandingY) {
        // Ghasts fly and magma cubes jump several blocks high, so being lower doesn't keep them away
        if (mob instanceof net.minecraft.world.entity.monster.Ghast || mob instanceof net.minecraft.world.entity.monster.Slime) {
            return false;
        }
        boolean blaze = mob instanceof net.minecraft.world.entity.monster.Blaze;
        if (ourStandingY - (blaze ? mob.getY() : standingY(mob)) < 1.5) {
            return false;
        }
        net.minecraft.world.entity.LivingEntity living = (net.minecraft.world.entity.LivingEntity) mob;
        boolean shooter = blaze || living.isHolding(stack -> stack.getItem() instanceof ProjectileWeaponItem);
        return !shooter || !living.hasLineOfSight(playerContext.player());
    }

    public java.util.Optional<Entity> findMobTargetingPlayer() {
        net.minecraft.world.entity.player.Player player = playerContext.player();
        boolean wearingGold = isPlayerWearingGoldArmor(player);

        // Collect ghasts that own a nearby fireball
        java.util.Set<Entity> ghastsThreatening = playerContext.entitiesStream()
                .filter(e -> e instanceof net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball && e.isAlive())
                .filter(e -> e.distanceToSqr(player) <= 8.0 * 8.0)
                .map(e -> ((net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball) e).getOwner())
                .filter(owner -> owner instanceof net.minecraft.world.entity.monster.Ghast)
                .collect(java.util.stream.Collectors.toSet());

        // Sticky ghast: if we're already tracking a ghast, keep it as a threat while it's alive and within 40 blocks
        if (currentMobTarget instanceof net.minecraft.world.entity.monster.Ghast
                && currentMobTarget.isAlive()
                && currentMobTarget.distanceToSqr(player) <= 40.0 * 40.0) {
            ghastsThreatening.add(currentMobTarget);
        }

        net.minecraft.world.damagesource.DamageSource lastDamage = player.getLastDamageSource();
        final Entity lastAttacker = lastDamage != null ? lastDamage.getEntity() : null;

        double aggroRangeSq = settings.highwayMobAggroRange.value * settings.highwayMobAggroRange.value;
        double magmaRangeSq = settings.highwayMagmaCubeAggroRange.value * settings.highwayMagmaCubeAggroRange.value;
        double maxRangeSq = settings.highwayMobMaxAggroRange.value * settings.highwayMobMaxAggroRange.value;
        double ourStandingY = standingY(player);

        return playerContext.entitiesStream()
                .filter(e -> e instanceof net.minecraft.world.entity.LivingEntity && e.isAlive())
                .filter(e -> !(e instanceof net.minecraft.world.entity.player.Player))
                .filter(e -> e == lastAttacker
                        // isAggressive is the synced anger flag: set for piglins (even when we wear gold armor),
                        // brutes, zombified piglins, skeletons/wither skeletons, zoglins and endermen
                        || (e instanceof net.minecraft.world.entity.monster.Enemy && e instanceof net.minecraft.world.entity.Mob
                            && ((net.minecraft.world.entity.Mob) e).isAggressive()
                            && e.distanceToSqr(player) <= aggroRangeSq)
                        // hoglins never set the aggressive flag and zoglins attack on sight; both charge from up to 16 blocks
                        || ((e instanceof net.minecraft.world.entity.monster.Zoglin || e instanceof net.minecraft.world.entity.monster.hoglin.Hoglin) && e.distanceToSqr(player) <= aggroRangeSq)
                        || (e instanceof net.minecraft.world.entity.monster.piglin.Piglin && !wearingGold
                            && ((net.minecraft.world.entity.monster.piglin.Piglin) e).isAdult()
                            && e.distanceToSqr(player) <= aggroRangeSq)
                        || (e instanceof net.minecraft.world.entity.monster.piglin.PiglinBrute && e.distanceToSqr(player) <= aggroRangeSq)
                        || (e instanceof net.minecraft.world.entity.monster.MagmaCube
                            && e.distanceToSqr(player) <= magmaRangeSq)
                        // a lit blaze is in attack mode (blazes override isOnFire to their synced charged flag)
                        || (e instanceof net.minecraft.world.entity.monster.Blaze && e.isOnFire() && e.distanceToSqr(player) <= aggroRangeSq)
                        || (e instanceof net.minecraft.world.entity.monster.EnderMan
                            && ((net.minecraft.world.entity.monster.EnderMan) e).isCreepy()
                            && e.distanceToSqr(player) <= aggroRangeSq)
                        || ghastsThreatening.contains(e))
                .filter(e -> !(e instanceof net.minecraft.world.entity.monster.Ghast) || ghastsThreatening.contains(e))
                .filter(e -> e.distanceToSqr(player) <= maxRangeSq || e instanceof net.minecraft.world.entity.monster.Ghast || e == lastAttacker)
                // corridor only gates pre-emptive proximity targets; angry mobs and whatever hit us get engaged anywhere
                .filter(e -> e == lastAttacker
                        || (e instanceof net.minecraft.world.entity.Mob && ((net.minecraft.world.entity.Mob) e).isAggressive())
                        || ghastsThreatening.contains(e)
                        || isEntityInHighwayCorridor(e))
                .filter(e -> !stuckBelowUs(e, ourStandingY))
                // a mob a stalled chase gave up on stays ignored unless it gets within reach after all
                .filter(e -> !isMobIgnored(e) || mobReachDistance(e) <= settings.highwayMobCombatApproachDistance.value)
                .min(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(player)));
    }

    // Only an empty main hand can accept a shulker box (Mob.canReplaceCurrentItem; adults always
    // spawn holding a crossbow or golden sword and a box never beats either). A baby already holding
    // junk can still swap it for a custom-named box via canReplaceEqualItem, so every baby counts.
    public boolean isPotentialShulkerThief(Entity entity) {
        if (!(entity instanceof net.minecraft.world.entity.monster.piglin.Piglin piglin) || !entity.isAlive()) {
            return false;
        }
        return piglin.isBaby() || piglin.getMainHandItem().isEmpty();
    }

    public boolean shulkerThiefNear(BlockPos pos, double range) {
        if (range <= 0) {
            return false;
        }
        double rangeSq = range * range;
        return playerContext.entitiesStream()
                .filter(this::isPotentialShulkerThief)
                .anyMatch(e -> e.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= rangeSq);
    }

    public Optional<Entity> findShulkerThief() {
        return playerContext.entitiesStream()
                .filter(e -> e instanceof net.minecraft.world.entity.monster.piglin.Piglin && e.isAlive())
                .filter(e -> HighwayContext.shulkerItemList.contains(((net.minecraft.world.entity.monster.piglin.Piglin) e).getMainHandItem().getItem()))
                .filter(e -> thiefHuntFailCooldown <= 0 || !e.getUUID().equals(thiefHuntIgnoredThief))
                .min(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(playerContext.player())));
    }

    public boolean maybeStartThiefHunt(HighwayState returnState) {
        if (!settings.highwayRecoverStolenShulkers.value) {
            return false;
        }
        Optional<Entity> thief = findShulkerThief();
        if (thief.isEmpty()) {
            return false;
        }
        Helper.HELPER.logDirect("A piglin is carrying a shulker box, hunting it to get the box back.");
        thiefTarget = thief.get();
        thiefHuntReturnState = returnState;
        thiefHuntStartPos = playerContext.playerFeet();
        thiefHuntBestDist = Double.MAX_VALUE;
        baritone.getPathingBehavior().cancelEverything();
        ctx.transitionTo(HighwayState.ShulkerThiefHunt);
        ctx.resetTimer();
        return true;
    }

    public void attackEntity(Entity target) {
        attackEntity(target, target.getBoundingBox().getCenter());
    }

    /** @param aimAt where on the target to aim, for a hit that has to go over something in front of its middle */
    public void attackEntity(Entity target, Vec3 aimAt) {
        Rotation aim = RotationUtils.calcRotationFromVec3d(playerContext.playerHead(), aimAt, playerContext.playerRotations());
        baritone.getLookBehavior().updateTarget(aim, true);

        if (playerContext.player().getAttackStrengthScale(0.5f) < 1.0f) {
            return;
        }
        Optional<Rotation> serverRotation = baritone.getLookBehavior().getServerRotation();
        if (serverRotation.isEmpty()) {
            return;
        }
        Vec3 eyes = playerContext.playerHead();
        Vec3 dir = RotationUtils.calcLookDirectionFromRotation(serverRotation.get());
        if (target.getBoundingBox().clip(eyes, eyes.add(dir.scale(3.0))).isEmpty()) {
            return;
        }
        playerContext.minecraft().gameMode.attack(playerContext.player(), target);
        playerContext.player().swing(InteractionHand.MAIN_HAND);
    }
}
