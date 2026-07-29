package com.cameralock.client;

import com.cameralock.client.config.CameraLockConfig;
import com.cameralock.client.config.CameraLockConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Random;

public final class CameraLockManager {
	private static boolean enabled;
	private static LivingEntity currentTarget;
	private static boolean filterInitialized;

	private static float filteredYaw;
	private static float filteredPitch;
	private static float lastYaw;
	private static float lastPitch;

	private static int attackCooldown;
	private static int jumpCooldown;
	private static final Random RANDOM = new Random();
	
	private static double currentOffsetX, currentOffsetY, currentOffsetZ;
	private static double targetOffsetX, targetOffsetY, targetOffsetZ;
	private static int offsetTimer;

	private CameraLockManager() {
	}

	public static boolean toggle() {
		enabled = !enabled;
		filterInitialized = false;
		attackCooldown = 0;
		jumpCooldown = 0;
		if (!enabled) currentTarget = null;
		return enabled;
	}

	public static boolean isEnabled() {
		return enabled;
	}

	public static boolean isLockingActive() {
		if (!enabled || currentTarget == null || !currentTarget.isAlive()) return false;
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		if (player == null) return false;
		
		CameraLockConfig config = CameraLockConfigManager.get();
		if (player.distanceTo(currentTarget) > config.lockRadius) return false;
		if (config.checkVisibility && !isVisibleStrict(player, currentTarget)) return false;

		return true;
	}

	public static void tick(Minecraft client) {
		if (!enabled) return;
		LocalPlayer player = client.player;
		if (player == null || player.level() == null) return;

		CameraLockConfig config = CameraLockConfigManager.get();
		
		boolean currentValid = currentTarget != null && isValidTarget(player, currentTarget, config);
		if (config.stickyTarget && currentValid && isInFOV(player, currentTarget, config.lockFOV)) {
			// Keep target
		} else {
			LivingEntity bestNew = findBestTarget(player, config);
			if (bestNew != null && bestNew != currentTarget) {
				currentTarget = bestNew;
			}
		}

		if (currentTarget == null || !currentTarget.isAlive()) {
			currentTarget = null;
			filterInitialized = false;
			return;
		}

		if (config.randomizeTarget) {
			if (offsetTimer <= 0) {
				float height = currentTarget.getBbHeight();
				float width = currentTarget.getBbWidth();
				targetOffsetX = (RANDOM.nextDouble() - 0.5) * width * 0.4;
				double torsoPoint = (0.4 + RANDOM.nextDouble() * 0.25) * height;
				targetOffsetY = torsoPoint - currentTarget.getEyeHeight();
				targetOffsetZ = (RANDOM.nextDouble() - 0.5) * width * 0.4;
				offsetTimer = 10 + RANDOM.nextInt(15);
			}
			offsetTimer--;
		} else {
			targetOffsetX = targetOffsetY = targetOffsetZ = 0;
		}

		if (config.autoAttack && attackCooldown <= 0) {
			tryAttack(client, player, currentTarget, config);
		}
		if (attackCooldown > 0) attackCooldown--;
		if (jumpCooldown > 0) jumpCooldown--;
	}

	public static void applyLook(LocalPlayer player, float tickDelta) {
		if (!enabled || currentTarget == null || !currentTarget.isAlive()) return;

		CameraLockConfig config = CameraLockConfigManager.get();
		if (player.distanceTo(currentTarget) > config.lockRadius) return;
		if (config.checkVisibility && !isVisibleStrict(player, currentTarget)) return;

		if (!filterInitialized) {
			filteredYaw = player.getYRot();
			filteredPitch = player.getXRot();
			lastYaw = filteredYaw;
			lastPitch = filteredPitch;
			filterInitialized = true;
		}

		float offsetLerp = 0.04f; 
		currentOffsetX = Mth.lerp(offsetLerp, currentOffsetX, targetOffsetX);
		currentOffsetY = Mth.lerp(offsetLerp, currentOffsetY, targetOffsetY);
		currentOffsetZ = Mth.lerp(offsetLerp, currentOffsetZ, targetOffsetZ);

		double targetX = Mth.lerp(tickDelta, currentTarget.xo, currentTarget.getX()) + currentOffsetX;
		double targetY = Mth.lerp(tickDelta, currentTarget.yo, currentTarget.getY()) + currentTarget.getEyeHeight() + currentOffsetY;
		double targetZ = Mth.lerp(tickDelta, currentTarget.zo, currentTarget.getZ()) + currentOffsetZ;
		
		Vec3 eyePosition = player.getEyePosition(tickDelta);
		double deltaX = targetX - eyePosition.x;
		double deltaY = targetY - eyePosition.y;
		double deltaZ = targetZ - eyePosition.z;
		double horizontalDistance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

		float targetYaw = (float) (Mth.atan2(deltaZ, deltaX) * (180.0D / Math.PI)) - 90.0F;
		float targetPitch = (float) (-(Mth.atan2(deltaY, horizontalDistance) * (180.0D / Math.PI)));

		float yawDiff = Mth.wrapDegrees(targetYaw - filteredYaw);
		float pitchDiff = targetPitch - filteredPitch;

		float lerp = (float) (0.22f * (6.0 / Math.max(1.0, config.smoothness)));
		
		// Increased limit to at least 2.5 degrees per frame for better reactivity
		float maxStep = 2.5f; 
		
		lastYaw = filteredYaw;
		lastPitch = filteredPitch;
		
		filteredYaw += Mth.clamp(yawDiff * lerp, -maxStep, maxStep);
		filteredPitch += Mth.clamp(pitchDiff * lerp, -maxStep, maxStep);

		player.setYRot(filteredYaw);
		player.setXRot(Mth.clamp(filteredPitch, -90.0F, 90.0F));
		
		player.yRotO = lastYaw;
		player.xRotO = lastPitch;
		player.yHeadRot = filteredYaw;
		player.yHeadRotO = lastYaw;
	}

