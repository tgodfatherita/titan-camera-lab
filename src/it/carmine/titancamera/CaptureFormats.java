package it.carmine.titancamera;

import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.*;
import android.util.Size;
import java.util.*;

/** Native outputs of one lens that have a matching viewfinder and capture pipeline. */
final class CaptureFormats {
    enum Shape {
        SQUARE("1:1",1,1), CLASSIC("4:3",4,3), WIDE("16:9",16,9);
        final String title;
        final int w,h;
        Shape(String title,int w,int h){this.title=title;this.w=w;this.h=h;}
        boolean matches(Size s){return (long)s.getWidth()*h==(long)s.getHeight()*w;}
        static Shape of(Size s){for(Shape shape:values())if(shape.matches(s))return shape;return null;}
    }
    private static final Size JPEG_DEFAULT=new Size(3000,3000),VIDEO_DEFAULT=new Size(1920,1080);
    private final List<Size> photos=new ArrayList<>(),videos=new ArrayList<>();
    private final Map<Shape,Size> finders=new EnumMap<>(Shape.class),analysis=new EnumMap<>(Shape.class);
    private final Set<Size> rejectedPhoto=new HashSet<>(),rejectedVideo=new HashSet<>();

    CaptureFormats(StreamConfigurationMap map,boolean checkFocus) {
        Size[] preview=map.getOutputSizes(SurfaceTexture.class),yuv=map.getOutputSizes(ImageFormat.YUV_420_888);
        for(Shape shape:Shape.values()) {
            for(Size s:preview)if(shape.matches(s) && s.getWidth()<=1920 && area(s)<=1920L*1080 &&
                    (!finders.containsKey(shape) || area(s)>area(finders.get(shape))))finders.put(shape,s);
            if(yuv!=null)for(Size s:yuv)if(shape.matches(s) && s.getWidth()>=480 && s.getWidth()<=1440 &&
                    (!analysis.containsKey(shape) || Math.abs(s.getWidth()-720)<Math.abs(analysis.get(shape).getWidth()-720)))analysis.put(shape,s);
        }
        Set<Size> jpeg=new LinkedHashSet<>(Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)));
        Size[] high=map.getHighResolutionOutputSizes(ImageFormat.JPEG);
        if(high!=null)Collections.addAll(jpeg,high);
        for(Size s:jpeg){
            Shape shape=Shape.of(s);
            if(shape!=null && finders.containsKey(shape) && (!checkFocus || analysis.containsKey(shape)) && area(s)>=500_000)photos.add(s);
        }
        List<MediaCodecInfo.VideoCapabilities> enc=avcCapabilities(true),dec=avcCapabilities(false);
        for(Size s:map.getOutputSizes(MediaRecorder.class)){
            if(!finders.containsKey(Shape.of(s)) || s.getHeight()<720)continue;
            long duration=map.getOutputMinFrameDuration(MediaRecorder.class,s);
            if(duration>34_000_000L || !supports(enc,s) || !supports(dec,s))continue;
            videos.add(s);
        }
        Comparator<Size> largest=Comparator.comparingLong(CaptureFormats::area).reversed();
        photos.sort(largest);videos.sort(largest);
        if(photos.isEmpty() || videos.isEmpty())throw new IllegalStateException("Nessun formato di ripresa compatibile");
    }
    private static List<MediaCodecInfo.VideoCapabilities> avcCapabilities(boolean encoder) {
        List<MediaCodecInfo.VideoCapabilities> result=new ArrayList<>();
        for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()){
            if(info.isEncoder()!=encoder || info.isAlias() || !info.isHardwareAccelerated())continue;
            for(String mime:info.getSupportedTypes())if(MediaFormat.MIMETYPE_VIDEO_AVC.equalsIgnoreCase(mime)){
                MediaCodecInfo.CodecCapabilities caps=info.getCapabilitiesForType(mime);
                if(!encoder || Arrays.stream(caps.colorFormats).anyMatch(c->c==MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface))
                    result.add(caps.getVideoCapabilities());
            }
        }
        return result;
    }
    private static boolean supports(List<MediaCodecInfo.VideoCapabilities> caps,Size size) {
        for(MediaCodecInfo.VideoCapabilities c:caps)if(c.areSizeAndRateSupported(size.getWidth(),size.getHeight(),30))return true;
        return false;
    }
    synchronized List<Size> choices(boolean video){
        List<Size> result=new ArrayList<>(video?videos:photos);result.removeAll(video?rejectedVideo:rejectedPhoto);return result;
    }
    synchronized void reject(boolean video,Size failed){(video?rejectedVideo:rejectedPhoto).add(failed);}
    Size restore(boolean video,String preference){
        List<Size> choices=choices(video);
        for(Size s:choices)if(s.toString().equals(preference))return s;
        Size original=video?VIDEO_DEFAULT:JPEG_DEFAULT;
        if(choices.isEmpty())throw new IllegalStateException("Formato di ripresa non disponibile");
        return choices.contains(original)?original:choices.get(0);
    }
    Size preview(Size output){return finders.get(Shape.of(output));}
    Size analysis(Size output){return analysis.get(Shape.of(output));}
    static long area(Size s){return (long)s.getWidth()*s.getHeight();}
    static String mp(Size s){
        long tenths=Math.round(area(s)/100000.0);
        return (tenths%10==0?Long.toString(tenths/10):String.format(Locale.ITALIAN,"%.1f",tenths/10.0))+" MP";
    }
    static String videoQuality(Size s){
        if(s.equals(new Size(3840,2160)))return "4K UHD";
        if(s.equals(VIDEO_DEFAULT))return "Full HD";
        if(s.equals(new Size(1280,720)))return "HD";
        if(s.equals(new Size(2560,1440)))return "QHD";
        return s.getHeight()+"p";
    }
    static String label(boolean video,Size s){
        return Shape.of(s).title+" · "+(video?videoQuality(s)+" · 30":mp(s));
    }
    static int bitrate(int w,int h){
        return (int)Math.max(4_000_000,Math.min(60_000_000,Math.round(16_000_000d*w*h/(1920d*1080))));
    }
}
