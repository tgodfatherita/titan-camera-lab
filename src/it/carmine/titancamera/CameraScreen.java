package it.carmine.titancamera;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import java.util.Locale;

/** Viewfinder and shooting controls; preferences live in their own window. */
final class CameraScreen {
    static final int WHITE=0xfff7f7f8, MUTED=0xffabaeb4, GOLD=0xffffd451, GROUP=0xff202023;
    private static final int RED=0xffff453a;
    private static final int EXPOSURE_MARGIN_DP=14;
    final Context context;
    final LinearLayout root;
    final TextureView preview;
    final FocusMarker marker;
    final ExposureSlider exposure;
    final Button photo,video,less,more,zoomValue,jobAction;
    final ImageButton gallery,settings,shutter,flip,flash;
    final ProgressBar progress;
    final TextView jobStatus;
    final Button format;
    private final TextView notice,timer;
    private final FrameLayout stage;
    private final Finder finder;
    private final LinearLayout jobCard,zoom,modes;
    private final Dial shutterIcon,albumIcon;
    private final Runnable hideNotice;

    CameraScreen(Context c) {
        context=c;
        root=new LinearLayout(c);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xff060607);
        root.setOnApplyWindowInsetsListener((v,in)->{
            v.setPadding(in.getSystemWindowInsetLeft(),in.getSystemWindowInsetTop(),in.getSystemWindowInsetRight(),in.getSystemWindowInsetBottom());
            return in;
        });
        stage=new Stage(c);
        root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
        finder=new Finder(c);
        finder.setBackground(round(c,0xff000000,12));finder.setClipToOutline(true);
        stage.addView(finder,new FrameLayout.LayoutParams(-1,-1,Gravity.TOP|Gravity.LEFT));
        preview=new TextureView(c);marker=new FocusMarker(c);
        finder.addView(preview,new FrameLayout.LayoutParams(-1,-1));
        finder.addView(marker,new FrameLayout.LayoutParams(-1,-1));
        exposure=new ExposureSlider(c);
        stage.addView(exposure,frame(dp(c,ExposureSlider.WIDTH_DP),dp(c,ExposureSlider.TRACK_DP+ExposureSlider.LABEL_DP),
                Gravity.RIGHT|Gravity.CENTER_VERTICAL,EXPOSURE_MARGIN_DP,0));

        format=button(c,"",12);format.setGravity(Gravity.CENTER);
        format.setPadding(dp(c,11),0,dp(c,11),0);format.setBackground(round(c,0xc827272a,14));
        stage.addView(format,frame(-2,dp(c,36),Gravity.TOP|Gravity.START,14,10));
        timer=label(c,14,WHITE,true);timer.setGravity(Gravity.CENTER);timer.setFontFeatureSettings("tnum");
        timer.setBackground(round(c,0xffb62524,16));timer.setVisibility(View.GONE);
        stage.addView(timer,frame(dp(c,74),dp(c,28),Gravity.TOP|Gravity.CENTER_HORIZONTAL,0,12));

        notice=label(c,13,WHITE,false);notice.setGravity(Gravity.CENTER);notice.setMaxLines(3);
        notice.setPadding(dp(c,14),dp(c,7),dp(c,14),dp(c,7));notice.setBackground(round(c,0xe02a2a2e,15));
        notice.setVisibility(View.GONE);notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        stage.addView(notice,frame(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL,12,9));
        hideNotice=()->notice.setVisibility(View.GONE);

        jobCard=new LinearLayout(c);jobCard.setOrientation(LinearLayout.VERTICAL);jobCard.setGravity(Gravity.CENTER);
        jobCard.setPadding(dp(c,18),dp(c,13),dp(c,18),dp(c,6));jobCard.setBackground(round(c,0xf32a2a2e,20));
        jobStatus=label(c,15,WHITE,true);jobStatus.setGravity(Gravity.CENTER);jobCard.addView(jobStatus);
        progress=new ProgressBar(c,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(GOLD));
        LinearLayout.LayoutParams bar=new LinearLayout.LayoutParams(-1,dp(c,4));bar.topMargin=dp(c,12);jobCard.addView(progress,bar);
        jobAction=button(c,"",14);jobAction.setTextColor(GOLD);jobCard.addView(jobAction,new LinearLayout.LayoutParams(-1,dp(c,44)));
        stage.addView(jobCard,frame(dp(c,256),-2,Gravity.CENTER,0,0));jobCard.setVisibility(View.GONE);

