package it.carmine.titancamera;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.Range;
import android.view.*;
import android.widget.*;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.function.IntConsumer;
import static it.carmine.titancamera.CameraScreen.*;

/** The fixed exposure control at the right of the viewfinder. */
final class ExposureSlider extends FrameLayout {
    static final int WIDTH_DP=48, TRACK_DP=144, LABEL_DP=24;
    private final SeekBar bar;
    private final TextView value;
    private final NumberFormat numbers=NumberFormat.getNumberInstance(Locale.ITALY);
    private float evStep;
    IntConsumer changed;

    ExposureSlider(Context c){
        super(c);
        setBackground(round(c,0xff202023,23));
        setVisibility(GONE);
        setClickable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        numbers.setMaximumFractionDigits(2);
        FrameLayout track=new FrameLayout(c);
        addView(track,new FrameLayout.LayoutParams(-1,dp(c,TRACK_DP),Gravity.TOP));
        bar=new SeekBar(c);bar.setLayoutDirection(LAYOUT_DIRECTION_LTR);
        bar.setContentDescription("Luminosità");
        bar.setSplitTrack(false);
        bar.setProgressTintList(ColorStateList.valueOf(GOLD));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0xff757579));
        bar.setThumb(new Sun(c));bar.setThumbTintList(null);
        bar.setPadding(dp(c,14),0,dp(c,14),0);
        bar.setRotation(-90);
        track.addView(bar,new FrameLayout.LayoutParams(dp(c,TRACK_DP),dp(c,WIDTH_DP),Gravity.CENTER));
        value=label(c,11,WHITE,true);value.setGravity(Gravity.CENTER);
        value.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(value,new FrameLayout.LayoutParams(-1,dp(c,LABEL_DP),Gravity.BOTTOM));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int index,boolean fromUser){
                showValue(index);
                if(fromUser && changed!=null)changed.accept(index);
            }
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){}
        });
    }
    void display(Range<Integer> range,float step,int index,boolean enabled){
        if(range==null || range.getLower().equals(range.getUpper()) || !(step>0)){setVisibility(GONE);return;}
        evStep=step;
        if(bar.getMin()!=range.getLower())bar.setMin(range.getLower());
        if(bar.getMax()!=range.getUpper())bar.setMax(range.getUpper());
        if(bar.getProgress()!=index)bar.setProgress(index);
        showValue(index);
        bar.setEnabled(enabled);setVisibility(VISIBLE);
    }
    private void showValue(int index){
        String text=(index>0?"+":"")+numbers.format(index*evStep)+" EV";
        if(text.contentEquals(value.getText()))return;
        value.setText(text);
        if(Build.VERSION.SDK_INT>=30)bar.setStateDescription(text);
    }
    private static final class Sun extends Drawable {
        private final int size;
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Sun(Context c){size=dp(c,20);paint.setColor(GOLD);paint.setStrokeCap(Paint.Cap.ROUND);}
        @Override public int getIntrinsicWidth(){return size;}
        @Override public int getIntrinsicHeight(){return size;}
        @Override public void draw(Canvas canvas){
            Rect box=getBounds();
            canvas.save();canvas.translate(box.exactCenterX(),box.exactCenterY());
            float scale=Math.min(box.width(),box.height())/20f;canvas.scale(scale,scale);
            canvas.drawCircle(0,0,4.1f,paint);paint.setStrokeWidth(1.5f);
            for(int i=0;i<8;i++){canvas.drawLine(0,-6,0,-8.5f,paint);canvas.rotate(45);}
            canvas.restore();
        }
        @Override public void setAlpha(int a){paint.setAlpha(a);invalidateSelf();}
        @Override public void setColorFilter(ColorFilter f){paint.setColorFilter(f);invalidateSelf();}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
