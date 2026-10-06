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
import android.widget.SeekBar;
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
    private TranslationRepository translations;
    private boolean firstCreate=true;
    private int dp(int n){return (int)(getResources().getDisplayMetrics().density*n+0.5f);}
    @Override public void onCreate(Bundle state){super.onCreate(state);prefs=new ImePreferences(this);translations=new TranslationRepository(this);build();}
    @Override protected void onResume(){super.onResume();updateActivation();}
    @Override protected void onDestroy(){if(translations!=null)translations.close();super.onDestroy();}
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
        label(content,"打字如常。\n外语，在日常里遇见。",24,palette.text,Typeface.NORMAL,8);
        label(content,"一款轻盈、克制的输入法",13,palette.secondary,Typeface.NORMAL,12);
        LinearLayout setup=card(24);
        activation=label(setup,"",14,palette.text,Typeface.BOLD,0);
        button(setup,"1  启用轻语输入法",()->startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)),true);
        button(setup,"2  切换到轻语",()->((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker(),false);
        label(setup,"首次启用时，Android 会显示输入法通用提示。输入与翻译在本机处理；模型下载不包含输入内容。",12,palette.secondary,Typeface.NORMAL,12);
        LinearLayout options=card(16);
        label(options,"输入偏好",18,palette.text,Typeface.BOLD,0);
        toggle(options,"候选释义","中文显示所选外语；英文默认显示中文", "translation",true);
        button(options,"释义显示语言  ·  "+TranslationRepository.languageName(prefs.glossLanguage()),this::languageDialog,false);
        button(options,"离线翻译模型",this::modelDialog,false);
        button(options,"中文键盘  ·  "+(prefs.keyboardMode().equals("t9")?"九键":"全键盘"),this::keyboardDialog,false);
        toggle(options,"轻触震动","按键提供轻量触觉反馈", "haptic",true);
        toggle(options,"按键预览","轻触字母时放大提示", "preview",true);
        toggle(options,"记住常用词","仅在本机保存中文词频；密码字段不学习", "learning",true);
        toggle(options,"剪贴板记录","在本机保留最近 100 条；密码字段不记录", "clipboard",true);
        button(options,"外观  ·  "+themeName(),this::themeDialog,false);
        button(options,"键盘风格  ·  "+(prefs.style().equals("flat")?"平整":"圆角"),this::styleDialog,false);
        button(options,"键盘高度  ·  "+heightName(),this::heightDialog,false);
        LinearLayout practice=card(16);
        label(practice,"试着输入",18,palette.text,Typeface.BOLD,0);
        label(practice,"输入 kaifa、xiangmu、sheji，看看候选上方的释义。单击输入原文，长按看释义，上滑输入译文。",13,palette.secondary,Typeface.NORMAL,10);
        EditText edit=new EditText(this);edit.setHint("在这里打几个字…");edit.setTextSize(17);edit.setTextColor(palette.text);edit.setHintTextColor(palette.secondary);edit.setSingleLine(false);edit.setMinLines(2);edit.setGravity(Gravity.TOP);
        editorForInsets=edit;
        edit.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);edit.setBackground(tint(palette.background,12));edit.setPadding(dp(14),dp(12),dp(14),dp(12));
        LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(-1,dp(112));ep.topMargin=dp(14);practice.addView(edit,ep);
        label(content,"顺手的小动作",16,palette.text,Typeface.BOLD,24);
        label(content,"空格左右滑动，移动光标\n长按退格，连续删除\n退格向左滑，删除一个片段\n长按字母第一排，输入数字\n左右滑动候选栏，查看更多候选\n点候选列表末尾，展开或收起\n长按候选，查看完整释义\n上滑候选，输入对应译文\n长按「中 / EN」，切换系统输入法",13,palette.secondary,Typeface.NORMAL,12);
        button(content,"关于轻语 · 0.2.0",this::about,false);
        label(content,"先把输入做好，再顺便遇见外语。",12,palette.secondary,Typeface.NORMAL,16);
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
    private String heightName(){return Math.round(prefs.height()*100)+"%";}
    private void heightDialog(){
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(24),dp(12),dp(24),dp(8));
        TextView value=label(body,heightName(),22,palette.accent,Typeface.BOLD,0);
        label(body,"拖动调节 · 78%–124%",13,palette.secondary,Typeface.NORMAL,8);
        SeekBar slider=new SeekBar(this);slider.setContentDescription("键盘高度");slider.setMax(1000);slider.setProgress(Math.round((prefs.height()-ImePreferences.MIN_HEIGHT)/(ImePreferences.MAX_HEIGHT-ImePreferences.MIN_HEIGHT)*1000));
        slider.setProgressTintList(android.content.res.ColorStateList.valueOf(palette.accent));slider.setThumbTintList(android.content.res.ColorStateList.valueOf(palette.accent));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar v,int progress,boolean fromUser){if(fromUser){float height=ImePreferences.MIN_HEIGHT+(ImePreferences.MAX_HEIGHT-ImePreferences.MIN_HEIGHT)*progress/1000f;prefs.store.edit().putFloat("height",height).apply();value.setText(Math.round(height*100)+"%");}}
            public void onStartTrackingTouch(SeekBar v){} public void onStopTrackingTouch(SeekBar v){}
        });
        body.addView(slider,new LinearLayout.LayoutParams(-1,dp(56)));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("键盘高度").setView(body).setPositiveButton("完成",null).setNeutralButton("恢复标准",(d,w)->prefs.store.edit().putFloat("height",1f).apply()).create();dialog.setOnDismissListener(d->build());dialog.show();
    }
    private void languageDialog(){String[] names={"英语 · English","日语 · 日本語","法语 · Français"},values={"en","ja","fr"};String language=prefs.glossLanguage();int index=language.equals("ja")?1:language.equals("fr")?2:0;new AlertDialog.Builder(this).setTitle("释义显示语言").setSingleChoiceItems(names,index,(d,i)->{prefs.store.edit().putString("gloss_language",values[i]).apply();d.dismiss();build();}).setNegativeButton("取消",null).show();}
    private void keyboardDialog(){String[] names={"中文全键盘","中文九键"},values={"full","t9"};new AlertDialog.Builder(this).setTitle("中文键盘模式").setSingleChoiceItems(names,prefs.keyboardMode().equals("t9")?1:0,(d,i)->{prefs.store.edit().putString("keyboard_mode",values[i]).apply();d.dismiss();build();}).setNegativeButton("取消",null).show();}
    private void styleDialog(){String[] names={"圆角","平整"},values={"classic","flat"};new AlertDialog.Builder(this).setTitle("键盘风格").setSingleChoiceItems(names,prefs.style().equals("flat")?1:0,(d,i)->{prefs.store.edit().putString("style",values[i]).apply();d.dismiss();build();}).setNegativeButton("取消",null).show();}
    private void modelDialog(){
        String language=prefs.glossLanguage();
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(24),dp(12),dp(24),dp(8));
        TextView state=label(body,TranslationRepository.stateText(translations.status(language)),15,palette.text,Typeface.NORMAL,0);
        label(body,"中文 ↔ "+TranslationRepository.languageName(language)+"\n常用词与短语可直接查看；完整句子需要下载模型。模型仅在 Wi-Fi 下载，翻译在本机运行。",13,palette.secondary,Typeface.NORMAL,12);
        android.widget.ImageView badge=new android.widget.ImageView(this);badge.setImageResource(prefs.dark(this)?R.drawable.google_translate_badge_dark:R.drawable.google_translate_badge);badge.setContentDescription("powered by Google Translate");badge.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);LinearLayout.LayoutParams badgeLayout=new LinearLayout.LayoutParams(dp(176),dp(16));badgeLayout.topMargin=dp(16);body.addView(badge,badgeLayout);
        label(body,"端侧翻译可能不准确，请结合语境阅读。Google SDK 会发送设备信息、安装标识及运行指标，不发送输入与译文。",12,palette.secondary,Typeface.NORMAL,12);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("离线翻译模型").setView(body).setPositiveButton("下载模型",null).setNegativeButton("关闭",null).create();
        dialog.setOnShowListener(unused->{
            Button download=dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            TranslationRepository.StateCallback update=modelState->{if(isFinishing()||isDestroyed()||!dialog.isShowing())return;state.setText(TranslationRepository.stateText(modelState));download.setEnabled(modelState!=TranslationRepository.State.DOWNLOADING&&modelState!=TranslationRepository.State.READY);download.setText(modelState==TranslationRepository.State.READY?"已就绪":modelState==TranslationRepository.State.FAILED?"重新下载":"下载模型");};
            download.setOnClickListener(v->translations.ensureModels(language,update));translations.refreshModels(language,update);
        });dialog.show();
    }
    private void updateActivation(){
        if(activation==null)return;InputMethodManager imm=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);boolean enabled=false;
        for(InputMethodInfo info:imm.getEnabledInputMethodList())if(info.getPackageName().equals(getPackageName()))enabled=true;
        boolean selected=Settings.Secure.getString(getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD)!=null && Settings.Secure.getString(getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD).startsWith(getPackageName()+"/");
        activation.setText(selected?"已就绪 · 轻语是当前输入法":enabled?"已启用 · 还需切换到轻语":"两步开始使用");
    }
    private void about(){
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("轻语输入法 0.2.0").setMessage("Android 8.0 及以上\n\n轻语自有代码：GNU GPL-3.0-only。完整许可证随应用提供。\n\n中文引擎：AOSP PinyinIME\nApache License 2.0\n来源：android.googlesource.com/platform/packages/inputmethods/PinyinIME\n\n本地英文释义：CC-CEDICT\nMDBG 与社区贡献者维护\nCC BY-SA 4.0\n来源：https://www.mdbg.net/chinese/dictionary?page=cc-cedict\n简短释义补充了常用表达；详细词典保留原始义项与拼音。\n\n整句翻译：Google Translate · ML Kit 端侧模型\n输入与翻译内容在本机处理，不发送至 Google 服务器。模型仅用户主动下载；Google SDK 另会联网获取更新和兼容信息，并发送设备信息、安装标识、语言配置、输入输出长度和运行指标以诊断及改进 SDK。\n\n部分译文由 Google Translate 自动生成，可能不准确；日语和法语会经英语中转。Google 不对译文的准确性、可靠性、适销性、特定用途适用性及不侵权性提供担保。\n翻译服务说明：https://cloud.google.com/translate\nSDK 隐私说明：https://developers.google.com/ml-kit/terms\n\n词频与最近 100 条剪贴板记录仅保存在本机。密码字段不学习、不记录。输入内容不写入应用日志。\n\n完整来源、许可证及转换脚本随项目提供。").setPositiveButton("知道了",null).create();
        dialog.show();TextView message=dialog.findViewById(android.R.id.message);if(message!=null)android.text.util.Linkify.addLinks(message,android.text.util.Linkify.WEB_URLS);
    }
}
