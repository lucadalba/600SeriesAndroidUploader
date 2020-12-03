package info.nightscout.android.pushover;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.annotation.NonNull;
import android.util.Log;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

import info.nightscout.android.R;
import info.nightscout.android.UploaderApplication;
import info.nightscout.android.history.MessageItem;
import info.nightscout.android.history.PumpHistoryHandler;
import info.nightscout.android.medtronic.Stats;
import info.nightscout.android.medtronic.UserLogMessage;
import info.nightscout.android.model.medtronicNg.PumpHistoryInterface;
import info.nightscout.android.model.store.StatPushover;
import io.realm.Realm;
import okhttp3.Headers;
import retrofit2.Response;

import static info.nightscout.android.history.PumpHistorySender.SENDER_ID_PUSHOVER;
import static info.nightscout.android.utils.ToolKit.getWakeLock;
import static info.nightscout.android.utils.ToolKit.releaseWakeLock;

public class PushoverUploadService extends Service {
    private static final String TAG = PushoverUploadService.class.getSimpleName();

    private static final String PUSHOVER_URL = "https://api.pushover.net/";

    private Context mContext;

    private PumpHistoryHandler pumpHistoryHandler;
    private StatPushover statPushover;

    PushoverApi pushoverApi;

    private boolean valid;
    private String apiToken;
    private String userToken;
    private int messagesSent;
    private int appLimit;
    private int appRemaining;
    private long appReset;

    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate called");

