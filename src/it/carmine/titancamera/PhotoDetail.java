package it.carmine.titancamera;

import android.graphics.*;
import android.media.ExifInterface;
import java.io.*;

/** Score the subject in the original JPEG. The winning image is saved verbatim. */
final class PhotoDetail {
    private PhotoDetail() {}

    static double score(byte[] jpeg, float displayX, float displayY, boolean explicitTarget) throws IOException {
        int orientation = new ExifInterface(new ByteArrayInputStream(jpeg))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        float x=displayX, y=displayY;
        switch (orientation) {
            case 2: x=1-displayX; break;
            case 3: x=1-displayX; y=1-displayY; break;
            case 4: y=1-displayY; break;
            case 5: x=displayY; y=displayX; break;
            case 6: x=displayY; y=1-displayX; break;
            case 7: x=1-displayY; y=1-displayX; break;
            case 8: x=1-displayY; y=displayX; break;
            default: break;
        }
        BitmapRegionDecoder decoder=BitmapRegionDecoder.newInstance(jpeg,0,jpeg.length,false);
        if (decoder==null) throw new IOException("JPEG non leggibile");
        Bitmap region=null;
        try {
            int w=decoder.getWidth(), h=decoder.getHeight();
            int side=Math.max(1,Math.min(w,h)/(explicitTarget?9:5));
            int left=Math.max(0,Math.min(w-side,Math.round(x*w)-side/2));
            int top=Math.max(0,Math.min(h-side,Math.round(y*h)-side/2));
            BitmapFactory.Options options=new BitmapFactory.Options();
            options.inSampleSize=2;
            options.inPreferredConfig=Bitmap.Config.ARGB_8888;
            region=decoder.decodeRegion(new Rect(left,top,left+side,top+side),options);
            if(region==null) throw new IOException("Regione JPEG non leggibile");
            return edgeScore(region);
        } finally {
            if (region!=null) region.recycle();
            decoder.recycle();
        }
    }

    // Compare edge energy at two scales with the same exposure. Weight real edges,
    // not isolated high ISO noise in a flat or clipped patch.
    private static double edgeScore(Bitmap region) {
        int w=region.getWidth(), h=region.getHeight();
        int[] y=new int[w*h];
        region.getPixels(y,0,w,0,0,w,h);
        for (int i=0;i<y.length;i++) {
            int rgb=y[i];
            y[i]=(77*((rgb>>16)&255)+150*((rgb>>8)&255)+29*(rgb&255))>>8;
        }
        double narrow=0, wide=0;
        for (int row=3;row<h-3;row++) for (int col=3;col<w-3;col++) {
            int i=row*w+col;
            int gx=y[i-1-w]+2*y[i-1]+y[i-1+w]-y[i+1-w]-2*y[i+1]-y[i+1+w];
            int gy=y[i-w-1]+2*y[i-w]+y[i-w+1]-y[i+w-1]-2*y[i+w]-y[i+w+1];
            int bx=y[i-3-w]+2*y[i-3]+y[i-3+w]-y[i+3-w]-2*y[i+3]-y[i+3+w];
            int by=y[i-3*w-1]+2*y[i-3*w]+y[i-3*w+1]-y[i+3*w-1]-2*y[i+3*w]-y[i+3*w+1];
            int gradient=bx*bx+by*by;
            if (gradient<64*64) continue;
            narrow+=gx*gx+gy*gy;
            wide+=gradient;
        }
        return wide==0 ? 0 : narrow/wide;
    }
}
