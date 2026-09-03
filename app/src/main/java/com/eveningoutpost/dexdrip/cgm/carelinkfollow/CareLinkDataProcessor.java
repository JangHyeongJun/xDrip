package com.eveningoutpost.dexdrip.cgm.carelinkfollow;

import com.eveningoutpost.dexdrip.Home;
import com.eveningoutpost.dexdrip.models.BgReading;
import com.eveningoutpost.dexdrip.models.APStatus;
import com.eveningoutpost.dexdrip.models.BloodTest;
import com.eveningoutpost.dexdrip.models.DateUtil;
import com.eveningoutpost.dexdrip.models.Sensor;
import com.eveningoutpost.dexdrip.models.Treatments;
import com.eveningoutpost.dexdrip.models.UserError;
import com.eveningoutpost.dexdrip.models.JoH;
import com.eveningoutpost.dexdrip.utilitymodels.Constants;
import com.eveningoutpost.dexdrip.utilitymodels.Inevitable;
import com.eveningoutpost.dexdrip.utilitymodels.Pref;
import com.eveningoutpost.dexdrip.utilitymodels.PumpStatus;
import com.eveningoutpost.dexdrip.cgm.carelinkfollow.message.ActiveNotification;
import com.eveningoutpost.dexdrip.cgm.carelinkfollow.message.Alarm;
import com.eveningoutpost.dexdrip.cgm.carelinkfollow.message.ClearedNotification;
import com.eveningoutpost.dexdrip.cgm.carelinkfollow.message.Marker;
import com.eveningoutpost.dexdrip.cgm.carelinkfollow.message.RecentData;
import com.eveningoutpost.dexdrip.cgm.carelinkfollow.message.SensorGlucose;
import com.eveningoutpost.dexdrip.cgm.carelinkfollow.message.TextMap;
import com.eveningoutpost.dexdrip.g5model.DexSessionKeeper;

import java.text.SimpleDateFormat;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static com.eveningoutpost.dexdrip.models.BgReading.SPECIAL_FOLLOWER_PLACEHOLDER;
import static com.eveningoutpost.dexdrip.models.Treatments.pushTreatmentSyncToWatch;


/**
 * Medtronic CareLink Data Processor
 * - process CareLink data and convert to xDrip internal data
 * - update xDrip internal data
 */
public class CareLinkDataProcessor {


    private static final String TAG = "CareLinkFollowDP";
    private static final boolean D = false;

    private static final String SOURCE_CARELINK_FOLLOW = "CareLink Follow";
    private static final String SENSOR_CONNECTED_MESSAGE = "Sensor Connected";
    private static final long SENSOR_START_DEDUP_WINDOW_MS = Constants.MINUTE_IN_MS * 15;
    private static final long MAX_CARELINK_SENSOR_AGE_MS = Constants.DAY_IN_MS * 15;

    private static final double AUTO_BASAL_MARKER_TO_HOURLY_RATE = 12d;
    private static final long AUTO_BASAL_SLOT_DURATION_MS = Constants.MINUTE_IN_MS * 5;

    private static long getSensorAgeMillis(final RecentData recentData) {
        if (recentData == null) {
            return -1;
        }

        if (recentData.sensorDurationMinutes > 0) {
            return recentData.sensorDurationMinutes * Constants.MINUTE_IN_MS;
        }

        if (recentData.sensorDurationHours > 0) {
            return recentData.sensorDurationHours * Constants.HOUR_IN_MS;
        }

        return -1;
    }

    private static long getBestSensorAnchorTimestamp(final RecentData recentData) {
        long bestTimestamp = -1;

        if (recentData == null) {
            return bestTimestamp;
        }

        if (recentData.lastSensorTSAsDate != null) {
            bestTimestamp = Math.max(bestTimestamp, recentData.lastSensorTSAsDate.getTime());
        }
        if (recentData.dLastSensorTime != null) {
            bestTimestamp = Math.max(bestTimestamp, recentData.dLastSensorTime.getTime());
        }
        if (recentData.lastSG != null && recentData.lastSG.getDate() != null) {
            bestTimestamp = Math.max(bestTimestamp, recentData.lastSG.getDate().getTime());
        }
        if (recentData.medicalDeviceTimeAsDate != null) {
            bestTimestamp = Math.max(bestTimestamp, recentData.medicalDeviceTimeAsDate.getTime());
        }
        if (recentData.dMedicalDeviceTime != null) {
            bestTimestamp = Math.max(bestTimestamp, recentData.dMedicalDeviceTime.getTime());
        }
        if (recentData.lastConduitDateTime != null) {
            bestTimestamp = Math.max(bestTimestamp, recentData.lastConduitDateTime.getTime());
        }

        return bestTimestamp;
    }

