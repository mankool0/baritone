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

import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.behavior.highway.HighwayContext;
import baritone.behavior.highway.NetherHighwayBuilderBehavior;
import baritone.behavior.highway.State;
import baritone.behavior.highway.enums.HighwayState;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.CrystalBreakPlanner;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Breaks an end crystal standing where the highway puts a block. A crystal never moves, so no hole drops it out of the
 * way, and hitting it sets off a power 6 explosion. So: pick a spot in reach of it where a little cover keeps the blast
 * off ({@link CrystalBreakPlanner}), put obsidian under it if the blast would take the floor there, walk there, put the
 * cover down, check it from where we actually stand, hit. When nowhere hides us, or no spot that does works out, take
 * the blast on an enchanted golden apple from wherever it costs the least and the knockback leaves us safe.
 */
public class CrystalRemoval extends State {

    private static final int PATH_TIMEOUT_TICKS = 400;
    private static final int CENTER_TIMEOUT_TICKS = 60;
    private static final int COVER_TIMEOUT_TICKS = 60;
    private static final int ATTACK_TIMEOUT_TICKS = 100;
    private static final int SWAP_TIMEOUT_TICKS = 100;
    private static final int EAT_TIMEOUT_TICKS = 120;
    /** Ticks with the use key held and the client not eating before the eat counts as not starting. */
    private static final int EAT_START_TICKS = 40;
    /** Spots behind cover tried before taking the blast instead. */
    private static final int MAX_PLANS = 3;
    /** Spots to take the blast from tried before giving up on the crystal for {@link #IGNORE_TICKS}. */
    private static final int MAX_TANK_PLANS = 2;
    private static final int IGNORE_TICKS = 1200;
    /** Health and absorption the hit and any fall after it must leave us. */
    private static final float TANK_KEEP_HEALTH = 10;
    /** Absorption IV from an enchanted golden apple. */
    private static final float GAPPLE_ABSORPTION = 16;
    /** Resistance this much from running out is eaten again before the hit. */
    private static final int MIN_RESISTANCE_TICKS = 200;
    /** Health lost since a tank plan was made that makes its budget a different one. */
    private static final float TANK_HEALTH_SLACK = 4;

    private enum Phase {GOING, SWAPPING, CENTERING, COVERING, EATING, ATTACKING}

    private EndCrystal crystal;
    private CrystalBreakPlanner.Plan plan;
    private Phase phase;
    private BetterBlockPos goingTo; // the plan's stand, or the spot beside it to swap its floor from first
    private int phaseTicks;
    private int plans;
    private boolean tanking; // hiding is out, plans take the blast on a gapple
    private int tankPlans;
    private float plannedHealth; // our health when the tank plan was made, its budget counted from it
    private int gapplesBefore; // the gapples on us as the eat began, so it's done once one is gone
    private int notEatingTicks;
    private final Set<BetterBlockPos> badStands = new HashSet<>();

    public CrystalRemoval(HighwayState state) {
        super(state);
    }

    @Override
    public void handle(HighwayContext context) {
        if (crystal == null) {
            crystal = findCrystal(context);
        }
        if (crystal == null || crystal.isRemoved()) {
            Helper.HELPER.logDirect(phase == Phase.ATTACKING ? "Broke the end crystal." : "The end crystal is gone.");
            finish(context);
            return;
        }

        if (plan == null && !planNext(context)) {
            return;
        }

        phaseTicks++;
        switch (phase) {
            case GOING -> going(context);
            case SWAPPING -> swapping(context);
            case CENTERING -> centering(context);
            case COVERING -> covering(context);
            case EATING -> eating(context);
            case ATTACKING -> attacking(context);
        }
    }

