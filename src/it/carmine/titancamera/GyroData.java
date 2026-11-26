package it.carmine.titancamera;

import android.content.*;
import android.database.Cursor;
import android.media.*;
import android.net.Uri;
import android.provider.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

final class GyroData {
    final GyroMath math;
    final long[] ptsUs;
    private GyroData(GyroMath math, long[] ptsUs) {
        this.math = math;
        this.ptsUs = ptsUs;
        android.util.Log.i("TitanStabilize", "GYRO frames="+ptsUs.length+" strength="+math.strength);
    }

    static GyroData prepare(Context context,Uri source,GalleryVideo.Info video,Runnable check)throws Exception{
        if (video.width<=0 || video.height<=0 || (video.rotation!=90 && video.rotation!=270))
            throw new IOException("Per questo formato scegli Marcata.");
        long[] actual=video.videoTimes;
        if (actual.length<3) throw new IOException("Video troppo breve per il giroscopio");
        double[] times = new double[actual.length];
        for (int i=1; i<actual.length; i++) {
            times[i] = (actual[i]-actual[0])*1e-6;
            if (actual[i]<=actual[i-1] || Math.abs(times[i]-times[i-1]-1./30)>.020)
                throw new IOException("Tempi irregolari. Scegli Marcata.");
        }
        double fps = (times.length-1)/times[times.length-1];
        if (fps<29 || fps>31) throw new IOException("Per questa frequenza video scegli Marcata.");
        CaptureDraft draft = CaptureDraft.resolve(context, source);
        if (draft != null) {
            while (MotionRecorder.exporting.contains(source.toString())) { check.run(); Thread.sleep(20); }
            if (!draft.calibration.isFile()) throw new IOException("Dati del giroscopio incompleti. Seleziona Marcata e riprova.");
            JSONObject info = new JSONObject(new String(java.nio.file.Files.readAllBytes(draft.calibration.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            long base = info.getLong("firstCameraNs");
            double f = info.optDouble("focal");
            double zoom=info.optDouble("captureZoom",1.0);
            double referenceFocal=f/Math.max(video.height/1920.0,video.width/1440.0);
            boolean front=info.optBoolean("front",false);
            if (base<=0 || info.optBoolean("eis",true) || info.optInt("clock")!=1 || info.optInt("rotation")!=video.rotation ||
                    video.rotation!=(front?270:90) ||
                    info.optInt("width")!=video.height || info.optInt("height")!=video.width || !"Titan 2".equals(info.optString("model")) ||
                    !(referenceFocal>=500 && referenceFocal<=4000) || !(zoom>=1 && zoom<=10.01)) throw new IOException("Calibrazione non disponibile. Seleziona Marcata.");
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(draft.samples),65536))) {
                GyroMath math = new GyroMath(row -> {
                    check.run();
                    try { row[0] = (in.readLong()-base)*1e-9; }
                    catch (EOFException e) { return false; }
                    for (int axis=1; axis<4; axis++) row[axis]=in.readFloat();
                    // In the upright, unmirrored selfie file the optical and horizontal
                    // axes are reversed relative to the rear camera; vertical is shared.
                    if(front){row[1]=-row[1];row[3]=-row[3];}
                    return true;
                }, times, f*zoom, video.width, video.height);
                return new GyroData(math, actual);
            }
        }
        // Historical public movement archives were recorded only at 1920 × 1080.
        if(video.rotation!=90 || video.width!=1080 || video.height!=1920)throw new IOException("Archivio movimento non compatibile con il video.");
        String name=null;
        try(Cursor c=context.getContentResolver().query(source,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())name=c.getString(0);}
        if(name==null)throw new IOException("Nome del video non disponibile");
        String saved=context.getSharedPreferences("motion",0).getString(source.toString(),null);Uri archive=saved==null?null:Uri.parse(saved);
        if(archive==null){
            try(Cursor c=context.getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,new String[]{MediaStore.MediaColumns._ID},MediaStore.MediaColumns.DISPLAY_NAME+"=?",new String[]{name.replace(".mp4","")+"_MOVIMENTO.zip"},null)){
                if(c!=null&&c.moveToFirst())archive=ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI,c.getLong(0));}
        }
        if(archive==null)throw new IOException("Questo video non ha dati del giroscopio. Seleziona Marcata o registra un nuovo video.");
        Map<String,String> contents=new HashMap<>();long total=0;
        try(InputStream raw=context.getContentResolver().openInputStream(archive);ZipInputStream zip=new ZipInputStream(raw)){
            ZipEntry e;byte[] buf=new byte[65536];
            while((e=zip.getNextEntry())!=null){check.run();ByteArrayOutputStream out=new ByteArrayOutputStream();int n;
                while((n=zip.read(buf))!=-1){total+=n;if(total>64L*1024*1024)throw new IOException("Archivio movimento troppo grande");out.write(buf,0,n);check.run();}
                if(Arrays.asList("metadata.json","sensors.csv","frames.jsonl","video-pts.csv").contains(e.getName()))contents.put(e.getName(),out.toString("UTF-8"));}
        }
        for(String s:new String[]{"metadata.json","sensors.csv","frames.jsonl","video-pts.csv"})if(!contents.containsKey(s))throw new IOException("Archivio movimento incompleto");
        JSONObject meta=new JSONObject(contents.get("metadata.json"));
        if(!name.equals(meta.getString("video_name")))throw new IOException("Dati movimento appartenenti a un altro video");
        if(meta.optInt("camera_timestamp_source",-1)!=1||meta.optInt("sensor_orientation")!=90||meta.optInt("recording_width")!=1920||meta.optInt("recording_height")!=1080||!"Titan 2".equals(meta.optString("model")))throw new IOException("Calibrazione Giroscopio disponibile solo per Titan 2, video verticale 1080p");
        if(meta.has("collection_error")||meta.has("encoded_timestamps_error"))throw new IOException("La raccolta del movimento non è completa");
        String[] frameLines=contents.get("frames.jsonl").trim().split("\\r?\\n");
        long base=new JSONObject(frameLines[0]).getLong("sensor_timestamp_ns");
        for(String s:frameLines)if(new JSONObject(s).optInt("eis",0)!=0)throw new IOException("Giroscopio richiede un originale registrato senza EIS");
        List<Double> pts=new ArrayList<>();String[] lines=contents.get("video-pts.csv").trim().split("\\r?\\n");
        for(int i=1;i<lines.length;i++)pts.add(Double.parseDouble(lines[i].split(",")[1]));
        if (actual.length!=pts.size()) throw new IOException("Numero fotogrammi non corrispondente ai dati movimento");
        for (int i=0;i<actual.length;i++) if (Math.abs(actual[i]-pts.get(i))>2)
            throw new IOException("Tempi del video diversi dai dati movimento");
        List<double[]> samples=new ArrayList<>();
        for(String line:contents.get("sensors.csv").split("\\r?\\n")){if(!line.startsWith("4,"))continue;String[] v=line.split(",");double t=(Long.parseLong(v[1])-base)*1e-9;
            if(!samples.isEmpty()){double dt=t-samples.get(samples.size()-1)[0];if(dt<=0||dt>.05)throw new IOException("Interruzione nei dati giroscopio: usa Marcata");}
            double[] sample={t,Double.parseDouble(v[4]),Double.parseDouble(v[5]),Double.parseDouble(v[6])};for(double value:sample)if(!Double.isFinite(value))throw new IOException("Dati movimento non validi");samples.add(sample);}
        if(samples.size()<3)throw new IOException("Dati giroscopio assenti");
        double focal=meta.getJSONArray("intrinsics").getDouble(0)*1920/(meta.getJSONArray("active_array").getDouble(2)-meta.getJSONArray("active_array").getDouble(0));
        if(!Double.isFinite(focal)||focal<500||focal>4000)throw new IOException("Calibrazione ottica non valida");
        check.run();GyroMath math=new GyroMath(samples.toArray(new double[0][]),times,focal,1080,1920);check.run();
        return new GyroData(math,actual);
    }
}
