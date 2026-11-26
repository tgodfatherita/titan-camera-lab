package it.carmine.titancamera;

import android.graphics.Rect;
import java.util.Locale;

/** One centered sensor crop is shared by preview, focus, JPEG and recorder requests. */
final class CameraZoom {
    private static final float[] STOPS={1f,1.5f,2f,3f,4f,5f,7f,10f};
    private CameraZoom() {}

    static float step(float current,float maximum,boolean up) {
        if(up) {
            for(float stop:STOPS) if(stop>current+.025f) return Math.min(maximum,stop);
            return maximum;
        }
        for(int i=STOPS.length-1;i>=0;i--) if(STOPS[i]<current-.025f) return STOPS[i];
        return 1f;
    }
    static String label(float ratio) {
        int tenths=Math.round(ratio*10);
        return tenths%10==0 ? (tenths/10)+"×" : String.format(Locale.ITALY,"%.1f×",tenths/10f);
    }
    static Rect crop(Rect active,float ratio) {
        if(active==null || active.isEmpty()) return null;
        if(ratio<=1f) return new Rect(active);
        int w=Math.min(active.width(),2*(int)Math.ceil(active.width()/(2.0*ratio)));
        int h=Math.min(active.height(),2*(int)Math.ceil(active.height()/(2.0*ratio)));
        int left=active.left+2*((active.width()-w)/4),top=active.top+2*((active.height()-h)/4);
        return new Rect(left,top,left+w,top+h);
    }
}