    /**
     * Works out the next spot to try and sets off for it: behind cover first, then, with those used up, taking the
     * blast on a gapple. Returns false when there's none, having left the state.
     */
    private boolean planNext(HighwayContext context) {
        if (!tanking) {
            String why;
            if (plans < MAX_PLANS) {
                plans++;
                plan = plan(context);
                if (plan != null) {
                    begin(context);
                    return true;
                }
                if (context.threats().deferCrystal(crystal)) {
                    Helper.HELPER.logDirect("Can't break the end crystal at " + crystal.blockPosition().toShortString()
                            + " yet: nowhere in reach of it where cover keeps the blast off. Trying again once the builder has nothing else to do.");
                    finish(context);
                    return false;
                }
                why = "nowhere in reach of it where cover keeps the blast off";
            } else {
                why = "tried " + MAX_PLANS + " spots behind cover";
            }
            if (!context.eating().hasGapple() && needsGapple(context.playerContext().player())) {
                giveUp(context, why + ", and there's no enchanted golden apple to take the blast on");
                return false;
            }
            Helper.HELPER.logDirect("End crystal at " + crystal.blockPosition().toShortString() + ": " + why + ", so taking the blast on an enchanted golden apple instead.");
            tanking = true;
        }
        if (tankPlans >= MAX_TANK_PLANS) {
            giveUp(context, "tried " + MAX_TANK_PLANS + " spots to take the blast from");
            return false;
        }
        tankPlans++;
        plannedHealth = context.playerContext().player().getHealth();
        plan = planner(context).tankPlan(context.playerContext().player().position(), badStands, tank(context));
        if (plan == null) {
            giveUp(context, "nowhere in reach of it leaves us enough health and somewhere safe to land, even on a gapple");
            return false;
        }
        begin(context);
        return true;
    }

    private void begin(HighwayContext context) {
        String swap = plan.swap() != null ? ", with obsidian put in for the floor there first" : "";
        if (plan.tank()) {
            Helper.HELPER.logDirect(String.format("Breaking the end crystal at %s from %s on an enchanted golden apple, behind %d block(s) of cover, %.0f%% of us in view of the blast, about %.0f HP%s.",
                    crystal.blockPosition().toShortString(), plan.stand().toShortString(), plan.covers().size(), plan.exposure() * 100, plan.damage(), swap));
        } else {
            Helper.HELPER.logDirect(String.format("Breaking the end crystal at %s from %s behind %d block(s) of cover, %.0f%% of us in view of the blast%s.",
                    crystal.blockPosition().toShortString(), plan.stand().toShortString(), plan.covers().size(), plan.exposure() * 100, swap));
        }
        context.baritone().getPathingBehavior().cancelEverything();
        goTo(context, plan.swap() != null ? plan.swapFrom() : plan.stand());
    }

    @Override
    public void onExit(HighwayContext context) {
        releaseKeys(context);
    }

    private void going(HighwayContext context) {
        IPlayerContext ctx = context.playerContext();
        if (context.baritone().getCustomGoalProcess().isActive() || context.baritone().getPathingBehavior().isPathing()) {
            if (phaseTicks > PATH_TIMEOUT_TICKS) {
                replan(context, "took too long to get to " + goingTo.toShortString());
            }
            return;
        }
        if (!ctx.playerFeet().equals(goingTo)) {
            replan(context, "couldn't get to " + goingTo.toShortString());
            return;
        }
        if (ctx.player().onGround()) {
            setPhase(goingTo.equals(plan.stand()) ? Phase.CENTERING : Phase.SWAPPING);
        }
    }

