package com.favasur.blockyplanet.player;

import com.favasur.blockyplanet.BlockyPlanetMod;
import com.favasur.blockyplanet.config.BlockyPlanetConfig;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Handles custom radial gravity for the Blocky Planet dimension.
 *
 * Players are attracted toward the planet center (origin). Gravity decreases
 * near the center. When SPACE is held, a thruster counteracts gravity.
 * The player's up-vector is smoothly rotated to align with the planet surface.
 *
 * Reference: https://www.bowerbyte.com/posts/blocky-planet/#player-gravity
 */
public final class CustomGravity {

    static final double SURFACE_GRAVITY = 0.08;
    /** Gravity reaches zero at this fraction of the planet radius from center. */
    static final double GRAVITY_FALLOFF_FRACTION = 0.125;
    static final double THRUSTER_ACCELERATION = 0.15;
    static final double ROTATION_SPEED = 0.12;

    // ─── Thruster held-state tracking ───────────────────────────────────────
    // The mixin detects jump presses (velocity-based). We track the held state
    // so the thruster stays active as long as the player keeps pressing jump.
    private static final java.util.Map<PlayerEntity, Boolean> THRUSTER_HELD = new java.util.WeakHashMap<>();

    private CustomGravity() {}

    /**
     * Apply custom gravity and thruster for one tick.
     *
     * @param player      The player
     * @param jumpPressed True on the tick the jump key is initially pressed
     * @return true if custom gravity was applied (player is in Blocky Planet dimension)
     */
    public static boolean applyGravity(PlayerEntity player, boolean jumpPressed) {
        if (!isBlockyPlanetDimension(player.getWorld())) {
            return false;
        }

        // Update thruster held-state
        if (jumpPressed) {
            THRUSTER_HELD.put(player, true);
        }
        boolean spaceHeld = THRUSTER_HELD.getOrDefault(player, false);
        // Release when player touches ground
        if (player.isOnGround() || player.isTouchingWater()) {
            spaceHeld = false;
            THRUSTER_HELD.remove(player);
        }

        Vec3d pos = player.getPos();
        Vec3d toCenter = Vec3d.ZERO.subtract(pos);
        double dist = toCenter.length();

        if (dist < 0.001) return true;

        Vec3d down = toCenter.normalize();
        Vec3d up = down.negate();

        // ─── Gravity ────────────────────────────────────────────────────
        double gravityStrength = SURFACE_GRAVITY;
        double falloffRadius = BlockyPlanetConfig.getPlanetRadius() * GRAVITY_FALLOFF_FRACTION;
        if (dist < falloffRadius) {
            gravityStrength *= Math.max(0, dist / falloffRadius);
        }
        Vec3d gravity = down.multiply(gravityStrength);
        player.addVelocity(gravity.x, gravity.y, gravity.z);

        // ─── Thruster (SPACE while airborne) ─────────────────────────────
        boolean airborne = !player.isOnGround() && !player.isTouchingWater();
        if (airborne && spaceHeld) {
            Vec3d thrust = up.multiply(THRUSTER_ACCELERATION);
            player.addVelocity(thrust.x, thrust.y, thrust.z);
            player.addVelocity(gravity.x * -0.6, gravity.y * -0.6, gravity.z * -0.6);

            // Set fall distance to 0 to prevent fall damage while thrusting
            player.fallDistance = 0;
        }

        // ─── Smooth rotation (client side only) ─────────────────────────
        if (player.getWorld().isClient) {
            rotateTowardsUp(player, up);
        }

        return true;
    }

    private static void rotateTowardsUp(PlayerEntity player, Vec3d desiredUp) {
        Vec3d lookVec = player.getRotationVec(1.0f);

        // Project look direction onto the plane perpendicular to desiredUp
        Vec3d projectedLook = lookVec.subtract(
            desiredUp.multiply(lookVec.dotProduct(desiredUp))
        );
        if (projectedLook.lengthSquared() < 0.001) {
            projectedLook = new Vec3d(1, 0, 0);
        } else {
            projectedLook = projectedLook.normalize();
        }

        double targetPitch = Math.asin(-projectedLook.y) * (180.0 / Math.PI);
        double targetYaw = Math.atan2(-projectedLook.x, -projectedLook.z) * (180.0 / Math.PI);

        targetPitch = MathHelper.clamp(targetPitch, -80, 80);

        double newPitch = player.getPitch() + (targetPitch - player.getPitch()) * ROTATION_SPEED;
        double newYaw = MathHelper.lerpAngleDegrees(player.getYaw(), (float) targetYaw, (float) ROTATION_SPEED);

        player.setPitch((float) newPitch);
        player.setYaw((float) newYaw);
    }

    /**
     * Check if the given world is the Blocky Planet dimension.
     * Compares the dimension's registry key against our dimension ID.
     */
    private static boolean isBlockyPlanetDimension(World world) {
        return world.getRegistryKey().getValue().equals(BlockyPlanetMod.DIMENSION_ID);
    }
}
