package it.carmine.titancamera;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Rect;
import android.hardware.camera2.params.MeteringRectangle;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.camera2.params.OutputConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.*;
import android.widget.*;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {
    private static final String TAG = "TitanCameraLab";
    private HandlerThread thread;
    private Handler worker;
    private TextureView preview;
    private CameraScreen screen;
    private CameraSettings settings;
    private volatile CaptureGallery latestCapture;
    private int galleryRequest;
    private final java.util.concurrent.ExecutorService thumbnails=java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile float selectedZoom=1;
    private float cameraZoom=1;
    private volatile float zoomLimit=1;
    private Rect activeArray,requestedCrop;
    private long recordingStarted;
    private final Runnable applyZoom=this::applySelectedZoom;
    private volatile Range<Integer> exposureRange;
    private volatile int photoExposure,videoExposure;
    private volatile float exposureStep;
    private final Runnable applyExposure=this::applySelectedExposure;
    private volatile PhotoCapture.Mode photoMode=PhotoCapture.Mode.BALANCED;
    private PhotoCapture photo;
    private PhotoCapture timerFocus;
    private PhotoTimer photoTimer;
    private long timerSpaceDown=Long.MIN_VALUE;
    private FocusMarker focusMarker;
    private boolean subjectChosen, touchFocusSupported, touchExposureSupported;
    private float subjectX=.5f, subjectY=.5f;
    private Rect photoCrop;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private CameraCharacteristics characteristics;
    private ImageReader jpeg, focusReader;
    private Float lockedPhotoLens;
    private Surface previewSurface;
    private MediaRecorder recorder;
    private CaptureDraft activeDraft;
    private volatile boolean videoMode;
    private volatile boolean recording, busy, foreground;
    private volatile boolean opening,frontFacing,canSwitchLens;
    private volatile boolean photoFlashAvailable,torchAvailable,photoFlashOn,videoTorchOn;
    private boolean supportsManual;
    private int cameraGeneration;
    private String catalogId;
    private long exposure = 10000000L, lastStatus;
    private int sensitivity = 100, sensorOrientation;
    private volatile Size videoSize,photoSize;
    private volatile CaptureFormats formats;
    private Size lastWorkingPhoto,lastWorkingVideo;
    private boolean verifyPointFocus;
    private int configurationId;
    private FormatPicker picker;
    private volatile Size previewSize;
    private Range<Integer> fps;
    private TextView jobStatus;
    private ProgressBar jobProgress;
    private volatile Uri lastVideo;
    private volatile boolean processingUi;
    private MotionRecorder motion;
    private volatile boolean gyroProfile=true;
    private long processingRequestedAt;
    private final Handler uiHandler=new Handler(Looper.getMainLooper());
    private final Runnable jobTick=new Runnable(){public void run(){refreshJob();if(foreground)uiHandler.postDelayed(this,600);}};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        thread = new HandlerThread("TitanCamera"); thread.start(); worker = new Handler(thread.getLooper());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN|WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        getWindow().setNavigationBarColor(0xff060607);
        videoMode=getSharedPreferences("camera",0).getBoolean("video",false);
        frontFacing=getSharedPreferences("camera",0).getBoolean("front",false);
        gyroProfile=getSharedPreferences("stabilization",0).getBoolean("gyroProfile",true);
        try {photoMode=PhotoCapture.Mode.valueOf(getSharedPreferences("photo",0).getString("mode","BALANCED"));}
        catch(IllegalArgumentException e){photoMode=PhotoCapture.Mode.BALANCED;}
        screen=new CameraScreen(this);preview=screen.preview;focusMarker=screen.marker;
        jobStatus=screen.jobStatus;jobProgress=screen.progress;
        settings=new CameraSettings(this,new CameraSettings.Change(){
            public void photo(PhotoCapture.Mode chosen) {
                photoMode=chosen;getSharedPreferences("photo",0).edit().putString("mode",chosen.name()).apply();updateUi();
            }
            public void format(boolean video){showFormats(video);}
            public void gyro(boolean on) {
                gyroProfile=on;getSharedPreferences("stabilization",0).edit().putBoolean("gyroProfile",on).apply();
                transform();updateUi();
            }
        });
        FrameLayout finderWindow=new FrameLayout(this){
            @Override public boolean dispatchKeyEventPreIme(KeyEvent event){
                // The first space after a tap can otherwise be consumed when Android leaves touch mode.
                return handlePhotoTimerKey(event) || super.dispatchKeyEventPreIme(event);
            }
            @Override public boolean dispatchTouchEvent(MotionEvent event){
                boolean handled=super.dispatchTouchEvent(event);
                if(event.getActionMasked()==MotionEvent.ACTION_UP && !hasFocus())requestFocus();
                return handled;
            }
        };
        finderWindow.setFocusableInTouchMode(true);
        finderWindow.addView(screen.root,new FrameLayout.LayoutParams(-1,-1));
        photoTimer=new PhotoTimer(this,screen,()->worker.post(this::prepareTimedPhoto),
                ()->{if(!videoMode)pressShutter();},()->worker.post(this::releaseTimerFocus));
        finderWindow.addView(photoTimer,new FrameLayout.LayoutParams(-1,-1));
        setContentView(finderWindow);
        finderWindow.requestFocus();
        screen.exposure.changed=this::changeExposure;
        screen.settings.setOnClickListener(v->settings.show());
        screen.format.setOnClickListener(v->showFormats(videoMode));
        screen.flip.setOnClickListener(v->switchCamera());
        screen.flash.setOnClickListener(v->toggleFlash());
        screen.photo.setOnClickListener(v->selectMode(false));screen.video.setOnClickListener(v->selectMode(true));
        screen.less.setOnClickListener(v->changeZoom(CameraZoom.step(selectedZoom,zoomLimit,false)));
        screen.more.setOnClickListener(v->changeZoom(CameraZoom.step(selectedZoom,zoomLimit,true)));
        screen.zoomValue.setOnClickListener(v->changeZoom(1));
        screen.gallery.setOnClickListener(v->openLatestCapture());
        settings.discard.setOnClickListener(v->confirmDiscard());
        String savedVideo=getSharedPreferences("stabilization",0).getString("lastVideo",null);if(savedVideo!=null)lastVideo=Uri.parse(savedVideo);
        if (!StabilizeService.running) {
            try { GalleryVideo.reconcile(this); } catch(Exception e) { Log.w(TAG,"Gallery recovery pending",e); }
        }
        if (!CaptureDraft.prefs(this).getBoolean("singleResult",false))
            CaptureDraft.prefs(this).edit().remove("status").putBoolean("singleResult",true).apply();
        settings.pick.setOnClickListener(v->{if(recording||busy||processingUi)return;Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(pick,50);});
        settings.process.setOnClickListener(v->{if(processingUi){startService(new Intent(this,StabilizeService.class).setAction(StabilizeService.CANCEL));}else {CaptureDraft draft=CaptureDraft.current(this);Uri source=draft==null?lastVideo:draft.uri;if(source!=null)beginStabilization(source);}});
        settings.last.setOnClickListener(v->{String value=getSharedPreferences("stabilization",0).getString("result",null);if(value!=null)try{startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(value),"video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));}catch(Exception e){note("Apri la copia dalla galleria, cartella TitanCameraLab.");}});
        handleIncoming(getIntent());
        screen.jobAction.setOnClickListener(v->settings.process.performClick());
        installFinderGestures();
        screen.shutter.setOnClickListener(v->pressShutter());
        screen.shutter.setOnLongClickListener(v->{if(videoMode)return false;openPhotoTimer();return true;});
        updateUi();
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture s,int w,int h) { worker.post(()->openCamera()); }
            public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h) {
                Size output=previewSize;
                if(output!=null)s.setDefaultBufferSize(output.getWidth(),output.getHeight());
                transform();
            }
            public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
            public void onSurfaceTextureUpdated(SurfaceTexture s) {}
        });
    }
    private void pressShutter(){
        if(!foreground || busy || processingUi || camera==null || session==null || !screen.shutter.isEnabled())return;
        busy=true;updateUi();
        worker.post(()->{
            if(!videoMode)takePhoto();
            else if(recording)stopVideo();
            else startVideo();
        });
    }
    private void openPhotoTimer(){
        if(foreground && !videoMode && !busy && !processingUi && session!=null && screen.shutter.isEnabled())photoTimer.open();
    }
    private boolean handlePhotoTimerKey(KeyEvent event){
        if(event.getKeyCode()!=KeyEvent.KEYCODE_SPACE || !foreground || videoMode || photoTimer==null || !hasWindowFocus())return false;
        if(event.getAction()==KeyEvent.ACTION_DOWN){
            if(event.getRepeatCount()==0){timerSpaceDown=event.getDownTime();openPhotoTimer();}
        }else if(event.getAction()==KeyEvent.ACTION_UP){
            // Android can use DOWN to move keyboard focus when the last action was a touch.
            if(!event.isCanceled() && timerSpaceDown!=event.getDownTime())openPhotoTimer();
            timerSpaceDown=Long.MIN_VALUE;
        }else{
            return false;
        }
        return true;
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        return handlePhotoTimerKey(event) || super.dispatchKeyEvent(event);
    }
    @Override public void onBackPressed(){if(photoTimer==null || !photoTimer.dismiss())super.onBackPressed();}
    @Override public void onWindowFocusChanged(boolean focus){
        super.onWindowFocusChanged(focus);if(!focus && photoTimer!=null)photoTimer.dismiss();
    }
    private void showFormats(boolean video) {
        CaptureFormats catalog=formats;
        if(catalog==null || recording || processingUi || busy || !foreground)return;
        Size current=video?videoSize:photoSize;
        if(current==null || (picker!=null && picker.dialog.isShowing()))return;
        picker=new FormatPicker(this,video,catalog.choices(video),current,size->selectFormat(video,size));
        picker.dialog.show();
    }
    private void selectFormat(boolean video,Size selected) {
        if(busy || recording || processingUi || !foreground || formats==null || !formats.choices(video).contains(selected))return;
        if(selected.equals(video?videoSize:photoSize))return;
        busy=true;updateUi();
        worker.post(()->{
            if(video)videoSize=selected;else photoSize=selected;
            getSharedPreferences("formats",0).edit().putString(lensKey(video?"video":"photo"),selected.toString()).apply();
            if(video==videoMode && camera!=null && foreground)configure();else {busy=false;updateUi();}
        });
    }
    private void selectMode(boolean video) {
        if(video==videoMode || busy || recording || processingUi || camera==null)return;
        busy=true;videoMode=video;getSharedPreferences("camera",0).edit().putBoolean("video",video).apply();
        updateUi();worker.post(this::configure);
    }
    private String lensKey(String setting) {return frontFacing?"front."+setting:setting;}
    private void toggleFlash() {
        if(!foreground || opening || busy || processingUi || session==null)return;
        final boolean video=videoMode;
        worker.post(()->{
            if(!foreground || camera==null || session==null || video!=videoMode || processingUi)return;
            if(video){
                if(!torchAvailable)return;
                videoTorchOn=!videoTorchOn;
                try {submitRepeating();}
                catch(Exception e){
                    videoTorchOn=!videoTorchOn;
                    Log.w(TAG,"Video light unavailable",e);note("Impossibile cambiare la luce video.");updateUi();return;
                }
            }else{
                if(!photoFlashAvailable || (photo!=null && photo.mode!=null))return;
                photoFlashOn=!photoFlashOn;
            }
            getSharedPreferences("camera",0).edit().putBoolean(lensKey(video?"videoTorch":"photoFlash"),
                    video?videoTorchOn:photoFlashOn).apply();
            updateUi();
        });
    }
    private void switchCamera() {
        if(!foreground || !canSwitchLens || busy || opening || recording || processingUi)return;
        busy=true;updateUi();
        worker.post(()->{
            closeCamera();
            frontFacing=!frontFacing;
            getSharedPreferences("camera",0).edit().putBoolean("front",frontFacing).apply();
            note(frontFacing?"Fotocamera frontale":"Fotocamera posteriore");
            openCamera();
        });
    }
    private void changeZoom(float value) {
        if(busy || recording || processingUi || camera==null || !Float.isFinite(value)) return;
        float next=Math.max(1,Math.min(zoomLimit,value));
        if(Math.abs(next-selectedZoom)<.0001f) return;
        selectedZoom=next;screen.zoom(next,zoomLimit,true);
        getSharedPreferences("camera",0).edit().putFloat(lensKey("zoom"),next).apply();
        worker.removeCallbacks(applyZoom);worker.post(applyZoom);
    }
    private void applySelectedZoom() {
        if(camera==null || recording || recorder!=null || (photo!=null && photo.mode!=null))return;
        cameraZoom=selectedZoom;requestedCrop=CameraZoom.crop(activeArray,cameraZoom);
        resetPhotoTarget();
        if(session!=null)repeat();
    }
    private void changeExposure(int index){
        Range<Integer> range=exposureRange;
        if(!foreground || busy || processingUi || session==null || range==null)return;
        int selected=range.clamp(index);
        if(selected==(videoMode?videoExposure:photoExposure))return;
        if(videoMode)videoExposure=selected;else photoExposure=selected;
        getSharedPreferences("camera",0).edit().putInt(lensKey(videoMode?"videoExposure":"photoExposure"),selected).apply();
        worker.removeCallbacks(applyExposure);worker.post(applyExposure);
    }
    private void applySelectedExposure(){
        if(!foreground || camera==null || session==null || processingUi)return;
        if(!videoMode && photo!=null)photo.exposureChanged();else repeat();
    }
    private void installFinderGestures() {
        final int slop=ViewConfiguration.get(this).getScaledTouchSlop();
        preview.setOnTouchListener(new View.OnTouchListener(){
            boolean moved;
            float startX,startY,initialSpan,initialZoom;
            int first=-1,second=-1;
            private float distance(MotionEvent e,int a,int b) {
                return (float)Math.hypot(e.getX(a)-e.getX(b),e.getY(a)-e.getY(b));
            }
            private void anchor(MotionEvent e,int excluded) {
                first=-1;second=-1;int a=-1;
                for(int i=0;i<e.getPointerCount();i++) {
                    if(i==excluded)continue;
                    if(a<0){a=i;continue;}
                    first=e.getPointerId(a);second=e.getPointerId(i);
                    initialSpan=distance(e,a,i);initialZoom=selectedZoom;break;
                }
            }
            private void scale(MotionEvent e) {
                if(first<0 || second<0 || initialSpan<1)return;
                int a=e.findPointerIndex(first),b=e.findPointerIndex(second);
                if(a>=0 && b>=0)changeZoom(initialZoom*distance(e,a,b)/initialSpan);
            }
            @Override public boolean onTouch(View view,MotionEvent event) {
                if(busy||recording||processingUi) return false;
                int action=event.getActionMasked();
                if(action==MotionEvent.ACTION_DOWN){first=second=-1;moved=false;startX=event.getX();startY=event.getY();}
                if(event.getPointerCount()>1 || Math.hypot(event.getX()-startX,event.getY()-startY)>slop) moved=true;
                if(action==MotionEvent.ACTION_POINTER_DOWN && second<0)anchor(event,-1);
                if(action==MotionEvent.ACTION_MOVE || action==MotionEvent.ACTION_POINTER_UP)scale(event);
                if(action==MotionEvent.ACTION_POINTER_UP) {
                    int index=event.getActionIndex(),id=event.getPointerId(index);
                    if(id==first || id==second)anchor(event,index);
                }
                if(action==MotionEvent.ACTION_UP && !moved && !videoMode) {
                    view.performClick();
                    PointF point=PhotoMetering.touch(event.getX(),event.getY(),view.getWidth(),view.getHeight(),previewSize,sensorOrientation);
                    float sx=event.getX()/view.getWidth(),sy=event.getY()/view.getHeight();
                    if(point!=null){
                        // The front viewfinder is mirrored. JPEGs and sensor regions use the unmirrored point.
                        if(frontFacing)point.x=1-point.x;
                        worker.post(()->focusOn(point,sx,sy));
                    }
                }
                return true;
            }
        });
    }
    private void focusOn(PointF point,float sx,float sy) {
        if(videoMode || session==null || !foreground || (photo!=null && photo.mode!=null)) return;
        if(!touchFocusSupported && !touchExposureSupported) {note("Fuoco ed esposizione sul punto non disponibili.");return;}
        if(photo!=null)photo.cancel();
        subjectX=point.x;subjectY=point.y;subjectChosen=true;lockedPhotoLens=null;
        runOnUiThread(()->focusMarker.aim(sx,sy));
        photo=newPhotoCapture();photo.beginFocus();
    }
    private void refreshGallery(Uri justSaved) {
        runOnUiThread(()->{
            if(thumbnails.isShutdown() || isDestroyed() || isFinishing())return;
            int request=++galleryRequest;
            if(justSaved!=null){
                latestCapture=new CaptureGallery(justSaved,null);
                screen.thumbnail(null);updateUi();
            }
            CaptureGallery previous=latestCapture;
            thumbnails.execute(()->{
                try {
                    Uri uri=justSaved!=null?justSaved:CaptureGallery.latestPhoto(getContentResolver(),
                            getSharedPreferences("camera",0).getString("lastPhotoName",null));
                    CaptureGallery item=CaptureGallery.load(getContentResolver(),uri,previous);
                    runOnUiThread(()->{
                        if(request!=galleryRequest || isDestroyed() || isFinishing())return;
                        if(item!=latestCapture)screen.thumbnail(item==null?null:item.thumbnail);
                        latestCapture=item;updateUi();
                    });
                } catch(RuntimeException e){Log.w(TAG,"Thumbnail unavailable",e);}
            });
        });
    }
    private void openLatestCapture() {
        CaptureGallery item=latestCapture;if(item==null)return;
        try{startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(item.uri,"image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));}
        catch(Exception e){refreshGallery(null);note("Apri la foto dalla galleria, cartella TitanCameraLab.");}
    }
    private void note(String message){Log.i(TAG,message);runOnUiThread(()->screen.message(message));}
    private void updateUi(){runOnUiThread(()->{
        CaptureDraft draft=CaptureDraft.current(this);
        boolean pending=draft!=null && !recording;
        boolean ready=camera!=null && session!=null && !busy && !processingUi;
        boolean editable=!busy && !recording && !processingUi;
        if(!foreground || !ready || videoMode)photoTimer.dismiss();
        screen.exposure.display(exposureRange,exposureStep,videoMode?videoExposure:photoExposure,ready && foreground);
        screen.controls(videoMode,recording,ready&&!recording,ready&&(!videoMode||!pending),recordingStarted);
        screen.lens(frontFacing,canSwitchLens,editable && !opening);
        screen.flash(videoMode,videoMode?torchAvailable:photoFlashAvailable,videoMode?videoTorchOn:photoFlashOn,ready && foreground);
        screen.zoom(selectedZoom,zoomLimit,ready&&!recording);
        String p=photoSize==null?"":CaptureFormats.label(false,photoSize),v=videoSize==null?"":CaptureFormats.label(true,videoSize);
        boolean choose=editable && formats!=null && camera!=null;
        screen.setFormat(videoMode?v:p,choose);settings.formats(p,v,choose);
        CameraScreen.enable(screen.gallery,editable && latestCapture!=null);
        String jobAction=processingUi?"Annulla":pending?"Riprendi salvataggio":"Stabilizza selezionato";
        boolean canProcess=processingUi || (editable && (pending||lastVideo!=null));
        boolean canDiscard=pending && editable && !MotionRecorder.exporting.contains(draft.uri.toString());
        settings.state(photoMode,gyroProfile,editable,pending,editable&&!pending,jobAction,canProcess,
                getSharedPreferences("stabilization",0).contains("result"),editable,canDiscard);
        screen.job(processingUi,pending&&!busy,jobAction,canProcess);
    });}
    @Override public void onResume() { super.onResume(); foreground=true;refreshGallery(null);
        processingUi=StabilizeService.running||processingRequestedAt>0;uiHandler.removeCallbacks(jobTick);uiHandler.post(jobTick);
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO},10);
        else if(preview.isAvailable())worker.post(this::openCamera);
    }
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] results) { super.onRequestPermissionsResult(r,p,results); if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) { if(preview.isAvailable())worker.post(this::openCamera); } else note("Consenti l’accesso alla fotocamera per continuare."); }
    @Override public void onPause() { foreground=false;photoTimer.dismiss();uiHandler.removeCallbacks(jobTick); worker.post(()-> { if(recording)stopVideo(); closeCamera(); }); super.onPause(); }
    @Override public void onDestroy() {if(picker!=null)picker.dialog.dismiss();settings.dismiss();thumbnails.shutdown();worker.post(()-> { closeCamera(); thread.quitSafely(); }); super.onDestroy(); }
    private boolean contains(int[] values,int value) { if(values!=null)for(int v:values)if(v==value)return true; return false; }
    private void openCamera() {
        if(!foreground||processingUi||StabilizeService.running||camera!=null||opening||!preview.isAvailable()||checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;
        final int generation=++cameraGeneration;
        try {
            CameraManager manager=(CameraManager)getSystemService(CAMERA_SERVICE);
            String back=null,front=null;
            for(String candidate:manager.getCameraIdList()){
                Integer facing=manager.getCameraCharacteristics(candidate).get(CameraCharacteristics.LENS_FACING);
                if(back==null && Objects.equals(facing,CameraCharacteristics.LENS_FACING_BACK))back=candidate;
                if(front==null && Objects.equals(facing,CameraCharacteristics.LENS_FACING_FRONT))front=candidate;
                if(back!=null && front!=null)break;
            }
            canSwitchLens=back!=null && front!=null;
            if(!canSwitchLens)frontFacing=front!=null;
            String id=frontFacing?front:back;
            if(id==null)throw new IllegalStateException("Nessuna fotocamera disponibile");
            characteristics=manager.getCameraCharacteristics(id);
            android.util.Rational evStep=characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
            exposureStep=evStep==null?0:evStep.floatValue();
            exposureRange=Float.isFinite(exposureStep) && exposureStep>0?characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE):null;
            photoExposure=exposureRange==null?0:exposureRange.clamp(getSharedPreferences("camera",0).getInt(lensKey("photoExposure"),0));
            videoExposure=exposureRange==null?0:exposureRange.clamp(getSharedPreferences("camera",0).getInt(lensKey("videoExposure"),0));
            torchAvailable=Boolean.TRUE.equals(characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
            photoFlashAvailable=torchAvailable && contains(characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES),
                    CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
            photoFlashOn=photoFlashAvailable && getSharedPreferences("camera",0).getBoolean(lensKey("photoFlash"),false);
            videoTorchOn=torchAvailable && getSharedPreferences("camera",0).getBoolean(lensKey("videoTorch"),false);
            activeArray=characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Float maximum=characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            zoomLimit=maximum==null || activeArray==null?1:Math.max(1,maximum);
            selectedZoom=Math.max(1,Math.min(zoomLimit,getSharedPreferences("camera",0).getFloat(lensKey("zoom"),1)));
            cameraZoom=selectedZoom;
            requestedCrop=CameraZoom.crop(activeArray,cameraZoom);
            supportsManual=contains(characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR);
            sensorOrientation=characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            Integer regions=characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
            Integer aeRegions=characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
            touchExposureSupported=aeRegions!=null && aeRegions>0;
            touchFocusSupported=regions!=null && regions>0 &&
                    contains(characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),CaptureRequest.CONTROL_AF_MODE_AUTO);
            StreamConfigurationMap map=characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            verifyPointFocus=touchFocusSupported && supportsManual &&
                    contains(characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),CaptureRequest.CONTROL_AF_MODE_OFF);
            if(formats==null || !id.equals(catalogId)){
                formats=null;lastWorkingPhoto=null;lastWorkingVideo=null;
                formats=new CaptureFormats(map,verifyPointFocus);
                catalogId=id;
                if(Build.VERSION.SDK_INT>=31){
                    StreamConfigurationMap maxMap=characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION);
                    Log.i(TAG,"MAX_PIXEL_MODE capability="+contains(characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),
                            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR)+" jpeg="+
                            (maxMap==null?"absent":Arrays.toString(maxMap.getOutputSizes(android.graphics.ImageFormat.JPEG))));
                }
                Log.i(TAG,"FORMATS photo="+formats.choices(false)+" video="+formats.choices(true));
            }
            videoSize=formats.restore(true,getSharedPreferences("formats",0).getString(lensKey("video"),""));
            photoSize=formats.restore(false,getSharedPreferences("formats",0).getString(lensKey("photo"),""));
            fps=new Range<>(30,30);
            if(!Arrays.asList(characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)).contains(fps))
                throw new IllegalStateException("Registrazione a 30 fps non disponibile.");
            Log.i(TAG,"CAPABILITIES camera="+id+" maxZoom="+zoomLimit+" manual="+supportsManual+" video="+videoSize+" photo="+photoSize);
            opening=true;busy=true;updateUi();
            manager.openCamera(id,new CameraDevice.StateCallback(){
                public void onOpened(CameraDevice c){
                    if(generation!=cameraGeneration){c.close();return;}
                    opening=false;
                    if(!foreground||processingUi||StabilizeService.running){c.close();busy=false;updateUi();return;}
                    camera=c;configure();
                }
                private void lost(CameraDevice c,String message){
                    if(generation!=cameraGeneration){c.close();return;}
                    if(camera!=c)c.close();
                    closeCamera();note(message);
                }
                public void onDisconnected(CameraDevice c){lost(c,"Fotocamera scollegata. Riapri l’app.");}
                public void onError(CameraDevice c,int code){lost(c,"Errore fotocamera "+code+". Riapri l’app.");}
            },worker);
        }catch(Exception e){opening=false;error(e);}
    }
    private void closePhotoReaders(){
        if(jpeg!=null){jpeg.close();jpeg=null;}
        if(focusReader!=null){focusReader.close();focusReader=null;}
    }
    private void preparePhotoReaders(){
        if(videoMode){closePhotoReaders();return;}
        if(jpeg!=null && jpeg.getWidth()==photoSize.getWidth() && jpeg.getHeight()==photoSize.getHeight())return;
        closePhotoReaders();
        jpeg=ImageReader.newInstance(photoSize.getWidth(),photoSize.getHeight(),android.graphics.ImageFormat.JPEG,3);
        jpeg.setOnImageAvailableListener(this::photoAvailable,worker);
        Size size=formats.analysis(photoSize);
        if(verifyPointFocus && size!=null){
            focusReader=ImageReader.newInstance(size.getWidth(),size.getHeight(),android.graphics.ImageFormat.YUV_420_888,3);
            focusReader.setOnImageAvailableListener(this::focusPreviewAvailable,worker);
        }
    }
    private void recoverFormat(Exception problem){
        boolean video=videoMode;
        Size failed=video?videoSize:photoSize,previous=video?lastWorkingVideo:lastWorkingPhoto;
        discardRecording();
        if(!foreground || camera==null || failed==null || failed.equals(previous) || formats.choices(video).size()<2){error(problem);return;}
        Log.w(TAG,"Formato non compatibile "+failed,problem);
        formats.reject(video,failed);
        Size fallback=formats.restore(video,previous==null?"":previous.toString());
        if(video)videoSize=fallback;else photoSize=fallback;
        getSharedPreferences("formats",0).edit().putString(lensKey(video?"video":"photo"),fallback.toString()).apply();
        note("Formato non disponibile · ripristinato "+CaptureFormats.label(video,fallback));
        busy=true;updateUi();configure();
    }
    private void transform() { if(previewSize==null)return; float w=preview.getWidth(),h=preview.getHeight(); if(w==0||h==0)return;
        // SurfaceTexture already applies the sensor rotation for portrait. Fit without cropping or stretching.
        float imageW=previewSize.getHeight(),imageH=previewSize.getWidth();
        float scale=Math.min(w/imageW,h/imageH)*(videoMode && gyroProfile?(float)GyroMath.ZOOM:1f);
        Matrix m=new Matrix();m.setScale(imageW*scale/w,imageH*scale/h,w/2,h/2);preview.setTransform(m);
    }
    private void configure() {
        if(camera==null||!foreground||processingUi){busy=false;updateUi();return;}
        try {
            resetPhotoTarget();
            if(session!=null){session.close();session=null;}
            if(previewSurface!=null){previewSurface.release();previewSurface=null;}
            final int attempt=++configurationId;final CameraDevice device=camera;
            preparePhotoReaders();previewSize=formats.preview(videoMode?videoSize:photoSize);
            SurfaceTexture texture=preview.getSurfaceTexture(); if(texture==null)return;
            texture.setDefaultBufferSize(previewSize.getWidth(),previewSize.getHeight()); previewSurface=new Surface(texture);
            final float aspect=(float)previewSize.getHeight()/previewSize.getWidth();
            runOnUiThread(()->{screen.aspect(aspect);transform();});
            // Explicitly negotiate the stream size even if the old session still owns this texture.
            OutputConfiguration finderOutput=new OutputConfiguration(previewSize,SurfaceTexture.class);
            finderOutput.addSurface(previewSurface);
            List<OutputConfiguration> outputs=new ArrayList<>();outputs.add(finderOutput);
            if(videoMode&&recorder!=null)outputs.add(new OutputConfiguration(recorder.getSurface()));
            else if(!videoMode)outputs.add(new OutputConfiguration(jpeg.getSurface()));
            final ImageReader analysis=videoMode?null:focusReader;
            if(analysis!=null) outputs.add(new OutputConfiguration(analysis.getSurface()));
            camera.createCaptureSessionByOutputConfigurations(outputs,new CameraCaptureSession.StateCallback(){
                public void onConfigured(CameraCaptureSession s){if(camera!=device||attempt!=configurationId||!foreground||processingUi){s.close();return;}session=s;repeat();
                    if(!videoMode)lastWorkingPhoto=photoSize;
                    if(recorder!=null&&!recording){try{recorder.start();recordingStarted=SystemClock.elapsedRealtime();recording=true;lastWorkingVideo=videoSize;}catch(Exception e){recoverFormat(e);return;}}
                    if(!recording)note(videoMode?"Pronto · "+CaptureFormats.label(true,videoSize):"Pronto · tocca il soggetto per mettere a fuoco");
                    busy=false;updateUi();}
                public void onConfigureFailed(CameraCaptureSession s){
                    s.close();if(camera!=device || attempt!=configurationId || !foreground || processingUi)return;
                    recoverFormat(new IllegalStateException("Combinazione di formati non supportata."));
                }
            },worker);
        }catch(IllegalArgumentException e){recoverFormat(e);}
        catch(Exception e){discardRecording();error(e);}
    }
    private CaptureRequest.Builder base(int template) throws CameraAccessException {
        CaptureRequest.Builder b=camera.createCaptureRequest(template);
        if(videoMode&&recorder!=null&&motion!=null)b.setTag(motion);
        b.set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO);
        if(requestedCrop!=null)b.set(CaptureRequest.SCALER_CROP_REGION,requestedCrop);
        int afMode=videoMode ? CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO : CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
        if(!videoMode && subjectChosen && touchFocusSupported) afMode=CaptureRequest.CONTROL_AF_MODE_AUTO;
        if(!videoMode && subjectChosen && lockedPhotoLens!=null) {
            afMode=CaptureRequest.CONTROL_AF_MODE_OFF;
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE,lockedPhotoLens);
        }
        int[] afModes=characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if(!contains(afModes,afMode))afMode=contains(afModes,CaptureRequest.CONTROL_AF_MODE_AUTO)
                ?CaptureRequest.CONTROL_AF_MODE_AUTO:CaptureRequest.CONTROL_AF_MODE_OFF;
        b.set(CaptureRequest.CONTROL_AF_MODE,afMode);
        if(!videoMode && (touchFocusSupported || (subjectChosen && touchExposureSupported))) {
            Rect crop=photoCrop!=null?photoCrop:requestedCrop;
            MeteringRectangle region=PhotoMetering.region(subjectX,subjectY,crop,previewSize,sensorOrientation,subjectChosen);
            if(region!=null){
                MeteringRectangle[] target={region};
                if(touchFocusSupported)b.set(CaptureRequest.CONTROL_AF_REGIONS,target);
                if(!touchFocusSupported && subjectChosen && touchExposureSupported)b.set(CaptureRequest.CONTROL_AE_REGIONS,target);
            }
        }
        b.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON);
        if(exposureRange!=null)b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,videoMode?videoExposure:photoExposure);
        b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO);
        b.set(CaptureRequest.FLASH_MODE,videoMode && videoTorchOn?CaptureRequest.FLASH_MODE_TORCH:CaptureRequest.FLASH_MODE_OFF);
        if(videoMode)b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,fps);
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,0);
        return b;
    }
    private final CameraCaptureSession.CaptureCallback metadata=new CameraCaptureSession.CaptureCallback(){
        @Override public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest req,TotalCaptureResult result){
            if(req.getTag() instanceof MotionRecorder)((MotionRecorder)req.getTag()).frame(result);
            if(s!=session)return;
            if(!videoMode && Objects.equals(req.get(CaptureRequest.SCALER_CROP_REGION),requestedCrop)) photoCrop=result.get(CaptureResult.SCALER_CROP_REGION);
            Long t=result.get(CaptureResult.SENSOR_EXPOSURE_TIME);Integer iso=result.get(CaptureResult.SENSOR_SENSITIVITY);
            if(t!=null)exposure=t;if(iso!=null)sensitivity=iso;
            if(SystemClock.elapsedRealtime()-lastStatus<1500)return;lastStatus=SystemClock.elapsedRealtime();
            Log.i(TAG,"RESULT recording="+recording+" zoom="+cameraZoom+" crop="+result.get(CaptureResult.SCALER_CROP_REGION)+" exposureNs="+exposure+" iso="+sensitivity+
                    " evIndex="+result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)+
                    " flashMode="+result.get(CaptureResult.FLASH_MODE)+" flashState="+result.get(CaptureResult.FLASH_STATE));
        }
    };
    private void submitRepeating() throws CameraAccessException {
        CaptureRequest.Builder b=base(videoMode?CameraDevice.TEMPLATE_RECORD:CameraDevice.TEMPLATE_PREVIEW);
        b.addTarget(previewSurface);
        if(recorder!=null)b.addTarget(recorder.getSurface());
        session.setRepeatingRequest(b.build(),metadata,worker);
    }
    private void repeat(){
        try{if(session!=null && camera!=null)submitRepeating();}
        catch(Exception e){error(e);}
    }
    private String filename(String suffix){return "Titan_"+new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.ROOT).format(new Date())+suffix;}
    private Uri createPhoto(String name){
        ContentValues v=new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
        v.put(MediaStore.MediaColumns.MIME_TYPE,"image/jpeg");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH,"DCIM/TitanCameraLab");
        v.put(MediaStore.MediaColumns.DATE_TAKEN,System.currentTimeMillis());
        v.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri uri=getContentResolver().insert(CaptureGallery.IMAGES,v);
        if(uri==null)throw new IllegalStateException("Impossibile creare il file");
        return uri;
    }
    private void publish(Uri uri){ContentValues v=new ContentValues();v.put(MediaStore.MediaColumns.IS_PENDING,0);if(getContentResolver().update(uri,v,null,null)!=1)throw new IllegalStateException("Foto non pubblicata");}
    private void startVideo(){try{
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){busy=false;updateUi();note("Consenti il microfono nelle impostazioni dell’app per registrare video con audio.");return;}
        activeDraft=CaptureDraft.create(this);
        lastVideo=null;
        if(gyroProfile)motion=MotionRecorder.start(this,activeDraft,characteristics,videoSize,requestedCrop);
        recorder=new MediaRecorder();recorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);recorder.setOutputFile(activeDraft.video);recorder.setVideoEncodingBitRate(CaptureFormats.bitrate(videoSize.getWidth(),videoSize.getHeight()));recorder.setVideoFrameRate(fps.getUpper());recorder.setVideoSize(videoSize.getWidth(),videoSize.getHeight());recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);recorder.setAudioEncodingBitRate(128000);recorder.setAudioSamplingRate(48000);recorder.setOrientationHint(sensorOrientation);recorder.setOnErrorListener((r,w,e)->worker.post(()->{note("Registrazione interrotta: errore "+w);stopVideo();}));recorder.prepare();configure();
    }catch(Exception e){discardRecording();error(e);configure();}}
    private void stopVideo() {
        if (session!=null) {
            try { session.stopRepeating(); session.abortCaptures(); }
            catch (Exception e) { Log.w(TAG,"Closing camera capture session",e); }
        }
        try {
            recorder.stop();
        } catch (Exception e) {
            discardRecording();busy=false;note("Video troppo breve o interrotto.");configure();return;
        }
        recording=false;
        releaseRecorder();
        CaptureDraft draft=activeDraft;
        activeDraft=null;
        if(motion!=null) { motion.finishAfterCallbacks(); motion=null; }
        CaptureDraft.prefs(this).edit().putBoolean("autoDraft",true).apply();
        busy=false;
        runOnUiThread(()->beginStabilization(draft.uri));
    }

    private void releaseRecorder() {
        if (recorder!=null) { recorder.release(); recorder=null; }
    }

    private void discardRecording() {
        recording=false;releaseRecorder();
        if(motion!=null) { motion.abort();motion=null; }
        if(activeDraft!=null) {
            try { CaptureDraft.retire(this,activeDraft.uri); } catch(Exception e) { Log.w(TAG,"Cleaning unfinished capture",e); }
            activeDraft=null;
        }
    }

    private void confirmDiscard() {
        CaptureDraft draft=CaptureDraft.current(this);
        if(draft==null||recording||busy||processingUi)return;
        new android.app.AlertDialog.Builder(this).setMessage("Eliminare la ripresa in sospeso?")
                .setNegativeButton("Mantieni",null).setPositiveButton("Elimina",(dialog,button)-> {
                    try {
                        if (StabilizeService.running || MotionRecorder.exporting.contains(draft.uri.toString())) return;
                        GalleryVideo.reconcile(this);
                        CaptureDraft.retire(this,draft.uri);
                        StabilizeService.status="Ripresa scartata";
                        CaptureDraft.prefs(this).edit().remove("autoDraft").putString("status",StabilizeService.status).apply();
                        refreshJob();
                    } catch(Exception e) { note("Impossibile liberare la ripresa. Riprova."); }
                }).show();
    }
    private void resetPhotoTarget() {
        if(photo!=null) { photo.cancel();photo=null; }
        timerFocus=null;
        subjectChosen=false;subjectX=.5f;subjectY=.5f;photoCrop=null;lockedPhotoLens=null;
        runOnUiThread(()->focusMarker.setState(null));
    }
    private void setFocusState(FocusMarker.State state) {
        runOnUiThread(()->focusMarker.setState(state));
        if(state==FocusMarker.State.SCANNING) note(touchFocusSupported?"Messa a fuoco sul punto…":"Misuro la luce sul punto…");
        else if(state==FocusMarker.State.METERED) note("Esposizione sul punto · obiettivo a fuoco fisso");
        else if(state==FocusMarker.State.FOCUSED) note("Punto a fuoco · premi Scatta");
        else note(touchFocusSupported?"Fuoco non confermato · tocca un dettaglio con più contrasto":"Esposizione sul punto non confermata");
    }
    private PhotoCapture newPhotoCapture() {
        return new PhotoCapture(session,characteristics,worker,previewSurface,jpeg.getSurface(),this::base,
                this::finishPhoto,metadata,this::setFocusState,subjectX,subjectY,subjectChosen,
                focusReader==null?null:focusReader.getSurface(),lockedPhotoLens);
    }
    private void prepareTimedPhoto(){
        if(!foreground || busy || videoMode || processingUi || session==null || jpeg==null)return;
        if(photo==null){photo=newPhotoCapture();timerFocus=photo;}
        photo.prepareFocus();
    }
    private void releaseTimerFocus(){
        if(timerFocus!=null && timerFocus==photo && photo.mode==null){
            Float lens=photo.verifiedLens();
            if(subjectChosen && lens!=null)lockedPhotoLens=lens;
            photo.cancelPreparation();photo=null;
            if(foreground && session!=null && !videoMode)repeat();
        }
        timerFocus=null;
    }
    private void takePhoto() {
        timerFocus=null;
        if(session==null||jpeg==null||!foreground) { busy=false;updateUi();return; }
        if(photo==null) photo=newPhotoCapture();
        note("Scatto in corso…");
        photo.shoot(photoMode,photoFlashOn);
    }

    private void focusPreviewAvailable(ImageReader reader) {
        if(reader!=focusReader) return;
        try(Image image=reader.acquireLatestImage()) {
            if(image!=null && photo!=null) photo.focusImage(image);
        } catch(IllegalStateException e) { Log.w(TAG,"Focus preview unavailable",e); }
    }
    private void photoAvailable(ImageReader reader) {
        if(reader!=jpeg) return;
        try {
            while(true) {
                byte[] bytes;long timestamp;
                try(Image image=reader.acquireNextImage()) {
                    if(image==null) return;
                    if(photo==null) continue;
                    timestamp=image.getTimestamp();
                    ByteBuffer buffer=image.getPlanes()[0].getBuffer();
                    bytes=new byte[buffer.remaining()];buffer.get(bytes);
                }
                photo.image(timestamp,bytes);
            }
        } catch(Exception e) {
            if(photo!=null) {
                PhotoCapture shot=photo;shot.cancel();
                finishPhoto(shot,null,"Impossibile leggere la foto. Riprova.");
            }
            Log.w(TAG,"JPEG reader",e);
        }
    }

    private void finishPhoto(PhotoCapture shot,byte[] data,String feedback) {
        if(photo!=shot) return;
        if(subjectChosen) lockedPhotoLens=shot.verifiedLens();
        photo=null;
        try {
            if(data!=null)refreshGallery(savePhoto(data,shot.mode.suffix));
            note(feedback);
        } catch(Exception e) { note("Foto non salvata. Controlla lo spazio disponibile.");Log.e(TAG,"Save photo",e); }
        finally {
            busy=false;
            if(foreground && !videoMode && session!=null) repeat();
            updateUi();
        }
    }

    private Uri savePhoto(byte[] data,String suffix) throws Exception {
        String name=filename("_"+suffix+".jpg");
        Uri uri=createPhoto(name);
        try {
            try(OutputStream out=getContentResolver().openOutputStream(uri)) {
                if(out==null) throw new IllegalStateException("File non accessibile");
                out.write(data);
            }
            publish(uri);
        } catch(Exception e) { getContentResolver().delete(uri,null,null);throw e; }
        getSharedPreferences("camera",0).edit().putString("lastPhotoName",name).apply();
        return uri;
    }
    private void error(Exception e){Log.e(TAG,"Camera error",e);busy=false;note("Errore: "+e.getMessage());updateUi();}
    private void closeCamera(){cameraGeneration++;opening=false;configurationId++;resetPhotoTarget();if(session!=null){session.close();session=null;}if(camera!=null){camera.close();camera=null;}closePhotoReaders();if(previewSurface!=null){previewSurface.release();previewSurface=null;}discardRecording();busy=false;updateUi();}
    private void beginStabilization(Uri uri){
        if(recording||processingUi||StabilizeService.running)return;
        processingUi=true;processingRequestedAt=SystemClock.elapsedRealtime();jobStatus.setText("Avvio dell’elaborazione "+(gyroProfile?"Giroscopio":"Marcata")+"…");updateUi();
        worker.post(this::closeCamera);
        try{startForegroundService(new Intent(this,StabilizeService.class).setData(uri).putExtra("gyro",gyroProfile));}
        catch(Exception e){processingUi=false;processingRequestedAt=0;
            if(foreground)CaptureDraft.prefs(this).edit().remove("autoDraft").apply();
            note("Ripresa in attesa. Premi Riprendi salvataggio.");worker.post(this::openCamera);}
    }
    private void refreshJob(){
        boolean active=StabilizeService.running;if(active)processingRequestedAt=0;
        boolean waiting=processingRequestedAt>0&&SystemClock.elapsedRealtime()-processingRequestedAt<3000;
        if(active||waiting){processingUi=true;jobProgress.setVisibility(View.VISIBLE);jobProgress.setProgress(StabilizeService.percent);if(active)jobStatus.setText(StabilizeService.status);}
        else{
            jobProgress.setVisibility(View.GONE);
            if(processingUi){processingUi=false;processingRequestedAt=0;
                if(!StabilizeService.status.isEmpty())note(StabilizeService.status);
                if(foreground&&preview.isAvailable())worker.post(this::openCamera);}
            String text=StabilizeService.status;if(text.isEmpty())text=getSharedPreferences("stabilization",0).getString("status","In galleria verrà salvato il video stabilizzato.");
            if(getSharedPreferences("stabilization",0).getBoolean("interrupted",false))text="Salvataggio interrotto. Puoi riprendere la stabilizzazione.";
            CaptureDraft draft=CaptureDraft.current(this);
            if (draft!=null && !busy && !recording && !processingUi && !text.startsWith("Salvataggio interrotto") && !text.startsWith("Stabilizzazione annullata"))
                text="Ripresa in sospeso. Premi Riprendi salvataggio.";
            if(foreground && !busy && !recording && !processingUi && draft!=null && CaptureDraft.prefs(this).getBoolean("autoDraft",false))
                beginStabilization(draft.uri);
            else jobStatus.setText(text);
        }
        updateUi();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==50&&result==RESULT_OK&&data!=null&&data.getData()!=null){Uri uri=data.getData();try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}lastVideo=uri;getSharedPreferences("stabilization",0).edit().putString("lastVideo",uri.toString()).apply();beginStabilization(uri);}}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleIncoming(intent);}
    private void handleIncoming(Intent intent){if(intent==null)return;Uri uri=intent.getData();if(Intent.ACTION_SEND.equals(intent.getAction()))uri=intent.getParcelableExtra(Intent.EXTRA_STREAM);if(uri!=null&&"content".equals(uri.getScheme())){lastVideo=uri;getSharedPreferences("stabilization",0).edit().putString("lastVideo",uri.toString()).apply();note("Video selezionato · premi Stabilizza selezionato");}}
}

