package com.phonegap.plugins.twiliovoice;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.twilio.voice.Call;
import com.twilio.voice.CallException;
import com.twilio.voice.CallInvite;
import com.twilio.voice.ConnectOptions;
import com.twilio.voice.RegistrationException;
import com.twilio.voice.RegistrationListener;
import com.twilio.voice.Voice;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PermissionHelper;
import org.apache.cordova.PluginResult;
import org.apache.cordova.PluginResult.Status;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

import timber.log.Timber;

/**
 * Twilio Voice Plugin for Cordova/PhoneGap
 * <p>
 * Based on Twilio's Voice Quickstart for Android
 * https://github.com/twilio/voice-quickstart-android/blob/master/app/src/main/java/com/twilio/voice/quickstart/VoiceActivity.java
 *
 * @author Jeff Linwood, https://github.com/jefflinwood
 */
public class TwilioVoicePlugin extends CordovaPlugin {

    public final static String TAG = "TwilioVoicePlugin";
    // Constants for Intents and Broadcast Receivers
    public static final String INCOMING_CALL_INVITE = "INCOMING_CALL_INVITE";
    public static final String INCOMING_CALL_NOTIFICATION_ID = "INCOMING_CALL_NOTIFICATION_ID";
    public static final String ACTION_INCOMING_CALL = "INCOMING_CALL";
    public static final int PERMISSION_REQUEST_CODE = 0;
    // Twilio Voice Registration Listener
    private final RegistrationListener mRegistrationListener = new RegistrationListener() {
        @Override
        public void onRegistered(@NonNull String accessToken, @NonNull String fcmToken) {
            Timber.tag(TAG).d("Registered Voice Client");
        }

        @Override
        public void onError(@NonNull RegistrationException exception, @NonNull String accessToken, @NonNull String fcmToken) {
            Timber.tag(TAG).e(exception, "Error registering Voice Client: %s", exception.getMessage());
        }
    };
    private CallbackContext mInitCallbackContext;
    // Twilio Voice Member Variables
    private Call mCall;
    private CallInvite mCallInvite;
    // Access Token
    private String mAccessToken;
    // FCM Token
    private String mFCMToken;
    // An incoming call intent to process (can be null)
    private Intent mIncomingCallIntent;
    private AudioManager audioManager;
    private int savedAudioMode = AudioManager.MODE_INVALID;
    private final Call.Listener callListener = callListener();

    private Call.Listener callListener() {
        return new Call.Listener() {

            @Override
            public void onRinging(@NonNull Call call) {
                Timber.tag(TAG).d("Ringing");
            }

            @Override
            public void onConnected(@NonNull Call call) {
                mCall = call;
                Timber.tag(TAG).d("On twilio call connected");
                Intent serviceIntent = new Intent(cordova.getActivity(), AudioForegroundService.class);
                cordova.getActivity().startForegroundService(serviceIntent);
                JSONObject callProperties = new JSONObject();
                try {
                    callProperties.putOpt("from", call.getFrom());
                    callProperties.putOpt("to", call.getTo());
                    callProperties.putOpt("callSid", call.getSid());
                    callProperties.putOpt("isMuted", call.isMuted());
                    setAudioFocus(true);
                } catch (JSONException e) {
                    Timber.tag(TAG).e(e);
                }
                javascriptCallback("oncalldidconnect", callProperties, mInitCallbackContext);
            }

            @Override
            public void onDisconnected(@NonNull Call call, CallException exception) {
                mCall = null;
                Timber.tag(TAG).d("On Twilio call disconnected");
                stopForegroundService();
                setAudioFocus(false);
                javascriptCallback("oncalldiddisconnect", mInitCallbackContext);
            }

            @Override
            public void onConnectFailure(@NonNull Call call, @NonNull CallException exception) {
                mCall = null;
                setAudioFocus(false);
                javascriptErrorback(exception.getErrorCode(), exception.getMessage(), mInitCallbackContext);
            }

            @Override
            public void onReconnected(@NonNull Call call) {
                mCall = call;
                setAudioFocus(true);
            }

            @Override
            public void onReconnecting(@NonNull Call call, @NonNull CallException exception) {
                Timber.tag(TAG).e(exception, "Reconnecting");
            }
        };
    }