        mContext = this.getBaseContext();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy called");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Received start id " + startId + "  : " + intent);

        if (intent != null) {
            if (startId == 1)
                new Upload().start();
            else {
                Log.d(TAG, "Service already in progress with previous task");
            }
        }

        return START_NOT_STICKY;
    }

    private class Upload extends Thread {
        public void run() {

            PowerManager.WakeLock wl = getWakeLock(mContext, TAG, 90000);

            pumpHistoryHandler = new PumpHistoryHandler(mContext);

            if (UploaderApplication.isOnline() && pumpHistoryHandler.dataStore.isPushoverEnable()) {
                statPushover = (StatPushover) Stats.getInstance().readRecord(StatPushover.class);
                statPushover.incRun();

                pushoverApi = new PushoverApi(PUSHOVER_URL);
                if (isValid()) process();
                else statPushover.incValidError();
            }

            pumpHistoryHandler.close();

            releaseWakeLock(wl);
            stopSelf();
        }
    }

    public boolean isValid() {

        valid = pumpHistoryHandler.dataStore.isPushoverValidated();
        apiToken = pumpHistoryHandler.dataStore.getPushoverAPItoken();
        userToken = pumpHistoryHandler.dataStore.getPushoverUSERtoken();
        String apiCheck = pumpHistoryHandler.dataStore.getPushoverAPItokenCheck();
        String userCheck = pumpHistoryHandler.dataStore.getPushoverUSERtokenCheck();

        try {

            if (!valid && apiToken.equals(apiCheck) && userToken.equals(userCheck)) {
                UserLogMessage.send(mContext, UserLogMessage.TYPE.WARN, R.string.ul_share__pushover_validation_failed);
                throw new Exception("validation failed, check settings");
            }

            else if (valid && !(apiToken.equals(apiCheck) && userToken.equals(userCheck))) {
                valid = false;
                updateValidation();
            }

            if (!valid) {
                if (apiToken.length() != 30 || userToken.length() != 30) {
                    UserLogMessage.send(mContext, UserLogMessage.TYPE.WARN, R.string.ul_share__pushover_validation_failed);
                    throw new Exception("api/user token is not valid");
                }

                PushoverEndpoints pushoverEndpoints = pushoverApi.getPushoverEndpoints();

                PushoverEndpoints.Message pem = new PushoverEndpoints.Message();
                pem.setToken(apiToken);
                pem.setUser(userToken);

                Response<PushoverEndpoints.Message> response = pushoverEndpoints.validate(pem).execute();

                if (!response.isSuccessful())
                    throw new Exception("no response " + response.message());
                else if (response.body() == null)
                    throw new Exception("response body null");
                else if (response.code() != 200 && response.code() != 400)
                    throw new Exception("server error " + response.code());

                String status = response.body().getStatus();
                if (response.code() == 400 || status == null || !status.equals("1")) {
                    updateValidation();
                    UserLogMessage.send(mContext, UserLogMessage.TYPE.WARN, R.string.ul_share__pushover_validation_failed);
                    throw new Exception("account error");

                } else {

                    valid = true;
                    updateValidation();

                    UserLogMessage.send(mContext, UserLogMessage.TYPE.SHARE, String.format("{id;%s} {id;%s}",
                            R.string.ul_share__pushover, R.string.ul_share__is_available));

                    String[] devices = response.body().getDevices();
                    if (devices != null) {
                        StringBuilder sb = new StringBuilder();
                        for (String s : devices) {
                            sb.append(sb.length() > 0 ? " '" : "'");
                            sb.append(s);
                            sb.append("'");
                        }
                        if (sb.length() > 0)
                            UserLogMessage.sendE(mContext, UserLogMessage.TYPE.PUSHOVER, String.format("{id;%s}: %s",
                                    R.string.ul_share__pushover, sb.toString()));
                    }
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Pushover validation failed: " + e.getMessage());
            UserLogMessage.sendE(mContext, String.format("{id;%s}: api: %s %s user: %s %s %s",
                    R.string.ul_share__pushover,
                    apiToken == null ? "" : apiToken.length(),
                    apiToken == null ? "null": apiToken,
                    userToken == null ? "" : userToken.length(),
                    userToken == null ? "null" : userToken,
                    e.getMessage()
            ));
        }

        return valid;
    }

    private void resetValidation() {
        valid = false;
        apiToken = "";
        userToken = "";
        updateValidation();
    }

    private void updateValidation() {
        pumpHistoryHandler.storeRealm.executeTransaction(new Realm.Transaction() {
            @Override
            public void execute(@NonNull Realm realm) {
                pumpHistoryHandler.dataStore.setPushoverValidated(valid);
                pumpHistoryHandler.dataStore.setPushoverAPItokenCheck(apiToken);
                pumpHistoryHandler.dataStore.setPushoverUSERtokenCheck(userToken);
            }
        });
    }

    private void process() {
        messagesSent = 0;

        pumpHistoryHandler.historyRealm.executeTransaction(new Realm.Transaction() {
            @Override
            public void execute(@NonNull Realm realm) {

                List<PumpHistoryInterface> records = pumpHistoryHandler.getSenderRecordsREQ(SENDER_ID_PUSHOVER);

                for (PumpHistoryInterface record : records) {

                    List<info.nightscout.android.history.MessageItem> messageItems = record.message(pumpHistoryHandler.getPumpHistorySender(), SENDER_ID_PUSHOVER);

                    boolean success = true;
                    for (MessageItem messageItem : messageItems) {
                        success &= send(messageItem);
                    }

                    if (success) {
                        pumpHistoryHandler.setSenderRecordACK(record, SENDER_ID_PUSHOVER);
                    } else {
                        statPushover.incError();
                        break;
                    }

                }

            }
        });

        if (messagesSent > 0) {
            statPushover.setMessagesSent(statPushover.getMessagesSent() + messagesSent);
            statPushover.setLimit(appLimit);
            statPushover.setRemaining(appRemaining);
            statPushover.setResetTime(appReset * 1000);
            DateFormat df = new SimpleDateFormat("yyyy/MM/dd", Locale.ENGLISH);
            Log.i(TAG, String.format("Sent: %s Limit: %s Remaining: %d Reset: %s",
                    messagesSent, appLimit, appRemaining, df.format(appReset * 1000)));
            UserLogMessage.sendN(mContext, UserLogMessage.TYPE.PUSHOVER, String.format("{id;%s}: {id;%s} %s",
                    R.string.ul_share__pushover, R.string.ul_share__processed, messagesSent));
        }
    }

    public boolean send(MessageItem messageItem) {
        boolean success;

        String title = messageItem.getTitle();
        String message = messageItem.getMessage();
        String extended = messageItem.getExtended();

        if (pumpHistoryHandler.dataStore.isPushoverEnableTitleTime()
                && messageItem.getClock().length() > 0)
            title += " " + messageItem.getClock();

        if (pumpHistoryHandler.dataStore.isPushoverEnableTitleText()
                && pumpHistoryHandler.dataStore.getPushoverTitleText().length() > 0)
            title += " " + pumpHistoryHandler.dataStore.getPushoverTitleText();

        if (extended.length() > 0)
            message += " • " + extended;

        // Pushover will fail with a empty message string
        if (message.length() == 0)
            message = "...";

        PushoverEndpoints pushoverEndpoints = pushoverApi.getPushoverEndpoints();

        PushoverEndpoints.Message pem = new PushoverEndpoints.Message();
        pem.setToken(apiToken);
        pem.setUser(userToken);

        pem.setTitle(title);
        pem.setMessage(message);
        pem.setTimestamp(String.valueOf(messageItem.getDate().getTime() / 1000L));

        String priority;
        String sound;
        switch (messageItem.getType()) {
            case ALERT_ON_HIGH:
                priority = pumpHistoryHandler.dataStore.getPushoverPriorityOnHigh();
                sound = pumpHistoryHandler.dataStore.getPushoverSoundOnHigh();
                break;
            case ALERT_ON_LOW:
                priority = pumpHistoryHandler.dataStore.getPushoverPriorityOnLow();
                sound = pumpHistoryHandler.dataStore.getPushoverSoundOnLow();
                break;
            case ALERT_BEFORE_HIGH:
                priority = pumpHistoryHandler.dataStore.getPushoverPriorityBeforeHigh();
                sound = pumpHistoryHandler.dataStore.getPushoverSoundBeforeHigh();
                break;
            case ALERT_BEFORE_LOW:
                priority = pumpHistoryHandler.dataStore.getPushoverPriorityBeforeLow();
                sound = pumpHistoryHandler.dataStore.getPushoverSoundBeforeLow();
                break;
            case ALERT_EMERGENCY:
                priority = pumpHistoryHandler.dataStore.getPushoverPriorityPumpEmergency();
                sound = pumpHistoryHandler.dataStore.getPushoverSoundPumpEmergency();
                break;
            case ALERT_ACTIONABLE:
                priority = pumpHistoryHandler.dataStore.getPushoverPriorityPumpActionable();
                sound = pumpHistoryHandler.dataStore.getPushoverSoundPumpActionable();
                break;
            case ALERT_INFORMATIONAL:
                priority = pumpHistoryHandler.dataStore.getPushoverPriorityPumpInformational();
                sound = pumpHistoryHandler.dataStore.getPushoverSoundPumpInformational();
                break;
            case REMINDER:
                priority = pumpHistoryHandler.dataStore.getPushoverPriorityPumpReminder();
                sound = pumpHistoryHandler.dataStore.getPushoverSoundPumpReminder();
                break;
            case BOLUS:
                priority = pumpHistoryHandler.dataStore.getPushoverPriorityBolus();
                sound = pumpHistoryHandler.dataStore.getPushoverSoundBolus();
                break;
            case BASAL:
                priority = pumpHistoryHandler.dataStore.getPushoverPriorityBasal();
                sound = pumpHistoryHandler.dataStore.getPushoverSoundBasal();
                break;
            case SUSPEND:
            case RESUME:
                priority = pumpHistoryHandler.dataStore.getPushoverPrioritySuspendResume();
                sound = pumpHistoryHandler.dataStore.getPushoverSoundSuspendResume();
                break;
            case BG:
                priority = pumpHistoryHandler.dataStore.getPushoverPriorityBG();
                sound = pumpHistoryHandler.dataStore.getPushoverSoundBG();
                break;
            case CALIBRATION:
                priority = pumpHistoryHandler.dataStore.getPushoverPriorityCalibration();
                sound = pumpHistoryHandler.dataStore.getPushoverSoundCalibration();
                break;
            case CONSUMABLE:
                priority = pumpHistoryHandler.dataStore.getPushoverPriorityConsumables();
                sound = pumpHistoryHandler.dataStore.getPushoverSoundConsumables();
                break;
            case DAILY_TOTALS:
                priority = pumpHistoryHandler.dataStore.getPushoverPriorityDailyTotals();
                sound = pumpHistoryHandler.dataStore.getPushoverSoundDailyTotals();
                break;
            case AUTOMODE_ACTIVE:
                priority = pumpHistoryHandler.dataStore.getPushoverPriorityAutoModeActive();
                sound = pumpHistoryHandler.dataStore.getPushoverSoundAutoModeActive();
                break;
            case AUTOMODE_STOP:
                priority = pumpHistoryHandler.dataStore.getPushoverPriorityAutoModeStop();
                sound = pumpHistoryHandler.dataStore.getPushoverSoundAutoModeStop();
                break;
            case AUTOMODE_EXIT:
                priority = pumpHistoryHandler.dataStore.getPushoverPriorityAutoModeExit();
                sound = pumpHistoryHandler.dataStore.getPushoverSoundAutoModeExit();
                break;
            case AUTOMODE_MINMAX:
                priority = pumpHistoryHandler.dataStore.getPushoverPriorityAutoModeMinMax();
                sound = pumpHistoryHandler.dataStore.getPushoverSoundAutoModeMinMax();
                break;
            case ALERT_UPLOADER_ERROR:
                priority = pumpHistoryHandler.dataStore.getPushoverPriorityUploaderPumpErrors();
                sound = pumpHistoryHandler.dataStore.getPushoverSoundUploaderPumpErrors();
                break;
            case ALERT_UPLOADER_STATUS:
                priority = pumpHistoryHandler.dataStore.getPushoverPriorityUploaderStatus();
                sound = pumpHistoryHandler.dataStore.getPushoverSoundUploaderStatus();
                break;
            case ALERT_UPLOADER_BATTERY:
                priority = pumpHistoryHandler.dataStore.getPushoverPriorityUploaderBattery();
                sound = pumpHistoryHandler.dataStore.getPushoverSoundUploaderBattery();
                break;
            default:

                if (messageItem.getPriority() == MessageItem.PRIORITY.EMERGENCY) {
                    priority = pumpHistoryHandler.dataStore.getPushoverPriorityPumpEmergency();
                    sound = pumpHistoryHandler.dataStore.getPushoverSoundPumpEmergency();
                } else if (messageItem.getPriority() == MessageItem.PRIORITY.HIGH) {
                    priority = pumpHistoryHandler.dataStore.getPushoverPriorityPumpActionable();
                    sound = pumpHistoryHandler.dataStore.getPushoverSoundPumpActionable();
                } else if (messageItem.getPriority() == MessageItem.PRIORITY.NORMAL) {
                    priority = pumpHistoryHandler.dataStore.getPushoverPriorityPumpInformational();
                    sound = pumpHistoryHandler.dataStore.getPushoverSoundPumpInformational();
                } else {
                    priority = PRIORITY.NORMAL.string;
                    sound = SOUND.NONE.string;
                }
        }

        if (messageItem.isCleared()) {
            priority = pumpHistoryHandler.dataStore.getPushoverPriorityCleared();
            sound = pumpHistoryHandler.dataStore.getPushoverSoundCleared();
        } else if (messageItem.isSilenced()
                && pumpHistoryHandler.dataStore.isPushoverEnableSilencedOverride()) {
            priority = pumpHistoryHandler.dataStore.getPushoverPrioritySilenced();
            sound = pumpHistoryHandler.dataStore.getPushoverSoundSilenced();
        }

        if (pumpHistoryHandler.dataStore.isPushoverEnableBackfillOverride() &&
                System.currentTimeMillis() - messageItem.getDate().getTime() > pumpHistoryHandler.dataStore.getPushoverBackfillOverrideAge() * 60000L) {
            priority = pumpHistoryHandler.dataStore.getPushoverPriorityBackfill();
            sound = pumpHistoryHandler.dataStore.getPushoverSoundBackfill();
        }

        if (pumpHistoryHandler.dataStore.isPushoverEnablePriorityOverride())
            priority = pumpHistoryHandler.dataStore.getPushoverPriorityOverride();
        if (pumpHistoryHandler.dataStore.isPushoverEnableSoundOverride())
            sound = pumpHistoryHandler.dataStore.getPushoverSoundOverride();

        pem.setPriority(priority);
        pem.setSound(sound);

        // Use device name to send the message directly to that device, rather than all of the user's devices (multiple devices may be separated by a comma)
        pem.setDevice(pumpHistoryHandler.dataStore.getPushoverSendToDevice());

        if (priority.equals(PRIORITY.EMERGENCY.string)) {
            pem.setRetry(pumpHistoryHandler.dataStore.getPushoverEmergencyRetry());
            pem.setExpire(pumpHistoryHandler.dataStore.getPushoverEmergencyExpire());
        }

        try {
            Response<PushoverEndpoints.Message> response = pushoverEndpoints.postMessage(pem).execute();

            if (!response.isSuccessful()) {
                throw new Exception("no response " + response.message());
            } else if (response.body() == null) {
                throw new Exception("response body null");
            } else if (response.code() == 400) {
                resetValidation();
                throw new Exception("account error");
            } else if (response.code() != 200) {
                throw new Exception("server error");
            }

            try {
                Headers headers = response.headers();
                for (int i = 0, count = headers.size(); i < count; i++) {
                    String name = headers.name(i);
                    if ("X-Limit-App-Limit".equalsIgnoreCase(name)) {
                        appLimit = Integer.parseInt(headers.value(i));
                    } else if ("X-Limit-App-Remaining".equalsIgnoreCase(name)) {
                        appRemaining = Integer.parseInt(headers.value(i));
                    } else if ("X-Limit-App-Reset".equalsIgnoreCase(name)) {
                        appReset = Long.parseLong(headers.value(i));
                    }
                }
            } catch (Exception ignored) {}

            UserLogMessage.sendE(mContext, UserLogMessage.TYPE.PUSHOVER,
                    String.format("{id;%s}: %s/%s {date.time;%s} '%s' '%s' '%s' '%s'%s",
                            R.string.ul_share__pushover,
                            appLimit - appRemaining,
                            appLimit,
                            messageItem.getDate().getTime(),
                            pem.getTitle(),
                            pem.getMessage(),
                            pem.getPriority(),
                            pem.getSound(),
                            pem.getDevice().length() == 0 ? "" : " '" + pem.getDevice() + "'"
                    ));

            messagesSent++;

            success = true;
            Log.i(TAG, "success");
        } catch (Exception e) {
            success = false;
            Log.e(TAG, "failed: " + e.getMessage());
        }

        return success;
    }

    public enum PRIORITY {
        LOWEST("-2"),
        LOW("-1"),
        NORMAL("0"),
        HIGH("1"),
        EMERGENCY("2");

        private String string;

        PRIORITY(String string) {
            this.string = string;
        }
    }

    public enum SOUND {
        PUSHOVER("pushover"),
        BIKE("bike"),
        BUGLE("bugle"),
        CASHREGISTER("cashregister"),
        CLASSICAL("classical"),
        COSMIC("cosmic"),
        FALLING("falling"),
        GAMELAN("gamelan"),
        INCOMING("incoming"),
        INTERMISSION("intermission"),
        MAGIC("magic"),
        MECHANICAL("mechanical"),
        PIANOBAR("pianobar"),
        SIREN("siren"),
        SPACEALARM("spacealarm"),
        TUGBOAT("tugboat"),
        ALIEN("alien"),
        CLIMB("climb"),
        PERSISTENT("persistent"),
        ECHO("echo"),
        UPDOWN("updown"),
        NONE("none");

        private String string;

        SOUND(String string) {
            this.string = string;
        }
    }

}

/*

LOWEST
no sounds
no notification pop-up
useful as a ledger of events without bothering user

LOW
no sounds
notification pop-up

NORMAL
sounds
notification pop-up

HIGH
sounds
notification pop-up coloured red

EMERGENCY
sounds
notification pop-up coloured red
can repeat at >=30 seconds for specified time
persistent alarm that needs to be acknowledged by user

*/