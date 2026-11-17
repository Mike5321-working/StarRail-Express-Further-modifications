package com.habitrain.core.game.sre.role.drone;

import com.habitrain.core.game.sre.role.AttackableRoleProp;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * Ephemeral tactical quadcopter entity ({@code habitrain_core:recon_drone}).
 *
 * <p>Hitbox {@code 0.5 × 0.35}, 1 HP, {@code .noSave()}. Never persists across
 * matches, meetings, disconnects, or server restarts.
 */
public final class ReconDroneEntity extends PathfinderMob implements AttackableRoleProp {

    public static final byte MODE_DEPLOYING = 0;
    public static final byte MODE_ACTIVE = 1;
    public static final byte MODE_SWAPPING = 2;
    public static final byte MODE_RECALLING = 3;
    public static final byte MODE_CRASHED = 4;
    public static final byte MODE_SCRAPPED = 5;

    private static final EntityDataAccessor<Optional<UUID>> OWNER =
            SynchedEntityData.defineId(ReconDroneEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Integer> BATTERY =
            SynchedEntityData.defineId(ReconDroneEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Byte> MODE =
            SynchedEntityData.defineId(ReconDroneEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Integer> MODE_TICKS =
            SynchedEntityData.defineId(ReconDroneEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<ItemStack> PAYLOAD =
            SynchedEntityData.defineId(ReconDroneEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<Byte> PAYLOAD_CATEGORY =
            SynchedEntityData.defineId(ReconDroneEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Optional<UUID>> PAYLOAD_MOUNTER =
            SynchedEntityData.defineId(ReconDroneEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Integer> CHARGE_TICKS =
            SynchedEntityData.defineId(ReconDroneEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> OPERATOR_PILOTING =
            SynchedEntityData.defineId(ReconDroneEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> SPRINTING =
            SynchedEntityData.defineId(ReconDroneEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> STRAFE_TILT =
            SynchedEntityData.defineId(ReconDroneEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> FORWARD_TILT =
            SynchedEntityData.defineId(ReconDroneEntity.class, EntityDataSerializers.FLOAT);

    public ReconDroneEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setNoAi(true);
        this.setNoGravity(true);
        this.setSilent(true);
        this.noPhysics = false;
    }

    public static AttributeSupplier.Builder attributes() {
        return createMobAttributes()
                .add(Attributes.MAX_HEALTH, 1.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D);
    }

    @Override
    protected void registerGoals() {}

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(OWNER, Optional.empty());
        builder.define(BATTERY, DroneRules.MAX_BATTERY);
        builder.define(MODE, MODE_DEPLOYING);
        builder.define(MODE_TICKS, DroneRules.DEPLOY_TICKS);
        builder.define(PAYLOAD, ItemStack.EMPTY);
        builder.define(PAYLOAD_CATEGORY, (byte) DronePayloadCategory.NONE.ordinal());
        builder.define(PAYLOAD_MOUNTER, Optional.empty());
        builder.define(CHARGE_TICKS, 0);
        builder.define(OPERATOR_PILOTING, false);
        builder.define(SPRINTING, false);
        builder.define(STRAFE_TILT, 0.0F);
        builder.define(FORWARD_TILT, 0.0F);
    }

    public void setup(UUID ownerUuid, int initialBattery, float yaw, float pitch) {
        entityData.set(OWNER, Optional.of(ownerUuid));
        entityData.set(BATTERY, DroneRules.clampBattery(initialBattery));
        entityData.set(MODE, MODE_DEPLOYING);
        entityData.set(MODE_TICKS, DroneRules.DEPLOY_TICKS);
        entityData.set(OPERATOR_PILOTING, true);
        setYRot(yaw);
        setYHeadRot(yaw);
        setYBodyRot(yaw);
        setXRot(pitch);
    }

    public @Nullable UUID ownerId() {
        return entityData.get(OWNER).orElse(null);
    }

    public int battery() {
        return entityData.get(BATTERY);
    }

    public void setBattery(int value) {
        entityData.set(BATTERY, DroneRules.clampBattery(value));
    }

    public byte droneMode() {
        return entityData.get(MODE);
    }

    public void setDroneMode(byte mode, int ticks) {
        entityData.set(MODE, mode);
        entityData.set(MODE_TICKS, Math.max(0, ticks));
        setNoGravity(mode != MODE_CRASHED && mode != MODE_SCRAPPED);
    }

    public int modeTicks() {
        return entityData.get(MODE_TICKS);
    }

    public void setModeTicks(int ticks) {
        entityData.set(MODE_TICKS, Math.max(0, ticks));
    }

    public ItemStack payloadStack() {
        return entityData.get(PAYLOAD);
    }

    public DronePayloadCategory payloadCategory() {
        return DronePayloadCategory.byId(entityData.get(PAYLOAD_CATEGORY));
    }

    public @Nullable UUID payloadMounterId() {
        return entityData.get(PAYLOAD_MOUNTER).orElse(null);
    }

    public void setPayload(ItemStack stack, DronePayloadCategory category) {
        setPayload(stack, category, null);
    }

    public void setPayload(ItemStack stack, DronePayloadCategory category, @Nullable UUID mounterUuid) {
        if (stack == null || stack.isEmpty() || category == null || !category.hasPayload()) {
            entityData.set(PAYLOAD, ItemStack.EMPTY);
            entityData.set(PAYLOAD_CATEGORY, (byte) DronePayloadCategory.NONE.ordinal());
            entityData.set(PAYLOAD_MOUNTER, Optional.empty());
        } else {
            entityData.set(PAYLOAD, stack.copy());
            entityData.set(PAYLOAD_CATEGORY, (byte) category.ordinal());
            entityData.set(PAYLOAD_MOUNTER, Optional.ofNullable(mounterUuid));
        }
    }

    public int chargeTicks() {
        return entityData.get(CHARGE_TICKS);
    }

    public void setChargeTicks(int ticks) {
        entityData.set(CHARGE_TICKS, Math.max(0, ticks));
    }

    public boolean isOperatorPiloting() {
        return entityData.get(OPERATOR_PILOTING);
    }

    public void setOperatorPiloting(boolean piloting) {
        entityData.set(OPERATOR_PILOTING, piloting);
    }

    public boolean isDroneSprinting() {
        return entityData.get(SPRINTING);
    }

    public void setDroneSprinting(boolean sprinting) {
        entityData.set(SPRINTING, sprinting);
    }

    public float strafeTilt() {
        return entityData.get(STRAFE_TILT);
    }

    public float forwardTilt() {
        return entityData.get(FORWARD_TILT);
    }

    public void setTilt(float strafe, float forward) {
        entityData.set(STRAFE_TILT, strafe);
        entityData.set(FORWARD_TILT, forward);
    }

    public boolean isCrashed() {
        return droneMode() == MODE_CRASHED;
    }

    public boolean isScrapped() {
        return droneMode() == MODE_SCRAPPED;
    }

    public boolean isAttackableProp() {
        return !isRemoved() && droneMode() != MODE_RECALLING && droneMode() != MODE_SCRAPPED;
    }

    @Override
    public boolean isAttackableRoleProp() {
        return isAttackableProp();
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean isPickable() {
        return !isRemoved() && droneMode() != MODE_RECALLING;
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    @Override
    public void push(double x, double y, double z) {}

    @Override
    public boolean causeFallDamage(float fallDistance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (!level().isClientSide && isAttackableProp() && amount > 0.0F
                && (source.getEntity() != null || source.getDirectEntity() != null || source.is(DamageTypeTags.IS_EXPLOSION))) {
            DroneOperatorService.destroyDrone(this, true, source.getEntity());
            return true;
        }
        return false;
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (!DroneOperatorService.shouldConsumeDirectEntityInteract(this, player)) {
            return InteractionResult.PASS;
        }
        if (level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            return DroneOperatorService.handleEntityInteract(this, serverPlayer);
        }
        return InteractionResult.PASS;
    }

    @Override
    public void tick() {
        super.tick();
        if (level() instanceof ServerLevel serverLevel) {
            if (isScrapped()) {
                if (!DroneOperatorService.isTrackedScrapped(this)) {
                    discard();
                    return;
                }
                setNoGravity(false);
                Vec3 vel = getDeltaMovement();
                double nextVy = onGround() ? 0.0D : Math.max(-0.72D, vel.y - 0.065D);
                Vec3 next = new Vec3(vel.x * 0.60D, nextVy, vel.z * 0.60D);
                setDeltaMovement(next);
                move(MoverType.SELF, next);
                if (tickCount % 4 == 0) {
                    serverLevel.sendParticles(ParticleTypes.LARGE_SMOKE,
                            getX(), getY() + 0.16D, getZ(), 2, 0.09D, 0.05D, 0.09D, 0.015D);
                }
                if (tickCount % 6 == 0) {
                    serverLevel.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                            getX(), getY() + 0.14D, getZ(), 4, 0.14D, 0.08D, 0.14D, 0.05D);
                }
                int rem = modeTicks() - 1;
                if (rem <= 0) {
                    spawnDestructionBurst(serverLevel);
                    DroneOperatorService.untrackScrapped(this);
                    discard();
                } else {
                    setModeTicks(rem);
                }
                return;
            }
            if (!DroneOperatorService.owns(this)) {
                discard();
                return;
            }
            if (isCrashed()) {
                setNoGravity(false);
                Vec3 vel = getDeltaMovement();
                double nextVy = onGround() ? 0.0D : Math.max(-0.65D, vel.y - 0.055D);
                Vec3 next = new Vec3(vel.x * 0.65D, nextVy, vel.z * 0.65D);
                setDeltaMovement(next);
                move(MoverType.SELF, next);
                if (tickCount % 6 == 0) {
                    serverLevel.sendParticles(ParticleTypes.LARGE_SMOKE,
                            getX(), getY() + 0.18D, getZ(), 2, 0.08D, 0.05D, 0.08D, 0.01D);
                }
                if (tickCount % 10 == 0) {
                    serverLevel.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                            getX(), getY() + 0.15D, getZ(), 3, 0.12D, 0.08D, 0.12D, 0.03D);
                }
            } else {
                setNoGravity(true);
            }
        }
    }

    public void spawnDestructionBurst(ServerLevel level) {
        level.sendParticles(ParticleTypes.EXPLOSION, getX(), getY() + 0.2D, getZ(), 2, 0.12D, 0.12D, 0.12D, 0.02D);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, getX(), getY() + 0.2D, getZ(), 28, 0.25D, 0.2D, 0.25D, 0.18D);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, getX(), getY() + 0.2D, getZ(), 18, 0.2D, 0.15D, 0.2D, 0.04D);
        level.sendParticles(ParticleTypes.CRIT, getX(), getY() + 0.2D, getZ(), 16, 0.22D, 0.18D, 0.22D, 0.15D);
        level.playSound(null, blockPosition(), SoundEvents.ITEM_BREAK, SoundSource.PLAYERS, 1.0F, 0.75F);
        level.playSound(null, blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.55F, 1.55F);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
    }
}
