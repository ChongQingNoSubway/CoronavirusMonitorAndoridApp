package com.example.myapplication;


import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Camera;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.hardware.Camera.PreviewCallback;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Handler;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public class HeartMeasure extends AppCompatActivity implements SurfaceHolder.Callback {
    SurfaceHolder SurfaceHolder;
    SurfaceView SurfaceView;
    public Camera mCamera;
    boolean mPreviewRunning;

    private static int averageIndex = 0;
    private static final int averageArraySize = 4;
    private static final int[] averageArray = new int[averageArraySize];
    public static Context c;


    private static final AtomicBoolean processing = new AtomicBoolean(false);
    public  static TextView ts = null;
    private static double beats = 0;
    private static long startTime = 0;
    private static int beatsIndex = 0;
    private static final int beatsArraySize = 3;
    private static final int[] beatsArray = new int[beatsArraySize];

    public enum TYPE {
        GREEN, RED
    };

    private static TYPE currentType = TYPE.GREEN;

    public static TYPE getCurrent() {
        return currentType;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ts = (TextView) findViewById(R.id.number1);
        SurfaceView = (SurfaceView) findViewById(R.id.bgc);
        SurfaceHolder = SurfaceView.getHolder();
        SurfaceHolder.addCallback(this);
        SurfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        c = getApplicationContext();
    }

    @Override
    protected void onDestroy(){
        super.onDestroy();
        SurfaceView.getHolder().removeCallback(this);
    }

    public void onPause() {
        super.onPause();
        if (mCamera != null) {
            mCamera.setPreviewCallback(null);
            SurfaceView.getHolder().removeCallback(this);
            mCamera.release();
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mCamera = Camera.open();
        Camera.Parameters parameters = mCamera.getParameters();
        if(parameters.getMaxExposureCompensation() != parameters.getMinExposureCompensation()){
            parameters.setExposureCompensation(0);
        }
        mCamera.setParameters(parameters);
        mCamera.setPreviewCallback(previewCallback);
        startTime = System.currentTimeMillis();

        final Handler mHandler = new Handler();
        Runnable r = new Runnable() {

            @Override
            public void run() {
                //do something
                mPreviewRunning =false;
                mCamera.setPreviewCallback(null);
                mCamera.stopPreview();
            }
        };

//主线程中调用：
        mHandler.postDelayed(r, 10*1000);
    }

    @Override
    public void surfaceChanged(SurfaceHolder arg0, int arg1, int arg2, int arg3){
        if (mPreviewRunning) {
            mCamera.stopPreview();
        }
        Camera.Parameters p = mCamera.getParameters();
        p.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
        mCamera.setParameters(p);
        try {
            mCamera.setPreviewDisplay(arg0);
        } catch (IOException e) {
            e.printStackTrace();
        }
        setCameraDisplayOrientation(mCamera);

        mCamera.startPreview();
        mPreviewRunning = true;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mCamera.stopPreview();
        mPreviewRunning = false;
        mCamera.release();
    }


    public void setCameraDisplayOrientation(android.hardware.Camera camera) {
        Camera.Parameters parameters = camera.getParameters();
        android.hardware.Camera.CameraInfo camInfo = new android.hardware.Camera.CameraInfo();
        android.hardware.Camera.getCameraInfo(0, camInfo);

        Display display = ((WindowManager) this.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        int rotation = display.getRotation();
        int degrees = 0;
        switch (rotation) {
            case Surface.ROTATION_0: degrees = 0; break;
            case Surface.ROTATION_90: degrees = 90; break;
            case Surface.ROTATION_180: degrees = 180; break;
            case Surface.ROTATION_270: degrees = 270; break;
        }

        int result;
        if (camInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (camInfo.orientation + degrees) % 360;
            result = (360 - result) % 360;  // compensate the mirror
        } else {  // back-facing
            result = (camInfo.orientation - degrees + 360) % 360;
        }
        camera.setDisplayOrientation(result);
    }

    /**
     * FROM: https://github.com/phishman3579/android-heart-rate-monitor
     */
    private PreviewCallback previewCallback = new PreviewCallback() {


        @Override
        public void onPreviewFrame(byte[] data, Camera camera) {
            if (mPreviewRunning == false) {
                return;
            }
            if (data == null) throw new NullPointerException();
            Camera.Size size = camera.getParameters().getPreviewSize();
            if (size == null) throw new NullPointerException();

            if (!processing.compareAndSet(false, true)) return;

            int width = size.width;
            int height = size.height;

            int imgAvg = ImageProcessing.decodeYUV420SPtoRedAvg(data.clone(), height, width);
            // Log.i(TAG, "imgAvg="+imgAvg);
            if (imgAvg == 0 || imgAvg == 255) {
                processing.set(false);
                return;
            }

            int averageArrayAvg = 0;
            int averageArrayCnt = 0;
            for (int i = 0; i < averageArray.length; i++) {
                if (averageArray[i] > 0) {
                    averageArrayAvg += averageArray[i];
                    averageArrayCnt++;
                }
            }

            int rollingAverage = (averageArrayCnt > 0) ? (averageArrayAvg / averageArrayCnt) : 0;

            TYPE newType = currentType;

            if (imgAvg < rollingAverage) {
                newType = TYPE.RED;
                if (newType != currentType) {
                    beats++;
                    // Log.d(TAG, "BEAT!! beats="+beats);
                }
            } else if (imgAvg > rollingAverage) {
                newType = TYPE.GREEN;
            }

            if (averageIndex == averageArraySize) averageIndex = 0;
            averageArray[averageIndex] = imgAvg;
            averageIndex++;

            ts.setText("please wait 45S , and put your finger on the back camera");
            long endTime = System.currentTimeMillis();
            double totalTimeInSecs = (endTime - startTime) / 1000d;
            if (totalTimeInSecs >= 10) {
                ts.setText("finish the measure");
                double bps = (beats / totalTimeInSecs);
                int dpm = (int) (bps * 60d);
                if (dpm < 30 || dpm > 180) {
                    startTime = System.currentTimeMillis();
                    beats = 0;
                    processing.set(false);
                    return;
                }

                // Log.d(TAG,
                // "totalTimeInSecs="+totalTimeInSecs+" beats="+beats);

                if (beatsIndex == beatsArraySize) beatsIndex = 0;
                beatsArray[beatsIndex] = dpm;
                beatsIndex++;

                int beatsArrayAvg = 0;
                int beatsArrayCnt = 0;
                for (int i = 0; i < beatsArray.length; i++) {
                    if (beatsArray[i] > 0) {
                        beatsArrayAvg += beatsArray[i];
                        beatsArrayCnt++;
                    }
                }
                int beatsAvg = (beatsArrayAvg / beatsArrayCnt);
                ts.setText(String.valueOf(beatsAvg));
                SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(c);
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putString("LAST_MEASURE", String.valueOf(beatsAvg));
                editor.commit();

                startTime = System.currentTimeMillis();
                beats = 0;
            }
            processing.set(false);
        }

    };


}