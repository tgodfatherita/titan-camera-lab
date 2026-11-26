package it.carmine.titancamera;

import android.content.*;
import android.database.Cursor;
import android.media.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Journals the transition from private input to a single visible, verified MediaStore item. */
final class GalleryVideo {
    static Uri reserve(Context ctx, Uri source, String mode) throws IOException {
        ContentValues v = new ContentValues();
        String date = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.ROOT).format(new Date());
        v.put(MediaStore.MediaColumns.DISPLAY_NAME,"Titan_"+date+"_"+mode.toUpperCase(Locale.ROOT)+".mp4");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH,"DCIM/TitanCameraLab");
        v.put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4");
        v.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri uri=ctx.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,v);
        if (uri==null) throw new IOException("La galleria non è disponibile");
        if (!CaptureDraft.prefs(ctx).edit().putString("pending",uri.toString()).putString("pendingSource",source.toString())
                .putBoolean("verified",false).commit()) {
            ctx.getContentResolver().delete(uri,null,null);
            throw new IOException("Spazio esaurito durante il salvataggio");
        }
        return uri;
    }

    static void publish(Context ctx, Uri result) throws IOException {
        if (!CaptureDraft.prefs(ctx).edit().putBoolean("verified",true).commit())
            throw new IOException("Impossibile confermare il salvataggio");
        reconcile(ctx);
        if (!result.toString().equals(CaptureDraft.prefs(ctx).getString("result",null)))
            throw new IOException("Video non pubblicato. Riprova.");
    }

    static void reconcile(Context ctx) throws IOException {
        SharedPreferences prefs=CaptureDraft.prefs(ctx);
        String key=prefs.getString("pending",null);
        if (key==null) return;
        Uri result=Uri.parse(key);
        ContentResolver resolver=ctx.getContentResolver();
        int pending;
        try (Cursor c=resolver.query(result,new String[]{MediaStore.MediaColumns.IS_PENDING},null,null,null)) {
            if(c==null) throw new IOException("Galleria momentaneamente non accessibile");
            pending=c.moveToFirst() ? c.getInt(0) : -1;
        }
        if (pending==1 && prefs.getBoolean("verified",false)) {
            ContentValues ready=new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING,0);
            if (resolver.update(result,ready,null,null)!=1) throw new IOException("Galleria non aggiornata. Riprova.");
            pending=0;
        }
        if (pending==0) {
            if (!prefs.edit().putString("result",key).putString("status","Video stabilizzato salvato in galleria").commit())
                throw new IOException("Salvataggio dello stato non riuscito");
            String source=prefs.getString("pendingSource",null);
            if (source!=null) CaptureDraft.retire(ctx,Uri.parse(source));
        } else if (pending==1 && resolver.delete(result,null,null)!=1) {
            throw new IOException("Pulizia del file temporaneo non riuscita");
        }
        if (!prefs.edit().remove("pending").remove("pendingSource").remove("verified").putBoolean("interrupted",false).commit())
            throw new IOException("Impossibile aggiornare lo stato del salvataggio");
    }

    /** Inspect both the source and the actual gallery descriptor before retiring a recording. */
    static final class Info {
        final int width,height,audioTracks,rotation;
        long[] videoTimes;
        int videoPackets,audioPackets;
        long firstVideo=Long.MAX_VALUE,lastVideo=-1,firstAudio=Long.MAX_VALUE,lastAudio=-1;
        final long durationMs;

        static Info read(File source, ParcelFileDescriptor dest, boolean keepTimes, Runnable check) throws IOException {
            MediaExtractor ex=new MediaExtractor();
            try {
                if(source!=null) ex.setDataSource(source.getAbsolutePath());
                else ex.setDataSource(dest.getFileDescriptor());
                return new Info(ex,keepTimes,check);
            } finally { ex.release(); }
        }
        private Info(MediaExtractor ex, boolean keepTimes, Runnable check) throws IOException {
            int video=HardwareGyro.track(ex,"video/");
            if(video<0) throw new IOException("Traccia video mancante");
            MediaFormat f=ex.getTrackFormat(video);
            rotation=f.getInteger(MediaFormat.KEY_ROTATION,0);
            boolean sideways=rotation%180!=0;
            if(keepTimes) videoTimes=new long[512];
            width=f.getInteger(sideways?MediaFormat.KEY_HEIGHT:MediaFormat.KEY_WIDTH);
            height=f.getInteger(sideways?MediaFormat.KEY_WIDTH:MediaFormat.KEY_HEIGHT);
            durationMs=f.getLong(MediaFormat.KEY_DURATION,0)/1000;
            ex.selectTrack(video);
            int sounds=0;
            for(int t=0;t<ex.getTrackCount();t++) if(ex.getTrackFormat(t).getString(MediaFormat.KEY_MIME).startsWith("audio/")){
                ex.selectTrack(t); sounds++;
            }
            audioTracks=sounds;
            while(ex.getSampleTrackIndex()>=0) {
                long pts=ex.getSampleTime();
                if(ex.getSampleSize()<=0) throw new IOException("Campione multimediale incompleto");
                if(ex.getSampleTrackIndex()==video) {
                    if(keepTimes) {
                        if(videoPackets==videoTimes.length) videoTimes=Arrays.copyOf(videoTimes,videoPackets*2);
                        videoTimes[videoPackets]=pts;
                    }
                    videoPackets++;firstVideo=Math.min(firstVideo,pts);lastVideo=Math.max(lastVideo,pts);
                } else { audioPackets++;firstAudio=Math.min(firstAudio,pts);lastAudio=Math.max(lastAudio,pts); }
                ex.advance();
                check.run();
            }
            if(videoPackets==0 || (audioTracks>0 && audioPackets==0)) throw new IOException("Registrazione incompleta");
            if(keepTimes) videoTimes=Arrays.copyOf(videoTimes,videoPackets);
        }
        void verify(Info output) throws IOException {
            if(output.width!=width || output.height!=height || videoPackets!=output.videoPackets ||
                    output.audioTracks!=audioTracks || output.audioPackets!=audioPackets ||
                    Math.abs((lastVideo-firstVideo)-(output.lastVideo-output.firstVideo))>80_000 ||
                    Math.abs(durationMs-output.durationMs)>150 ||
                    (audioTracks>0 && (Math.abs((firstVideo-firstAudio)-(output.firstVideo-output.firstAudio))>60_000 ||
                            Math.abs((lastVideo-lastAudio)-(output.lastVideo-output.lastAudio))>80_000)))
                throw new IOException("Verifica di fotogrammi, durata o audio non riuscita");
        }
    }
}