    private static Long getSensorConnectedTimestamp(final RecentData recentData) {
        if (recentData == null || recentData.notificationHistory == null) {
            return null;
        }

        long latestTimestamp = -1;

        if (recentData.notificationHistory.activeNotifications != null) {
            for (ActiveNotification notification : recentData.notificationHistory.activeNotifications) {
                latestTimestamp = Math.max(latestTimestamp, getSensorConnectedTimestamp(recentData, notification));
            }
        }

        if (recentData.notificationHistory.clearedNotifications != null) {
            for (ClearedNotification notification : recentData.notificationHistory.clearedNotifications) {
                latestTimestamp = Math.max(latestTimestamp, getSensorConnectedTimestamp(recentData, notification));
            }
        }

        return latestTimestamp > 0 ? latestTimestamp : null;
    }

    private static long getSensorConnectedTimestamp(final RecentData recentData, final com.eveningoutpost.dexdrip.cgm.carelinkfollow.message.Notification notification) {
        if (recentData == null || notification == null || notification.dateTime == null) {
            return -1;
        }

        final String message = TextMap.getNotificationMessage(recentData.getDeviceFamily(), notification.getMessageId(), notification.faultId);
        
        // Support both Guardian and Simplera sensor connected messages
        // Guardian devices may use different message identifier than Simplera (N797)
        if (SENSOR_CONNECTED_MESSAGE.equals(message) || 
            (message != null && message.toLowerCase().contains("sensor connected") && 
             !message.toLowerCase().contains("lost") && !message.toLowerCase().contains("error"))) {
            UserError.Log.d(TAG, "Detected sensor connected event: " + message + " at " + JoH.dateTimeText(notification.dateTime.getTime()) + 
                    " (Device: " + recentData.getDeviceFamily() + ")");
            return notification.dateTime.getTime();
        }

        return -1;
    }

    private static Long inferSensorStartTimestamp(final RecentData recentData) {
        final Long connectedTimestamp = getSensorConnectedTimestamp(recentData);
        if (connectedTimestamp != null && connectedTimestamp > 0) {
            UserError.Log.d(TAG, "Using sensor connected timestamp for start time: " + JoH.dateTimeText(connectedTimestamp));
            return connectedTimestamp;
        }

        final long sensorAgeMillis = getSensorAgeMillis(recentData);
        final long anchorTimestamp = getBestSensorAnchorTimestamp(recentData);
        if (sensorAgeMillis <= 0 || anchorTimestamp <= 0 || sensorAgeMillis > MAX_CARELINK_SENSOR_AGE_MS) {
            if (sensorAgeMillis > MAX_CARELINK_SENSOR_AGE_MS) {
                UserError.Log.d(TAG, "Sensor age exceeds maximum: " + (sensorAgeMillis / Constants.HOUR_IN_MS) + " hours");
            }
            return null;
        }

        final long inferredStart = anchorTimestamp - sensorAgeMillis;
        if (inferredStart > 0) {
            UserError.Log.d(TAG, "Inferred sensor start time: " + JoH.dateTimeText(inferredStart) + 
                    " (age: " + (sensorAgeMillis / Constants.HOUR_IN_MS) + "h, anchor: " + JoH.dateTimeText(anchorTimestamp) + ")");
            return inferredStart;
        }
        return null;
    }