	private static void tryAttack(Minecraft client, LocalPlayer player, LivingEntity target, CameraLockConfig config) {
		if (client.gameMode == null) return;
		if (player.distanceTo(target) > 4.5D) return;

		if (!isLookingAtEntityDirectly(client, player, target)) return;

		ItemStack mainHandItem = player.getMainHandItem();
		boolean isMace = mainHandItem.is(Items.MACE);
		
		String itemPath = BuiltInRegistries.ITEM.getKey(mainHandItem.getItem()).getPath();
		boolean isSword = itemPath.contains("sword");

		if (config.autoCriticals && !config.swordMode18 && !isMace) {
			float charge = player.getAttackStrengthScale(0.0F);
			if (player.onGround() && charge > 0.65F && jumpCooldown <= 0) {
				player.jumpFromGround();
				jumpCooldown = 15;
				return;
			}
			if (!player.onGround() && player.fallDistance > 0.08F && charge >= 0.9F) {
				performAttack(client, player, target);
				attackCooldown = 3;
				return;
			}
			if (!player.onGround() || charge < 0.9F) return;
		}

		if (isMace && config.maceLogic) {
			if (player.fallDistance > config.maceMinFallDistance && player.getY() > target.getY()) {
				performAttack(client, player, target);
				attackCooldown = 5;
			}
			return;
		}

		if (isSword && config.swordMode18) {
			performAttack(client, player, target);
			attackCooldown = 4 + RANDOM.nextInt(2); 
			return;
		}

		if (player.getAttackStrengthScale(0.0F) >= 0.9F) {
			performAttack(client, player, target);
			attackCooldown = 2 + RANDOM.nextInt(2);
		}
	}

	private static boolean isLookingAtEntityDirectly(Minecraft client, LocalPlayer player, LivingEntity target) {
		HitResult hitResult = client.hitResult;
		if (hitResult != null && hitResult.getType() == HitResult.Type.ENTITY) {
			return ((EntityHitResult) hitResult).getEntity() == target;
		}
		return false;
	}

	private static boolean isVisibleStrict(LocalPlayer player, LivingEntity target) {
		Vec3 start = player.getEyePosition();
		Vec3 end = target.getEyePosition();
		HitResult result = player.level().clip(new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		return result.getType() == HitResult.Type.MISS;
	}

	private static boolean isInFOV(LocalPlayer player, LivingEntity target, double fov) {
		if (fov >= 360) return true;
		float targetYaw = (float) (Mth.atan2(target.getZ() - player.getZ(), target.getX() - player.getX()) * (180.0D / Math.PI)) - 90.0F;
		float angleDiff = Math.abs(Mth.wrapDegrees(targetYaw - player.getYRot()));
		return angleDiff <= fov / 2.0;
	}

	private static void performAttack(Minecraft client, LocalPlayer player, LivingEntity target) {
		if (client.gameMode != null) {
			client.gameMode.attack(player, target);
			player.swing(InteractionHand.MAIN_HAND);
		}
	}

	private static LivingEntity findBestTarget(LocalPlayer player, CameraLockConfig config) {
		if (!config.targetPlayers && !config.targetMobs) return null;
		AABB searchBox = player.getBoundingBox().inflate(config.lockRadius);
		List<LivingEntity> candidates = player.level().getEntitiesOfClass(LivingEntity.class, searchBox,
			entity -> isValidTarget(player, entity, config) && isInFOV(player, entity, config.lockFOV));

		if (config.prioritizeLowHP) {
			return candidates.stream().min(Comparator.comparingDouble(LivingEntity::getHealth).thenComparingDouble(player::distanceTo)).orElse(null);
		}
		return candidates.stream().min(Comparator.comparingDouble(player::distanceTo)).orElse(null);
	}

	private static boolean isValidTarget(LocalPlayer player, LivingEntity entity, CameraLockConfig config) {
		if (entity == player || !entity.isAlive()) return false;
		if (player.distanceTo(entity) > config.lockRadius) return false;
		if (config.checkVisibility && !player.hasLineOfSight(entity)) return false;
		return entity instanceof Player ? config.targetPlayers : config.targetMobs;
	}
}