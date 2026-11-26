package it.carmine.titancamera;

import android.media.*;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.function.IntConsumer;

/** Surface-to-surface transcoding; the CPU only handles compressed samples and camera motion. */
final class HardwareGyro {
    private static final String AVC=MediaFormat.MIMETYPE_VIDEO_AVC;

    static void encode(File input, ParcelFileDescriptor output, GyroData data, Runnable check, IntConsumer progress) throws Exception {
        MediaExtractor video = new MediaExtractor(), audio = new MediaExtractor();
        MediaCodec decoder = null, encoder = null;
        MediaMuxer mux = null;
        Surface encoderSurface = null;
        GyroSurface gl = null;
        try {
            video.setDataSource(input.getAbsolutePath());
            int track = track(video,"video/");
            if (track<0) throw new IOException("Traccia video assente");
            video.selectTrack(track);
            MediaFormat source = video.getTrackFormat(track);
            int rotation=source.getInteger(MediaFormat.KEY_ROTATION,0);
            int nativeWidth=source.getInteger(MediaFormat.KEY_WIDTH),nativeHeight=source.getInteger(MediaFormat.KEY_HEIGHT);
            // Keep the established upright Full HD output. Other wide sizes use the same
            // encoder dimensions as the recorder and a standard MP4 display rotation.
            boolean storeNative=nativeWidth!=nativeHeight && (nativeWidth!=1920 || nativeHeight!=1080);
            int w=storeNative?nativeWidth:nativeHeight,h=storeNative?nativeHeight:nativeWidth;
            audio.setDataSource(input.getAbsolutePath());
            int audioTrack = track(audio,"audio/");
            if (audioTrack>=0) audio.selectTrack(audioTrack);
            source.setInteger(MediaFormat.KEY_ROTATION,0); // GL applies the portrait transform explicitly.
            source.setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP,0);
            source.setInteger(MediaFormat.KEY_OPERATING_RATE,240);

            MediaFormat format = MediaFormat.createVideoFormat(AVC,w,h);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE,CaptureFormats.bitrate(w,h));
            format.setInteger(MediaFormat.KEY_FRAME_RATE,30);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES,0);
            format.setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP,0);
            format.setInteger(MediaFormat.KEY_LATENCY,1);
            format.setInteger(MediaFormat.KEY_OPERATING_RATE,240);
            for (String key : new String[]{MediaFormat.KEY_COLOR_STANDARD,MediaFormat.KEY_COLOR_RANGE,MediaFormat.KEY_COLOR_TRANSFER})
                if (source.containsKey(key)) format.setInteger(key,source.getInteger(key));
            encoder = codec(AVC,true,w,h);
            encoder.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderSurface = encoder.createInputSurface();
            encoder.start();
            gl = new GyroSurface(encoderSurface,nativeHeight,nativeWidth,data.math.focalPixels,storeNative,rotation);
            decoder = codec(source.getString(MediaFormat.KEY_MIME),false,nativeWidth,nativeHeight);
            decoder.configure(source,gl.decoderSurface,null,0);
            decoder.start();
            mux = new MediaMuxer(output.getFileDescriptor(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            if(storeNative)mux.setOrientationHint(rotation);
            Log.i("TitanStabilize","SURFACES decoder="+decoder.getName()+" encoder="+encoder.getName());
            Writer writer = new Writer(encoder,mux,audio,audioTrack,data.ptsUs);
            render(video,decoder,encoder,gl,writer,data,check,progress);
            mux.stop(); // Writes the MP4 index before the service verifies the result.
        } finally {
            if (decoder!=null) decoder.release();
            if (gl!=null) gl.close();
            if (encoder!=null) encoder.release();
            if (encoderSurface!=null) encoderSurface.release();
            if (mux!=null) mux.release();
            video.release();
            audio.release();
        }
    }

    private static MediaCodec codec(String mime, boolean encoder,int w,int h) throws IOException {
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder()!=encoder || !info.isHardwareAccelerated() || info.isAlias()) continue;
            for (String type : info.getSupportedTypes()) if (type.equalsIgnoreCase(mime)) {
                MediaCodecInfo.CodecCapabilities caps=info.getCapabilitiesForType(type);
                if(caps.getVideoCapabilities().areSizeAndRateSupported(w,h,30) &&
                        (!encoder || java.util.Arrays.stream(caps.colorFormats).anyMatch(c->c==MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)))
                    return MediaCodec.createByCodecName(info.getName());
            }
        }
        throw new IOException("Codec hardware non disponibile");
    }
    static int track(MediaExtractor extractor,String prefix) {
        for (int i=0;i<extractor.getTrackCount();i++)
            if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith(prefix)) return i;
        return -1;
    }

    private static void render(MediaExtractor video, MediaCodec decoder, MediaCodec encoder, GyroSurface gl,
                               Writer writer, GyroData data, Runnable check, IntConsumer progress) throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputEnd=false, decoderEnd=false;
        int frame=0, lastPercent=-1;
        long lastActivity=SystemClock.elapsedRealtime();
        while (!writer.eos) {
            check.run();
            if (SystemClock.elapsedRealtime()-lastActivity>10_000) throw new IOException("Il codec video non risponde");
            boolean backPressure=frame-writer.frames>=6;
            if (writer.drain(decoderEnd || backPressure ? 5_000 : 0)) lastActivity=SystemClock.elapsedRealtime();
            if (decoderEnd || frame-writer.frames>=6) continue;
            while (!inputEnd) {
                int slot=decoder.dequeueInputBuffer(0);
                if (slot<0) break;
                long pts=video.getSampleTime();
                int size=pts<0 ? -1 : video.readSampleData(decoder.getInputBuffer(slot),0);
                inputEnd=size<0;
                decoder.queueInputBuffer(slot,0,Math.max(0,size),Math.max(0,pts),inputEnd ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                if (!inputEnd) video.advance();
                lastActivity=SystemClock.elapsedRealtime();
            }
            int slot=decoder.dequeueOutputBuffer(info,2000);
            if (slot<0) continue;
            lastActivity=SystemClock.elapsedRealtime();
            boolean image=info.size>0 && (info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0;
            decoderEnd=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
            if (image && (frame>=data.ptsUs.length || Math.abs(info.presentationTimeUs-data.ptsUs[frame])>2))
                throw new IOException("Sequenza dei fotogrammi decodificati diversa dalla ripresa");
            decoder.releaseOutputBuffer(slot,image);
            if (image) {
                gl.draw(data.math.inverseRotation,frame,data.ptsUs[frame]);
                int percent=++frame*100/data.ptsUs.length;
                if (percent!=lastPercent) { progress.accept(percent); lastPercent=percent; }
            }
            if (decoderEnd) {
                if (frame!=data.ptsUs.length) throw new IOException("Decodifica video incompleta");
                encoder.signalEndOfInputStream();
            }
        }
        if (writer.frames!=data.ptsUs.length) throw new IOException("Codifica video incompleta");
    }

    /** Muxes AAC samples without decoding. Interleave audio and video on their original timestamps. */
    private static final class Writer {
        final MediaCodec encoder;
        final MediaMuxer mux;
        final MediaExtractor audio;
        final int audioTrack;
        final long[] pts;
        int videoTrack=-1, frames;
        long encoderOffsetUs;
        boolean eos;
        ByteBuffer audioBytes=ByteBuffer.allocateDirect(32*1024);
        final MediaCodec.BufferInfo info=new MediaCodec.BufferInfo(), sound=new MediaCodec.BufferInfo();

        Writer(MediaCodec encoder,MediaMuxer mux,MediaExtractor audio,int sourceAudio,long[] pts) {
            this.encoder=encoder; this.mux=mux; this.audio=audio; this.pts=pts;
            audioTrack=sourceAudio<0 ? -1 : mux.addTrack(audio.getTrackFormat(sourceAudio));
        }
        boolean drain(long wait) throws IOException {
            boolean activity=false;
            while (!eos) {
                int index=encoder.dequeueOutputBuffer(info,wait);
                wait=0;
                if (index==MediaCodec.INFO_TRY_AGAIN_LATER) break;
                if (index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoTrack=mux.addTrack(encoder.getOutputFormat());
                    mux.start();
                    activity=true;
                    continue;
                }
                if (index<0) continue;
                try {
                    if (info.size>0 && (info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0) {
                        if (videoTrack<0 || frames>=pts.length) throw new IOException("Troppi fotogrammi codificati");
                        // Some surface codecs rebase their first PTS to zero or quantize to a millisecond.
                        if (frames==0) encoderOffsetUs=info.presentationTimeUs-pts[0];
                        long delta=info.presentationTimeUs-encoderOffsetUs-pts[frames];
                        if (Math.abs(delta)>1100) throw new IOException("Fotogramma "+frames+" fuori sequenza ("+delta+" µs)");
                        info.presentationTimeUs=pts[frames];
                        copySoundUntil(info.presentationTimeUs);
                        mux.writeSampleData(videoTrack,encoder.getOutputBuffer(index),info);
                        frames++;
                    }
                    eos=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                    if (eos) copySoundUntil(Long.MAX_VALUE);
                } finally { encoder.releaseOutputBuffer(index,false); }
                activity=true;
            }
            return activity;
        }
        private void copySoundUntil(long limit) throws IOException {
            if (audioTrack<0) return;
            while (audio.getSampleTime()>=0 && audio.getSampleTime()<=limit) {
                long bytes=audio.getSampleSize();
                if (bytes>audioBytes.capacity()) audioBytes=ByteBuffer.allocateDirect(Math.toIntExact(bytes));
                audioBytes.clear();
                int size=audio.readSampleData(audioBytes,0);
                if (size<0) throw new EOFException();
                sound.set(0,size,audio.getSampleTime(),MediaCodec.BUFFER_FLAG_KEY_FRAME);
                mux.writeSampleData(audioTrack,audioBytes,sound);
                audio.advance();
            }
        }
    }
}
