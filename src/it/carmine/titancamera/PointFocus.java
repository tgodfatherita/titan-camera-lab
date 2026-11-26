package it.carmine.titancamera;

import android.graphics.PointF;
import android.hardware.camera2.*;
import android.media.Image;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.*;

/** Measure contrast at the tapped point while the lens sweeps, then hold the best position. */
final class PointFocus {
    interface Result { void ready(Float distance,boolean confirmed); }
    private final CameraCaptureSession session;
    private final PhotoCapture.Requests requests;
    private final Handler worker;
    private final Surface preview,analysis;
    private final PointF point;
    private final float limit,seed;
    private final boolean seedLocked,lockExposure;
    private final Result result;
    private final List<Float> probes=new ArrayList<>();
    private final Map<Float,Double> scores=new TreeMap<>();
    private final Set<Long> eligible=new HashSet<>();
    private final Map<Long,Double> images=new HashMap<>();
    private final Runnable deadline=this::finishSweep;
    private boolean refined,stopped;
    private int index,generation,samples;
    private double scoreSum;

    PointFocus(CameraCaptureSession session,PhotoCapture.Requests requests,Handler worker,
               Surface preview,Surface analysis,PointF point,float limit,float seed,
               boolean seedLocked,boolean lockExposure,Result result) {
        this.session=session;this.requests=requests;this.worker=worker;this.preview=preview;this.analysis=analysis;
        this.point=point;this.limit=limit;this.seed=Math.max(0,Math.min(limit,seed));
        this.seedLocked=seedLocked;this.lockExposure=lockExposure;this.result=result;
    }
    void start() {
        probes.add(seed);
        for(float fraction:new float[]{0,.14f,.28f,.50f,.75f,1}) addProbe(limit*fraction);
        worker.postDelayed(deadline,3200);
        probe();
    }
    private void addProbe(float value) {
        for(float existing:probes) if(Math.abs(existing-value)<Math.max(.01f,limit*.02f)) return;
        probes.add(value);
    }
    private void probe() {
        final int step=++generation;
        final float distance=probes.get(index);
        eligible.clear();images.clear();samples=0;scoreSum=0;
        try {
            CaptureRequest.Builder b=requests.create(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(preview);b.addTarget(analysis);
            b.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_OFF);
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE,distance);
            if(lockExposure) b.set(CaptureRequest.CONTROL_AE_LOCK,true);
            session.setRepeatingRequest(b.build(),new CameraCaptureSession.CaptureCallback() {
                int frames;
                @Override public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest request,TotalCaptureResult r) {
                    if(stopped || generation!=step) return;
                    frames++;
                    if(!settled(r,distance,frames)) return;
                    Long ts=r.get(CaptureResult.SENSOR_TIMESTAMP);
                    if(ts!=null) { if(eligible.size()>12) eligible.clear();eligible.add(ts);match(ts); }
                }
            },worker);
        } catch(Exception e) { Log.w("TitanPhoto","Point focus probe",e);finishSweep(); }
    }
    static boolean settled(TotalCaptureResult r,float distance,int frames) {
        if(frames<2 || Objects.equals(r.get(CaptureResult.LENS_STATE),CaptureResult.LENS_STATE_MOVING)) return false;
        Float actual=r.get(CaptureResult.LENS_FOCUS_DISTANCE);
        return actual==null ? frames>=3 : Math.abs(actual-distance)<=Math.max(.18f,distance*.04f);
    }
    void image(Image image) {
        if(stopped) return;
        long ts=image.getTimestamp();
        if(images.size()>12) images.clear();
        images.put(ts,contrast(image,point));
        match(ts);
    }
    private void match(long ts) {
        if(!eligible.contains(ts) || !images.containsKey(ts)) return;
        eligible.remove(ts);scoreSum+=images.remove(ts);
        if(++samples<2) return;
        float distance=probes.get(index);
        scores.put(distance,scoreSum/samples);
        Log.i("TitanPhoto","PROBE distance="+distance+" contrast="+scores.get(distance));
        if(++index==probes.size() && !refined) {
            refined=true;
            float best=strongest();
            List<Float> sorted=new ArrayList<>(probes);
            Collections.sort(sorted);
            int at=sorted.indexOf(best);
            if(at>0) addProbe((best+sorted.get(at-1))/2);
            if(at+1<sorted.size()) addProbe((best+sorted.get(at+1))/2);
        }
        if(index<probes.size()) probe(); else finishSweep();
    }
    private float strongest() {
        float best=seed;
        double high=-1;
        for(Map.Entry<Float,Double> entry:scores.entrySet()) {
            if(entry.getValue()>high) { high=entry.getValue();best=entry.getKey(); }
        }
        return best;
    }
    private void finishSweep() {
        if(stopped) return;
        cancel();
        if(scores.size()<2) { result.ready(null,seedLocked);return; }
        float best=strongest();
        double peak=scores.get(best), reference=scores.getOrDefault(seed,0.0);
        if(seedLocked && reference>=peak*.96) best=seed;
        boolean confirmed=peak>80 && (seedLocked || peak>reference*1.08);
        Log.i("TitanPhoto","POINT seed="+seed+" distance="+best+" peak="+peak+" confirmed="+confirmed);
        result.ready(best,confirmed);
    }
    void cancel() { stopped=true;worker.removeCallbacks(deadline);eligible.clear();images.clear(); }

    private static double contrast(Image image,PointF center) {
        Image.Plane plane=image.getPlanes()[0];
        ByteBuffer bytes=plane.getBuffer();
        int w=image.getWidth(),h=image.getHeight(),stride=plane.getRowStride(),pixel=plane.getPixelStride();
        int radius=Math.max(3,Math.min(w,h)/24);
        int cx=Math.max(radius+1,Math.min(w-radius-2,Math.round(center.x*(w-1))));
        int cy=Math.max(radius+1,Math.min(h-radius-2,Math.round(center.y*(h-1))));
        long sum=0;
        int base=bytes.position();
        for(int y=cy-radius;y<=cy+radius;y++) {
            int row=base+y*stride;
            for(int x=cx-radius;x<=cx+radius;x++) {
                int i=row+x*pixel;
                int tl=bytes.get(i-stride-pixel)&255, t=bytes.get(i-stride)&255, tr=bytes.get(i-stride+pixel)&255;
                int l=bytes.get(i-pixel)&255, r=bytes.get(i+pixel)&255;
                int bl=bytes.get(i+stride-pixel)&255, b=bytes.get(i+stride)&255, br=bytes.get(i+stride+pixel)&255;
                int gx=tl+2*l+bl-tr-2*r-br, gy=tl+2*t+tr-bl-2*b-br;
                sum+=Math.max(0,gx*gx+gy*gy-256);
            }
        }
        int side=2*radius+1;
        return (double)sum/(side*side);
    }
}