    @Override
    protected void pluginInitialize() {
        super.pluginInitialize();

        Timber.tag(TAG).d("initialize()");

        // initialize sound SoundPoolManager
        SoundPoolManager.getInstance(cordova.getActivity());

        Context context = cordova.getActivity().getApplicationContext();
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        // Handle an incoming call intent if launched from a notification
        Intent intent = cordova.getActivity().getIntent();
        if (Objects.equals(intent.getAction(), ACTION_INCOMING_CALL)) {
            mIncomingCallIntent = intent;
        }
    }

    @Override
    public void onRestoreStateForActivityResult(Bundle state, CallbackContext callbackContext) {
        super.onRestoreStateForActivityResult(state, callbackContext);
        Timber.tag(TAG).d("onRestoreStateForActivityResult()");
        mInitCallbackContext = callbackContext;
    }

    /**
     * Android Cordova Action Router
     * <p>
     * Executes the request.
     * <p>
     * This method is called from the WebView thread. To do a non-trivial amount
     * of work, use: cordova.getThreadPool().execute(runnable);
     * <p>
     * To run on the UI thread, use:
     * cordova.getActivity().runOnUiThread(runnable);
     *
     * @param action          The action to execute.
     * @param args            The exec() arguments in JSON form.
     * @param callbackContext The callback context used when calling back into JavaScript.
     * @return Whether the action was valid.
     */
    @Override
    public boolean execute(final String action, final JSONArray args, final CallbackContext callbackContext) throws JSONException {
        if ("initializeWithAccessToken".equals(action)) {
            Timber.tag(TAG).d("Initializing with Access Token");

            mAccessToken = args.optString(0);
            mInitCallbackContext = callbackContext;

            // request Audio permission
            if (ContextCompat.checkSelfPermission(cordova.getContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                Timber.tag(TAG).d("Has Record Audio Permission. Continue initialize.");
                initializeWithAccessToken();
            } else {
                Timber.tag(TAG).d("Need Record Audio Permission.");
                CordovaPlugin context = this;

                cordova.getThreadPool().execute(() -> {
                    PermissionHelper.requestPermission(context, PERMISSION_REQUEST_CODE, Manifest.permission.RECORD_AUDIO);
                });
            }

            return true;
        } else if ("call".equals(action)) {
            call(args, callbackContext);
            return true;
        } else if ("acceptCallInvite".equals(action)) {
            acceptCallInvite(args, callbackContext);
            return true;
        } else if ("disconnect".equals(action)) {
            disconnect(args, callbackContext);
            return true;
        } else if ("sendDigits".equals(action)) {
            sendDigits(args, callbackContext);
            return true;
        } else if ("muteCall".equals(action)) {
            muteCall(callbackContext);
            return true;
        } else if ("unmuteCall".equals(action)) {
            unmuteCall(callbackContext);
            return true;
        } else if ("isCallMuted".equals(action)) {
            isCallMuted(callbackContext);
            return true;
        } else if ("callStatus".equals(action)) {
            callStatus(callbackContext);
            return true;
        } else if ("rejectCallInvite".equals(action)) {
            rejectCallInvite(args, callbackContext);
            return true;
        } else if ("showNotification".equals(action)) {
            //showNotification(args, callbackContext);
            return true;
        } else if ("cancelNotification".equals(action)) {
            //cancelNotification(args, callbackContext);
            return true;
        } else if ("setSpeaker".equals(action)) {
            setSpeaker(args, callbackContext);
            return true;
        }

        return false;
    }

    private void initializeWithAccessToken() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_INCOMING_CALL);

        if (mIncomingCallIntent != null) {
            Timber.tag(TAG).d("initialize(): Handle an incoming call");
            handleIncomingCallIntent(mIncomingCallIntent);
            mIncomingCallIntent = null;
        }

