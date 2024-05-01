package com.phonegap.plugins.twiliovoice;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
// import androidx.core.app.NotificationCompat;
// import androidx.localbroadcastmanager.content.LocalBroadcastManager;
// import com.google.firebase.iid.FirebaseInstanceId;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.twilio.voice.Call;
import com.twilio.voice.CallException;
import com.twilio.voice.CallInvite;
import com.twilio.voice.RegistrationException;
import com.twilio.voice.RegistrationListener;
import com.twilio.voice.Voice;
import com.twilio.voice.ConnectOptions;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PermissionHelper;
import org.apache.cordova.PluginResult;
import org.apache.cordova.PluginResult.Status;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import timber.log.Timber;

/**
 * Twilio Voice Plugin for Cordova/PhoneGap
 * <p>
 * Based on Twilio's Voice Quickstart for Android
 * https://github.com/twilio/voice-quickstart-android/blob/master/app/src/main/java/com/twilio/voice/quickstart/VoiceActivity.java
 *
 * @author Jeff Linwood, https://github.com/jefflinwood
 */
public class TwilioVoicePlugin extends CordovaPlugin implements AudioManager.OnAudioFocusChangeListener {
    private static final int PLAY_SERVICES_RESOLUTION_REQUEST = 9000;
    public static final int PERMISSION_REQUEST_CODE = 0;

    // Constants for Intents and Broadcast Receivers
    public static final String RECEIVER_ACTION_SET_FCM_TOKEN = "SET_FCM_TOKEN";
    public static final String RECEIVER_ACTION_INCOMING_CALL = "INCOMING_CALL";
    public static final String INTENT_EXTRA_INCOMING_CALL_INVITE = "INCOMING_CALL_INVITE";
    public static final String INTENT_EXTRA_INCOMING_CALL_NOTIFICATION_ID = "INCOMING_CALL_NOTIFICATION_ID";
    public static final String INTENT_EXTRA_FCM_TOKEN = "FCM_TOKEN";

    // Cordova actions
    private static final String ACTION_CALL = "call";
    private static final String ACTION_ACCEPT_CALL_INVITE = "acceptCallInvite";
    private static final String ACTION_DISCONNECT = "disconnect";
    private static final String ACTION_SEND_DIGITS = "sendDigits";
    private static final String ACTION_MUTE_CALL = "muteCall";
    private static final String ACTION_UN_MUTE_CALL = "unmuteCall";
    private static final String ACTION_IS_CALL_MUTED = "isCallMuted";
    private static final String ACTION_CALL_STATUS = "callStatus";
    private static final String ACTION_REJECT_CALL_INVITE = "rejectCallInvite";
    private static final String ACTION_SHOW_NOTIFICATION = "showNotification";
    private static final String ACTION_CANCEL_NOTIFICATION = "cancelNotification";
    private static final String ACTION_SET_SPEAKER = "setSpeaker";
    private static final String ACTION_SET_SHARED_EVENT_LISTENER = "setSharedEventListener";

    // Event constants
    private static final JSONObject JSON_OBJECT_EMPTY = new JSONObject();
    private static final String KEY_TYPE = "type";
    private static final String KEY_DATA = "data";

    private static final String EVENT_TYPE_CALL_DID_CONNECT = "callDidConnect";
    private static final String EVENT_TYPE_CALL_DID_DISCONNECT = "callDidDisconnect";
    private static final String EVENT_TYPE_CLIENT_INITIALIZED = "clientInitialized";
    private static final String EVENT_TYPE_CALL_INVITE_RECEIVED = "callInviteReceived";
    private static final String EVENT_TYPE_CALL_INVITE_CANCELED = "callInviteCanceled";
    private static final String EVENT_TYPE_CLIENT_REGISTERED = "clientRegistered";
    private static final String EVENT_TYPE_CLIENT_REGISTER_ERROR = "clientRegisterError";
    private static final String EVENT_TYPE_CALL_CONNECT_FAILURE = "callConnectFailure";
    private static final String EVENT_TYPE_CALL_RINGING = "callRinging";
    private static final String EVENT_TYPE_CALL_DID_RECONNECT = "callDidReconnect";
    private static final String EVENT_TYPE_CALL_RECONNECTING = "callReconnecting";

