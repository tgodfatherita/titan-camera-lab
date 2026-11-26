package it.carmine.titancamera;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.system.Os;
import android.system.OsConstants;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import java.nio.charset.StandardCharsets;
import android.util.Log;
import com.arthenica.ffmpegkit.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Publish one verified video per recording. The foreground worker owns the private draft until then. */
public class StabilizeService extends Service {
    public static final String CANCEL="it.carmine.titancamera.CANCEL";
    public static volatile boolean running;
    public static volatile int percent;
    public static volatile String status="";
    private static final String CHANNEL="stabilization";
    private static final int NOTICE=71;
    private volatile boolean cancelled;
    private boolean gyro;
    private String profileName;
    private volatile FFmpegSession current;
    private final Object commitLock=new Object();
    private boolean committed;
    private long lastNotice;
    private PowerManager.WakeLock wake;

    @Override public IBinder onBind(Intent i){return null;}
    @Override public void onCreate(){super.onCreate();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"Elaborazione video",NotificationManager.IMPORTANCE_LOW));}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        if(CANCEL.equals(intent.getAction())){cancelWork();if(!running)stopSelf();return START_NOT_STICKY;}
        if(running)return START_NOT_STICKY;
        Uri input=intent.getData();if(input==null||(!"content".equals(input.getScheme()) && CaptureDraft.resolve(this,input)==null)){stopSelf();return START_NOT_STICKY;}
        gyro=intent.getBooleanExtra("gyro",false);profileName=gyro?"Giroscopio":"Marcata";
        running=true;cancelled=false;committed=false;percent=0;status="Preparazione del video…";
        try{
            if(Build.VERSION.SDK_INT>=35)startForeground(NOTICE,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
            else startForeground(NOTICE,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"TitanCamera:Stabilize");wake.acquire(6*60*60*1000L);
            getSharedPreferences("stabilization",0).edit().remove("autoDraft").putBoolean("interrupted",true).putString("source",input.toString()).apply();
            new Thread(()->process(input),"TitanStabilize").start();
        }catch(Exception e){CaptureDraft.prefs(this).edit().remove("autoDraft").apply();running=false;status="Avvio non riuscito: "+e.getMessage();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();Log.e("TitanStabilize",status,e);}
        return START_NOT_STICKY;
    }
    private Notification notification(){
        PendingIntent open=PendingIntent.getActivity(this,1,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel=PendingIntent.getService(this,2,new Intent(this,StabilizeService.class).setAction(CANCEL),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_save).setContentTitle("Titan · stabilizzazione "+profileName).setContentText(status).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).setProgress(100,percent,false).addAction(new Notification.Action.Builder(null,"Annulla",cancel).build()).build();
    }
    private void progress(String label,int value){
        if(cancelled)return;status=label;percent=Math.max(percent,Math.min(value,99));
        long now=SystemClock.elapsedRealtime();if(now-lastNotice>500){lastNotice=now;getSystemService(NotificationManager.class).notify(NOTICE,notification());}
    }
    private void checkCancelled(){if(cancelled)throw new CancellationException();}
    private void cancelWork(){synchronized(commitLock){if(committed)return;cancelled=true;status="Annullamento…";}FFmpegSession s=current;if(s!=null)s.cancel();}
    @Override public void onTimeout(int startId,int type){cancelWork();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    @Override public void onDestroy(){if(running)cancelWork();if(wake!=null&&wake.isHeld())wake.release();super.onDestroy();}
    private void process(Uri source) {
        long begin=SystemClock.elapsedRealtime();
        File dir=new File(getCacheDir(),"stabilize-job");
        File cachedInput=new File(dir,"input.mp4"), trf=new File(dir,"motion.trf");
        ParcelFileDescriptor sourceFd=null;
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Spazio temporaneo non disponibile");
            GalleryVideo.reconcile(this);
            CaptureDraft draft=CaptureDraft.resolve(this,source);
            File input;
            if (draft!=null) input=draft.video;
            else {
                if (!"content".equals(source.getScheme())) throw new IOException("Questa registrazione è già stata elaborata");
                try { sourceFd=getContentResolver().openFileDescriptor(source,"r"); }
                catch (FileNotFoundException ignored) { /* Some document providers expose only an asset stream. */ }
                if (sourceFd!=null && OsConstants.S_ISREG(Os.fstat(sourceFd.getFileDescriptor()).st_mode)) {
                    input=new File("/proc/self/fd/"+sourceFd.getFd());
                } else {
                    try (InputStream in=getContentResolver().openInputStream(source); OutputStream out=new FileOutputStream(cachedInput)) {
                        if (in==null) throw new IOException("Video non accessibile");
                        copy(in,out);
                    }
                    input=cachedInput;
                }
            }
            GalleryVideo.Info original=GalleryVideo.Info.read(input,null,gyro,this::checkCancelled);
            Log.i("TitanStabilize","START mode="+profileName+" private="+(draft!=null)+" durationMs="+original.durationMs);
            GyroData movement=gyro ? GyroData.prepare(this,source,original,this::checkCancelled) : null;
            checkCancelled();
            Uri pending=GalleryVideo.reserve(this,source,profileName);
            try (ParcelFileDescriptor out=getContentResolver().openFileDescriptor(pending,"rwt")) {
                if (out==null) throw new IOException("Impossibile scrivere il video in galleria");
                boolean accelerated=false;
                if (movement!=null) {
                    try {
                        HardwareGyro.encode(input,out,movement,this::checkCancelled,
                                p -> progress("Stabilizzazione Giroscopio · "+p+"%",3+p*91/100));
                        original.verify(GalleryVideo.Info.read(null,out,false,this::checkCancelled));
                        accelerated=true;
                    } catch (CancellationException e) { throw e; }
                    catch (Exception e) {
                        checkCancelled();
                        Log.w("TitanStabilize","Hardware gyro unavailable, retrying same geometry",e);
                        reset(out);
                    }
                }
                if (!accelerated) {
                    encodeWithFilters(input,out,trf,original,movement);
                }
                Os.fsync(out.getFileDescriptor());
            }
            synchronized(commitLock) {
                checkCancelled();
                GalleryVideo.publish(this,pending);
                committed=true;
            }
            percent=100;status="Video "+profileName+" salvato in galleria";
            Log.i("TitanStabilize","SUCCESS mode="+profileName+" frames="+original.videoPackets+" elapsedMs="+(SystemClock.elapsedRealtime()-begin)+" result="+pending);
        } catch (CancellationException e) {
            status=CaptureDraft.resolve(this,source)!=null ? "Stabilizzazione annullata. Puoi riprovare o scartare la ripresa." : "Stabilizzazione annullata";
            Log.i("TitanStabilize","CANCELLED");
        } catch (Exception e) {
            status="Salvataggio interrotto. "+(e.getMessage()==null?"Riprova.":e.getMessage());
            Log.e("TitanStabilize",status,e);
        } finally {
            try { GalleryVideo.reconcile(this); } catch (Exception e) { Log.w("TitanStabilize","Pending gallery transaction",e); }
            if (sourceFd!=null) try { sourceFd.close(); } catch (IOException ignored) {}
            cachedInput.delete(); trf.delete(); new File(dir,"gyro.filter").delete(); current=null;
            CaptureDraft.prefs(this).edit().putBoolean("interrupted",false).putString("status",status).apply();
            if(wake!=null&&wake.isHeld())wake.release();
            stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();running=false;
        }
    }

    private static void reset(ParcelFileDescriptor out) throws Exception {
        Os.ftruncate(out.getFileDescriptor(),0);
        Os.lseek(out.getFileDescriptor(),0,OsConstants.SEEK_SET);
    }

    private static Boolean hasAvcEncoder;
    private static boolean ffmpegHardwareEncoder() {
        if (hasAvcEncoder==null) {
            FFmpegSession query=FFmpegKit.executeWithArguments(new String[]{"-hide_banner","-encoders"});
            hasAvcEncoder=query.getAllLogsAsString().contains("h264_mediacodec");
        }
        return hasAvcEncoder;
    }

    private void encodeWithFilters(File input, ParcelFileDescriptor out, File trf, GalleryVideo.Info original, GyroData movement) throws Exception {
        String workers=Integer.toString(Math.max(2,Math.min(6,Runtime.getRuntime().availableProcessors())));
        FFmpegKitConfig.setEnvironmentVariable("OMP_NUM_THREADS",workers);
        FFmpegKitConfig.setSessionHistorySize(3);
        List<String> base=Arrays.asList("-hide_banner","-nostdin","-y","-threads",workers,"-filter_threads",workers,"-i",input.getAbsolutePath());
        String transform;
        if (movement==null) {
            List<String> detect=new ArrayList<>(base);
            Collections.addAll(detect,"-vf","vidstabdetect=shakiness=5:accuracy=15:stepsize=6:mincontrast=0.2:result="+trf,
                    "-an","-f","null","/dev/null");
            runPass(detect.toArray(new String[0]),original.durationMs,0,35,"Analisi del movimento");
            int shift=(int)Math.max(1,Math.round(70*Math.sqrt((double)original.width*original.height/(1920*1080))));
            transform="vidstabtransform=input="+trf+":smoothing=15:optalgo=gauss:maxshift="+shift+":maxangle=0.03:crop=black:optzoom=1:zoom=1:interpol=bicubic";
        } else {
            File filter=new File(trf.getParentFile(),"gyro.filter");
            movement.math.writeFilter(filter);
            transform=new String(Files.readAllBytes(filter.toPath()),StandardCharsets.UTF_8);
        }
        boolean useHardware=ffmpegHardwareEncoder();
        while(true) {
            checkCancelled();
            List<String> args=new ArrayList<>(base);
            Collections.addAll(args,"-vf",transform,"-map","0:v:0","-map","0:a?");
            if (useHardware) Collections.addAll(args,"-c:v","h264_mediacodec","-pix_fmt","nv12","-b:v",Integer.toString(CaptureFormats.bitrate(original.width,original.height)),"-g","30");
            else Collections.addAll(args,"-c:v","libx264","-threads",workers,"-preset","superfast","-crf","18","-pix_fmt","yuv420p");
            Collections.addAll(args,"-fps_mode","passthrough","-c:a","copy","-f","mp4","/proc/self/fd/"+out.getFd());
            try {
                runPass(args.toArray(new String[0]),original.durationMs,movement==null?35:3,movement==null?60:92,"Stabilizzazione "+profileName);
                original.verify(GalleryVideo.Info.read(null,out,false,this::checkCancelled));
                Log.i("TitanStabilize","FILTERS mode="+profileName+" hardwareEncoder="+useHardware);
                return;
            } catch (IOException e) {
                if (!useHardware) throw e;
                checkCancelled(); reset(out); useHardware=false;
                Log.w("TitanStabilize","Retrying with software encoder",e);
            }
        }
    }
    private void runPass(String[] args,long duration,int base,int span,String label)throws IOException{
        checkCancelled();progress(label+"…",base);
        FFmpegSession s=FFmpegSession.create(args,null,entry->{String m=entry.getMessage();if(m.contains("Final zoom")||m.contains("Error")||m.contains("not found"))Log.i("TitanStabilize",m.trim());},stats->{int p=(int)Math.min(100,100*stats.getTime()/Math.max(1,duration));progress(label+" · "+p+"%",base+span*p/100);});
        current=s;checkCancelled();FFmpegKitConfig.ffmpegExecute(s);current=null;checkCancelled();
        if(!ReturnCode.isSuccess(s.getReturnCode())){String log=s.getAllLogsAsString();try(FileOutputStream out=new FileOutputStream(new File(getFilesDir(),"stabilization-error.log"))){out.write(log.getBytes(java.nio.charset.StandardCharsets.UTF_8));}throw new IOException("Elaborazione video non riuscita (codice "+s.getReturnCode()+"). Puoi riprovare.");}
    }
    private void copy(InputStream in,OutputStream out)throws IOException{byte[] buffer=new byte[1024*1024];int n;while((n=in.read(buffer))!=-1){checkCancelled();out.write(buffer,0,n);}}
}
