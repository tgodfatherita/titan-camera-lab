package it.carmine.titancamera;

import android.hardware.camera2.*;
import android.os.*;
import android.util.*;
import android.view.Surface;
import java.util.*;

/** A focus operation belongs to one camera session. A shutter press saves one JPEG. */
final class PhotoCapture {
    enum Mode {
        BALANCED("Bilanciata","BILANCIATA"), FAST("Rapida · 1/120 s","RAPIDA"), AUTO("Automatica","AUTO");
        final String label, suffix;
        Mode(String label,String suffix) { this.label=label;this.suffix=suffix; }
        @Override public String toString() { return label; }
    }
    interface Requests { CaptureRequest.Builder create(int template) throws CameraAccessException; }
    interface Result { void finish(PhotoCapture shot, byte[] jpeg, String message); }
    interface FocusListener { void changed(FocusMarker.State state); }
    private static final String TAG="TitanPhoto";
    private final CameraCaptureSession session;
    private final CameraCharacteristics camera;
    private final Handler worker;
    private final Surface preview, jpeg, analysis;
    private PointFocus sweep;
    private boolean verifying;
    private Float lastLens;
    private Float lensPosition;
    private final Requests requests;
    private final Result result;
    private final CameraCaptureSession.CaptureCallback previewResults;
    private final FocusListener focusListener;
    private final boolean explicitTarget;
    Mode mode;
    private final float subjectX, subjectY;
    private long begin, focusAt;
    private boolean focusComplete;
    private boolean closed, capturing, focused, aeReady;
    private boolean flashRequested,waitingForFlash;
    private Integer stillFlashState;
    private int focusMode, focusGeneration, expected, failed, matched, chosen;
    private long meterNs;
    private int meterIso;
    private final Map<Long,Integer> completed=new HashMap<>();
    private final Map<Long,Integer> started=new HashMap<>();
    private final LinkedHashMap<Long,byte[]> incoming=new LinkedHashMap<>();
    private byte[] best;
    private double bestScore=Double.NEGATIVE_INFINITY;
    private final Runnable focusDeadline=this::nativeFocusReady;
    private final Runnable settleDeadline=()->{focused=false;finishFocus();};
    private final Runnable imageDeadline=this::finishAvailable;
    private final Runnable flashDeadline=this::flashTimedOut;

