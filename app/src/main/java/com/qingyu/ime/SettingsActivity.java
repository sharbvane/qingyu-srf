package com.qingyu.ime;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;

/** Onboarding and essentials only. Test inputs are local and not retained. */
public final class SettingsActivity extends Activity {
    private ImePreferences prefs;
    private Palette palette;
    private LinearLayout content;
    private TextView activation;
    private EditText editorForInsets;
    private boolean firstCreate=true;
    private int dp(int n){return (int)(getResources().getDisplayMetrics().density*n+0.5f);}
    @Override public void onCreate(Bundle state){super.onCreate(state);prefs=new ImePreferences(this);build();}
    @Override protected void onResume(){super.onResume();updateActivation();}
    private void build(){
        palette=new Palette(prefs.dark(this));
        getWindow().setStatusBarColor(palette.background);getWindow().setNavigationBarColor(palette.background);
        getWindow().getDecorView().setSystemUiVisibility(prefs.dark(this)?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(palette.background);
        android.widget.FrameLayout viewport=new android.widget.FrameLayout(this);
        viewport.setBackgroundColor(palette.background);
        viewport.addView(scroll,new android.widget.FrameLayout.LayoutParams(-1,-1));
        viewport.setOnApplyWindowInsetsListener((v,insets)->{
            if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);}
            else v.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());
            return insets;
        });
        scroll.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            if(editorForInsets!=null&&editorForInsets.hasFocus()&&(b-t!=ob-ot))v.post(()->editorForInsets.requestRectangleOnScreen(new android.graphics.Rect(0,0,editorForInsets.getWidth(),editorForInsets.getHeight()),true));
        });
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(24),dp(28),dp(24),dp(30));scroll.addView(content);setContentView(viewport);
        label(content,"Q I N G Y U",11,palette.secondary,Typeface.BOLD,0);
        label(content,"轻语",40,palette.text,Typeface.BOLD,12);
        label(content,"打字如常。\n英文，在日常里遇见。",24,palette.text,Typeface.NORMAL,8);
        label(content,"一款离线、轻盈的中文输入法",13,palette.secondary,Typeface.NORMAL,12);
        LinearLayout setup=card(24);
        activation=label(setup,"",14,palette.text,Typeface.BOLD,0);
        button(setup,"1  启用轻语输入法",()->startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)),true);
        button(setup,"2  切换到轻语",()->((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker(),false);
        label(setup,"首次启用时，Android 会显示输入法通用提示。轻语没有联网权限，输入内容不上传。",12,palette.secondary,Typeface.NORMAL,12);
        LinearLayout options=card(16);
        label(options,"输入偏好",18,palette.text,Typeface.BOLD,0);
        toggle(options,"英文释义","候选上方显示较弱的英文注释", "translation",true);
        toggle(options,"轻触震动","按键提供轻量触觉反馈", "haptic",true);
        toggle(options,"按键预览","轻触字母时放大提示", "preview",true);
        toggle(options,"记住常用词","仅在本机保存中文词频；密码字段不学习", "learning",true);
        button(options,"外观  ·  "+themeName(),this::themeDialog,false);
        button(options,"键盘高度  ·  "+heightName(),this::heightDialog,false);
        LinearLayout practice=card(16);
        label(practice,"试着输入",18,palette.text,Typeface.BOLD,0);
        label(practice,"输入 kaifa、xiangmu、sheji，看看中文上方的英文。点中文，只会上屏中文。",13,palette.secondary,Typeface.NORMAL,10);
        EditText edit=new EditText(this);edit.setHint("在这里打几个字…");edit.setTextSize(17);edit.setTextColor(palette.text);edit.setHintTextColor(palette.secondary);edit.setSingleLine(false);edit.setMinLines(2);edit.setGravity(Gravity.TOP);
        editorForInsets=edit;
        edit.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);edit.setBackground(tint(palette.background,12));edit.setPadding(dp(14),dp(12),dp(14),dp(12));
        LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(-1,dp(112));ep.topMargin=dp(14);practice.addView(edit,ep);
        label(content,"顺手的小动作",16,palette.text,Typeface.BOLD,24);
        label(content,"空格左右滑动，移动光标\n长按退格，连续删除\n退格向左滑，删除一个片段\n长按字母第一排，输入数字\n左右滑动候选栏，查看更多候选\n点候选栏右上角，展开或收起\n长按「中 / EN」，切换系统输入法",13,palette.secondary,Typeface.NORMAL,12);
        button(content,"关于轻语 · 0.1.0",this::about,false);
        label(content,"先把中文打好，再顺便遇见英文。",12,palette.secondary,Typeface.NORMAL,16);
        updateActivation();
        if(firstCreate){content.setFocusableInTouchMode(true);content.requestFocus();firstCreate=false;}
    }
    private TextView label(LinearLayout parent,String text,int size,int color,int style,int margin){
        TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(color);v.setTypeface(Typeface.create("sans-serif",style));v.setLineSpacing(dp(4),1);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(margin);parent.addView(v,lp);return v;
    }
    private GradientDrawable tint(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private LinearLayout card(int margin){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);v.setPadding(dp(20),dp(20),dp(20),dp(20));v.setBackground(tint(palette.key,20));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(margin);content.addView(v,lp);return v;}
    private void button(LinearLayout parent,String text,Runnable action,boolean primary){
        Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(primary?palette.accentText:palette.accent);b.setBackground(tint(primary?palette.accent:palette.function,12));b.setPadding(dp(12),dp(4),dp(12),dp(4));b.setOnClickListener(v->action.run());
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(48));lp.topMargin=dp(14);parent.addView(b,lp);
    }
    private void toggle(LinearLayout parent,String title,String subtitle,String key,boolean fallback){
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout copy=new LinearLayout(this);copy.setOrientation(LinearLayout.VERTICAL);label(copy,title,15,palette.text,Typeface.NORMAL,0);label(copy,subtitle,12,palette.secondary,Typeface.NORMAL,3);
        row.addView(copy,new LinearLayout.LayoutParams(0,-2,1));Switch toggle=new Switch(this);toggle.setContentDescription(title);toggle.setChecked(prefs.store.getBoolean(key,fallback));toggle.setOnCheckedChangeListener((v,value)->prefs.store.edit().putBoolean(key,value).apply());row.addView(toggle);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(20);parent.addView(row,lp);
    }
    private String themeName(){String theme=prefs.store.getString("theme","auto");return theme.equals("dark")?"深色":theme.equals("light")?"浅色":"跟随系统";}
    private void themeDialog(){String[] names={"跟随系统","浅色","深色"},values={"auto","light","dark"};String theme=prefs.store.getString("theme","auto");int index=theme.equals("light")?1:theme.equals("dark")?2:0;new AlertDialog.Builder(this).setTitle("外观").setSingleChoiceItems(names,index,(d,i)->{prefs.store.edit().putString("theme",values[i]).apply();d.dismiss();build();}).setNegativeButton("取消",null).show();}
    private String heightName(){float height=prefs.height();return height<1?"紧凑":height>1?"宽松":"标准";}
    private void heightDialog(){String[] names={"紧凑","标准","宽松"};float[] values={0.88f,1f,1.12f};int index=prefs.height()<1?0:prefs.height()>1?2:1;new AlertDialog.Builder(this).setTitle("键盘高度").setSingleChoiceItems(names,index,(d,i)->{prefs.store.edit().putFloat("height",values[i]).apply();d.dismiss();build();}).setNegativeButton("取消",null).show();}
    private void updateActivation(){
        if(activation==null)return;InputMethodManager imm=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);boolean enabled=false;
        for(InputMethodInfo info:imm.getEnabledInputMethodList())if(info.getPackageName().equals(getPackageName()))enabled=true;
        boolean selected=Settings.Secure.getString(getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD)!=null && Settings.Secure.getString(getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD).startsWith(getPackageName()+"/");
        activation.setText(selected?"已就绪 · 轻语是当前输入法":enabled?"已启用 · 还需切换到轻语":"两步开始使用");
    }
    private void about(){new AlertDialog.Builder(this).setTitle("轻语输入法 0.1.0").setMessage("基础体验版 · Android 8.0 及以上\n\n轻语自有代码：GNU GPL-3.0-only。完整许可证随应用提供。\n\n中文引擎：AOSP PinyinIME\nApache License 2.0\n来源：android.googlesource.com/platform/packages/inputmethods/PinyinIME\n\n本地英文释义：CC-CEDICT\nMDBG 与社区贡献者维护\nCC BY-SA 4.0\n来源：www.mdbg.net/chinese/dictionary?page=cc-cedict\n已压缩为简体词目与简短释义，并补充常用表达。完整来源、许可证及转换脚本随项目提供。\n\n输入内容不会联网发送。词频仅保存在本机；英文释义是词典常见义，不是语境翻译。\n\n这是首版 MVP，现代词汇覆盖、细致触摸调优与真机长期使用仍需迭代。") .setPositiveButton("知道了",null).show();}
}