    private static void ensureSensorStartTreatment(final long timestamp, final String notes) {
        final Treatments lastSensorStart = Treatments.lastEventTypeFromXdrip(Treatments.SENSOR_START_EVENT_TYPE);
        if (lastSensorStart == null || Math.abs(lastSensorStart.timestamp - timestamp) >= SENSOR_START_DEDUP_WINDOW_MS) {
            String finalNotes = notes != null ? notes : "";
            try {
                long warmupMs = DexSessionKeeper.getWarmupPeriod();
                if (warmupMs > 0) {
                    long now = JoH.tsl();
                    long elapsed = now - timestamp;
                    if (elapsed < warmupMs) {
                        long remaining = Math.max(0L, warmupMs - elapsed);
                        long mins = Math.round((double) remaining / (double) Constants.MINUTE_IN_MS);
                        finalNotes = finalNotes + " (warmup remaining: " + mins + " min)";
                    }
                }
            } catch (Exception e) {
                UserError.Log.d(TAG, "Could not compute warmup remaining: " + e);
            }
            Treatments.sensorStart(timestamp, finalNotes);
        }
    }

    private static void syncSensorSession(final RecentData recentData) {
        final Long inferredStartTimestamp = inferSensorStartTimestamp(recentData);
        if (inferredStartTimestamp == null || inferredStartTimestamp <= 0) {
            return;
        }

        final long now = JoH.tsl();
        if (inferredStartTimestamp > now + Constants.MINUTE_IN_MS * 5 || now - inferredStartTimestamp > MAX_CARELINK_SENSOR_AGE_MS) {
            return;
        }

        final Sensor currentSensor = Sensor.currentSensor();
        final Long connectedTimestamp = getSensorConnectedTimestamp(recentData);
        final boolean confirmedBySensorConnected = connectedTimestamp != null;

        if (currentSensor == null) {
            Sensor.create(inferredStartTimestamp);
            if (confirmedBySensorConnected) {
                ensureSensorStartTreatment(inferredStartTimestamp, "Started by CareLink sensor connected");
            }
            UserError.Log.i(TAG, "Created CareLink sensor session at " + JoH.dateTimeText(inferredStartTimestamp)
                    + (confirmedBySensorConnected ? " (sensor connected)" : " (age inference)"));
            return;
        }

        if (currentSensor.started_at + SENSOR_START_DEDUP_WINDOW_MS < inferredStartTimestamp) {
            if (confirmedBySensorConnected) {
                Sensor.create(inferredStartTimestamp);
                ensureSensorStartTreatment(inferredStartTimestamp, "Started by CareLink sensor connected");
                UserError.Log.i(TAG, "Started new CareLink sensor session at " + JoH.dateTimeText(inferredStartTimestamp));
            } else {
                UserError.Log.d(TAG, "Skipping sensor session change from age inference (inferred: "
                        + JoH.dateTimeText(inferredStartTimestamp) + ", current: " + JoH.dateTimeText((long) currentSensor.started_at) + ")");
            }
            return;
        }

        if (confirmedBySensorConnected && inferredStartTimestamp + SENSOR_START_DEDUP_WINDOW_MS < currentSensor.started_at) {
            currentSensor.started_at = inferredStartTimestamp;
            currentSensor.save();
            ensureSensorStartTreatment(inferredStartTimestamp, "Start time updated from CareLink sensor connected");
            UserError.Log.i(TAG, "Updated CareLink sensor start time to " + JoH.dateTimeText(inferredStartTimestamp));
        }
    }

    private static Double getAutoBasalRateFromMarker(final Marker marker) {
        if (marker == null || !Marker.MARKER_TYPE_AUTO_BASAL.equals(marker.type)) {
            return null;
        }

        Float deliveredAmount = null;
        if (marker.data != null && marker.data.dataValues != null && marker.data.dataValues.bolusAmount != null) {
            deliveredAmount = marker.data.dataValues.bolusAmount;
        } else if (marker.bolusAmount != null) {
            deliveredAmount = marker.bolusAmount;
        }

        if (deliveredAmount == null || deliveredAmount <= 0) {
            return null;
        }

        return deliveredAmount * AUTO_BASAL_MARKER_TO_HOURLY_RATE;
    }

    private static long getAutoBasalSlotEndTimestamp(final Marker marker) {
        return marker.getDate().getTime() + AUTO_BASAL_SLOT_DURATION_MS;
    }

