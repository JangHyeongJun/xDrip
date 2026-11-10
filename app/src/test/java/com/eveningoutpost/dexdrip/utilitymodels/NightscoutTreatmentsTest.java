package com.eveningoutpost.dexdrip.utilitymodels;

import com.eveningoutpost.dexdrip.RobolectricTestWithConfig;
import com.eveningoutpost.dexdrip.models.DateUtil;
import com.eveningoutpost.dexdrip.models.Treatments;

import org.junit.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

public class NightscoutTreatmentsTest extends RobolectricTestWithConfig {

    @Test
    public void processTreatmentResponse_matchesAllNearbyTreatmentsBeforeCreating() throws Exception {
        final long timestamp = (System.currentTimeMillis() / 1000) * 1000 - 60 * 60 * 1000;
        final Treatments meal = Treatments.create(15d, 0d, timestamp);
        meal.enteredBy = "CareLink Follow";
        meal.save();
        final Treatments bolus = Treatments.create(0d, 0.65d, timestamp + 10_000);
        bolus.enteredBy = "CareLink Follow";
        bolus.save();

        final String response = "["
                + "{\"_id\":\"507f1f77bcf86cd799439011\",\"created_at\":\""
                + DateUtil.toISOString(timestamp)
                + "\",\"eventType\":\"Meal Bolus\",\"enteredBy\":\"CareLink Follow\",\"carbs\":15,\"insulin\":0},"
                + "{\"_id\":\"507f1f77bcf86cd799439012\",\"created_at\":\""
                + DateUtil.toISOString(timestamp + 1_000)
                + "\",\"eventType\":\"Meal Bolus\",\"enteredBy\":\"CareLink Follow\",\"carbs\":0,\"insulin\":0.65}]";

        assertThat(NightscoutTreatments.processTreatmentResponse(response)).isFalse();
        assertThat(Treatments.listByTimestamp(timestamp, 60_000)).hasSize(2);
    }

    @Test
    public void processTreatmentResponse_removesPreviouslyImportedDuplicate() throws Exception {
        final long timestamp = (System.currentTimeMillis() / 1000) * 1000 - 60 * 60 * 1000;
        final Treatments meal = Treatments.create(15d, 0d, timestamp);
        meal.enteredBy = "CareLink Follow";
        meal.save();
        final Treatments bolus = Treatments.create(0d, 0.65d, timestamp + 10_000);
        bolus.enteredBy = "CareLink Follow";
        bolus.save();
        final Treatments duplicate = Treatments.create(0d, 0.65d, timestamp + 1_000, "507f1f77bcf86cd799439012");
        duplicate.enteredBy = "CareLink Follow " + NightscoutUploader.VIA_NIGHTSCOUT_TAG;
        duplicate.save();

        final String response = "[{\"_id\":\"507f1f77bcf86cd799439012\",\"created_at\":\""
                + DateUtil.toISOString(timestamp + 1_000)
                + "\",\"eventType\":\"Meal Bolus\",\"enteredBy\":\"CareLink Follow\",\"carbs\":0,\"insulin\":0.65}]";

        assertThat(NightscoutTreatments.processTreatmentResponse(response)).isTrue();
        assertThat(Treatments.byuuid("507f1f77bcf86cd799439012")).isNull();
        assertThat(Treatments.listByTimestamp(timestamp, 60_000)).hasSize(2);
    }

    @Test
    public void removePreviouslyImportedDuplicates_cleansOnlyNightscoutMirror() {
        final long timestamp = (System.currentTimeMillis() / 1000) * 1000 - 60 * 60 * 1000;
        final Treatments bolus = Treatments.create(0d, 0.65d, timestamp);
        bolus.enteredBy = "CareLink Follow";
        bolus.save();
        final Treatments duplicate = Treatments.create(0d, 0.65d, timestamp + 1_000,
                "507f1f77bcf86cd799439013");
        duplicate.enteredBy = "CareLink Follow " + NightscoutUploader.VIA_NIGHTSCOUT_TAG;
        duplicate.save();
        PersistentStore.setBoolean(NightscoutTreatments.DUPLICATE_TREATMENT_CLEANUP_KEY, false);

        assertThat(NightscoutTreatments.removePreviouslyImportedDuplicates()).isEqualTo(1);
        final List<Treatments> remaining = Treatments.listByTimestamp(timestamp, 60_000);
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).uuid).isEqualTo(bolus.uuid);
    }
}