    private void goTo(HighwayContext context, BetterBlockPos target) {
        goingTo = target;
        context.baritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(target));
        setPhase(Phase.GOING);
    }

    /**
     * From beside the stand, mines the floor under it and puts obsidian in its place. The blast hurts us before it
     * breaks any block, and then takes the netherrack around us, so this keeps our footing where we'd otherwise drop
     * into the hole it leaves.
     */
    private void swapping(HighwayContext context) {
        IPlayerContext ctx = context.playerContext();
        BlockPos floor = plan.swap();
        BlockState state = ctx.world().getBlockState(floor);
        if (CrystalBreakPlanner.blastProof(state)) {
            releaseKeys(context);
            goTo(context, plan.stand());
            return;
        }
        if (phaseTicks > SWAP_TIMEOUT_TICKS) {
            replan(context, "couldn't put obsidian in for the floor at " + floor.toShortString());
            return;
        }
        if (!state.canBeReplaced()) {
            int pick = context.inventory().putPickaxeHotbar();
            if (pick == -1) {
                replan(context, "nothing to mine the floor at " + floor.toShortString() + " with");
                return;
            }
            if (pick >= 9) {
                return; // the move onto the hotbar hasn't landed yet
            }
            Optional<Rotation> rotation = RotationUtils.reachable(ctx, floor, ctx.playerController().getBlockReachDistance());
            if (rotation.isEmpty()) {
                replan(context, "can't reach the floor at " + floor.toShortString());
                return;
            }
            ctx.player().getInventory().setSelectedSlot(pick);
            context.baritone().getLookBehavior().updateTarget(rotation.get(), true);
            context.baritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
            return;
        }
        context.baritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        int slot = context.inventory().putItemHotbar(Item.getId(Items.CRYING_OBSIDIAN));
        if (slot == -1) {
            slot = context.inventory().putItemHotbar(Item.getId(Items.OBSIDIAN));
        }
        if (slot == -1) {
            replan(context, "no obsidian left to put in for the floor at " + floor.toShortString());
            return;
        }
        if (slot >= 9) {
            return;
        }
        ctx.player().getInventory().setSelectedSlot(slot);
        context.baritone().getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
        context.placer().place(floor, (float) ctx.playerController().getBlockReachDistance(), true, false, InteractionHand.MAIN_HAND);
    }

    /** The plan was worked out from the middle of the block, and the cover goes right beside it. */
    private void centering(HighwayContext context) {
        Player player = context.playerContext().player();
        Vec3 target = Vec3.atBottomCenterOf(plan.stand());
        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        if (dx * dx + dz * dz <= 0.01 || phaseTicks > CENTER_TIMEOUT_TICKS) {
            releaseKeys(context);
            setPhase(Phase.COVERING);
            return;
        }
        player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        context.baritone().getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
        context.baritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
    }

    private void covering(HighwayContext context) {
        IPlayerContext ctx = context.playerContext();
        BlockPos next = plan.covers().stream().filter(pos -> ctx.world().getBlockState(pos).canBeReplaced()).findFirst().orElse(null);
        if (next == null) {
            releaseKeys(context);
            float exposure = exposureHere(context);
            if (exposure > plan.maxExposure()) {
                replan(context, String.format("%.0f%% of us is still in view behind the cover", exposure * 100));
                return;
            }
            if (plan.tank() && needsGapple(ctx.player())) {
                startEating(context);
            } else {
                setPhase(Phase.ATTACKING);
            }
            return;
        }
        if (phaseTicks > COVER_TIMEOUT_TICKS * plan.covers().size()) {
            replan(context, "couldn't put cover down at " + next.toShortString());
            return;
        }
        int slot = context.inventory().putAcceptableThrowawayHotbar();
        if (slot == -1) {
            slot = context.inventory().putItemHotbar(Item.getId(Items.CRYING_OBSIDIAN));
        }
        if (slot == -1) {
            slot = context.inventory().putItemHotbar(Item.getId(Items.OBSIDIAN));
        }
        if (slot == -1) {
            giveUp(context, "nothing to put down as cover");
            return;
        }
        if (slot >= 9) {
            return; // the move onto the hotbar hasn't landed yet
        }
        ctx.player().getInventory().setSelectedSlot(slot);
        context.baritone().getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
        context.placer().place(next, (float) ctx.playerController().getBlockReachDistance(), true, false, InteractionHand.MAIN_HAND);
    }

    /** Whether taking the blast needs a gapple first: no Resistance that lasts, or less than a fresh one's absorption. */
    private static boolean needsGapple(Player player) {
        MobEffectInstance resistance = player.getEffect(MobEffects.RESISTANCE);
        return resistance == null || resistance.getDuration() < MIN_RESISTANCE_TICKS
                || !player.hasEffect(MobEffects.ABSORPTION) || player.getAbsorptionAmount() < GAPPLE_ABSORPTION - 0.5f;
    }

    private void startEating(HighwayContext context) {
        gapplesBefore = context.inventory().getItemCountInventory(Item.getId(Items.ENCHANTED_GOLDEN_APPLE));
        notEatingTicks = 0;
        setPhase(Phase.EATING);
    }

    /** One enchanted golden apple, standing behind the cover: done once one is gone and its Resistance is on us. */
    private void eating(HighwayContext context) {
        LocalPlayer player = context.playerContext().player();
        int gapples = context.inventory().getItemCountInventory(Item.getId(Items.ENCHANTED_GOLDEN_APPLE));
        if (gapples < gapplesBefore && player.hasEffect(MobEffects.RESISTANCE)) {
            releaseKeys(context);
            setPhase(Phase.ATTACKING);
            return;
        }
        if (phaseTicks > EAT_TIMEOUT_TICKS || notEatingTicks > EAT_START_TICKS) {
            replan(context, "the gapple never went down");
            return;
        }
        if (gapples == 0) {
            giveUp(context, "no enchanted golden apple left to take the blast on");
            return;
        }
        if (player.hasContainerOpen()) {
            player.closeContainer();
            return;
        }
        if (!player.isUsingItem()) {
            int slot = context.inventory().putItemHotbar(Item.getId(Items.ENCHANTED_GOLDEN_APPLE));
            if (slot == -1 || slot >= 9) {
                notEatingTicks++;
                return; // not on the hotbar yet
            }
            player.getInventory().setSelectedSlot(slot);
            if (context.eating().clientLoaded()) {
                notEatingTicks++;
            }
        } else {
            notEatingTicks = 0;
        }
        // nothing in view to right-click instead of eating
        NetherHighwayBuilderBehavior.suppressHitResult = true;
        context.eating().holdUseToEat(Items.ENCHANTED_GOLDEN_APPLE);
    }

    private void attacking(HighwayContext context) {
        IPlayerContext ctx = context.playerContext();
        if (phaseTicks > ATTACK_TIMEOUT_TICKS) {
            replan(context, "the hit never landed");
            return;
        }
        if (plan.tank()) {
            if (needsGapple(ctx.player()) && !ctx.player().hasEffect(MobEffects.RESISTANCE)) {
                startEating(context); // it ran out before we got the hit in
                return;
            }
            if (ctx.player().getHealth() < plannedHealth - TANK_HEALTH_SLACK) {
                replan(context, "something else hurt us since the plan was made");
                return;
            }
        }
        // checked every tick: anything that shoves us out from behind the cover makes this a different hit
        float exposure = exposureHere(context);
        if (exposure > plan.maxExposure()) {
            replan(context, String.format("%.0f%% of us came into view of the blast", exposure * 100));
            return;
        }
        Vec3 eye = ctx.player().getEyePosition();
        if (!planner(context).clearShot(ctx.world(), eye, plan.aim())) {
            replan(context, "something's in the way of the hit");
            return;
        }
        context.threats().attackEntity(crystal, plan.aim());
    }

    private EndCrystal findCrystal(HighwayContext context) {
        BlockPos cell = context.scanner().crystalLocation();
        if (cell == null) {
            return null;
        }
        List<EndCrystal> crystals = context.playerContext().world().getEntitiesOfClass(EndCrystal.class, new AABB(cell), EndCrystal::isAlive);
        return crystals.isEmpty() ? null : crystals.get(0);
    }

    /**
     * What taking the blast costs us: the armour we wear and its Protection and Blast Protection, the server's
     * difficulty, the Resistance and absorption the gapple gives (or what we have, when it's still fresh), and the
     * health the budget has to leave.
     */
    private static CrystalBreakPlanner.Tank tank(HighwayContext context) {
        Player player = context.playerContext().player();
        float protection = 0;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemEnchantments enchantments = player.getItemBySlot(slot).getEnchantments();
            for (Holder<Enchantment> enchant : enchantments.keySet()) {
                if (enchant.is(Enchantments.PROTECTION)) {
                    protection += enchantments.getLevel(enchant);
                } else if (enchant.is(Enchantments.BLAST_PROTECTION)) {
                    protection += 2 * enchantments.getLevel(enchant);
                }
            }
        }
        boolean eats = needsGapple(player);
        MobEffectInstance resistance = player.getEffect(MobEffects.RESISTANCE);
        int amplifier = Math.max(eats ? 0 : -1, resistance != null ? resistance.getAmplifier() : -1);
        float absorption = eats ? Math.max(player.getAbsorptionAmount(), GAPPLE_ABSORPTION) : player.getAbsorptionAmount();
        return new CrystalBreakPlanner.Tank(context.playerContext().world().getDifficulty(), player.getArmorValue(),
                (float) player.getAttributeValue(Attributes.ARMOR_TOUGHNESS), protection, amplifier,
                (float) player.getAttributeValue(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE),
                player.getHealth() + absorption - TANK_KEEP_HEALTH);
    }

    /** The nearest spot in reach of the crystal where a little cover keeps the blast off, see {@link CrystalBreakPlanner}. */
    private CrystalBreakPlanner.Plan plan(HighwayContext context) {
        return planner(context).plan(context.playerContext().player().position(), badStands);
    }

    private CrystalBreakPlanner planner(HighwayContext context) {
        IPlayerContext ctx = context.playerContext();
        return new CrystalBreakPlanner(ctx.world(), crystal.position(), crystal.getBoundingBox(), ctx.player().getDimensions(Pose.STANDING),
                stand -> standable(ctx, stand),
                cell -> context.baritone().getBuilderProcess().checkNoEntityCollision(new AABB(cell), ctx.player()),
                context.inventory().getObsidianCountInventory() > 0);
    }

    /** How much of us the blast would see, standing up where we are now, the way it'll be when we hit. */
    private float exposureHere(HighwayContext context) {
        IPlayerContext ctx = context.playerContext();
        return planner(context).exposureAt(ctx.world(), ctx.player().position());
    }

    private static boolean standable(IPlayerContext ctx, BetterBlockPos pos) {
        return MovementHelper.canWalkOn(ctx, pos.below()) && MovementHelper.canWalkThrough(ctx, pos) && MovementHelper.canWalkThrough(ctx, pos.above());
    }

    private void setPhase(Phase phase) {
        this.phase = phase;
        phaseTicks = 0;
    }

    private void replan(HighwayContext context, String why) {
        Helper.HELPER.logDirect("End crystal: " + why + ", trying another spot.");
        releaseKeys(context);
        context.baritone().getPathingBehavior().cancelEverything();
        badStands.add(plan.stand());
        plan = null;
    }

    private void giveUp(HighwayContext context, String why) {
        Helper.HELPER.logDirect("Can't break the end crystal at " + crystal.blockPosition().toShortString() + ": " + why
                + ". Leaving it for " + IGNORE_TICKS / 20 + " s.");
        context.threats().ignoreCrystal(crystal, IGNORE_TICKS);
        finish(context);
    }

    private void finish(HighwayContext context) {
        releaseKeys(context);
        context.baritone().getPathingBehavior().cancelEverything();
        context.scanner().setCrystalLocation(null);
        context.transitionTo(HighwayState.Nothing);
    }

    private void releaseKeys(HighwayContext context) {
        context.baritone().getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
        context.baritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
        context.baritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        if (phase == Phase.EATING) {
            NetherHighwayBuilderBehavior.suppressHitResult = false;
            context.eating().releaseUseToEat();
        }
    }
}