        javascriptCallback("onclientinitialized", mInitCallbackContext);
    }

    private void stopForegroundService() {
        if (AudioForegroundService.isRunning()) {
            Timber.tag(TAG).d("Stopping audio foreground service");
            Intent serviceIntent = new Intent(cordova.getActivity(), AudioForegroundService.class);
            cordova.getActivity().stopService(serviceIntent);
        } else {
            Timber.tag(TAG).d("Audio foreground service is not running");
        }
    }

    private void call(final JSONArray arguments, final CallbackContext ignoredCallbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                String accessToken = arguments.getString(0);
                JSONObject options = arguments.getJSONObject(1);
                Map<String, String> map = new HashMap<>();
                map.put("accessToken", accessToken);

                Iterator<String> keys = options.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    try {
                        map.put(key, options.getString(key));
                    } catch (Exception e) {
                        Timber.tag(TAG).e(e.toString());
                    }
                }

                ConnectOptions connectOptions = new ConnectOptions.Builder(accessToken)
                    .params(map)
                    .build();
                mCall = Voice.connect(cordova.getActivity(), connectOptions, callListener);
            } catch (Exception e) {
                Timber.tag(TAG).e(e.toString());
            }
        });

    }

    private void acceptCallInvite(JSONArray ignoredArguments, final CallbackContext callbackContext) {
        if (mCallInvite == null) {
            callbackContext.sendPluginResult(new PluginResult(
                PluginResult.Status.ERROR));
            return;
        }
        cordova.getThreadPool().execute(() -> {
            mCallInvite.accept(cordova.getActivity(), callListener);
            callbackContext.success();
        });

    }

    private void rejectCallInvite(JSONArray ignoredArguments, final CallbackContext callbackContext) {
        if (mCallInvite == null) {
            callbackContext.sendPluginResult(new PluginResult(
                PluginResult.Status.ERROR));
            return;
        }
        cordova.getThreadPool().execute(() -> {
            mCallInvite.reject(cordova.getActivity());
            callbackContext.success();
        });
    }

    private void disconnect(JSONArray ignoredArguments, final CallbackContext callbackContext) {
        if (mCall == null) {
            callbackContext.sendPluginResult(new PluginResult(
                PluginResult.Status.ERROR));
            stopForegroundService();
            return;
        }
        cordova.getThreadPool().execute(() -> {
            mCall.disconnect();
            stopForegroundService();
            callbackContext.success();
        });
    }

    private void sendDigits(final JSONArray arguments, final CallbackContext callbackContext) {
        if (arguments == null || arguments.length() < 1 || mCall == null) {
            callbackContext.sendPluginResult(new PluginResult(
                PluginResult.Status.ERROR));
            return;
        }
        cordova.getThreadPool().execute(() -> {
            mCall.sendDigits(arguments.optString(0));
            callbackContext.success();
        });

    }

    private void muteCall(final CallbackContext callbackContext) {
        if (mCall == null) {
            callbackContext.sendPluginResult(new PluginResult(
                PluginResult.Status.ERROR));
            return;
        }
        cordova.getThreadPool().execute(() -> {
            mCall.mute(true);
            callbackContext.success();
        });
    }

    private void unmuteCall(final CallbackContext callbackContext) {
        if (mCall == null) {
            callbackContext.sendPluginResult(new PluginResult(
                PluginResult.Status.ERROR));
            return;
        }
        cordova.getThreadPool().execute(() -> {
            mCall.mute(false);
            callbackContext.success();
        });
    }

    private void isCallMuted(CallbackContext callbackContext) {
        if (mCall == null) {
            callbackContext.sendPluginResult(new PluginResult(
                PluginResult.Status.OK, false));
            return;
        }
        PluginResult result = new PluginResult(PluginResult.Status.OK, mCall.isMuted());
        callbackContext.sendPluginResult(result);
    }

    private void callStatus(CallbackContext callbackContext) {
        if (mCall == null) {
            callbackContext.sendPluginResult(new PluginResult(
                PluginResult.Status.ERROR));
            return;
        }
        String state = getCallState(mCall.getState());
        if (state == null) {
            state = "";
        }

        PluginResult result = new PluginResult(PluginResult.Status.OK, state);
        callbackContext.sendPluginResult(result);
    }

    /**
     * Changes sound from earpiece to speaker and back
     *
     * @param mode Speaker Mode
     */
    public void setSpeaker(final JSONArray arguments, final CallbackContext ignoredCallbackContext) {
        cordova.getThreadPool().execute(() -> {
            String mode = arguments.optString(0);
            if (mode.equals("on")) {
                Timber.tag(TAG).d("SPEAKER");
                audioManager.setMode(AudioManager.MODE_NORMAL);
                audioManager.setSpeakerphoneOn(true);
            } else {
                Timber.tag(TAG).d("EARPIECE");
                audioManager.setMode(AudioManager.MODE_IN_CALL);
                audioManager.setSpeakerphoneOn(false);
            }
        });
    }

    @SuppressLint("WrongConstant") // to suppress audioManager.setMode(savedAudioMode)
    private void setAudioFocus(boolean setFocus) {
        if (audioManager == null) {
            return;
        }

        // Request audio focus before making any device switch.
        AudioAttributes playbackAttributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();
        AudioFocusRequest focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(playbackAttributes)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(i -> {
            })
            .build();

        if (setFocus) {
            savedAudioMode = audioManager.getMode();

            audioManager.requestAudioFocus(focusRequest);
            /*
             * Start by setting MODE_IN_COMMUNICATION as default audio mode. It is
             * required to be in this mode when playout and/or recording starts for
             * best possible VoIP performance. Some devices have difficulties with speaker mode
             * if this is not set.
             */
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        } else {
            audioManager.setMode(savedAudioMode);
            audioManager.abandonAudioFocusRequest(focusRequest);
        }
    }

    // Plugin-to-Javascript communication methods
    private void javascriptCallback(String event, JSONObject arguments,
                                    CallbackContext callbackContext) {
        if (callbackContext == null) {
            return;
        }
        JSONObject options = new JSONObject();
        try {
            options.putOpt("callback", event);
            options.putOpt("arguments", arguments);
        } catch (JSONException e) {
            callbackContext.sendPluginResult(new PluginResult(
                PluginResult.Status.JSON_EXCEPTION));
            return;
        }
        PluginResult result = new PluginResult(Status.OK, options);
        result.setKeepCallback(true);
        callbackContext.sendPluginResult(result);

    }

    private void javascriptCallback(String event, CallbackContext callbackContext) {
        javascriptCallback(event, null, callbackContext);
    }


    private void javascriptErrorback(int ignoredErrorCode, String errorMessage, CallbackContext callbackContext) {
        JSONObject object = new JSONObject();
        try {
            object.putOpt("message", errorMessage);
        } catch (JSONException e) {
            callbackContext.sendPluginResult(new PluginResult(
                PluginResult.Status.JSON_EXCEPTION));
            return;
        }
        PluginResult result = new PluginResult(Status.ERROR, object);
        result.setKeepCallback(true);
        callbackContext.sendPluginResult(result);
    }

    @Override
    public void onDestroy() {
        //lifecycle events
        SoundPoolManager.getInstance(cordova.getActivity()).release();
        // LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(cordova.getActivity());
        // lbm.unregisterReceiver(mBroadcastReceiver);
        stopForegroundService();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
        boolean permissionError = false;
        for (int permissionResult : grantResults) {
            if (permissionResult == PackageManager.PERMISSION_DENIED) {
                permissionError = true;
                break;
            }
        }
        if (permissionError) {
            Timber.tag(TAG).e("Record Audio Permission Failed.");
        } else {
            Timber.tag(TAG).d("Record Audio Permission Granted. Initializing Twilio.");
            initializeWithAccessToken();
        }

        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    // Process incoming call invites
    private void handleIncomingCallIntent(Intent intent) {
        Timber.tag(TAG).d("handleIncomingCallIntent()");
        if (intent != null && intent.getAction() != null && intent.getAction().equals(ACTION_INCOMING_CALL)) {
            mCallInvite = intent.getParcelableExtra(INCOMING_CALL_INVITE);
            if (mCallInvite != null) {
                SoundPoolManager.getInstance(cordova.getActivity()).playRinging();
                NotificationManager mNotifyMgr =
                    (NotificationManager) cordova.getActivity().getSystemService(Activity.NOTIFICATION_SERVICE);
                mNotifyMgr.cancel(intent.getIntExtra(INCOMING_CALL_NOTIFICATION_ID, 0));
                JSONObject callInviteProperties = new JSONObject();
                try {
                    callInviteProperties.putOpt("from", mCallInvite.getFrom());
                    callInviteProperties.putOpt("to", mCallInvite.getTo());
                    callInviteProperties.putOpt("callSid", mCallInvite.getCallSid());
                } catch (JSONException e) {
                    Timber.tag(TAG).e(e);
                }
                Timber.tag(TAG).d("oncallinvitereceived");
                javascriptCallback("oncallinvitereceived", callInviteProperties, mInitCallbackContext);
            } else {
                SoundPoolManager.getInstance(cordova.getActivity()).stopRinging();
                Timber.tag(TAG).d("oncallinvitecanceled");
                javascriptCallback("oncallinvitecanceled", mInitCallbackContext);
            }
        }
    }

    private String getCallState(Call.State callState) {
        if (callState == Call.State.CONNECTED) {
            return "connected";
        } else if (callState == Call.State.CONNECTING) {
            return "connecting";
        } else if (callState == Call.State.DISCONNECTED) {
            return "disconnected";
        }
        return null;
    }
}
