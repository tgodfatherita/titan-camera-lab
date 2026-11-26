package it.carmine.titancamera;

import android.graphics.PointF;
import android.graphics.Rect;
import android.hardware.camera2.params.MeteringRectangle;
import android.util.Size;

/** Translate a touch on the fitted portrait preview into the sensor's visible crop. */
final class PhotoMetering {
    private PhotoMetering() {}

    static PointF touch(float x,float y,int viewWidth,int viewHeight,Size buffer,int rotation) {
        if(buffer==null || viewWidth<=0 || viewHeight<=0) return null;
        boolean sideways=rotation%180!=0;
        int width=sideways?buffer.getHeight():buffer.getWidth(), height=sideways?buffer.getWidth():buffer.getHeight();
        float scale=Math.min((float)viewWidth/width,(float)viewHeight/height);
        float u=(x-(viewWidth-width*scale)/2)/(width*scale);
        float v=(y-(viewHeight-height*scale)/2)/(height*scale);
        return u>=0 && u<=1 && v>=0 && v<=1 ? new PointF(u,v) : null;
    }

    static PointF sensorPoint(float u,float v,int rotation) {
        float x=u, y=v;
        if(rotation==90) { x=v;y=1-u; }
        else if(rotation==270) { x=1-v;y=u; }
        else if(rotation==180) { x=1-u;y=1-v; }
        return new PointF(x,y);
    }
    static MeteringRectangle region(float u,float v,Rect crop,Size buffer,int rotation,boolean explicitTarget) {
        if(crop==null || crop.isEmpty() || buffer==null) return null;
        PointF point=sensorPoint(u,v,rotation);
        double aspect=(double)buffer.getWidth()/buffer.getHeight();
        int width=Math.min(crop.width(),Math.max(1,(int)Math.round(crop.height()*aspect)));
        int height=Math.min(crop.height(),Math.max(1,(int)Math.round(crop.width()/aspect)));
        int left=crop.left+(crop.width()-width)/2, top=crop.top+(crop.height()-height)/2;
        int side=Math.max(1,Math.min(width,height)/(explicitTarget?12:6));
        int px=left+Math.max(0,Math.min(width-side,Math.round(point.x*width)-side/2));
        int py=top+Math.max(0,Math.min(height-side,Math.round(point.y*height)-side/2));
        return new MeteringRectangle(px,py,side,side,MeteringRectangle.METERING_WEIGHT_MAX);
    }
}
