package it.carmine.titancamera;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.drawable.*;
import android.os.Build;
import android.os.SystemClock;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.widget.*;
import static it.carmine.titancamera.CameraScreen.*;

/** One chooser and countdown for the physical keyboard and the on-screen shutter. */
final class PhotoTimer extends FrameLayout {
    private static final int[] SECONDS={2,5,10};
    private final CameraScreen screen;
    private final Button[] choices=new Button[SECONDS.length];
    private final Button cancel;
    private final TextView remaining;
    private final Runnable prepare,shoot,release;
    private final Runnable tick=this::updateCountdown;
    private long deadline;
    private int previousAccessibility,previousFocusPolicy;
    private Runnable unregisterBack;

    PhotoTimer(Activity host,CameraScreen screen,Runnable prepare,Runnable shoot,Runnable release){
        super(host);
        this.screen=screen;this.prepare=prepare;this.shoot=shoot;this.release=release;
        setVisibility(INVISIBLE);
        setBackgroundColor(0x66000000);setClickable(true);setFocusableInTouchMode(true);
        setAccessibilityPaneTitle("Autoscatto");
        setOnClickListener(v->{if(deadline==0)dismiss();});
        for(int i=0;i<SECONDS.length;i++){
            int seconds=SECONDS[i];
            Button choice=button(host,seconds+" s",16);
            choice.setTextColor(GOLD);choice.setFontFeatureSettings("tnum");
            GradientDrawable ring=round(host,0xff29292d,28);
            ring.setStroke(dp(host,1),0xff787162);
            choice.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44ffd451),ring,null));
            choice.setContentDescription("Autoscatto "+seconds+" secondi");
            choice.setOnClickListener(v->start(seconds));
            addView(choice,new FrameLayout.LayoutParams(dp(host,52),dp(host,52)));
            choices[i]=choice;
        }
        cancel=button(host,"×",31);cancel.setContentDescription("Annulla autoscatto");
        cancel.setBackground(round(host,0xff262629,32));cancel.setOnClickListener(v->dismiss());
        addView(cancel,new FrameLayout.LayoutParams(dp(host,60),dp(host,60)));
        remaining=label(host,52,WHITE,true);remaining.setGravity(Gravity.CENTER);remaining.setFontFeatureSettings("tnum");
        remaining.setBackground(round(host,0xc0212124,48));remaining.setVisibility(INVISIBLE);
        remaining.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        addView(remaining,new FrameLayout.LayoutParams(dp(host,96),dp(host,96)));
    }
    boolean active(){return getVisibility()==VISIBLE;}
    void open(){
        if(active() || getWidth()==0)return;
        deadline=0;setBackgroundColor(0x66000000);setVisibility(VISIBLE);
        screen.showShootingOptions(false);
        previousAccessibility=screen.root.getImportantForAccessibility();
        screen.root.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        previousFocusPolicy=screen.root.getDescendantFocusability();
        screen.root.setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        requestFocus();
        if(Build.VERSION.SDK_INT>=33)registerBack();
        int[] origin=new int[2],shutter=new int[2],finder=new int[2];
        getLocationInWindow(origin);screen.shutter.getLocationInWindow(shutter);screen.preview.getLocationInWindow(finder);
        float cx=shutter[0]-origin[0]+screen.shutter.getWidth()/2f;
        float cy=shutter[1]-origin[1]+screen.shutter.getHeight()/2f;
        center(cancel,cx,cy);
        center(remaining,finder[0]-origin[0]+screen.preview.getWidth()/2f,finder[1]-origin[1]+screen.preview.getHeight()/2f);
        for(int i=0;i<choices.length;i++){
            Button choice=choices[i];choice.animate().cancel();
            choice.setVisibility(VISIBLE);center(choice,cx,cy);
            choice.setScaleX(.25f);choice.setScaleY(.25f);choice.setAlpha(0);
            float offset=(i-1)*Math.min(dp(getContext(),78),(getWidth()-choice.getLayoutParams().width)/3f);
            float rise=dp(getContext(),i==1?108:72);
            choice.animate().translationXBy(offset).translationYBy(-rise).scaleX(1).scaleY(1).alpha(1)
                    .setDuration(260).setStartDelay(i*18L).setInterpolator(new DecelerateInterpolator(1.6f)).start();
        }
    }
    private static void center(View view,float x,float y){
        view.setX(x-view.getLayoutParams().width/2f);view.setY(y-view.getLayoutParams().height/2f);
    }
    @android.annotation.TargetApi(33)
    private void registerBack(){
        android.window.OnBackInvokedDispatcher dispatcher=((Activity)getContext()).getOnBackInvokedDispatcher();
        android.window.OnBackInvokedCallback back=this::dismiss;
        dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,back);
        unregisterBack=()->dispatcher.unregisterOnBackInvokedCallback(back);
    }
    private void start(int seconds){
        if(!active() || deadline!=0)return;
        deadline=SystemClock.elapsedRealtime()+seconds*1000L;
        for(Button choice:choices){choice.animate().cancel();choice.setVisibility(INVISIBLE);}
        setBackgroundColor(0x19000000);remaining.setVisibility(VISIBLE);
        postDelayed(prepare,Math.max(0,seconds*1000L-2200));
        updateCountdown();
    }
    private void updateCountdown(){
        if(!active() || deadline==0)return;
        long left=deadline-SystemClock.elapsedRealtime();
        if(left<=0){hideTimer();shoot.run();return;}
        int seconds=(int)((left+999)/1000);
        remaining.setText(Integer.toString(seconds));
        remaining.setContentDescription(seconds+(seconds==1?" secondo":" secondi"));
        remaining.setScaleX(1.09f);remaining.setScaleY(1.09f);remaining.animate().scaleX(1).scaleY(1).setDuration(160).start();
        postDelayed(tick,left-(seconds-1)*1000L);
    }
    boolean dismiss(){
        if(!active())return false;
        hideTimer();release.run();return true;
    }
    private void hideTimer(){
        deadline=0;removeCallbacks(tick);removeCallbacks(prepare);
        for(Button choice:choices)choice.animate().cancel();
        remaining.animate().cancel();remaining.setVisibility(INVISIBLE);
        setVisibility(INVISIBLE);
        screen.showShootingOptions(true);
        screen.root.setImportantForAccessibility(previousAccessibility);
        screen.root.setDescendantFocusability(previousFocusPolicy);
        ViewGroup window=(ViewGroup)getParent();
        if(window!=null && !window.hasFocus())window.requestFocus();
        if(unregisterBack!=null){unregisterBack.run();unregisterBack=null;}
    }
}
