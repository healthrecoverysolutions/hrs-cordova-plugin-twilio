package com.phonegap.plugins.twiliovoice;

import static android.content.Context.AUDIO_SERVICE;

import android.content.Context;
import android.media.AudioManager;
import android.media.SoundPool;

public class SoundPoolManager {

    private static SoundPoolManager instance;
    private final float volume;
    private final int ringingSoundId;
    private boolean ringing = false;
    private boolean loaded = false;
    private SoundPool soundPool;
    private int ringingStreamId;

    private SoundPoolManager(Context context) {
        // AudioManager audio settings for adjusting the volume
        AudioManager audioManager = (AudioManager) context.getSystemService(AUDIO_SERVICE);
        float actualVolume = (float) audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        float maxVolume = (float) audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        volume = actualVolume / maxVolume;

        // Load the sounds
        int maxStreams = 1;
        soundPool = new SoundPool.Builder()
            .setMaxStreams(maxStreams)
            .build();

        soundPool.setOnLoadCompleteListener((soundPool, sampleId, status) -> loaded = true);

        int ringingResourceId = context.getResources().getIdentifier("ringing", "raw", context.getPackageName());
        ringingSoundId = soundPool.load(context, ringingResourceId, 1);
    }

    public static SoundPoolManager getInstance(Context context) {
        if (instance == null) {
            instance = new SoundPoolManager(context);
        }
        return instance;
    }

    public void playRinging() {
        if (loaded && !ringing && soundPool != null) {
            ringingStreamId = soundPool.play(ringingSoundId, volume, volume, 1, -1, 1f);
            ringing = true;
        }
    }

    public void stopRinging() {
        if (ringing && soundPool != null) {
            soundPool.stop(ringingStreamId);
            ringing = false;
        }
    }

    public void release() {
        if (soundPool != null) {
            soundPool.unload(ringingSoundId);
            soundPool.release();
            soundPool = null;
        }
        instance = null;
    }

    public boolean isRinging() {
        return ringing;
    }
}
