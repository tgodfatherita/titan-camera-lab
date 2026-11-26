package it.carmine.titancamera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** A focus reticle over the tapped preview point, including on very bright subjects. */
final class FocusMarker extends View {
    enum State { SCANNING, FOCUSED, METERED, MISSED }
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private State state;
    private float x,y;

    FocusMarker(Context context) {
        super(context);
        density=getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.STROKE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    void aim(float viewX,float viewY) {
        x=viewX;y=viewY;
        setState(State.SCANNING);
    }
    void setState(State value) {
        state=value;invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        if(state==null) return;
        float cx=x*getWidth(), cy=y*getHeight();
        float radius=(state==State.SCANNING?18:15)*density;
        paint.setStrokeWidth(3.5f*density);paint.setColor(0xb0000000);
        canvas.drawRoundRect(cx-radius,cy-radius,cx+radius,cy+radius,4*density,4*density,paint);
        int color=state==State.MISSED?0xffff806e:state==State.FOCUSED?0xff62e9a2:0xffffcf55;
        paint.setStrokeWidth(1.7f*density);paint.setColor(color);
        canvas.drawRoundRect(cx-radius,cy-radius,cx+radius,cy+radius,4*density,4*density,paint);
    }
}