    private static long getLatestApStatusTimestamp() {
        final APStatus last = APStatus.last();
        return last != null ? last.timestamp : Long.MIN_VALUE;
    }

    private static void recordAutoBasalSlot(final long timestamp, final double autoBasalRate, final long cutoffTimestamp) {
        if (timestamp <= cutoffTimestamp) {
            return;
        }
        APStatus.createRecordAtTimestamp(timestamp, autoBasalRate);
    }

    static double resolveCurrentBasalRate(final RecentData recentData,
                                          final Double latestAutoBasalRate,
                                          final long latestAutoBasalEndTimestamp,
                                          final long pumpStatusTimestamp) {
        if (recentData != null && recentData.basal != null && recentData.basal.basalRate != null) {
            return Math.max(0d, recentData.basal.basalRate);
        }

        if (latestAutoBasalRate != null && pumpStatusTimestamp <= latestAutoBasalEndTimestamp) {
            return latestAutoBasalRate;
        }

        return 0d;
    }

    private static long getPumpStatusTimestamp(final RecentData recentData) {
        if (recentData == null) {
            return JoH.tsl();
        }
        if (recentData.lastConduitDateTime != null) {
            return recentData.lastConduitDateTime.getTime();
        }
        if (recentData.medicalDeviceTimeAsDate != null) {
            return recentData.medicalDeviceTimeAsDate.getTime();
        }
        if (recentData.dMedicalDeviceTime != null) {
            return recentData.dMedicalDeviceTime.getTime();
        }
        if (recentData.lastSG != null && recentData.lastSG.datetimeAsDate != null) {
            return recentData.lastSG.datetimeAsDate.getTime();
        }
        return JoH.tsl();
    }

