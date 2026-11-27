package dev.kizuna.meridian.currency;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public final class ParcelEscrowTest {
    private static final long DAY = 24_000;

    @Test
    public void sealBindsToAbsoluteDawnAcrossDays() {
        assertEquals(DAY, ParcelEscrow.nextDawn(DAY - 1));
        assertEquals(DAY + DAY, ParcelEscrow.nextDawn(DAY));
    }
}
