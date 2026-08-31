package gg.mineral.bot.base.client.combat;

/**
 * Optional legacy movement-input recovery applied after knockback packets.
 * The state is kept outside the player class so the exact first/second-hit
 * behaviour can be tested without constructing a Minecraft client.
 */
public final class VelocityInputRecovery {
    static final int RECOVERY_TICKS = 2;
    static final int TRADE_WINDOW_TICKS = 20;
    static final int MAX_RECOVERY_IMPULSES = 2;

    private int remainingInputTicks;
    private int consecutiveImpulses;
    private int lastImpulseTick = -1_000;

    public void onVelocityImpulse(
            boolean enabled,
            int currentTick,
            double velocityX,
            double velocityY,
            double velocityZ
    ) {
        if (!enabled) {
            reset();
            return;
        }
        if (velocityX * velocityX + velocityY * velocityY + velocityZ * velocityZ <= 1.0E-4D) {
            return;
        }

        if (currentTick - lastImpulseTick <= TRADE_WINDOW_TICKS) {
            ++consecutiveImpulses;
        } else {
            consecutiveImpulses = 1;
        }
        lastImpulseTick = currentTick;
        remainingInputTicks = consecutiveImpulses <= MAX_RECOVERY_IMPULSES ? RECOVERY_TICKS : 0;
    }

    public float consumeInputScale(boolean enabled) {
        if (!enabled) {
            reset();
            return 1.0F;
        }
        if (remainingInputTicks <= 0) {
            return 1.0F;
        }

        float scale = remainingInputTicks > 1 ? 0.65F : 0.85F;
        --remainingInputTicks;
        return scale;
    }

    private void reset() {
        remainingInputTicks = 0;
        consecutiveImpulses = 0;
        lastImpulseTick = -1_000;
    }
}
