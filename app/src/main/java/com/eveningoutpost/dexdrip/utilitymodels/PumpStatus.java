package com.eveningoutpost.dexdrip.utilitymodels;


import com.eveningoutpost.dexdrip.GcmActivity;
import com.eveningoutpost.dexdrip.Home;
import com.eveningoutpost.dexdrip.models.DateUtil;
import com.eveningoutpost.dexdrip.models.JoH;
import com.eveningoutpost.dexdrip.models.UserError.Log;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Created by jamorham on 18/08/2017.
 */

public class PumpStatus {

    private static final String TAG = "PumpStatus";
    private static final String PUMP_RESERVOIR = "pump-reservoir";
    private static final String PUMP_BOLUSIOB = "pump-bolusiob";
    private static final String PUMP_BATTERY = "pump-battery";
    private static final String PUMP_BASAL_ABSOLUTE = "pump-basal-absolute";
    private static final String PUMP_BASAL_PATTERN = "pump-basal-pattern";
    private static final String PUMP_GST_BATTERY = "pump-gst-battery";
    private static final String PUMP_RESERVOIR_TRACK = "pump-reservoir-track";
    private static final String PUMP_RESERVOIR_CHANGE = "pump-reservoir-change";
    private static final String TIME = "-time";
    private static final double RESERVOIR_CHANGE_THRESHOLD_UNITS = 1d;
    private static final long RESERVOIR_CHANGE_DEDUP_WINDOW_MS = Constants.HOUR_IN_MS * 6;

    private static String last_json = "";

    public static class ReservoirChange {
        public final long timestamp;
        public final double previousReservoir;
        public final double currentReservoir;

        private ReservoirChange(final long timestamp, final double previousReservoir, final double currentReservoir) {
            this.timestamp = timestamp;
            this.previousReservoir = previousReservoir;
            this.currentReservoir = currentReservoir;
        }
    }

    private static void setValue(String name, double value) {
        if (value < 0) value = -1;
        PersistentStore.setDouble(name, value);
        PersistentStore.setLong(name + TIME, JoH.tsl());
    }

    private static double getValue(String name) {
        long timeout = Constants.MINUTE_IN_MS * 180;  // 3 hours default
        if (name.equals(PUMP_BOLUSIOB)) {
            timeout = Constants.MINUTE_IN_MS * 30;
        } else if (name.equals(PUMP_RESERVOIR)) {
            timeout = Constants.MINUTE_IN_MS * 1440;  // 24 hours for reservoir data
        }

        final long ts = PersistentStore.getLong(name + TIME);
        if ((ts > 1503081681000L) && (JoH.msSince(ts) < timeout)) {
            return PersistentStore.getDouble(name);
        } else {
            return -1;
        }
    }

    private static void setStringValue(String name, String value) {
        PersistentStore.setString(name, value == null ? "" : value);
        PersistentStore.setLong(name + TIME, JoH.tsl());
    }

    private static String getStringValue(String name) {
        final long ts = PersistentStore.getLong(name + TIME);
        if ((ts > 1503081681000L) && (JoH.msSince(ts) < Constants.MINUTE_IN_MS * 180)) {
            return PersistentStore.getString(name);
        } else {
            return "";
        }
    }

    public static void setReservoir(double reservoir) {
        setValue(PUMP_RESERVOIR, reservoir);
    }

    public static ReservoirChange updateReservoir(final double reservoir, final long timestamp) {
        ReservoirChange inferredChange = null;
        final double previousReservoir = PersistentStore.getDouble(PUMP_RESERVOIR_TRACK);
        final long previousTimestamp = PersistentStore.getLong(PUMP_RESERVOIR_TRACK + TIME);

        if (reservoir >= 0 && timestamp > 0 && previousTimestamp > 0 && timestamp > previousTimestamp) {
            final double delta = reservoir - previousReservoir;
            final long lastInferredChange = PersistentStore.getLong(PUMP_RESERVOIR_CHANGE + TIME);
            Log.d(TAG, "Reservoir update: previous=" + JoH.qs(previousReservoir, 1)
                    + "U current=" + JoH.qs(reservoir, 1)
                    + "U delta=" + JoH.qs(delta, 1)
                    + "U threshold=" + JoH.qs(RESERVOIR_CHANGE_THRESHOLD_UNITS, 1) + "U");
            if (delta >= RESERVOIR_CHANGE_THRESHOLD_UNITS
                    && (lastInferredChange == 0 || Math.abs(timestamp - lastInferredChange) > RESERVOIR_CHANGE_DEDUP_WINDOW_MS)) {
                Log.d(TAG, "Reservoir change detected! Inferred refill from " + JoH.qs(previousReservoir, 1) + "U to " + JoH.qs(reservoir, 1) + "U");
                inferredChange = new ReservoirChange(timestamp, previousReservoir, reservoir);
                PersistentStore.setLong(PUMP_RESERVOIR_CHANGE + TIME, timestamp);
            }
        }

        setReservoir(reservoir);
        if (reservoir >= 0 && timestamp > 0) {
            PersistentStore.setDouble(PUMP_RESERVOIR_TRACK, reservoir);
            PersistentStore.setLong(PUMP_RESERVOIR_TRACK + TIME, timestamp);
        }
        return inferredChange;
    }

    private static double getReservoir() {
        return getValue(PUMP_RESERVOIR);
    }

    public static void setBolusIoB(double value) {
        setValue(PUMP_BOLUSIOB, value);
    }

