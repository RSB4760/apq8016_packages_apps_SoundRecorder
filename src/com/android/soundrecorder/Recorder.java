/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.soundrecorder;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.MediaPlayer.OnCompletionListener;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;

public class Recorder implements OnCompletionListener, MediaRecorder.OnInfoListener {
    static final String TAG = "Recorder";
    static final String SAMPLE_PREFIX = "recording";
    static final String SAMPLE_PATH_KEY = "sample_path";
    static final String SAMPLE_LENGTH_KEY = "sample_length";

    public static final int IDLE_STATE = 0;
    public static final int RECORDING_STATE = 1;
    public static final int PLAYING_STATE = 2;
    public static final int PAUSE_STATE = 3;

    int mState = IDLE_STATE;

    public static final int NO_ERROR = 0;
    public static final int SDCARD_ACCESS_ERROR = 1;
    public static final int INTERNAL_ERROR = 2;
    public static final int IN_CALL_RECORD_ERROR = 3;
    public static final int UNSUPPORTED_FORMAT = 4;
    public static final int RECORD_INTERRUPTED = 5;

    public int mChannels = 0;
    public int mSamplingRate = 0;

    public String mStoragePath = null;
    public String mTime;

    private int mMaxDuration;

    public interface OnStateChangedListener {
        public void onStateChanged(int state);
        public void onError(int error);
        public void onInfo(int what, int extra);
    }
    OnStateChangedListener mOnStateChangedListener = null;

    MediaPlayer.OnErrorListener mMPErrorListener = new MediaPlayer.OnErrorListener() {
        public boolean onError(MediaPlayer mp, int what, int extra) {
            stop();
            setError(SDCARD_ACCESS_ERROR);
            return true;
        }
    };

    MediaRecorder.OnErrorListener mMRErrorListener = new MediaRecorder.OnErrorListener() {
        public void onError(MediaRecorder mr, int what, int extra) {
            stop();
            setError(RECORD_INTERRUPTED);
        }
    };
    
    long mSampleStart = 0;       // time at which latest record or play operation started
    long mSampleLength = 0;      // length of current sample
    File mSampleFile = null;

    MediaRecorder mRecorder = null;
    MediaPlayer mPlayer = null;

    public Recorder(Context context) {
        if (context.getResources().getBoolean(R.bool.config_storage_path)) {
            mStoragePath = SoundRecorder.applyCustomStoragePath(context);
        } else {
            mStoragePath = SoundRecorder.STORAGE_PATH_LOCAL_PHONE;
        }
    }

    public Recorder() {
    }

    public void saveState(Bundle recorderState) {
        recorderState.putString(SAMPLE_PATH_KEY, mSampleFile.getAbsolutePath());
        recorderState.putLong(SAMPLE_LENGTH_KEY, mSampleLength);
    }

    public int getMaxAmplitude() {
        if (mState != RECORDING_STATE)
            return 0;
        return mRecorder.getMaxAmplitude();
    }

    public void restoreState(Bundle recorderState) {
        String samplePath = recorderState.getString(SAMPLE_PATH_KEY);
        if (samplePath == null)
            return;
        long sampleLength = recorderState.getLong(SAMPLE_LENGTH_KEY, -1);
        if (sampleLength == -1)
            return;

        File file = new File(samplePath);
        if (!file.exists())
            return;
        if (mSampleFile != null
                && mSampleFile.getAbsolutePath().compareTo(file.getAbsolutePath()) == 0)
            return;

        delete();
        mSampleFile = file;
        mSampleLength = sampleLength;

        signalStateChanged(IDLE_STATE);
    }

    public void setOnStateChangedListener(OnStateChangedListener listener) {
        mOnStateChangedListener = listener;
    }

    public void setChannels(int nChannelsCount) {
        mChannels = nChannelsCount;
    }

    public void setSamplingRate(int samplingRate) {
        mSamplingRate = samplingRate;
    }

    public int state() {
        return mState;
    }

    public int progress() {
        if (mState == RECORDING_STATE) {
            return (int) ((mSampleLength + (System.currentTimeMillis() - mSampleStart)) / 1000);
        } else if (mState == PLAYING_STATE) {
            return (int) ((System.currentTimeMillis() - mSampleStart) / 1000);
        }
        return 0;
    }