    PhotoCapture(CameraCaptureSession session, CameraCharacteristics camera, Handler worker, Surface preview,
                 Surface jpeg, Requests requests, Result result, CameraCaptureSession.CaptureCallback previewResults,
                 FocusListener focusListener, float x, float y, boolean explicitTarget, Surface analysis, Float lockedLens) {
        this.session=session;this.camera=camera;this.worker=worker;this.preview=preview;this.jpeg=jpeg;
        this.requests=requests;this.result=result;this.previewResults=previewResults;this.focusListener=focusListener;
        subjectX=x;subjectY=y;this.explicitTarget=explicitTarget;this.analysis=analysis;lensPosition=lockedLens;
        int[] modes=camera.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (explicitTarget && supports(modes,CaptureRequest.CONTROL_AF_MODE_AUTO)) focusMode=CaptureRequest.CONTROL_AF_MODE_AUTO;
        else if (supports(modes,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) focusMode=CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
        else if (supports(modes,CaptureRequest.CONTROL_AF_MODE_AUTO)) focusMode=CaptureRequest.CONTROL_AF_MODE_AUTO;
        else focusMode=CaptureRequest.CONTROL_AF_MODE_OFF;
    }
    void shoot(Mode selected,boolean flash) {
        if (closed || mode!=null) return;
        mode=selected;
        flashRequested=flash;
        begin=SystemClock.elapsedRealtime();
        prepareFocus();
    }
    void prepareFocus(){
        if(closed || capturing)return;
        if (focusGeneration==0 && lensPosition!=null) holdLens(lensPosition,true);
        else if (focusGeneration==0) beginFocus();
        else if (focusComplete) {
            if(!focused || !aeReady || (lensPosition==null && SystemClock.elapsedRealtime()-focusAt>=5000))beginFocus();
            else if(mode!=null)capture();
        }
        // A shutter press during an active scan is completed by finishFocus().
    }
    void exposureChanged(){
        if(closed || mode!=null)return;
        if(sweep!=null){sweep.cancel();sweep=null;}
        worker.removeCallbacks(focusDeadline);worker.removeCallbacks(settleDeadline);
        focusComplete=false;aeReady=false;meterNs=0;meterIso=0;
        if(lensPosition!=null)holdLens(lensPosition,focused);
        else beginFocus();
    }
    private static boolean supports(int[] values,int wanted) {
        if (values!=null) for (int value:values) if (value==wanted) return true;
        return false;
    }
    private CaptureRequest.Builder focusRequest(int trigger) throws CameraAccessException {
        CaptureRequest.Builder b=requests.create(CameraDevice.TEMPLATE_PREVIEW);
        b.addTarget(preview);
        b.set(CaptureRequest.CONTROL_AF_MODE,focusMode);
        b.set(CaptureRequest.CONTROL_AF_TRIGGER,trigger);
        return b;
    }
    void beginFocus() {
        final int generation=++focusGeneration;
        focusComplete=false;focused=false;aeReady=false;verifying=false;lensPosition=null;
        worker.removeCallbacks(focusDeadline);
        worker.postDelayed(focusDeadline,2100);
        if (explicitTarget) focusListener.changed(FocusMarker.State.SCANNING);
        CameraCaptureSession.CaptureCallback watch=new CameraCaptureSession.CaptureCallback() {
            private long triggerFrame=Long.MAX_VALUE;
            @Override public void onCaptureStarted(CameraCaptureSession s,CaptureRequest req,long time,long number) {
                if (Objects.equals(req.get(CaptureRequest.CONTROL_AF_TRIGGER),CaptureRequest.CONTROL_AF_TRIGGER_START))
                    triggerFrame=number;
            }
            @Override public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest req,TotalCaptureResult r) {
                if (closed||capturing||generation!=focusGeneration) return;
                previewResults.onCaptureCompleted(s,req,r);
                Long t=r.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                Integer iso=r.get(CaptureResult.SENSOR_SENSITIVITY);
                if (t!=null && iso!=null) { meterNs=t;meterIso=iso; }
                Integer ae=r.get(CaptureResult.CONTROL_AE_STATE), af=r.get(CaptureResult.CONTROL_AF_STATE);
                aeReady=ae==null || ae==CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                        ae==CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED || ae==CaptureResult.CONTROL_AE_STATE_LOCKED;
                if (focusComplete || verifying || (focusMode!=CaptureRequest.CONTROL_AF_MODE_OFF && r.getFrameNumber()<triggerFrame)) return;
                Float lens=r.get(CaptureResult.LENS_FOCUS_DISTANCE);if(lens!=null) lastLens=lens;
                focused=focusMode==CaptureRequest.CONTROL_AF_MODE_OFF || Objects.equals(af,CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED);
                if (focused && aeReady && meterNs>0) {
                    if (explicitTarget) Log.i(TAG,"LOCK point="+subjectX+","+subjectY+" distance="+r.get(CaptureResult.LENS_FOCUS_DISTANCE)+
                            " regions="+Arrays.toString(r.get(CaptureResult.CONTROL_AF_REGIONS)));
                    nativeFocusReady();
                }
                else if (Objects.equals(af,CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) &&
                         focusMode==CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE &&
                         supports(camera.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),CaptureRequest.CONTROL_AF_MODE_AUTO)) {
                    focusMode=CaptureRequest.CONTROL_AF_MODE_AUTO;
                    beginFocus();
                } else if (Objects.equals(af,CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)) nativeFocusReady();
            }
        };
        try {
            session.setRepeatingRequest(focusRequest(CaptureRequest.CONTROL_AF_TRIGGER_IDLE).build(),watch,worker);
            if (focusMode!=CaptureRequest.CONTROL_AF_MODE_OFF) {
                CaptureRequest start=focusRequest(CaptureRequest.CONTROL_AF_TRIGGER_START).build();
                // In AUTO a locked lens ignores a second START until it receives CANCEL.
                if (focusMode==CaptureRequest.CONTROL_AF_MODE_AUTO) {
                    session.captureBurst(Arrays.asList(focusRequest(CaptureRequest.CONTROL_AF_TRIGGER_CANCEL).build(),start),watch,worker);
                } else session.capture(start,watch,worker);
            }
        } catch (Exception e) { fail(e); }
    }
    private void nativeFocusReady() {
        if(closed || verifying || focusComplete) return;
        worker.removeCallbacks(focusDeadline);
        Float limit=camera.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        if(!explicitTarget || analysis==null || lastLens==null || limit==null || limit<=0) { finishFocus();return; }
        verifying=true;
        final float seed=lastLens;
        final boolean nativeLock=focused;
        int rotation=camera.get(CameraCharacteristics.SENSOR_ORIENTATION);
        boolean lockExposure=aeReady && Boolean.TRUE.equals(camera.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE));
        sweep=new PointFocus(session,requests,worker,preview,analysis,PhotoMetering.sensorPoint(subjectX,subjectY,rotation),
                limit,seed,nativeLock,lockExposure,(selected,confirmed)->{
                    sweep=null;
                    if(!closed) holdLens(selected!=null?selected:seed,confirmed);
                });
        sweep.start();
    }
    void focusImage(android.media.Image image) { if(sweep!=null) sweep.image(image); }
    private void holdLens(float distance,boolean confirmed) {
        lensPosition=distance;
        final int generation=++focusGeneration;
        try {
            CaptureRequest.Builder b=requests.create(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(preview);
            b.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_OFF);
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE,distance);
            session.setRepeatingRequest(b.build(),new CameraCaptureSession.CaptureCallback() {
                int frames;
                @Override public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest req,TotalCaptureResult r) {
                    if(closed || capturing || generation!=focusGeneration) return;
                    previewResults.onCaptureCompleted(s,req,r);
                    Long time=r.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                    Integer iso=r.get(CaptureResult.SENSOR_SENSITIVITY), ae=r.get(CaptureResult.CONTROL_AE_STATE);
                    if(time!=null && iso!=null) { meterNs=time;meterIso=iso; }
                    aeReady=ae==null || ae==CaptureResult.CONTROL_AE_STATE_CONVERGED || ae==CaptureResult.CONTROL_AE_STATE_LOCKED ||
                            ae==CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED;
                    if(!focusComplete && PointFocus.settled(r,distance,++frames) && aeReady) {
                        focused=confirmed;finishFocus();
                    }
                }
            },worker);
            worker.removeCallbacks(settleDeadline);worker.postDelayed(settleDeadline,900);
        } catch(Exception e) { fail(e); }
    }
    private void finishFocus() {
        if (closed || focusComplete || capturing) return;
        focusComplete=true;focusAt=SystemClock.elapsedRealtime();
        worker.removeCallbacks(settleDeadline);
        worker.removeCallbacks(focusDeadline);
        if (explicitTarget) {
            FocusMarker.State state=focused?FocusMarker.State.FOCUSED:FocusMarker.State.MISSED;
            if(focusMode==CaptureRequest.CONTROL_AF_MODE_OFF)state=aeReady?FocusMarker.State.METERED:FocusMarker.State.MISSED;
            focusListener.changed(state);
        }
        if (mode!=null) capture();
    }
    private void capture() {
        if (closed||capturing) return;
        capturing=true;
        worker.removeCallbacks(focusDeadline);
        if(flashRequested)meterFlash();else captureJpeg();
    }
    private void applyFocusedLens(CaptureRequest.Builder b) {
        if(lensPosition!=null) {
            b.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_OFF);
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE,lensPosition);
        } else b.set(CaptureRequest.CONTROL_AF_MODE,focusMode);
        b.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
    }
    private void meterFlash() {
        waitingForFlash=true;
        CameraCaptureSession.CaptureCallback watch=new CameraCaptureSession.CaptureCallback(){
            private long triggerFrame=Long.MAX_VALUE;
            private boolean sawPrecapture;
            @Override public void onCaptureStarted(CameraCaptureSession s,CaptureRequest req,long ts,long frame){
                if(Objects.equals(req.get(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER),CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START))
                    triggerFrame=frame;
            }
            @Override public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest req,TotalCaptureResult r){
                if(closed || !waitingForFlash || r.getFrameNumber()<triggerFrame)return;
                Integer ae=r.get(CaptureResult.CONTROL_AE_STATE);
                if(Objects.equals(ae,CaptureResult.CONTROL_AE_STATE_PRECAPTURE)){sawPrecapture=true;return;}
                boolean settled=Objects.equals(ae,CaptureResult.CONTROL_AE_STATE_CONVERGED) ||
                        Objects.equals(ae,CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED) || Objects.equals(ae,CaptureResult.CONTROL_AE_STATE_LOCKED);
                // Some devices skip the transient PRECAPTURE result. Ignore stale states just after START.
                if(settled && (sawPrecapture || r.getFrameNumber()-triggerFrame>=5))captureJpeg();
            }
        };
        try {
            CaptureRequest.Builder b=requests.create(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(preview);applyFocusedLens(b);
            b.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
            b.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
            session.setRepeatingRequest(b.build(),watch,worker);
            b.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            session.capture(b.build(),watch,worker);
            worker.postDelayed(flashDeadline,3500);
        } catch(Exception e){fail(e);}
    }
    private void flashTimedOut(){
        if(closed || !waitingForFlash)return;
        Log.w(TAG,"Flash metering timed out");
        end(true);result.finish(this,null,"Flash non pronto. Riprova.");
    }
    private void captureJpeg() {
        waitingForFlash=false;worker.removeCallbacks(flashDeadline);
        // In bright light one native exposure suffices. Indoors, compare a short burst
        // at the native metered exposure so the ISP can render shadows normally.
        expected=!flashRequested && mode==Mode.BALANCED && (meterNs==0 || meterNs>11_111_111L || !focused) ? 3 : 1;
        try {
            CaptureRequest.Builder b=requests.create(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(jpeg);
            b.set(CaptureRequest.JPEG_ORIENTATION,camera.get(CameraCharacteristics.SENSOR_ORIENTATION));
            b.set(CaptureRequest.JPEG_QUALITY,(byte)95);
            applyFocusedLens(b);
            if(flashRequested)b.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
            if (expected>1 && aeReady && Boolean.TRUE.equals(camera.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE)))
                b.set(CaptureRequest.CONTROL_AE_LOCK,true);
            if (!flashRequested && mode==Mode.FAST && meterNs>8_333_333L && meterIso>0 &&
                    supports(camera.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),
                            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) {
                Range<Long> times=camera.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                Range<Integer> isos=camera.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                long t=times.clamp(8_333_333L);
                int iso=isos.clamp((int)Math.min(Integer.MAX_VALUE,Math.round(meterIso*((double)meterNs/t))));
                b.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_OFF);
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME,t);
                b.set(CaptureRequest.SENSOR_SENSITIVITY,iso);
                b.set(CaptureRequest.SENSOR_FRAME_DURATION,Math.max(t,33_333_333L));
            }
            List<CaptureRequest> series=new ArrayList<>();
            for (int i=0;i<expected;i++) { b.setTag(i);series.add(b.build()); }
            session.captureBurst(series,stillResults,worker);
            worker.postDelayed(imageDeadline,5000);
        } catch (Exception e) { fail(e); }
    }
    private final CameraCaptureSession.CaptureCallback stillResults=new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureStarted(CameraCaptureSession s,CaptureRequest req,long timestamp,long frame) {
            if(!closed) started.put(timestamp,(Integer)req.getTag());
        }
        @Override public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest req,TotalCaptureResult r) {
            if (closed) return;
            Long ts=r.get(CaptureResult.SENSOR_TIMESTAMP);
            stillFlashState=r.get(CaptureResult.FLASH_STATE);
            Log.i(TAG,"FRAME mode="+mode.name()+" index="+req.getTag()+" af="+r.get(CaptureResult.CONTROL_AF_STATE)+
                    " focus="+r.get(CaptureResult.LENS_FOCUS_DISTANCE)+" t="+r.get(CaptureResult.SENSOR_EXPOSURE_TIME)+
                    " iso="+r.get(CaptureResult.SENSOR_SENSITIVITY)+" ae="+r.get(CaptureResult.CONTROL_AE_STATE)+
                    " aeMode="+r.get(CaptureResult.CONTROL_AE_MODE)+" evIndex="+r.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)+
                    " flashState="+stillFlashState);
            if (ts!=null) { completed.put(ts,(Integer)req.getTag()); match(ts); }
        }
        @Override public void onCaptureFailed(CameraCaptureSession s,CaptureRequest req,CaptureFailure f) {
            if (!closed && ++failed+matched>=expected) finishAvailable();
        }
    };
    void image(long ts,byte[] bytes) {
        if (closed || !capturing) return;
        incoming.put(ts,bytes);
        if(incoming.size()>expected) incoming.remove(incoming.keySet().iterator().next());
        match(ts);
    }
    private void match(long ts) {
        if(!completed.containsKey(ts) || !incoming.containsKey(ts)) return;
        consider(completed.remove(ts),incoming.remove(ts));
        if(matched+failed>=expected) deliver();
    }
    private void consider(int index,byte[] bytes) {
        double score=0;
        if(expected>1) {
            try { score=PhotoDetail.score(bytes,subjectX,subjectY,explicitTarget); }
            catch(Exception e) { score=-1;Log.w(TAG,"Detail score unavailable",e); }
        }
        matched++;
        if (best==null || score>bestScore) { best=bytes;bestScore=score;chosen=index; }
        Log.i(TAG,"CANDIDATE index="+index+" detail="+score);
    }
    private void finishAvailable() {
        if(closed) return;
        // A JPEG and its shutter timestamp still identify a valid frame if the final
        // metadata callback was lost. Never take an image from another shutter press.
        for(Map.Entry<Long,byte[]> entry:incoming.entrySet()) {
            Integer index=started.get(entry.getKey());
            if(index!=null) consider(index,entry.getValue());
        }
        deliver();
    }
    private void deliver() {
        if (closed) return;
        if (best==null) { fail(new IllegalStateException("JPEG non ricevuto. Riprova."));return; }
        byte[] winner=best;
        Log.i(TAG,"SELECT mode="+mode.name()+" count="+matched+" chosen="+chosen+
                " focusConfirmed="+focused+" elapsedMs="+(SystemClock.elapsedRealtime()-begin));
        end(!explicitTarget || !focused);
        String feedback=focused ? "Foto salvata in galleria" : "Foto salvata · messa a fuoco non confermata";
        if(flashRequested && stillFlashState!=null && stillFlashState!=CaptureResult.FLASH_STATE_FIRED && stillFlashState!=CaptureResult.FLASH_STATE_PARTIAL)
            feedback="Foto salvata · il flash non è scattato";
        result.finish(this,winner,feedback);
    }
    private void fail(Exception e) {
        if(closed) return;
        Log.w(TAG,"Scatto interrotto",e);
        end(true);
        if (explicitTarget) focusListener.changed(FocusMarker.State.MISSED);
        result.finish(this,null,mode==null ? "Tocca di nuovo il soggetto per mettere a fuoco." : "Scatto non riuscito. Riprova.");
    }
    Float verifiedLens() { return focused?lensPosition:null; }
    void cancel() { end(false); }
    void cancelPreparation(){end(true);}
    private void end(boolean unlock) {
        closed=true;waitingForFlash=false;
        worker.removeCallbacks(focusDeadline);worker.removeCallbacks(imageDeadline);worker.removeCallbacks(settleDeadline);worker.removeCallbacks(flashDeadline);
        if(sweep!=null) {sweep.cancel();sweep=null;}
        incoming.clear();completed.clear();started.clear();best=null;
        if (unlock && focusMode!=CaptureRequest.CONTROL_AF_MODE_OFF) {
            try { session.capture(focusRequest(CaptureRequest.CONTROL_AF_TRIGGER_CANCEL).build(),null,worker); }
            catch(Exception ignored) { /* A session that closed during capture is already unlocked. */ }
        }
    }
}