    private static final String DATA_KEY_FROM = "from";
    private static final String DATA_KEY_TO = "to";
    private static final String DATA_KEY_STATE = "state";
    private static final String DATA_KEY_CALL = "call";
    private static final String DATA_KEY_CALL_SID = "callSid";
    private static final String DATA_KEY_IS_MUTED = "isMuted";
    private static final String DATA_KEY_ACCESS_TOKEN = "accessToken";
    private static final String DATA_KEY_FCM_TOKEN = "fcmToken";
    private static final String DATA_KEY_ERROR_CODE = "errorCode";
    private static final String DATA_KEY_ERROR_MESSAGE = "errorMessage";

    private AudioManager audioManager;
    private int savedAudioMode = AudioManager.MODE_INVALID;
    private CallbackContext sharedEventContext;
    private CallbackContext mInitCallbackContext;
    private JSONArray mInitDeviceSetupArgs;
    private int mCurrentNotificationId = 1;
    private String mCurrentNotificationText;
    Call.Listener mCallListener = callListener();
    // Twilio Voice Member Variables
    private Call mCall;
    private CallInvite mCallInvite;
    // Access Token
    private String mAccessToken;
    // FCM Token
    private String mFCMToken;
    // Has the plugin been initialized
    private boolean mInitialized = false;
    // An incoming call intent to process (can be null)
    private Intent mIncomingCallIntent;

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (RECEIVER_ACTION_SET_FCM_TOKEN.equals(action)) {
                String fcmToken = intent.getStringExtra(INTENT_EXTRA_FCM_TOKEN);
                Timber.i("FCM Token : " + fcmToken);
                mFCMToken = fcmToken;
                if (fcmToken == null) {
                    javascriptErrorback(0, "Did not receive GCM Token - unable to receive calls", mInitCallbackContext);
                }
                if (mFCMToken != null) {
                    register();
                }
            } else if (RECEIVER_ACTION_INCOMING_CALL.equals(action)) {
                /*
                 * Handle the incoming call invite
                 */
                handleIncomingCallIntent(intent);
            }
        }
    };

    // Twilio Voice Registration Listener
    private RegistrationListener mRegistrationListener = new RegistrationListener() {
        @Override
        public void onRegistered(String accessToken, String fcmToken) {
            Timber.d("Registered Voice Client");
            TwilioVoicePlugin.this.onClientRegistered(accessToken, fcmToken);
        }

        @Override
        public void onError(RegistrationException exception, String accessToken, String fcmToken) {
            Timber.e(exception, "Error registering Voice Client: %s", exception.getMessage());
            TwilioVoicePlugin.this.onClientRegisterError(exception, accessToken, fcmToken);
        }
    };

    // Twilio Voice Call Listener
    // private Call.Listener mCallListener = new Call.Listener() {
    private Call.Listener callListener() {
        return new Call.Listener() {

            @Override
            public void onRinging(@NonNull Call call) {
                Timber.d("Ringing");
                emitCallListenerEvent(EVENT_TYPE_CALL_RINGING, call);
            }

            @Override
            public void onConnected(@NonNull Call call) {
                mCall = call;

                JSONObject callProperties = new JSONObject();
                try {
                    callProperties.put(DATA_KEY_FROM, call.getFrom());
                    callProperties.put(DATA_KEY_TO, call.getTo());
                    callProperties.put(DATA_KEY_CALL_SID, call.getSid());
                    callProperties.put(DATA_KEY_IS_MUTED, call.isMuted());
                    callProperties.put(DATA_KEY_STATE, call.getState().toString());
                    setAudioFocus(true);
                } catch (JSONException e) {
                    Timber.e(e);
                }
                javascriptCallback("oncalldidconnect", callProperties, mInitCallbackContext);
                emitCallListenerEvent(EVENT_TYPE_CALL_DID_CONNECT, call);
            }

            @Override
            public void onDisconnected(@NonNull Call call, CallException exception) {
                mCall = null;
                setAudioFocus(false);
                javascriptCallback("oncalldiddisconnect", mInitCallbackContext);
                emitCallListenerEvent(EVENT_TYPE_CALL_DID_DISCONNECT, call, exception);
            }

            @Override
            public void onConnectFailure(@NonNull Call call, @NonNull CallException exception) {
                mCall = null;
                setAudioFocus(false);
                javascriptErrorback(exception.getErrorCode(), exception.getMessage(), mInitCallbackContext);
                emitCallListenerEvent(EVENT_TYPE_CALL_CONNECT_FAILURE, call, exception);
            }

            @Override
            public void onReconnected(@NonNull Call call) {
                mCall = call;
                setAudioFocus(true);
                emitCallListenerEvent(EVENT_TYPE_CALL_DID_RECONNECT, call);
            }

            @Override
            public void onReconnecting(@NonNull Call call, @NonNull CallException exception) {
                Timber.d("Reconnecting");
                emitCallListenerEvent(EVENT_TYPE_CALL_RECONNECTING, call, exception);
            }
        };
    }

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        Timber.d("initialize()");

        // initialize sound SoundPoolManager
        SoundPoolManager.getInstance(cordova.getActivity());

        Context context = cordova.getActivity().getApplicationContext();
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        // Handle an incoming call intent if launched from a notification
        Intent intent = cordova.getActivity().getIntent();
        String action = intent.getAction();
        if (RECEIVER_ACTION_INCOMING_CALL.equals(action)) {
            mIncomingCallIntent = intent;
        }
    }

    @Override
    public void onRestoreStateForActivityResult(Bundle state, CallbackContext callbackContext) {
        super.onRestoreStateForActivityResult(state, callbackContext);
        Timber.d("onRestoreStateForActivityResult()");
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
    public boolean execute(final String action, final JSONArray args,
                           final CallbackContext callbackContext) throws JSONException {
        if ("initializeWithAccessToken".equals(action)) {
            Timber.d("Initializing with Access Token");

            mAccessToken = args.optString(0);

            mInitCallbackContext = callbackContext;

            // request Audio permission
            if (ContextCompat.checkSelfPermission(cordova.getContext(), Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED) {
                Timber.d("Has Record Audio Permission. Continue initialize.");

                initializeWithAccessToken();
            } else {
                Timber.d("Need Record Audio Permission.");
                CordovaPlugin context = this;

                cordova.getThreadPool().execute(new Runnable() {
                    public void run() {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                            PermissionHelper.requestPermission(context, PERMISSION_REQUEST_CODE, Manifest.permission.RECORD_AUDIO);
                        }
                    }
                });
            }

            return true;

        } else if (ACTION_CALL.equals(action)) {
            call(args, callbackContext);
            return true;
        } else if (ACTION_ACCEPT_CALL_INVITE.equals(action)) {
            acceptCallInvite(args, callbackContext);
            return true;
        } else if (ACTION_DISCONNECT.equals(action)) {
            disconnect(args, callbackContext);
            return true;
        } else if (ACTION_SEND_DIGITS.equals(action)) {
            sendDigits(args, callbackContext);
            return true;
        } else if (ACTION_MUTE_CALL.equals(action)) {
            muteCall(callbackContext);
            return true;
        } else if (ACTION_UN_MUTE_CALL.equals(action)) {
            unmuteCall(callbackContext);
            return true;
        } else if (ACTION_IS_CALL_MUTED.equals(action)) {
            isCallMuted(callbackContext);
            return true;
        } else if (ACTION_CALL_STATUS.equals(action)) {
            callStatus(callbackContext);
            return true;
        } else if (ACTION_REJECT_CALL_INVITE.equals(action)) {
            rejectCallInvite(args, callbackContext);
            return true;
        } else if (ACTION_SHOW_NOTIFICATION.equals(action)) {
            //showNotification(args, callbackContext);
            return true;
        } else if (ACTION_CANCEL_NOTIFICATION.equals(action)) {
            //cancelNotification(args, callbackContext);
            return true;
        } else if (ACTION_SET_SPEAKER.equals(action)) {
            setSpeaker(args, callbackContext);
            return true;
        } else if (ACTION_SET_SHARED_EVENT_LISTENER.equals(action)) {
            setSharedEventListener(callbackContext);
            return true;
        }

        return false;
    }

    private void setSharedEventListener(CallbackContext callbackContext) {
        if (sharedEventContext != null) {
            sharedEventContext.error("event listener callback overwritten");
        }
        sharedEventContext = callbackContext;
    }

    private void emitSharedJsEvent(String type, JSONObject data) {
        Timber.d("emitSharedJsEvent -> %s", type);
        try {
            if (sharedEventContext == null) {
                return;
            }
            if (data == null) {
                data = JSON_OBJECT_EMPTY;
            }
            JSONObject payload = new JSONObject()
                .put(KEY_TYPE, type)
                .put(KEY_DATA, data);
            PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, payload);
            pluginResult.setKeepCallback(true);
            sharedEventContext.sendPluginResult(pluginResult);
        } catch (JSONException e) {
            Timber.e("emitSharedJsEvent failed! -> %s", e.getMessage());
        }
    }

    private void emitCallListenerEvent(String eventType, Call call) {
        emitCallListenerEvent(eventType, call, null);
    }

    private void emitCallListenerEvent(String eventType, Call call, CallException exception) {
        JSONObject eventData = getCallJsonNested(call);

        if (exception != null) {
            try {
                eventData.put(DATA_KEY_ERROR_CODE, exception.getErrorCode());
                eventData.put(DATA_KEY_ERROR_MESSAGE, exception.getMessage());
            } catch (JSONException e) {
                Timber.e(e);
            }
        }

        emitSharedJsEvent(eventType, eventData);
    }

    private static JSONObject getCallJsonNested(Call call) {
        JSONObject result = new JSONObject();
        try {
            result.put(DATA_KEY_CALL, getCallJson(call));
        } catch (JSONException e) {
            Timber.e(e);
        }
        return result;
    }

    private static JSONObject getCallJson(Call call) {
        JSONObject result = new JSONObject();
        try {
            result.put(DATA_KEY_FROM, call.getFrom());
            result.put(DATA_KEY_TO, call.getTo());
            result.put(DATA_KEY_CALL_SID, call.getSid());
            result.put(DATA_KEY_IS_MUTED, call.isMuted());
            result.put(DATA_KEY_STATE, call.getState().toString());
        } catch (JSONException e) {
            Timber.e(e);
        }
        return result;
    }

    private void initializeWithAccessToken() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(RECEIVER_ACTION_INCOMING_CALL);

        if (mIncomingCallIntent != null) {
            Timber.d("initialize(): Handle an incoming call");
            handleIncomingCallIntent(mIncomingCallIntent);
            mIncomingCallIntent = null;
        }

        javascriptCallback("onclientinitialized", mInitCallbackContext);
        emitSharedJsEvent(EVENT_TYPE_CLIENT_INITIALIZED, null);
    }

    private void call(final JSONArray arguments, final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    String accessToken = arguments.getString(0);
                    JSONObject options = arguments.getJSONObject(1);
                    Map<String, String> map = new HashMap();
                    map.put("accessToken", accessToken);

                    Iterator<String> keys = options.keys();
                    while(keys.hasNext()) {
                        String key = keys.next();
                        try {
                            map.put(key, options.getString(key));
                        } catch (Exception e) {
                            Timber.e(e.toString());
                        }
                    }

                    ConnectOptions connectOptions = new ConnectOptions.Builder(accessToken)
                            .params(map)
                            .build();
                    mCall = Voice.connect(cordova.getActivity(), connectOptions, mCallListener);
                } catch (Exception e) {
                    Timber.e(e.toString());
                }
            }
        });

    }

    private void acceptCallInvite(JSONArray arguments, final CallbackContext callbackContext) {
        if (mCallInvite == null) {
            callbackContext.sendPluginResult(new PluginResult(
                    PluginResult.Status.ERROR));
            return;
        }
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                mCallInvite.accept(cordova.getActivity(), mCallListener);
                callbackContext.success();
            }
        });

    }

    private void rejectCallInvite(JSONArray arguments, final CallbackContext callbackContext) {
        if (mCallInvite == null) {
            callbackContext.sendPluginResult(new PluginResult(
                    PluginResult.Status.ERROR));
            return;
        }
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                mCallInvite.reject(cordova.getActivity());
                callbackContext.success();
            }
        });
    }

    private void disconnect(JSONArray arguments, final CallbackContext callbackContext) {
        if (mCall == null) {
            callbackContext.sendPluginResult(new PluginResult(
                    PluginResult.Status.ERROR));
            return;
        }
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                mCall.disconnect();
                callbackContext.success();
            }
        });
    }

    private void sendDigits(final JSONArray arguments,
                            final CallbackContext callbackContext) {
        if (arguments == null || arguments.length() < 1 || mCall == null) {
            callbackContext.sendPluginResult(new PluginResult(
                    PluginResult.Status.ERROR));
            return;
        }
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                mCall.sendDigits(arguments.optString(0));
                callbackContext.success();
            }
        });

    }

    private void muteCall(final CallbackContext callbackContext) {
        if (mCall == null) {
            callbackContext.sendPluginResult(new PluginResult(
                    PluginResult.Status.ERROR));
            return;
        }
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                mCall.mute(true);
                callbackContext.success();
            }
        });
    }

    private void unmuteCall(final CallbackContext callbackContext) {
        if (mCall == null) {
            callbackContext.sendPluginResult(new PluginResult(
                    PluginResult.Status.ERROR));
            return;
        }
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                mCall.mute(false);
                callbackContext.success();
            }
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

    // private void showNotification(JSONArray arguments, CallbackContext context) {
    //     Context acontext = TwilioVoicePlugin.this.webView.getContext();
    //     NotificationManager mNotifyMgr = (NotificationManager) acontext.getSystemService(Activity.NOTIFICATION_SERVICE);
    //     mNotifyMgr.cancelAll();
    //     mCurrentNotificationText = arguments.optString(0);

    //     PackageManager pm = acontext.getPackageManager();
    //     Intent notificationIntent = pm.getLaunchIntentForPackage(acontext.getPackageName());
    //     notificationIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    //     notificationIntent.putExtra("notificationTag", "BVNotification");

    //     PendingIntent pendingIntent = PendingIntent.getActivity(acontext, 0, notificationIntent, 0);
    //     int notification_icon = acontext.getResources().getIdentifier("notification", "drawable", acontext.getPackageName());
    //     NotificationCompat.Builder mBuilder =
    //             new NotificationCompat.Builder(acontext)
    //                     .setSmallIcon(notification_icon)
    //                     .setContentTitle("Incoming Call")
    //                     .setContentText(mCurrentNotificationText)
    //                     .setContentIntent(pendingIntent);
    //     mNotifyMgr.notify(mCurrentNotificationId, mBuilder.build());

    //     context.success();
    // }

    // private void cancelNotification(JSONArray arguments, CallbackContext context) {
    //     NotificationManager mNotifyMgr = (NotificationManager) TwilioVoicePlugin.this.webView.getContext().getSystemService(Activity.NOTIFICATION_SERVICE);
    //     mNotifyMgr.cancel(mCurrentNotificationId);
    //     context.success();
    // }

    /**
     * Changes sound from earpiece to speaker and back
     */
    public void setSpeaker(final JSONArray arguments, final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                String mode = arguments.optString(0);
                if (mode.equals("on")) {
                    Timber.d("SPEAKER");
                    audioManager.setMode(AudioManager.MODE_NORMAL);
                    audioManager.setSpeakerphoneOn(true);
                } else {
                    Timber.d("EARPIECE");
                    audioManager.setMode(AudioManager.MODE_IN_CALL);
                    audioManager.setSpeakerphoneOn(false);
                }
            }
        });
    }

    @Override
    public void onAudioFocusChange(int i) {
        Timber.d("onAudioFocusChange() focus = %s", nameOfAudioFocusGainConstant(i));
    }

    private static String nameOfAudioFocusGainConstant(int audioFocus) {
        switch (audioFocus) {
            case AudioManager.AUDIOFOCUS_GAIN:
                return "AUDIOFOCUS_GAIN";
            case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT:
                return "AUDIOFOCUS_GAIN_TRANSIENT";
            case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK:
                return "AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK";
            case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE:
                return "AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE";
            case AudioManager.AUDIOFOCUS_LOSS:
                return "AUDIOFOCUS_LOSS";
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                return "AUDIOFOCUS_LOSS_TRANSIENT";
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                return "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK";
            case AudioManager.AUDIOFOCUS_NONE:
                return "AUDIOFOCUS_NONE";
            default:
                return "UNKNOWN";
        }
    }

    private static String nameOfAudioFocusRequestConstant(int audioFocus) {
        switch (audioFocus) {
            case AudioManager.AUDIOFOCUS_REQUEST_FAILED:
                return "AUDIOFOCUS_REQUEST_FAILED";
            case AudioManager.AUDIOFOCUS_REQUEST_GRANTED:
                return "AUDIOFOCUS_REQUEST_GRANTED";
            case AudioManager.AUDIOFOCUS_REQUEST_DELAYED:
                return "AUDIOFOCUS_REQUEST_DELAYED";
            default:
                return "UNKNOWN";
        }
    }

    @SuppressLint("WrongConstant")
    private void setAudioFocus(boolean setFocus) {
        Timber.d("setAudioFocus() %s", setFocus);
        if (audioManager != null) {
            if (setFocus) {

                // Save the current mode so we can revert later
                savedAudioMode = audioManager.getMode();
                int targetMode = AudioManager.MODE_NORMAL;

                // If lower than android 11 (API 30) then revert to previous behavior.
                // (Fixes choppy / inaudible sound)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    targetMode = AudioManager.MODE_IN_COMMUNICATION;
                }

                /*
                 * Start by setting MODE_IN_COMMUNICATION as default audio mode. It is
                 * required to be in this mode when playout and/or recording starts for
                 * best possible VoIP performance. Some devices have difficulties with speaker mode
                 * if this is not set.
                 *
                 * EDIT 04-30-2024:
                 * Need to use MODE_NORMAL to get sufficient volume output levels from the other call participant.
                 * We do not utilize recording functionality from twilio, so the above limitation can be bypassed.
                 */
                audioManager.setMode(targetMode);
                Timber.d("audio mode set to %s", targetMode);

                final int focusType = AudioManager.AUDIOFOCUS_GAIN;

                // Request audio focus before making any device switch.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    AudioAttributes playbackAttributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();
                    AudioFocusRequest focusRequest = new AudioFocusRequest.Builder(focusType)
                        .setAudioAttributes(playbackAttributes)
                        .setAcceptsDelayedFocusGain(true)
                        .setOnAudioFocusChangeListener(this)
                        .build();
                    final int resultCode = audioManager.requestAudioFocus(focusRequest);
                    final String resultName = nameOfAudioFocusRequestConstant(resultCode);
                    Timber.d("audio focus request result (new API) = %s", resultName);
                } else {
                    final int resultCode = audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, focusType);
                    final String resultName = nameOfAudioFocusRequestConstant(resultCode);
                    Timber.d("audio focus request result (legacy API) = %s", resultName);
                }
            } else {
                audioManager.setMode(savedAudioMode);
                final int resultCode = audioManager.abandonAudioFocus(this);
                final String resultName = nameOfAudioFocusRequestConstant(resultCode);
                Timber.d("abandon audio focus request result = %s", resultName);
            }
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

    private void javascriptCallback(String event,
                                    CallbackContext callbackContext) {
        javascriptCallback(event, null, callbackContext);
    }


    private void javascriptErrorback(int errorCode, String errorMessage, CallbackContext callbackContext) {
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

    private void fireDocumentEvent(String eventName) {
        if (eventName != null) {
            javascriptCallback(eventName, mInitCallbackContext);
        }
    }

    @Override
    public void onDestroy() {
        //lifecycle events
        SoundPoolManager.getInstance(cordova.getActivity()).release();
        // LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(cordova.getActivity());
        // lbm.unregisterReceiver(mBroadcastReceiver);
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions,
                                          int[] grantResults) throws JSONException {
        boolean permissionError = false;
        for (int permissionResult : grantResults) {
            if (permissionResult == PackageManager.PERMISSION_DENIED) {
                permissionError = true;
            }
        }
        if (permissionError) {
            Timber.e("Record Audio Permission Failed.");
        } else {
            Timber.d("Record Audio Permission Granted. Initializing Twilio.");
            initializeWithAccessToken();
        }
    }

    /*
     * Register your FCM token with Twilio to enable receiving incoming calls via FCM
     */
    private void register() {
        Voice.register(mAccessToken, Voice.RegistrationChannel.FCM, mFCMToken, mRegistrationListener);
    }

    private void onClientRegistered(String accessToken, String fcmToken) {
        JSONObject eventData = new JSONObject();
        try {
            eventData.put(DATA_KEY_ACCESS_TOKEN, accessToken);
            eventData.put(DATA_KEY_FCM_TOKEN, fcmToken);
        } catch (JSONException ignored) {
        }

        emitSharedJsEvent(EVENT_TYPE_CLIENT_REGISTERED, eventData);
    }

    private void onClientRegisterError(RegistrationException exception, String accessToken, String fcmToken) {
        JSONObject eventData = new JSONObject();
        try {
            eventData.put(DATA_KEY_ERROR_CODE, exception.getErrorCode());
            eventData.put(DATA_KEY_ERROR_MESSAGE, exception.getMessage());
            eventData.put(DATA_KEY_ACCESS_TOKEN, accessToken);
            eventData.put(DATA_KEY_FCM_TOKEN, fcmToken);
        } catch (JSONException ignored) {
        }

        emitSharedJsEvent(EVENT_TYPE_CLIENT_REGISTER_ERROR, eventData);
    }

    // Process incoming call invites
    private void handleIncomingCallIntent(Intent intent) {
        Timber.d("handleIncomingCallIntent()");
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (RECEIVER_ACTION_INCOMING_CALL.equals(action)) {
            mCallInvite = intent.getParcelableExtra(INTENT_EXTRA_INCOMING_CALL_INVITE);
            if (mCallInvite != null) {
                SoundPoolManager.getInstance(cordova.getActivity()).playRinging();
                NotificationManager mNotifyMgr =
                        (NotificationManager) cordova.getActivity().getSystemService(Activity.NOTIFICATION_SERVICE);
                mNotifyMgr.cancel(intent.getIntExtra(INTENT_EXTRA_INCOMING_CALL_NOTIFICATION_ID, 0));
                JSONObject callInviteProperties = new JSONObject();
                try {
                    callInviteProperties.putOpt(DATA_KEY_FROM, mCallInvite.getFrom());
                    callInviteProperties.putOpt(DATA_KEY_TO, mCallInvite.getTo());
                    callInviteProperties.putOpt(DATA_KEY_CALL_SID, mCallInvite.getCallSid());
                } catch (JSONException e) {
                    Timber.e(e);
                }
                Timber.d("oncallinvitereceived");
                javascriptCallback("oncallinvitereceived", callInviteProperties, mInitCallbackContext);
                emitSharedJsEvent(EVENT_TYPE_CALL_INVITE_RECEIVED, callInviteProperties);
            } else {
                SoundPoolManager.getInstance(cordova.getActivity()).stopRinging();
                Timber.d("oncallinvitecanceled");
                javascriptCallback("oncallinvitecanceled", mInitCallbackContext);
                emitSharedJsEvent(EVENT_TYPE_CALL_INVITE_CANCELED, null);
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

    // helper method to get a map of strings from a JSONObject
    public Map<String, String> getMap(JSONObject object) {
        if (object == null) {
            return null;
        }

        Map<String, String> map = new HashMap<String, String>();

        @SuppressWarnings("rawtypes")
        Iterator keys = object.keys();
        while (keys.hasNext()) {
            String key = (String) keys.next();
            map.put(key, object.optString(key));
        }
        return map;
    }

    // helper method to get a JSONObject from a Map of Strings
    public JSONObject getJSONObject(Map<String, String> map) throws JSONException {
        if (map == null) {
            return null;
        }

        JSONObject json = new JSONObject();
        for (String key : map.keySet()) {
            json.putOpt(key, map.get(key));
        }
        return json;
    }
}