    private static void createReservoirChangeTreatment(final PumpStatus.ReservoirChange reservoirChange) {
        if (reservoirChange == null) {
            return;
        }

        final String note = "CareLink inferred reservoir refill: "
                + JoH.qs(reservoirChange.previousReservoir, 1)
                + "U -> "
                + JoH.qs(reservoirChange.currentReservoir, 1)
                + "U";
        final String eventUuid = UUID.nameUUIDFromBytes(("carelink-reservoir-site-change:" + reservoirChange.timestamp)
                .getBytes(StandardCharsets.UTF_8)).toString();
        Treatments.createEvent(Treatments.SITE_CHANGE_EVENT_TYPE, note, reservoirChange.timestamp, eventUuid);
    }

    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    static synchronized void processData(final RecentData recentData, final boolean live) {

        List<SensorGlucose> filteredSgList;
        List<Marker> filteredMarkerList;
        Double latestAutoBasalRate = null;
        long latestAutoBasalEndTimestamp = -1;

        UserError.Log.d(TAG, "Start processsing data...");

        //SKIP ALL IF EMPTY!!!
        if (recentData == null) {
            UserError.Log.e(TAG, "Recent data is null, processing stopped!");
            return;
        }

        if (recentData.sgs == null) UserError.Log.d(TAG, "SGs is null!");

        //SKIP DATA processing if NO PUMP CONNECTION (time shift seems to be different in this case, needs further analysis)
        if (recentData.isNGP() && !recentData.pumpCommunicationState) {
            UserError.Log.d(TAG, "Not connected to pump => time can be wrong, leave processing!");
            return;
        }

        syncSensorSession(recentData);

        //SENSOR GLUCOSE (if available)
        if (recentData.sgs != null) {

            final BgReading lastBg = BgReading.lastNoSenssor();
            final long lastBgTimestamp = lastBg != null ? lastBg.timestamp : 0;

            //create filtered sortable SG list
            filteredSgList = new ArrayList<>();
            for (SensorGlucose sg : recentData.sgs) {
                //SG DateTime is null (sensor expired?)
                if (sg != null && sg.getDate() != null) {
                    filteredSgList.add(sg);
                }
            }

            if (filteredSgList.size() > 0) {

                final Sensor sensor = Sensor.createDefaultIfMissing();
                sensor.save();

                // place in order of oldest first
                Collections.sort(filteredSgList, (o1, o2) -> o1.getDate().compareTo(o2.getDate()));

                for (final SensorGlucose sg : filteredSgList) {

                    //Not EPOCH 0 (warmup?)
                    if (sg.getDate().getTime() > 1) {

                        //Not in the future
                        if (sg.getDate().getTime() < new Date().getTime() + 300_000) {

                            //Not 0 SG (not calibrated?)
                            if (sg.sg > 0) {

                                //newer than last BG
                                if (sg.getDate().getTime() > lastBgTimestamp) {

                                    if (sg.getDate().getTime() > 0) {

                                        //New entry
                                        if (BgReading.getForPreciseTimestamp(sg.getDate().getTime(), 10_000) == null) {
                                            UserError.Log.d(TAG, "NEW NEW NEW New entry: " + sg.toS());

                                            if (live) {
                                                final BgReading bg = new BgReading();
                                                bg.timestamp = sg.getDate().getTime();
                                                bg.calculated_value = (double) sg.sg;
                                                bg.raw_data = SPECIAL_FOLLOWER_PLACEHOLDER;
                                                bg.filtered_data = (double) sg.sg;
                                                bg.noise = "";
                                                bg.uuid = UUID.randomUUID().toString();
                                                bg.calculated_value_slope = 0;
                                                bg.sensor = sensor;
                                                bg.sensor_uuid = sensor.uuid;
                                                bg.source_info = SOURCE_CARELINK_FOLLOW;
                                                bg.save();
                                                bg.find_slope();
                                                Inevitable.task("entry-proc-post-pr", 500, () -> bg.postProcess(false));
                                            }
                                        }
                                    } else {
                                        UserError.Log.e(TAG, "Could not parse a timestamp from: " + sg.toS());
                                    }
                                }

                            } else {
                                UserError.Log.d(TAG, "SG is 0 (calibration missed?)");
                            }

                        } else {
                            UserError.Log.d(TAG, "SG DateTime is 0 (warmup phase?)");
                        }
                    } else {
                        UserError.Log.d(TAG, "SG DateTime in future: " + sg.datetime);
                    }
                }
            }
        }


        //MARKERS (if available)
        if (recentData.markers != null) {
            //Filter markers
            filteredMarkerList = new ArrayList<>();
            for (Marker marker : recentData.markers) {
                if (marker != null && marker.type != null && marker.getDate() != null) {
                    filteredMarkerList.add(marker);
                }
            }

            if (filteredMarkerList.size() > 0) {
                //sort markers by time
                Collections.sort(filteredMarkerList, (o1, o2) -> o1.getDate().compareTo(o2.getDate()));

                final long autoBasalCutoffTimestamp = getLatestApStatusTimestamp();
                Marker previousAutoBasalMarker = null;

                //process markers one-by-one
                for (Marker marker : filteredMarkerList) {

                    if (marker.type.equals(Marker.MARKER_TYPE_AUTO_BASAL) && (recentData.isNGP() || recentData.isCC())) {
                        final Double autoBasalRate = getAutoBasalRateFromMarker(marker);
                        if (autoBasalRate != null) {
                            final long startTimestamp = marker.getDate().getTime();
                            final long endTimestamp = getAutoBasalSlotEndTimestamp(marker);

                            // Insert zero-rate boundary when there is a gap between slots
                            if (previousAutoBasalMarker != null) {
                                final long previousEndTimestamp = getAutoBasalSlotEndTimestamp(previousAutoBasalMarker);
                                if (previousEndTimestamp != startTimestamp) {
                                    recordAutoBasalSlot(Math.min(previousEndTimestamp, startTimestamp - 1), 0d, autoBasalCutoffTimestamp);
                                }
                            }

                            // Record EVERY 5-min slot, not just rate transitions.
                            // This ensures Nightscout has full coverage even when
                            // the same rate continues across multiple slots.
                            recordAutoBasalSlot(startTimestamp, autoBasalRate, autoBasalCutoffTimestamp);

                            latestAutoBasalRate = autoBasalRate;
                            latestAutoBasalEndTimestamp = endTimestamp;
                            previousAutoBasalMarker = marker;
                        }
                        continue;
                    }

                    //FINGER BG
                    if (marker.isBloodGlucose() && Pref.getBooleanDefaultFalse("clfollow_download_finger_bgs")) {
                        //check required values
                        if (marker.getBloodGlucose() != null && !marker.getBloodGlucose().equals(0)) {
                            //new blood test
                            if (BloodTest.getForPreciseTimestamp(marker.getDate().getTime(), 10000) == null) {
                                BloodTest.create(marker.getDate().getTime(), marker.getBloodGlucose(), SOURCE_CARELINK_FOLLOW);
                            }
                        }

                        //INSULIN, MEAL => Treatment
                    } else if ((marker.type.equals(Marker.MARKER_TYPE_INSULIN) && Pref.getBooleanDefaultFalse("clfollow_download_boluses"))
                            || (marker.type.equals(Marker.MARKER_TYPE_MEAL) && Pref.getBooleanDefaultFalse("clfollow_download_meals"))) {

                        //insulin, meal only for pumps and cgm (cgm = currently only Simplera, no value in case of Guardian CGM)
                        if (recentData.isNGP() || recentData.isCGM()) {

                            Treatments t;
                            double carbs = 0;
                            double insulin = 0;

                            //Extract treament infos (carbs, insulin)
                            //Insulin
                            if (marker.type.equals(Marker.MARKER_TYPE_INSULIN)) {
                                carbs = 0;
                                if (marker.getInsulinAmount() != null) {
                                    insulin = marker.getInsulinAmount();
                                }
                                //SKIP if insulin = 0
                                if (insulin == 0) continue;
                                //Carbs
                            } else if (marker.type.equals(Marker.MARKER_TYPE_MEAL)) {
                                if (marker.getCarbAmount() != null) {
                                    carbs = marker.getCarbAmount();
                                }
                                insulin = 0;
                                //SKIP if carbs = 0
                                if (carbs == 0) continue;
                            }

                            //new Treatment
                            if (newTreatment(carbs, insulin, marker.getDate().getTime())) {
                                t = Treatments.create(carbs, insulin, marker.getDate().getTime());
                                if (t != null) {
                                    t.enteredBy = SOURCE_CARELINK_FOLLOW;
                                    t.save();
                                    if (Home.get_show_wear_treatments())
                                        pushTreatmentSyncToWatch(t, true);
                                    UserError.Log.d(TAG, "NEW TREATMENT: " + treatmentToString(t));
                                }
                            }
                        }

                    }

                }

                // Do NOT record trailing 0 after the last marker.
                // The pump is likely still running auto basal; a forced 0 here
                // creates false zero-drops on Nightscout and can also cause
                // the first marker of the next batch to be skipped (timestamp
                // equals the cutoff set by this 0-record).
            }
        }

        //PUMP INFO (Pump Status)
        if (recentData.isNGP() || recentData.isCC()) {
            final long pumpStatusTimestamp = getPumpStatusTimestamp(recentData);
            final PumpStatus.ReservoirChange reservoirChange = PumpStatus.updateReservoir(recentData.reservoirRemainingUnits, pumpStatusTimestamp);
            createReservoirChangeTreatment(reservoirChange);
            PumpStatus.setBattery(recentData.getDeviceBatteryLevel());
            if (recentData.gstBatteryLevel > 0) {
                PumpStatus.setGstBattery(recentData.gstBatteryLevel);
            }
            if (recentData.activeInsulin != null)
                PumpStatus.setBolusIoB(recentData.activeInsulin.amount);
            if (recentData.basal != null) {
                PumpStatus.setActiveBasalPattern(recentData.basal.activeBasalPattern);
            }
            final double currentBasalRate = resolveCurrentBasalRate(recentData, latestAutoBasalRate,
                    latestAutoBasalEndTimestamp, pumpStatusTimestamp);
            PumpStatus.setBasalAbsolute(currentBasalRate);
            PumpStatus.syncUpdate();
        }
        // Guardian device battery -> PumpStatus so it is uploaded to Nightscout
        else if (recentData.isGM()) {
            PumpStatus.setBattery(recentData.getDeviceBatteryLevel());
            if (recentData.gstBatteryLevel > 0) {
                PumpStatus.setGstBattery(recentData.gstBatteryLevel);
            }
            PumpStatus.syncUpdate();
        }
		
        // LAST ALARM -> NOTE (only for GC)
        if (Pref.getBooleanDefaultFalse("clfollow_download_notifications")) {

            // Only Guardian Connect, NGP has all in notifications
            if (recentData.isGM() && recentData.lastAlarm != null) {
                //Add notification from alarm
                if (recentData.lastAlarm.datetimeAsDate != null && recentData.lastAlarm.kind != null)
                    addNotification(recentData.lastAlarm.datetimeAsDate, recentData.getDeviceFamily(), recentData.lastAlarm);
            }
        }


        //NOTIFICATIONS -> NOTE
        if (Pref.getBooleanDefaultFalse("clfollow_download_notifications")) {
            if (recentData.notificationHistory != null) {
                //Active Notifications
                if (recentData.notificationHistory.activeNotifications != null) {
                    for (ActiveNotification activeNotification : recentData.notificationHistory.activeNotifications) {
                        addNotification(activeNotification.dateTime, recentData.getDeviceFamily(), activeNotification.getMessageId(), activeNotification.faultId);
                    }
                }
                //Cleared Notifications
                if (recentData.notificationHistory.clearedNotifications != null) {
                    for (ClearedNotification clearedNotification : recentData.notificationHistory.clearedNotifications) {
                        Date notificationDate = clearedNotification.triggeredDateTime != null ? clearedNotification.triggeredDateTime : clearedNotification.dateTime;
                        addNotification(notificationDate, recentData.getDeviceFamily(), clearedNotification.getMessageId(), clearedNotification.faultId);
                    }
                }
            }
        }

    }

