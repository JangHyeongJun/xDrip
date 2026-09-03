package com.eveningoutpost.dexdrip.cgm.carelinkfollow;

import static com.google.common.truth.Truth.assertThat;

import com.eveningoutpost.dexdrip.RobolectricTestWithConfig;
import com.eveningoutpost.dexdrip.cgm.carelinkfollow.message.Basal;
import com.eveningoutpost.dexdrip.cgm.carelinkfollow.message.RecentData;

import org.junit.Test;

public class CareLinkDataProcessorTest extends RobolectricTestWithConfig {

    @Test
    public void resolveCurrentBasalRate_prefersRecentDataBasalRate() {
        final RecentData recentData = new RecentData();
        recentData.basal = new Basal();
        recentData.basal.basalRate = 0.3d;

        final double result = CareLinkDataProcessor.resolveCurrentBasalRate(recentData,
                0.6d, 1_000L, 2_000L);

        assertThat(result).isWithin(0.000001d).of(0.3d);
    }

    @Test
    public void resolveCurrentBasalRate_fallsBackToMarkerRateWithinSlot() {
        final RecentData recentData = new RecentData();

        final double result = CareLinkDataProcessor.resolveCurrentBasalRate(recentData,
                0.6d, 2_000L, 1_500L);

        assertThat(result).isWithin(0.000001d).of(0.6d);
    }

    @Test
    public void resolveCurrentBasalRate_returnsZeroWhenNoCurrentBasalData() {
        final RecentData recentData = new RecentData();

        final double result = CareLinkDataProcessor.resolveCurrentBasalRate(recentData,
                0.6d, 1_000L, 2_000L);

        assertThat(result).isEqualTo(0d);
    }
}