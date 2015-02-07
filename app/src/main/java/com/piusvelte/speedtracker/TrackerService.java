/**
 Copyright 2015 Bryan Emmanuel <piusvelte@gmail.com>

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
package com.piusvelte.speedtracker;

import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Parcel;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.format.DateUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.ActivityRecognition;
import com.google.android.gms.location.ActivityRecognitionResult;
import com.google.android.gms.location.DetectedActivity;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;

import java.util.List;

public class TrackerService extends Service implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, LocationListener, View.OnClickListener, View.OnLongClickListener {

    private static final int MSG_UPDATE_TIME = 0;

    private static final String PREFS_FILE = "tracker";
    private static final String PREF_LOCATION = "location";
    private static final String PREF_TIMESTAMP = "timestamp";
    private static final String PREF_ACTIVITY = "activity";

    private static final String ACTION_DETECTED_ACTIVITY = "DETECTED_ACTIVITY";

    public static boolean isRunning = false;

    private ScreenReceiver mScreenReceiver;
    private WindowManager mWindowManager;
    private GoogleApiClient mGoogleApiClient;
    private Button mTrackerButton;

    private long mStartTime = 0l;
    private Location mStartLocation;
    private float mDistance = 0f;
    @NonNull
    private String mActivity = getActivity(DetectedActivity.UNKNOWN);

    private SharedPreferences mPreferences;

    private Handler mTimeHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_TIME:
                    updateTrackerButton();
                    mTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, DateUtils.SECOND_IN_MILLIS);
                    return;

                default:
                    super.handleMessage(msg);
                    break;
            }
        }
    };

    private PendingIntent mPendingIntent;

    public TrackerService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        // NO-OP
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;

        mPreferences = getSharedPreferences(PREFS_FILE, MODE_PRIVATE);

        addScreenReceiver();

        connectGoogleApiClient();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_DETECTED_ACTIVITY.equals(intent.getAction())) {
            ActivityRecognitionResult activityRecognitionResult = ActivityRecognitionResult.extractResult(intent);
            DetectedActivity detectedActivity = activityRecognitionResult.getMostProbableActivity();

            if (detectedActivity.getType() == DetectedActivity.ON_FOOT) {
                // determine walking or running
                setActivity(getMostProbableActivity(activityRecognitionResult.getProbableActivities()));
            } else {
                setActivity(detectedActivity.getType());
            }
        }

        return super.onStartCommand(intent, flags, startId);
    }

    private int getMostProbableActivity(@Nullable List<DetectedActivity> detectedActivityList) {
        int mostProbableType = DetectedActivity.UNKNOWN;

        if (detectedActivityList != null) {
            int mostProbableConfidence = 0;

            for (DetectedActivity detectedActivity : detectedActivityList) {
                // filter on walking or running
                int type = detectedActivity.getType();
                int confidence = detectedActivity.getConfidence();

                if ((type == DetectedActivity.WALKING || type == DetectedActivity.RUNNING) && confidence > mostProbableConfidence) {
                    mostProbableType = type;
                    mostProbableConfidence = confidence;
                }
            }
        }

        return mostProbableType;
    }

    @NonNull
    private String getActivity(int type) {
        switch (type) {
            case DetectedActivity.STILL:
                return "still";

            case DetectedActivity.ON_FOOT:
                return "on foot";

            case DetectedActivity.WALKING:
                return "walking";

            case DetectedActivity.RUNNING:
                return "running";

            case DetectedActivity.ON_BICYCLE:
                return "on bicycle";

            case DetectedActivity.IN_VEHICLE:
                return "in vehicle";

            case DetectedActivity.TILTING:
                return "tilting";

            default:
                return "unknown";
        }
    }

    private void setActivity(int type) {
        String activity = getActivity(type);

        if (!activity.equals(mActivity)) {
            mActivity = activity;

            mPreferences.edit()
                    .putString(PREF_ACTIVITY, mActivity)
                    .apply();
        }
    }

    private void addScreenReceiver() {
        if (mScreenReceiver == null) {
            mScreenReceiver = new ScreenReceiver();
            IntentFilter filter = new IntentFilter();
            // for faster syncing, use screen on to post a delayed runnable
            filter.addAction(Intent.ACTION_SCREEN_ON);
            // use screen off as a trigger to check zen mode
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            registerReceiver(mScreenReceiver, filter);
        }
    }

    private void removeScreenReceiver() {
        if (mScreenReceiver != null) {
            unregisterReceiver(mScreenReceiver);
            mScreenReceiver = null;
        }
    }

    private void connectGoogleApiClient() {
        if (mGoogleApiClient == null) {
            mGoogleApiClient = new GoogleApiClient.Builder(this)
                    .addApi(LocationServices.API)
                    .addApi(ActivityRecognition.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
        }

        mGoogleApiClient.connect();
    }

    private void startTracking() {
        resetTrackerButton();
        mTrackerButton.setTextColor(Color.GREEN);
        mStartLocation = LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient);
        mDistance = 0f;
        mStartTime = System.currentTimeMillis();
        mActivity = getActivity(DetectedActivity.UNKNOWN);
        saveTracker();
        resumeTracking();
    }

    private void stopTracking() {
        pauseTracking();
        mTrackerButton.setTextColor(Color.RED);
        mStartTime = 0l;
        mStartLocation = null;
        mDistance = 0f;
        mActivity = getActivity(DetectedActivity.UNKNOWN);
    }

    private void pauseTracking() {
        mTimeHandler.removeMessages(MSG_UPDATE_TIME);
        LocationServices.FusedLocationApi.removeLocationUpdates(mGoogleApiClient, this);
        ActivityRecognition.ActivityRecognitionApi.removeActivityUpdates(mGoogleApiClient, mPendingIntent);
    }

    private void resumeTracking() {
        mTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, DateUtils.SECOND_IN_MILLIS);
        LocationRequest locationRequest = new LocationRequest()
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                .setInterval(5 * DateUtils.SECOND_IN_MILLIS);

        LocationServices.FusedLocationApi.requestLocationUpdates(mGoogleApiClient, locationRequest, this);

        Intent intent = new Intent(this, TrackerService.class).setAction(ACTION_DETECTED_ACTIVITY);
        mPendingIntent = PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        ActivityRecognition.ActivityRecognitionApi.requestActivityUpdates(mGoogleApiClient, 0l, mPendingIntent);
    }

    private void addTrackerButton() {
        if (mWindowManager == null) {
            mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        }

        if (mTrackerButton == null) {
            mTrackerButton = new Button(this);
            mTrackerButton.setOnClickListener(this);
            mTrackerButton.setOnLongClickListener(this);
        }

        mTrackerButton.setBackgroundColor(getResources().getColor(R.color.translucent));
        mTrackerButton.setTextColor(Color.RED);
        resetTrackerButton();

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        Point screenSize = new Point();
        mWindowManager.getDefaultDisplay().getSize(screenSize);
        params.y = screenSize.y / 2;

        mWindowManager.addView(mTrackerButton, params);
    }

    private void resetTrackerButton() {
        mTrackerButton.setText(getString(R.string.tracker_data, 0f, 0, 0, "00", getActivity(DetectedActivity.UNKNOWN)));
    }

    private void updateTrackerButton() {
        long millis = System.currentTimeMillis() - mStartTime;
        float hours = millis / (float) DateUtils.HOUR_IN_MILLIS;
        long minutes = millis / DateUtils.MINUTE_IN_MILLIS;
        long seconds = (millis - minutes * DateUtils.MINUTE_IN_MILLIS) / DateUtils.SECOND_IN_MILLIS;
        String formattedSeconds;

        if (seconds < 10) {
            formattedSeconds = "0" + String.valueOf(seconds);
        } else {
            formattedSeconds = String.valueOf(seconds);
        }

        float kilometers = mDistance / 1000;
        float speed = kilometers / hours;

        mTrackerButton.setText(getString(R.string.tracker_data, speed, (int) mDistance, minutes, formattedSeconds, mActivity));
    }

    private boolean isTracking() {
        return mStartLocation != null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        mTimeHandler.removeMessages(MSG_UPDATE_TIME);

        if (isTracking()) {
            stopTracking();
        }

        removeScreenReceiver();

        if (mTrackerButton != null) {
            mWindowManager.removeView(mTrackerButton);
            mTrackerButton = null;
        }

        if (mGoogleApiClient != null) {
            mGoogleApiClient.disconnect();
        }

        isRunning = false;
    }

    @Override
    public void onConnected(Bundle bundle) {
        addTrackerButton();
        restoreTracker();
    }

    @Override
    public void onConnectionSuspended(int i) {
        pauseTracking();
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        stopSelf();
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location != null) {
            mDistance = mStartLocation.distanceTo(location);
        }
    }

    @Override
    public void onClick(View v) {
        if (isTracking()) {
            stopTracking();
            saveTracker();
        } else {
            startTracking();
        }
    }

    @Override
    public boolean onLongClick(View v) {
        if (isTracking()) {
            stopTracking();
            saveTracker();
        }

        stopSelf();
        return true;
    }

    private void saveTracker() {
        SharedPreferences.Editor editor = mPreferences.edit();

        if (isTracking()) {
            Parcel parcel = Parcel.obtain();
            mStartLocation.writeToParcel(parcel, 0);
            byte[] locationParcelData = parcel.marshall();
            parcel.recycle();
            String locationByteData = Base64.encodeToString(locationParcelData, Base64.NO_WRAP);

            editor.putString(PREF_LOCATION, locationByteData)
                    .putLong(PREF_TIMESTAMP, mStartTime)
                    .putString(PREF_ACTIVITY, mActivity)
                    .apply();
        } else {
            editor.remove(PREF_LOCATION)
                    .remove(PREF_TIMESTAMP)
                    .apply();
        }
    }

    private void restoreTracker() {
        String locationByteData = mPreferences.getString(PREF_LOCATION, null);
        long timestamp = mPreferences.getLong(PREF_TIMESTAMP, 0);

        if (locationByteData != null && timestamp > 0) {
            byte[] locationParcelData = Base64.decode(locationByteData, Base64.NO_WRAP);

            Parcel parcel = Parcel.obtain();
            parcel.unmarshall(locationParcelData, 0, locationByteData.length());
            parcel.setDataPosition(0);

            mStartLocation = Location.CREATOR.createFromParcel(parcel);
            mStartTime = timestamp;
            mActivity = mPreferences.getString(PREF_ACTIVITY, getActivity(DetectedActivity.UNKNOWN));

            onLocationChanged(LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient));

            mTrackerButton.setTextColor(Color.GREEN);
            resumeTracking();
        }
    }

    private class ScreenReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (isTracking()) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    pauseTracking();
                } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    resumeTracking();
                }
            }
        }
    }
}