    //Check if treatment is new (no identical entry (timestamp, carbs, insulin) exists)
    protected static boolean newTreatment(double carbs, double insulin, long timestamp) {

        List<Treatments> treatmentsList;
        //Treatment with same timestamp and carbs + insulin exists?
        treatmentsList = Treatments.listByTimestamp(timestamp);
        if (treatmentsList != null) {
            for (Treatments treatments : treatmentsList) {
                if (treatments.carbs == carbs && treatments.insulin == insulin)
                    return false;
            }
        }
        return true;
    }


    //Create notification from CareLink messageId
    protected static boolean addNotification(Date date, String deviceFamily, String messageId, String faultId) {

        if (deviceFamily != null && messageId != null)
            return addNotification(date, TextMap.getNotificationMessage(deviceFamily, messageId, faultId));
        else
            return false;

    }

    //Create notification from CareLink Alarm
    protected static boolean addNotification(Date date, String deviceFamily, Alarm alarm) {

        if (deviceFamily != null && alarm != null && alarm.kind != null)
            return addNotification(date, TextMap.getAlarmMessage(deviceFamily, alarm));
        else
            return false;

    }

    //Create notification from CareLink note info
    protected static boolean addNotification(Date date, String noteText) {

        //Valid date
        if (date != null && noteText != null) {
            //New note
            if (newNote(noteText, date.getTime())) {
                //create_note in Treatment is not good, because of automatic link to other treatments in 5 mins range
                Treatments note = new Treatments();
                note.notes = noteText;
                note.timestamp = date.getTime();
                note.created_at = DateUtil.toISOString(note.timestamp);
                note.uuid = UUID.randomUUID().toString();
                note.enteredBy = SOURCE_CARELINK_FOLLOW;
                note.save();
                if (Home.get_show_wear_treatments())
                    pushTreatmentSyncToWatch(note, true);
                return true;
            }
        }

        return false;

    }


    //Check note is new
    protected static boolean newNote(String note, long timestamp) {

        List<Treatments> treatmentsList;
        //Treatment with same timestamp and note text exists?
        treatmentsList = Treatments.listByTimestamp(timestamp);
        if (treatmentsList != null) {
            for (Treatments treatments : treatmentsList) {
                if (treatments.notes.contains(note))
                    return false;
            }
        }

        return true;

    }

    protected static String treatmentToString(Treatments treatments){
        return DateUtil.toISOString(treatments.timestamp) + " - "
                + String.format("%.3f", treatments.insulin) + "U "
                + String.format("%.0f", treatments.carbs) + "g ";
    }

}