    public static double getBolusIoB() {
        return getValue(PUMP_BOLUSIOB);
    }

    public static void setBattery(double value) {
        setValue(PUMP_BATTERY, value);
    }

    public static double getBattery() {
        return getValue(PUMP_BATTERY);
    }

    public static void setGstBattery(double value) {
        setValue(PUMP_GST_BATTERY, value);
    }

    public static double getGstBattery() {
        return getValue(PUMP_GST_BATTERY);
    }

    public static void setBasalAbsolute(double value) {
        setValue(PUMP_BASAL_ABSOLUTE, value);
    }

    public static double getBasalAbsolute() {
        return getValue(PUMP_BASAL_ABSOLUTE);
    }

    public static void setActiveBasalPattern(String value) {
        setStringValue(PUMP_BASAL_PATTERN, value);
    }

    public static String getActiveBasalPattern() {
        return getStringValue(PUMP_BASAL_PATTERN);
    }

    public static JSONObject toNightscoutJson() {
        final JSONObject json = new JSONObject();
        final double reservoir = getReservoir();
        final double bolusIoB = getBolusIoB();
        final double battery = getBattery();
        final double gstBattery = getGstBattery();
        final double basalAbsolute = getBasalAbsolute();
        final String activeBasalPattern = getActiveBasalPattern();
        final long now = JoH.tsl();
        boolean hasData = false;

        try {
            if (reservoir > -1) {
                json.put("reservoir", reservoir);
                hasData = true;
            }

            if (bolusIoB > -1) {
                final JSONObject iob = new JSONObject();
                iob.put("bolusiob", bolusIoB);
                json.put("iob", iob);
                hasData = true;
            }

            if (battery > -1) {
                final JSONObject batteryJson = new JSONObject();
                batteryJson.put("percent", battery);
                batteryJson.put("status", "normal");
                json.put("battery", batteryJson);
                hasData = true;
            }

            if (basalAbsolute > -1) {
                json.put("basalRate", basalAbsolute);
                hasData = true;
            }

            if (hasData) {
                json.put("clock", DateUtil.toISOString(now));
                final JSONObject status = new JSONObject();
                status.put("status", "normal");
                status.put("timestamp", DateUtil.toISOString(now));
                json.put("status", status);
            }

            if (activeBasalPattern.length() > 0) {
                final JSONObject extended = json.has("extended") ? json.getJSONObject("extended") : new JSONObject();
                extended.put("activeBasalPattern", activeBasalPattern);
                json.put("extended", extended);
                hasData = true;
            }

            if (gstBattery > -1) {
                final JSONObject extended = json.has("extended") ? json.getJSONObject("extended") : new JSONObject();
                extended.put("sensorTransmitterBattery", gstBattery);
                json.put("extended", extended);
                hasData = true;
            }
        } catch (JSONException e) {
            Log.e(TAG, "Got exception building Nightscout PumpStatus " + e);
            return null;
        }

        return hasData ? json : null;
    }

    public static String getReservoirString() {
        final double reservoir = getReservoir();
        if (reservoir > -1) {
            return "\uD83D\uDCDF" + "" + JoH.qs(reservoir, 1) + "U "; // pager emoji
        } else {
            return "";
        }
    }

    public static String getBolusIoBString() {
        final double value = getBolusIoB();
        if (value > -1) {
            // return "\u231B" + " " + JoH.qs(value, 1) + "U ";
            return "\u23F3" + "" + JoH.qs(value, 2) + "U "; // hourglass emoji
        } else {
            return "";
        }
    }

    public static String getBatteryString() {
        final double value = getBattery();
        if (value > -1) {
            return "\uD83D\uDD0B" + "" + JoH.qs(value, 0) + "% "; // battery emoji
        } else {
            return "";
        }
    }

    public static String getBasalAbsoluteString() {
        final double value = getBasalAbsolute();
        if (value > -1) {
            return "B" + JoH.qs(value, 2) + "U/h ";
        } else {
            return "";
        }
    }

    public static String toJson() {
        final JSONObject json = new JSONObject();
        try {
            json.put("reservoir", getReservoir());
            json.put("bolusiob", getBolusIoB());
            json.put("battery", getBattery());
            json.put("gstbattery", getGstBattery());
            json.put("basalabsolute", getBasalAbsolute());
            json.put("activebasalpattern", getActiveBasalPattern());
        } catch (JSONException e) {
            Log.e(TAG, "Got exception building PumpStatus " + e);
        }
        return json.toString();
    }

    public static void fromJson(String msg) {
        try {
            final JSONObject json = new JSONObject(msg);
            setReservoir(json.getDouble("reservoir"));
            setBolusIoB(json.getDouble("bolusiob"));
            setBattery(json.getDouble("battery"));
            setGstBattery(json.optDouble("gstbattery", -1));
            setBasalAbsolute(json.optDouble("basalabsolute", -1));
            setActiveBasalPattern(json.optString("activebasalpattern", ""));
        } catch (Exception e) {
            Log.e(TAG, "Got exception processing json msg: " + e + " " + msg);
        }
    }

    public static synchronized void syncUpdate() {
        if (Home.get_master()) {
            final String current_json = toJson();
            if (current_json.equals(last_json)) {
                Log.d(TAG, "No sync as data is identical");
            } else {
                Log.d(TAG, "Sending update: " + current_json);
                if (GcmActivity.sendPumpStatus(current_json)) {
                    last_json = current_json;
                }
            }
        }
    }
}
