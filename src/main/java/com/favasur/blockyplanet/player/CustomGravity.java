package com.favasur.blockyplanet.player;

import com.favasur.blockyplanet.BlockyPlanetMod;
import com.favasur.blockyplanet.config.BlockyPlanetConfig;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Handles custom radial gravity for the Blocky Planet dimension.
 *
 * Players are attracted toward the planet center (origin). Gravity decreases
 * near the center. When SPACE is held, a thruster counteracts gravity.
 * The player's up-vector is smoothly rotated to align with the planet surface.
 *
 * Reference: https://www.favasur.com/posts/blocky-planet/#player-gravity
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
    private static final java.util.Map<Player, Boolean> THRUSTER_HELD = new java.util.WeakHashMap<>();

    private CustomGravity() {}

    /**
     * Apply custom gravity and thruster for one tick.
     *
     * @param player      The player
     * @param jumpPressed True on the tick the jump key is initially pressed
     * @return true if custom gravity was applied (player is in Blocky Planet dimension)
     */
    public static boolean applyGravity(Player player, boolean jumpPressed) {
        if (!isBlockyPlanetDimension(player.level())) {
            return false;
        }

        // Update thruster held-state
        if (jumpPressed) {
            THRUSTER_HELD.put(player, true);
        }
        boolean spaceHeld = THRUSTER_HELD.getOrDefault(player, false);
        // Release when player touches ground
        if (player.onGround() || player.isInWater()) {
            spaceHeld = false;
            THRUSTER_HELD.remove(player);
        }

        Vec3 pos = player.position();
        Vec3 toCenter = Vec3.ZERO.subtract(pos);
        double dist = toCenter.length();

        if (dist < 0.001) return true;

        Vec3 down = toCenter.normalize();
        Vec3 up = down.scale(-1);

        // ─── Gravity ────────────────────────────────────────────────────
        double gravityStrength = SURFACE_GRAVITY;
        double falloffRadius = BlockyPlanetConfig.getPlanetRadius() * GRAVITY_FALLOFF_FRACTION;
        if (dist < falloffRadius) {
            gravityStrength *= Math.max(0, dist / falloffRadius);
        }
        Vec3 gravity = down.scale(gravityStrength);
        player.push(gravity.x, gravity.y, gravity.z);

        // ─── Thruster (SPACE while airborne) ─────────────────────────────
        boolean airborne = !player.onGround() && !player.isInWater();
        if (airborne && spaceHeld) {
            Vec3 thrust = up.scale(THRUSTER_ACCELERATION);
            player.push(thrust.x, thrust.y, thrust.z);
            player.push(gravity.x * -0.6, gravity.y * -0.6, gravity.z * -0.6);

            // Set fall distance to 0 to prevent fall damage while thrusting
            player.fallDistance = 0;
        }

        // ─── Smooth rotation (client side only) ─────────────────────────
        if (player.level().isClientSide) {
            rotateTowardsUp(player, up);
        }

        return true;
    }

    private static void rotateTowardsUp(Player player, Vec3 desiredUp) {
        Vec3 lookVec = player.getLookAngle();

        // Project look direction onto the plane perpendicular to desiredUp
        Vec3 projectedLook = lookVec.subtract(
            desiredUp.scale(lookVec.dot(desiredUp))
        );
        if (projectedLook.lengthSqr() < 0.001) {
            projectedLook = new Vec3(1, 0, 0);
        } else {
            projectedLook = projectedLook.normalize();
        }

        double targetPitch = Math.asin(-projectedLook.y) * (180.0 / Math.PI);
        double targetYaw = Math.atan2(-projectedLook.x, -projectedLook.z) * (180.0 / Math.PI);

        targetPitch = Mth.clamp(targetPitch, -80, 80);

        double newPitch = player.getXRot() + (targetPitch - player.getXRot()) * ROTATION_SPEED;
        double newYaw = Mth.rotLerp(player.getYRot(), (float) targetYaw, (float) ROTATION_SPEED);

        player.setXRot((float) newPitch);
        player.setYRot((float) newYaw);
    }

    /**
     * Check if the given world is a Blocky Planet dimension.
     * Delegates to the shared check in {@link BlockyPlanetMod} which handles
     * both the custom dimension AND the overworld override (when Tellus is
     * not loaded).
     */
    private static boolean isBlockyPlanetDimension(Level world) {
        return BlockyPlanetMod.isBlockyPlanetDimension(world);
    }
}
