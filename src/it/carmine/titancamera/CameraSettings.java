package it.carmine.titancamera;

import android.app.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.view.*;
import android.widget.*;
import android.graphics.drawable.RippleDrawable;
import java.util.*;
import static it.carmine.titancamera.CameraScreen.*;

/** A separate, scrollable settings window keeps the camera session and framing alive. */
final class CameraSettings {
    interface Change { void photo(PhotoCapture.Mode mode); void gyro(boolean on); void format(boolean video); }
    final Button pick,process,last,discard;
    private final Button photoFormat,videoFormat;
    private final Activity host;
    private final Dialog dialog;
    private final LinearLayout list;
    private final Map<PhotoCapture.Mode,Choice> photos=new EnumMap<>(PhotoCapture.Mode.class);
    private final Choice gyro,marked;
    private final ScrollView scroll;

    CameraSettings(Activity activity,Change change) {
        host=activity;dialog=new Dialog(host,android.R.style.Theme_Material_NoActionBar);
        LinearLayout page=new LinearLayout(host);page.setOrientation(LinearLayout.VERTICAL);page.setBackgroundColor(0xff101012);
        page.setOnApplyWindowInsetsListener((v,in)->{v.setPadding(in.getSystemWindowInsetLeft(),in.getSystemWindowInsetTop(),in.getSystemWindowInsetRight(),in.getSystemWindowInsetBottom());return in;});
        FrameLayout header=new FrameLayout(host);
        page.addView(header,new LinearLayout.LayoutParams(-1,dp(host,64)));
        TextView title=label(host,28,WHITE,true);title.setText("Impostazioni");title.setAccessibilityHeading(true);
        FrameLayout.LayoutParams titlePos=new FrameLayout.LayoutParams(-2,-2,Gravity.START|Gravity.CENTER_VERTICAL);
        titlePos.leftMargin=dp(host,22);header.addView(title,titlePos);
        Button done=button(host,"Fine",16);done.setTextColor(GOLD);done.setOnClickListener(v->dialog.dismiss());
        FrameLayout.LayoutParams donePos=new FrameLayout.LayoutParams(dp(host,62),dp(host,48),Gravity.END|Gravity.CENTER_VERTICAL);
        donePos.rightMargin=dp(host,10);header.addView(done,donePos);
        scroll=new ScrollView(host);scroll.setVerticalScrollBarEnabled(false);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        list=new LinearLayout(host);list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(host,20),0,dp(host,20),dp(host,24));scroll.addView(list);
        LinearLayout formats=section("Formati di acquisizione");
        photoFormat=action(formats,"Formato foto");photoFormat.setOnClickListener(v->change.format(false));
        videoFormat=action(formats,"Formato video");videoFormat.setOnClickListener(v->change.format(true));
        LinearLayout photoCard=section("Modalità foto");
        for(PhotoCapture.Mode mode:PhotoCapture.Mode.values()) {
            String name=mode==PhotoCapture.Mode.FAST?"Rapida":mode.label;
            String detail=mode==PhotoCapture.Mode.BALANCED?"Sceglie lo scatto più nitido in poca luce":
                    mode==PhotoCapture.Mode.FAST?"Tempo breve per soggetti in movimento":"Uno scatto con esposizione automatica";
            photos.put(mode,choice(photoCard,name,detail,()->change.photo(mode)));
        }
        LinearLayout videoCard=section("Stabilizzazione video");
        gyro=choice(videoCard,"Giroscopio","Corregge la ripresa con i sensori del Titan",()->change.gyro(true));
        marked=choice(videoCard,"Marcata","Misura il movimento nel video",()->change.gyro(false));
        LinearLayout tools=section("Libreria video");
        pick=action(tools,"Scegli video");process=action(tools,"Stabilizza selezionato");
        last=action(tools,"Apri video stabilizzato");discard=action(tools,"Scarta ripresa in sospeso");discard.setTextColor(0xffff7774);
        TextView credits=label(host,13,MUTED,false);
        credits.setText(creditsText());credits.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footer=new LinearLayout.LayoutParams(-1,-2);
        footer.topMargin=dp(host,20);list.addView(credits,footer);
        dialog.setContentView(page);
        Window win=dialog.getWindow();win.setStatusBarColor(0xff101012);win.setNavigationBarColor(0xff101012);
        win.setLayout(-1,-1);
    }
    void show(){if(!dialog.isShowing()){dialog.show();scroll.scrollTo(0,0);}}
    void dismiss(){dialog.dismiss();}
    private String creditsText() {
        String author="Sviluppata da Carmine Petruziello";
        try {
            String version=host.getPackageManager().getPackageInfo(host.getPackageName(),0).versionName;
            if(version!=null && !version.isEmpty())return "Versione "+version+" · "+author;
        } catch(PackageManager.NameNotFoundException ignored) {
            // Keep the author visible when Android cannot supply the installed version.
        }
        return author;
    }
    void formats(String photo,String video,boolean enabled){
        String p="Formato foto    "+photo+"   ›",v="Formato video    "+video+"   ›";
        if(!p.contentEquals(photoFormat.getText()))photoFormat.setText(p);
        if(!v.contentEquals(videoFormat.getText()))videoFormat.setText(v);
        photoFormat.setContentDescription("Formato foto");videoFormat.setContentDescription("Formato video");
        enable(photoFormat,enabled);enable(videoFormat,enabled);
    }
    private LinearLayout section(String text) {
        TextView heading=label(host,13,MUTED,true);heading.setText(text.toUpperCase(Locale.ITALIAN));
        heading.setLetterSpacing(.075f);heading.setAccessibilityHeading(true);
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);
        hp.setMargins(dp(host,12),dp(host,17),0,dp(host,8));list.addView(heading,hp);
        LinearLayout card=new LinearLayout(host);card.setOrientation(LinearLayout.VERTICAL);card.setBackground(round(host,GROUP,16));
        card.setClipToOutline(true);list.addView(card,new LinearLayout.LayoutParams(-1,-2));return card;
    }
    private void divider(LinearLayout group) {
        if(group.getChildCount()==0)return;
        View line=new View(host);line.setBackgroundColor(0xff353538);
        LinearLayout.LayoutParams bounds=new LinearLayout.LayoutParams(-1,1);bounds.leftMargin=dp(host,16);group.addView(line,bounds);
    }
    private static final class Choice {
        final LinearLayout row;final TextView check;
        Choice(LinearLayout row,TextView check){this.row=row;this.check=check;}
        void state(boolean selected,boolean enabled) {
            row.setSelected(selected);CameraScreen.enable(row,enabled);check.setVisibility(selected?View.VISIBLE:View.INVISIBLE);
        }
    }
    private Choice choice(LinearLayout group,String title,String detail,Runnable select) {
        divider(group);
        LinearLayout row=new LinearLayout(host);row.setGravity(Gravity.CENTER_VERTICAL);row.setFocusable(true);
        row.setPadding(dp(host,16),dp(host,9),dp(host,16),dp(host,9));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff),null,round(host,WHITE,0)));
        row.setMinimumHeight(dp(host,54));row.setContentDescription(title+". "+detail);
        group.addView(row,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout copy=new LinearLayout(host);copy.setOrientation(LinearLayout.VERTICAL);row.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        TextView name=label(host,16,WHITE,true);name.setText(title);copy.addView(name);
        TextView description=label(host,12,MUTED,false);description.setText(detail);
        LinearLayout.LayoutParams d=new LinearLayout.LayoutParams(-1,-2);d.topMargin=dp(host,3);copy.addView(description,d);
        TextView check=label(host,20,GOLD,true);check.setText("✓");row.addView(check);
        row.setOnClickListener(v->select.run());return new Choice(row,check);
    }
    private Button action(LinearLayout group,String title) {
        divider(group);
        Button action=button(host,title,15);action.setTextColor(GOLD);action.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
        action.setPadding(dp(host,16),0,dp(host,12),0);group.addView(action,new LinearLayout.LayoutParams(-1,dp(host,46)));
        return action;
    }
    void state(PhotoCapture.Mode photo,boolean useGyro,boolean editable,boolean pending,boolean canPick,
               String processTitle,boolean canProcess,boolean hasLast,boolean canOpen,boolean canDiscard) {
        for(Map.Entry<PhotoCapture.Mode,Choice> entry:photos.entrySet()) entry.getValue().state(entry.getKey()==photo,editable);
        gyro.state(useGyro,editable);marked.state(!useGyro,editable);
        enable(pick,canPick);process.setText(processTitle);enable(process,canProcess);
        last.setVisibility(hasLast?View.VISIBLE:View.GONE);enable(last,canOpen);
        discard.setVisibility(pending && canDiscard?View.VISIBLE:View.GONE);
    }
}
