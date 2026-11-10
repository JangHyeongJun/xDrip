package com.eveningoutpost.dexdrip.cgm.carelinkfollow.message;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

public class TextMapTest {

    @Test
    public void simpleraNotificationCodesUseNgpMessageMap() {
        assertThat(TextMap.getNotificationMessage(RecentData.DEVICE_FAMILY_SIMPLERA, "N797", null))
                .isEqualTo("Sensor Connected");
    }
}