    public int sampleLength() {
        return (int) (mSampleLength / 1000);
    }

    public File sampleFile() {
        return mSampleFile;
    }
    
    /**
     * Resets the recorder state. If a sample was recorded, the file is deleted.
     */
    public void delete() {
        stop();

        if (mSampleFile != null)
            mSampleFile.delete();

        mSampleFile = null;
        mSampleLength = 0;

        signalStateChanged(IDLE_STATE);
    }

    /**
     * Resets the recorder state. If a sample was recorded, the file is left on disk and will
     * be reused for a new recording.
     */
    public void clear() {
        stop();

        mSampleFile = null;
        mSampleLength = 0;

        signalStateChanged(IDLE_STATE);
    }

    public void startRecording(int outputfileformat, String extension,
                   Context context, int audiosourcetype, int codectype) {
        stop();

        if (mSampleFile != null) {
            mSampleFile.delete();
            mSampleFile = null;
            mSampleLength = 0;
        }

        File sampleDir = new File(mStoragePath);

        if (!sampleDir.exists()) {
            sampleDir.mkdirs();
        }

        if (!sampleDir.canWrite()) // Workaround for broken sdcard support on the device.
            sampleDir = new File("/storage/sdcard1/SoundRecorder/");

        try {
            if (!"".equals(context.getResources().getString(R.string.def_save_name_prefix))) {
                String prefix = context.getResources().
                        getString(R.string.def_save_name_prefix) + '-';
                mSampleFile = createTempFile(context, prefix, extension, sampleDir);
            } else {
                mSampleFile = File.createTempFile(SAMPLE_PREFIX, extension, sampleDir);
            }
        } catch (IOException e) {
            setError(SDCARD_ACCESS_ERROR);
            return;
        }

        mRecorder = new MediaRecorder();
        mRecorder.setAudioSource(audiosourcetype);
        //set channel for surround sound recording.
        if (mChannels > 0) {
            mRecorder.setAudioChannels(mChannels);
        }
        if (mSamplingRate > 0) {
            mRecorder.setAudioSamplingRate(mSamplingRate);
        }

        mRecorder.setOutputFormat(outputfileformat);
        mRecorder.setOnErrorListener(mMRErrorListener);

        mRecorder.setMaxDuration(mMaxDuration);
        mRecorder.setOnInfoListener(this);

        try {
            mRecorder.setAudioEncoder(codectype);
        } catch(RuntimeException exception) {
            setError(UNSUPPORTED_FORMAT);
            mRecorder.reset();
            mRecorder.release();
            if (mSampleFile != null) mSampleFile.delete();
            mSampleFile = null;
            mSampleLength = 0;
            mRecorder = null;
            return;
        }

        mRecorder.setOutputFile(mSampleFile.getAbsolutePath());

        // Handle IOException
        try {
            mRecorder.prepare();
        } catch(IOException exception) {
            setError(INTERNAL_ERROR);
            mRecorder.reset();
            mRecorder.release();
            if (mSampleFile != null) mSampleFile.delete();
            mSampleFile = null;
            mSampleLength = 0;
            mRecorder = null;
            return;
        }
        // Handle RuntimeException if the recording couldn't start
        Log.d(TAG,"audiosourcetype " +audiosourcetype);
        try {
            mRecorder.start();
        } catch (RuntimeException exception) {
            AudioManager audioMngr = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
            boolean isInCall = ((audioMngr.getMode() == AudioManager.MODE_IN_CALL) ||
                    (audioMngr.getMode() == AudioManager.MODE_IN_COMMUNICATION));
            if (isInCall) {
                setError(IN_CALL_RECORD_ERROR);
            } else {
                setError(INTERNAL_ERROR);
            }
            mRecorder.reset();
            mRecorder.release();
            mRecorder = null;
            return;
        }
        mSampleStart = System.currentTimeMillis();
        setState(RECORDING_STATE);
    }

