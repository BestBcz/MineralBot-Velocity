package gg.mineral.bot.base.client.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VelocityInputRecoveryTest {

    @Test
    void disabledRecoveryNeverScalesTheFirstThreeHits() {
        VelocityInputRecovery recovery = new VelocityInputRecovery();

        for (int hit = 0; hit < 3; ++hit) {
            recovery.onVelocityImpulse(false, hit * 5, 0.4D, 0.4D, 0.1D);
            assertEquals(1.0F, recovery.consumeInputScale(false));
            assertEquals(1.0F, recovery.consumeInputScale(false));
        }
    }

    @Test
    void enabledRecoveryKeepsTheLegacyFirstAndSecondHitScales() {
        VelocityInputRecovery recovery = new VelocityInputRecovery();

        recovery.onVelocityImpulse(true, 0, 0.4D, 0.4D, 0.1D);
        assertEquals(0.65F, recovery.consumeInputScale(true));
        assertEquals(0.85F, recovery.consumeInputScale(true));

        recovery.onVelocityImpulse(true, 5, 0.4D, 0.4D, 0.1D);
        assertEquals(0.65F, recovery.consumeInputScale(true));
        assertEquals(0.85F, recovery.consumeInputScale(true));

        recovery.onVelocityImpulse(true, 10, 0.4D, 0.4D, 0.1D);
        assertEquals(1.0F, recovery.consumeInputScale(true));
        assertEquals(1.0F, recovery.consumeInputScale(true));
    }
}