        LinearLayout dock=new LinearLayout(c);dock.setBackgroundColor(0xff060607);dock.setOrientation(LinearLayout.VERTICAL);
        dock.setGravity(Gravity.CENTER_HORIZONTAL);root.addView(dock,new LinearLayout.LayoutParams(-1,-2));
        zoom=new LinearLayout(c);zoom.setGravity(Gravity.CENTER);zoom.setBackground(round(c,0xff222225,25));
        LinearLayout.LayoutParams zoomParams=new LinearLayout.LayoutParams(-2,dp(c,40));zoomParams.topMargin=dp(c,6);
        dock.addView(zoom,zoomParams);
        less=button(c,"−",22);less.setContentDescription("Riduci zoom");zoom.addView(less,new LinearLayout.LayoutParams(dp(c,47),-1));
        zoomValue=button(c,"1×",15);zoomValue.setTextColor(GOLD);zoomValue.setFontFeatureSettings("tnum");
        zoom.addView(zoomValue,new LinearLayout.LayoutParams(dp(c,54),-1));
        more=button(c,"+",21);more.setContentDescription("Aumenta zoom");zoom.addView(more,new LinearLayout.LayoutParams(dp(c,47),-1));

        modes=new LinearLayout(c);modes.setGravity(Gravity.CENTER);
        dock.addView(modes,new LinearLayout.LayoutParams(-1,dp(c,36)));
        photo=button(c,"FOTO",12);video=button(c,"VIDEO",12);
        for(Button mode:new Button[]{photo,video}) {
            mode.setLetterSpacing(.10f);modes.addView(mode,new LinearLayout.LayoutParams(dp(c,74),-1));
        }
        FrameLayout bottom=new FrameLayout(c);dock.addView(bottom,new LinearLayout.LayoutParams(-1,dp(c,80)));
        gallery=iconButton(c,"Apri l’ultima foto scattata");albumIcon=new Dial(c,Dial.ALBUM);gallery.setImageDrawable(albumIcon);
        gallery.setBackground(round(c,GROUP,12));gallery.setClipToOutline(true);
        bottom.addView(gallery,frame(dp(c,46),dp(c,46),Gravity.CENTER_VERTICAL|Gravity.START,26,0));
        settings=iconButton(c,"Impostazioni");settings.setBackground(round(c,0xc827272a,24));
        settings.setImageDrawable(new Dial(c,Dial.GEAR));stage.addView(settings,frame(dp(c,44),dp(c,44),Gravity.TOP|Gravity.END,14,6));
        flash=iconButton(c,"Attiva flash foto");flash.setBackground(round(c,0xc827272a,24));
        StateListDrawable bolt=new StateListDrawable();
        bolt.addState(new int[]{android.R.attr.state_selected},new Dial(c,Dial.FLASH_ON));
        bolt.addState(new int[0],new Dial(c,Dial.FLASH_OFF));
        flash.setImageDrawable(bolt);
        FrameLayout.LayoutParams flashBounds=frame(dp(c,46),dp(c,46),Gravity.TOP|Gravity.END,14,5);
        flashBounds.setMarginEnd(dp(c,64));stage.addView(flash,flashBounds);
        flip=iconButton(c,"Passa alla fotocamera frontale");flip.setBackground(round(c,GROUP,24));
        flip.setImageDrawable(new Dial(c,Dial.FLIP));bottom.addView(flip,frame(dp(c,48),dp(c,48),Gravity.CENTER_VERTICAL|Gravity.END,26,0));
        shutter=iconButton(c,"Scatta");shutterIcon=new Dial(c,Dial.SHUTTER);shutter.setImageDrawable(shutterIcon);
        bottom.addView(shutter,new FrameLayout.LayoutParams(dp(c,74),dp(c,74),Gravity.CENTER));
    }
    private FrameLayout.LayoutParams frame(int w,int h,int gravity,int horizontal,int vertical) {
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(w,h,gravity);
        p.setMargins(dp(context,horizontal),dp(context,vertical),dp(context,horizontal),dp(context,vertical));return p;
    }
    void aspect(float aspect) { if(Math.abs(finder.aspect-aspect)>.001f){finder.aspect=aspect;finder.invalidateOutline();stage.requestLayout();} }
    void showShootingOptions(boolean show){
        int visibility=show?View.VISIBLE:View.INVISIBLE;
        zoom.setVisibility(visibility);modes.setVisibility(visibility);
    }
    void setFormat(String text,boolean enabled) {
        String title=text+"  ⌄";
        if(!title.contentEquals(format.getText()))format.setText(title);
        format.setContentDescription("Cambia formato, "+text);enable(format,enabled);
    }
    void message(String text) {
        if(text.startsWith("Pronto")) return;
        notice.removeCallbacks(hideNotice);notice.setText(text);notice.setVisibility(View.VISIBLE);
        notice.postDelayed(hideNotice,3500);
    }
    void controls(boolean isVideo,boolean recording,boolean switchable,boolean canShoot,long recordStart) {
        photo.setSelected(!isVideo);video.setSelected(isVideo);
        photo.setTextColor(isVideo?MUTED:GOLD);video.setTextColor(isVideo?GOLD:MUTED);
        photo.setEnabled(switchable);video.setEnabled(switchable);
        shutter.setEnabled(canShoot);shutter.setAlpha(canShoot?1f:.4f);
        shutter.setContentDescription(isVideo?(recording?"Ferma e salva":"Registra"):"Scatta");
        shutterIcon.state(isVideo,recording);
        timer.setVisibility(recording?View.VISIBLE:View.GONE);
        if(recording) {
            long t=Math.max(0,SystemClock.elapsedRealtime()-recordStart)/1000;
            timer.setText(String.format(Locale.ROOT,"%02d:%02d",t/60,t%60));
        }
        enable(settings,!recording);
    }
    void zoom(float ratio,float max,boolean enabled) {
        String value=CameraZoom.label(ratio);
        if(!value.contentEquals(zoomValue.getText())) zoomValue.setText(value);
        zoomValue.setContentDescription("Zoom "+value+", torna a 1×");
        enable(less,enabled && ratio>1.001f);enable(more,enabled && ratio<max-.001f);enable(zoomValue,enabled);
    }
    void lens(boolean front,boolean available,boolean enabled) {
        flip.setVisibility(available?View.VISIBLE:View.INVISIBLE);
        flip.setContentDescription(front?"Passa alla fotocamera posteriore":"Passa alla fotocamera frontale");
        flip.setSelected(front);
        enable(flip,enabled);
    }
    void flash(boolean videoMode,boolean available,boolean on,boolean ready) {
        flash.setVisibility(available?View.VISIBLE:View.GONE);
        flash.setSelected(on);
        String action=videoMode?(on?"Spegni torcia video":"Accendi torcia video"):(on?"Disattiva flash foto":"Attiva flash foto");
        flash.setContentDescription(action);
        enable(flash,ready);
    }
    void job(boolean active,boolean pending,String action,boolean enabled) {
        jobCard.setVisibility(active||pending?View.VISIBLE:View.GONE);
        progress.setVisibility(active?View.VISIBLE:View.GONE);
        jobAction.setText(action);enable(jobAction,enabled);
    }
    void thumbnail(Bitmap bitmap) {
        if(bitmap==null) {gallery.setScaleType(ImageView.ScaleType.FIT_CENTER);gallery.setImageDrawable(albumIcon);}
        else {gallery.setScaleType(ImageView.ScaleType.CENTER_CROP);gallery.setImageBitmap(bitmap);}
    }
    static int dp(Context c,float value){return Math.round(value*c.getResources().getDisplayMetrics().density);}
    static GradientDrawable round(Context c,int color,float radius) {
        GradientDrawable result=new GradientDrawable();result.setColor(color);result.setCornerRadius(dp(c,radius));return result;
    }
    static TextView label(Context c,float size,int color,boolean medium) {
        TextView v=new TextView(c);v.setTextColor(color);v.setTextSize(size);v.setIncludeFontPadding(false);
        v.setTypeface(Typeface.create(medium?"sans-serif-medium":"sans-serif",Typeface.NORMAL));return v;
    }
    static Button button(Context c,String text,float size) {
        Button b=new Button(c);b.setStateListAnimator(null);b.setAllCaps(false);b.setMinWidth(0);b.setMinimumWidth(0);
        b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(0,0,0,0);b.setGravity(Gravity.CENTER);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x38ffffff),null,round(c,WHITE,24)));
        b.setTextColor(WHITE);b.setTextSize(size);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));b.setText(text);return b;
    }
    static void enable(View v,boolean on){v.setEnabled(on);v.setAlpha(on?1f:.38f);}
    private static ImageButton iconButton(Context c,String name) {
        ImageButton b=new ImageButton(c);b.setBackgroundColor(0x00000000);b.setPadding(0,0,0,0);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);b.setContentDescription(name);return b;
    }
    private final class Stage extends FrameLayout {
        Stage(Context c){super(c);}
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            int width=MeasureSpec.getSize(widthSpec),height=MeasureSpec.getSize(heightSpec);
            int rail=exposure.getVisibility()==GONE?0:dp(getContext(),ExposureSlider.WIDTH_DP+EXPOSURE_MARGIN_DP+4);
            int free=Math.max(0,width-Math.round(height*finder.aspect));
            // Keep the normal centered framing when the picture already has a wide enough black border.
            ((LayoutParams)finder.getLayoutParams()).rightMargin=Math.max(0,Math.min(rail,2*rail-free));
            super.onMeasure(widthSpec,heightSpec);
        }
    }
    private static final class Finder extends FrameLayout {
        float aspect=1;
        Finder(Context c){
            super(c);
            // Keep the TextureView's surface stable across mode changes. Resizing the view
            // also changes Android's default camera buffer size to the display dimensions.
            setOutlineProvider(new ViewOutlineProvider(){
                @Override public void getOutline(View v,Outline outline) {
                    outline.setRoundRect(visibleFrame(),dp(getContext(),12));
                }
            });
        }
        Rect visibleFrame(){
            int w=getWidth(),h=getHeight();
            int frameW=Math.min(w,Math.round(h*aspect)),frameH=Math.min(h,Math.round(w/aspect));
            int x=(w-frameW)/2,y=(h-frameH)/2;
            return new Rect(x,y,x+frameW,y+frameH);
        }
    }
    private static final class Dial extends Drawable {
        static final int SHUTTER=0,GEAR=1,ALBUM=2,FLIP=3,FLASH_ON=4,FLASH_OFF=5;
        static final int[] TOOTH_ANGLES={-13,-8,8,13};
        final int type,intrinsic; final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean video,recording;
        Dial(Context c,int type){this.type=type;intrinsic=dp(c,type==SHUTTER?74:46);}
        void state(boolean v,boolean r){if(video!=v || recording!=r){video=v;recording=r;invalidateSelf();}}
        @Override public int getIntrinsicWidth(){return intrinsic;}
        @Override public int getIntrinsicHeight(){return intrinsic;}
        @Override public void draw(Canvas c) {
            Rect bounds=getBounds();c.save();c.translate(bounds.exactCenterX(),bounds.exactCenterY());
            float scale=Math.min(bounds.width(),bounds.height())/(type==SHUTTER?74f:46f);c.scale(scale,scale);
            p.setColor(WHITE);p.setStyle(Paint.Style.STROKE);p.setStrokeJoin(Paint.Join.ROUND);p.setStrokeCap(Paint.Cap.ROUND);
            if(type==SHUTTER){
                p.setStrokeWidth(2.6f);c.drawCircle(0,0,32.5f,p);p.setStyle(Paint.Style.FILL);p.setColor(video?RED:WHITE);
                if(recording)c.drawRoundRect(-13,-13,13,13,3.5f,3.5f,p);else c.drawCircle(0,0,27.7f,p);
            }else if(type==GEAR){
                p.setStrokeWidth(1.6f);Path path=new Path();
                for(int i=0;i<8;i++)for(int k=0;k<4;k++){
                    double a=(i*45+TOOTH_ANGLES[k])*Math.PI/180;
                    float r=k==1||k==2?10.2f:8.2f,x=(float)Math.cos(a)*r,y=(float)Math.sin(a)*r;
                    if(i==0&&k==0)path.moveTo(x,y);else path.lineTo(x,y);
                }
                path.close();c.drawPath(path,p);c.drawCircle(0,0,3.3f,p);
            }else if(type==FLIP){
                p.setStrokeWidth(2.2f);
                c.drawArc(-10,-8,10,8,202,130,false,p);
                c.drawArc(-10,-8,10,8,22,130,false,p);
                Path tips=new Path();tips.moveTo(9,-8);tips.lineTo(9,-3);tips.lineTo(4,-3);
                tips.moveTo(-9,8);tips.lineTo(-9,3);tips.lineTo(-4,3);c.drawPath(tips,p);
            }else if(type==FLASH_ON || type==FLASH_OFF){
                Path bolt=new Path();bolt.moveTo(3,-12);bolt.lineTo(-8,2);bolt.lineTo(-1,2);
                bolt.lineTo(-3,12);bolt.lineTo(8,-3);bolt.lineTo(1,-3);bolt.close();
                p.setStrokeWidth(1.7f);
                if(type==FLASH_ON){p.setStyle(Paint.Style.FILL);p.setColor(GOLD);}
                c.drawPath(bolt,p);
                if(type==FLASH_OFF){
                    p.setColor(GROUP);p.setStrokeWidth(4);c.drawLine(-10,-10,10,10,p);
                    p.setColor(WHITE);p.setStrokeWidth(1.7f);c.drawLine(-10,-10,10,10,p);
                }
            }else{
                p.setStrokeWidth(1.6f);c.drawRoundRect(-10,-8,10,8,3,3,p);c.drawCircle(4,-3,1.2f,p);
                Path ridge=new Path();ridge.moveTo(-8,5);ridge.lineTo(-3,-1);ridge.lineTo(1,3);ridge.lineTo(4,0);ridge.lineTo(8,5);c.drawPath(ridge,p);
            }
            c.restore();
        }
        @Override public void setAlpha(int alpha){} @Override public void setColorFilter(ColorFilter filter){}
        @Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    }
}