    public void pauseRecording() {
        if (mRecorder == null) {
            return;
        }
        try {
            mRecorder.pause();
        } catch (RuntimeException exception) {
            setError(INTERNAL_ERROR);
            Log.e(TAG, "Pause Failed");
        }
        mSampleLength = mSampleLength + (System.currentTimeMillis() - mSampleStart);
        setState(PAUSE_STATE);
    }

    public void resumeRecording() {
        if (mRecorder == null) {
            return;
        }
        try {
            mRecorder.start();
        } catch (RuntimeException exception) {
            setError(INTERNAL_ERROR);
            Log.e(TAG, "Resume Failed");
        }
        mSampleStart = System.currentTimeMillis();
        setState(RECORDING_STATE);
    }
    
    public void stopRecording() {
        if (mRecorder == null)
            return;
        try {
            mRecorder.stop();
        }catch (RuntimeException exception){
            setError(INTERNAL_ERROR);
            Log.e(TAG, "Stop Failed");
        }
        mRecorder.reset();
        mRecorder.release();
        mRecorder = null;
        mChannels = 0;
        mSamplingRate = 0;
        if (mState == RECORDING_STATE) {
            mSampleLength = mSampleLength + (System.currentTimeMillis() - mSampleStart);
        }
        setState(IDLE_STATE);
    }

    public void startPlayback() {
        stop();

        mPlayer = new MediaPlayer();
        try {
            mPlayer.setDataSource(mSampleFile.getAbsolutePath());
            mPlayer.setOnCompletionListener(this);
            mPlayer.setOnErrorListener(mMPErrorListener);
            mPlayer.prepare();
            mPlayer.start();
        } catch (IllegalArgumentException e) {
            setError(INTERNAL_ERROR);
            mPlayer = null;
            return;
        } catch (IOException e) {
            setError(SDCARD_ACCESS_ERROR);
            mPlayer = null;
            return;
        }

        mSampleStart = System.currentTimeMillis();
        setState(PLAYING_STATE);
    }

    public void stopPlayback() {
        if (mPlayer == null) // we were not in playback
            return;

        mPlayer.stop();
        mPlayer.release();
        mPlayer = null;
        setState(IDLE_STATE);
    }

    public void stop() {
        stopRecording();
        stopPlayback();
    }

    public void onCompletion(MediaPlayer mp) {
        stop();
    }

    private void setState(int state) {
        if (state == mState)
            return;

        mState = state;
        signalStateChanged(mState);
    }

    private void signalStateChanged(int state) {
        if (mOnStateChangedListener != null)
            mOnStateChangedListener.onStateChanged(state);
    }

    private void setError(int error) {
        if (mOnStateChangedListener != null)
            mOnStateChangedListener.onError(error);
    }

    public void setStoragePath(String path) {
        mStoragePath = path;
    }

    public File createTempFile(Context context, String prefix, String suffix, File directory)
            throws IOException {
        // Force a prefix null check first
        if (prefix.length() < 3) {
            throw new IllegalArgumentException("prefix must be at least 3 characters");
        }
        if (suffix == null) {
            suffix = ".tmp";
        }
        File tmpDirFile = directory;
        if (tmpDirFile == null) {
            String tmpDir = System.getProperty("java.io.tmpdir", ".");
            tmpDirFile = new File(tmpDir);
        }

        String nameFormat = context.getResources().getString(R.string.def_save_name_format);
        SimpleDateFormat df = new SimpleDateFormat(nameFormat);
        String currentTime = df.format(System.currentTimeMillis());
        if (!TextUtils.isEmpty(currentTime)) {
            currentTime = currentTime.replaceAll("[\\\\*|\":<>/?]", "_").replaceAll(" ",
                    "\\\\" + " ");
        }

        File result;
        do {
            result = new File(tmpDirFile, prefix + currentTime + suffix);
        } while (!result.createNewFile());
        return result;
    }

    public void setMaxDuration(int duration) {
        mMaxDuration = duration;
    }

    @Override
    public void onInfo(MediaRecorder mr, int what, int extra) {
        if (mOnStateChangedListener != null) {
            mOnStateChangedListener.onInfo(what, extra);
        }
    }
}
