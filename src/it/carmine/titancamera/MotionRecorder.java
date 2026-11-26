package it.carmine.titancamera;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.*;
import android.hardware.camera2.*;
import android.os.*;
import android.util.Log;
import android.util.Size;
import android.util.SizeF;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Only measurements used by the gyro profile are written, next to its private video. */
final class MotionRecorder implements SensorEventListener {
    static final Set<String> exporting = ConcurrentHashMap.newKeySet();
    private final CaptureDraft draft;
    private final SensorManager sensors;
    private final HandlerThread thread = new HandlerThread("TitanMotion");
    private final JSONObject info = new JSONObject();
    private final DataOutputStream output;
    private long firstCameraNs;
    private int sensorWidth,sensorHeight;
    private Size recordingSize;
    private double captureZoom=1;
    private boolean closed, eis;
    private IOException failure;

    private MotionRecorder(Context context, CaptureDraft draft) throws IOException {
        this.draft = draft;
        sensors = context.getSystemService(SensorManager.class);
        output = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(draft.samples), 65536));
    }

    // Each stream is center-cropped to its aspect ratio before it is scaled.
    private double streamScale(int w,int h) {
        return Math.max((double)recordingSize.getWidth()/w,(double)recordingSize.getHeight()/h);
    }

    static MotionRecorder start(Context context, CaptureDraft draft, CameraCharacteristics camera, Size size, Rect crop) throws Exception {
        MotionRecorder recorder = new MotionRecorder(context, draft);
        recorder.thread.start();
        try {
            float[] lens = camera.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION);
            Rect active = camera.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            recorder.recordingSize=size;
            if(active!=null && !active.isEmpty()) {
                recorder.sensorWidth=active.width();recorder.sensorHeight=active.height();
                double scale=recorder.streamScale(active.width(),active.height());
                if(lens!=null && lens.length>0 && lens[0]>0)recorder.info.put("focal",lens[0]*scale);
                else {
                    // The Titan selfie camera reports the lens and sensor dimensions in mm.
                    float[] mm=camera.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                    SizeF physical=camera.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                    Size pixels=camera.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
                    if(mm!=null && mm.length>0 && physical!=null && physical.getWidth()>0 && pixels!=null)
                        recorder.info.put("focal",mm[0]*pixels.getWidth()/physical.getWidth()*scale);
                }
                if(crop!=null && !crop.isEmpty())recorder.captureZoom=recorder.streamScale(crop.width(),crop.height())/scale;
            }
            recorder.info.put("model", Build.MODEL);
            recorder.info.put("clock", camera.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE));
            recorder.info.put("rotation", camera.get(CameraCharacteristics.SENSOR_ORIENTATION));
            recorder.info.put("front",Integer.valueOf(CameraCharacteristics.LENS_FACING_FRONT).equals(camera.get(CameraCharacteristics.LENS_FACING)));
            recorder.info.put("width", size.getWidth()).put("height", size.getHeight());
            Sensor gyro = recorder.sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            if (gyro == null || !recorder.sensors.registerListener(recorder, gyro, 5000, 0, new Handler(recorder.thread.getLooper())))
                throw new IOException("Giroscopio non disponibile. Seleziona Marcata.");
            return recorder;
        } catch (Exception e) {
            recorder.abort();
            throw e;
        }
    }

    @Override public synchronized void onSensorChanged(SensorEvent event) {
        if (closed || failure != null) return;
        try {
            output.writeLong(event.timestamp);
            for (int i = 0; i < 3; i++) output.writeFloat(event.values[i]);
        } catch (IOException e) { failure = e; }
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    synchronized void frame(TotalCaptureResult result) {
        if (closed) return;
        Long time = result.get(CaptureResult.SENSOR_TIMESTAMP);
        if (firstCameraNs == 0 && time != null) {
            firstCameraNs = time;
            Rect actual=result.get(CaptureResult.SCALER_CROP_REGION);
            if(actual!=null && !actual.isEmpty() && sensorWidth>=actual.width() && sensorHeight>=actual.height())
                captureZoom=streamScale(actual.width(),actual.height())/streamScale(sensorWidth,sensorHeight);
        }
        Integer mode = result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE);
        if (mode != null && mode != 0) eis = true;
    }

    void finishAfterCallbacks() {
        String key = draft.uri.toString();
        exporting.add(key);
        new Handler(thread.getLooper()).postDelayed(() -> {
            try {
                close();
                info.put("firstCameraNs", firstCameraNs).put("eis", eis).put("captureZoom",captureZoom);
                if (failure != null) throw failure;
                try (FileOutputStream file = new FileOutputStream(draft.calibration)) {
                    file.write(info.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception e) { Log.w("TitanMotion", "Private capture data incomplete", e); }
            finally { exporting.remove(key); }
        }, 120);
    }

    private synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        sensors.unregisterListener(this);
        thread.quitSafely();
        output.close();
    }

    void abort() {
        try { close(); } catch (IOException e) { Log.w("TitanMotion", "Closing gyroscope", e); }
    }
}
