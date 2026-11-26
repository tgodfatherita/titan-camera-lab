package it.carmine.titancamera;

import android.app.*;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.util.Size;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.Consumer;
import static it.carmine.titancamera.CameraScreen.*;

final class FormatPicker {
    private final Activity host;
    final Dialog dialog;
    private final boolean video;
    private final List<Size> sizes;
    private final LinearLayout group;
    private final Map<CaptureFormats.Shape,Button> tabs=new EnumMap<>(CaptureFormats.Shape.class);
    private final Map<CaptureFormats.Shape,Size> remembered=new EnumMap<>(CaptureFormats.Shape.class);
    private Size selected;

    FormatPicker(Activity host,boolean video,List<Size> sizes,Size current,Consumer<Size> apply) {
        this.host=host;this.video=video;this.sizes=sizes;selected=current;
        dialog=new Dialog(host,android.R.style.Theme_Material_NoActionBar);
        LinearLayout page=new LinearLayout(host);page.setOrientation(LinearLayout.VERTICAL);page.setBackgroundColor(0xff101012);
        page.setOnApplyWindowInsetsListener((v,in)->{
            v.setPadding(in.getSystemWindowInsetLeft()+dp(host,20),in.getSystemWindowInsetTop(),in.getSystemWindowInsetRight()+dp(host,20),in.getSystemWindowInsetBottom());return in;
        });
        LinearLayout header=new LinearLayout(host);header.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(header,new LinearLayout.LayoutParams(-1,dp(host,64)));
        Button back=button(host,"‹",28);back.setTextColor(GOLD);back.setContentDescription("Annulla");
        header.addView(back,new LinearLayout.LayoutParams(dp(host,38),dp(host,48)));back.setOnClickListener(v->dialog.dismiss());
        TextView title=label(host,24,WHITE,true);title.setText(video?"Formato video":"Formato foto");title.setAccessibilityHeading(true);
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button done=button(host,"Applica",16);done.setTextColor(GOLD);
        header.addView(done,new LinearLayout.LayoutParams(dp(host,72),dp(host,48)));
        done.setOnClickListener(v->{dialog.dismiss();apply.accept(selected);});
        LinearLayout bar=new LinearLayout(host);bar.setPadding(dp(host,3),dp(host,3),dp(host,3),dp(host,3));bar.setBackground(round(host,GROUP,14));
        page.addView(bar,new LinearLayout.LayoutParams(-1,dp(host,45)));
        for(CaptureFormats.Shape shape:CaptureFormats.Shape.values()){
            if(sizes.stream().noneMatch(shape::matches))continue;
            Button tab=button(host,shape.title,15);tabs.put(shape,tab);bar.addView(tab,new LinearLayout.LayoutParams(0,-1,1));
            tab.setOnClickListener(v->chooseShape(shape));
        }
        TextView subtitle=label(host,13,MUTED,true);subtitle.setText("RISOLUZIONE");subtitle.setLetterSpacing(.07f);
        LinearLayout.LayoutParams sub=new LinearLayout.LayoutParams(-1,-2);sub.setMargins(dp(host,12),dp(host,20),0,dp(host,8));page.addView(subtitle,sub);
        ScrollView scroll=new ScrollView(host);scroll.setVerticalScrollBarEnabled(false);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        group=new LinearLayout(host);group.setOrientation(LinearLayout.VERTICAL);group.setBackground(round(host,GROUP,16));group.setClipToOutline(true);scroll.addView(group);
        render();dialog.setContentView(page);
        Window win=dialog.getWindow();win.setStatusBarColor(0xff101012);win.setNavigationBarColor(0xff101012);win.setLayout(-1,-1);
    }
    private void chooseShape(CaptureFormats.Shape shape) {
        if(shape.matches(selected))return;
        Size next=remembered.get(shape);
        if(next==null){
            long target=CaptureFormats.area(selected);
            next=sizes.stream().filter(shape::matches).min(Comparator.comparingDouble(s->Math.abs(Math.log((double)CaptureFormats.area(s)/target)))).get();
        }
        selected=next;render();
    }
    private void render(){
        CaptureFormats.Shape shape=CaptureFormats.Shape.of(selected);remembered.put(shape,selected);
        tabs.forEach((key,b)->{boolean active=key==shape;b.setSelected(active);b.setTextColor(active?GOLD:MUTED);
            b.setBackground(round(host,active?0xff39393f:0x00000000,11));});
        group.removeAllViews();
        for(Size s:sizes){
            if(!shape.matches(s))continue;
            if(group.getChildCount()>0){
                View divider=new View(host);divider.setBackgroundColor(0xff353538);LinearLayout.LayoutParams line=new LinearLayout.LayoutParams(-1,1);
                line.leftMargin=dp(host,16);group.addView(divider,line);
            }
            String name=video?CaptureFormats.videoQuality(s):CaptureFormats.mp(s);
            String dimensions=s.getHeight()+" × "+s.getWidth();
            String detail=dimensions+" px"+(video?" · "+CaptureFormats.mp(s)+" · 30 fps":"");
            LinearLayout row=new LinearLayout(host);row.setGravity(Gravity.CENTER_VERTICAL);row.setFocusable(true);
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x35ffffff),null,round(host,WHITE,0)));
            row.setPadding(dp(host,16),dp(host,8),dp(host,16),dp(host,8));row.setMinimumHeight(dp(host,52));
            row.setContentDescription(name+", "+dimensions+" pixel");row.setSelected(s.equals(selected));group.addView(row,new LinearLayout.LayoutParams(-1,-2));
            LinearLayout copy=new LinearLayout(host);copy.setOrientation(LinearLayout.VERTICAL);row.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
            TextView main=label(host,16,WHITE,true);main.setText(name);copy.addView(main);
            TextView secondary=label(host,12,MUTED,false);secondary.setText(detail);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-2,-2);
            sp.topMargin=dp(host,3);copy.addView(secondary,sp);
            if(s.equals(selected)){TextView check=label(host,20,GOLD,true);check.setText("✓");row.addView(check);}
            row.setOnClickListener(v->{selected=s;render();});
        }
    }
}
