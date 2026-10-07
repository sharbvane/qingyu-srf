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
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.app.DownloadManager;
import android.widget.ProgressBar;

/** Onboarding and essentials only. Test inputs are local and not retained. */
public final class SettingsActivity extends Activity {
    private ImePreferences prefs;
    private Palette palette;
    private LinearLayout content;
    private TextView activation;
    private EditText editorForInsets;
    private TranslationRepository translations;
    private boolean modelManagerOpen;
    private TextView[] modelStates,modelSizes;
    private Button[] modelDownloads,modelDeletes;
    private long modelUiRevision;
    private static final String[] MODEL_LANGUAGES=TranslationRepository.glossLanguages();
    private AppUpdate updates;
    private boolean updateOpen,resumed,installStarting;
    private TextView updateStatus,updateVersion,updateNotes,updateSize;
    private Button updateCheck,updateDownload,updateCancel,updateInstall;
    private ProgressBar updateProgress;
    private final Handler updatePoll=new Handler(Looper.getMainLooper());
    private final Runnable pollDownload=new Runnable(){public void run(){if(resumed&&updateOpen){updates.refresh();updatePoll.postDelayed(this,800);}}};
    private boolean firstCreate=true;
    private int dp(int n){return (int)(getResources().getDisplayMetrics().density*n+0.5f);}
    @Override public void onCreate(Bundle state){super.onCreate(state);prefs=new ImePreferences(this);translations=new TranslationRepository(this);updates=new AppUpdate(this,this::updateUpdateRows);if(state!=null&&state.getBoolean("update_open")){buildUpdates(false);}else if(state!=null&&state.getBoolean("model_open")||getIntent().getBooleanExtra("model_manager",false))buildModelManager();else if(getIntent().getBooleanExtra("check_updates",false))buildUpdates(true);else build();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(intent.getBooleanExtra("check_updates",false))buildUpdates(true);else if(intent.getBooleanExtra("model_manager",false))buildModelManager();else build();}
    @Override protected void onSaveInstanceState(Bundle state){state.putBoolean("update_open",updateOpen);state.putBoolean("model_open",modelManagerOpen);super.onSaveInstanceState(state);}
    @Override protected void onResume(){super.onResume();resumed=true;updateActivation();if(modelManagerOpen)translations.refreshModels("en",state->updateModelRows());if(updateOpen){updatePoll.removeCallbacks(pollDownload);updatePoll.post(pollDownload);if(updates.installPending()&&getPackageManager().canRequestPackageInstalls())requestInstall();}}
    @Override protected void onPause(){resumed=false;installStarting=false;updatePoll.removeCallbacks(pollDownload);super.onPause();}
    @Override protected void onDestroy(){if(translations!=null)translations.close();if(updates!=null)updates.close();updatePoll.removeCallbacksAndMessages(null);super.onDestroy();}
    private void build(){
        modelManagerOpen=false;
        updateOpen=false;updatePoll.removeCallbacks(pollDownload);
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
        label(content,"轻语",30,palette.text,Typeface.BOLD,0);
        label(content,"打字如常，顺便遇见外语。",20,palette.text,Typeface.NORMAL,8);
        label(content,"一款轻盈、克制的输入法",13,palette.secondary,Typeface.NORMAL,12);
        LinearLayout setup=card(24);
        activation=label(setup,"",14,palette.text,Typeface.BOLD,0);
        button(setup,"1  启用轻语输入法",()->startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)),true);
        button(setup,"2  切换到轻语",()->((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker(),false);
        label(setup,"首次启用时，Android 会显示输入法通用提示。输入与翻译在本机处理；模型下载不包含输入内容。",12,palette.secondary,Typeface.NORMAL,12);
        LinearLayout options=card(16);
        label(options,"输入偏好",18,palette.text,Typeface.BOLD,0);
        toggle(options,"候选释义","键盘显示本地词义；英文默认显示中文", "translation",true);
        button(options,"释义显示语言  ·  "+TranslationRepository.languageName(prefs.glossLanguage()),this::languageDialog,false);
        button(options,"翻译模型管理",this::buildModelManager,false);
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
        label(practice,"输入 kaifa、xiangmu、sheji，看看本地释义。单击输入原文，长按看详情；上滑输入已有本地译文，其他翻译在详情页确认。",13,palette.secondary,Typeface.NORMAL,10);
        EditText edit=new EditText(this);edit.setHint("在这里打几个字…");edit.setTextSize(17);edit.setTextColor(palette.text);edit.setHintTextColor(palette.secondary);edit.setSingleLine(false);edit.setMinLines(2);edit.setGravity(Gravity.TOP);
        editorForInsets=edit;
        edit.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);edit.setBackground(tint(palette.background,12));edit.setPadding(dp(14),dp(12),dp(14),dp(12));
        LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(-1,dp(112));ep.topMargin=dp(14);practice.addView(edit,ep);
        label(content,"顺手的小动作",16,palette.text,Typeface.BOLD,24);
        label(content,"空格左右滑动，移动光标\n长按退格，连续删除\n退格向左滑，删除一个片段\n长按字母，左右滑动选择大小写与数字\n中文 Shift 单击临时大写，双击锁定大写\n左右滑动候选栏，查看更多候选\n点候选列表末尾展开，上下滑动浏览\n再次点击当前导航图标，收起面板\n长按候选，查看完整释义\n上滑候选，输入对应译文\n拼音输入时按回车，输入原始字母\n长按「中 / EN」，切换系统输入法",13,palette.secondary,Typeface.NORMAL,12);
        label(content,"版本与项目",16,palette.text,Typeface.BOLD,28);
        button(content,"检查更新",()->buildUpdates(true),false);
        button(content,"项目主页",this::projectHome,false);
        button(content,"关于轻语 · "+updates.currentVersion(),this::about,false);
        label(content,"先把输入做好，再顺便遇见外语。",12,palette.secondary,Typeface.NORMAL,16);
        updateActivation();
        if(firstCreate){content.setFocusableInTouchMode(true);content.requestFocus();firstCreate=false;}
    }
    private TextView label(LinearLayout parent,String text,int size,int color,int style,int margin){
        TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(color);v.setTypeface(Typeface.create("sans-serif",style));v.setLineSpacing(dp(4),1);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(margin);parent.addView(v,lp);return v;
    }
    private GradientDrawable tint(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private LinearLayout card(int margin){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);v.setPadding(dp(20),dp(20),dp(20),dp(20));v.setBackground(tint(palette.key,16));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(margin);content.addView(v,lp);return v;}
    private void button(LinearLayout parent,String text,Runnable action,boolean primary){
        Button b=new Button(this);b.setText(text);b.setContentDescription(text);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(primary?palette.accentText:palette.accent);b.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.pressed),tint(primary?palette.accent:android.graphics.Color.TRANSPARENT,10),tint(android.graphics.Color.WHITE,10)));b.setPadding(dp(12),dp(10),dp(12),dp(10));b.setMinHeight(dp(48));if(!primary)b.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);b.setOnClickListener(v->action.run());
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(primary?14:8);parent.addView(b,lp);
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
    private void languageDialog(){String[] values=TranslationRepository.glossLanguages(),names=new String[values.length];int index=0;for(int i=0;i<values.length;i++){names[i]=TranslationRepository.languageLabel(values[i])+(values[i].equals("en")?"":"（按需下载）");if(values[i].equals(prefs.glossLanguage()))index=i;}new AlertDialog.Builder(this).setTitle("释义显示语言").setSingleChoiceItems(names,index,(d,i)->{String selected=values[i];prefs.store.edit().putString("gloss_language",selected).apply();d.dismiss();build();if(!selected.equals("en")){buildModelManager();translations.ensureModels(selected,state->updateModelRows());}}).setNegativeButton("取消",null).show();}
    private void keyboardDialog(){String[] names={"中文全键盘","中文九键"},values={"full","t9"};new AlertDialog.Builder(this).setTitle("中文键盘模式").setSingleChoiceItems(names,prefs.keyboardMode().equals("t9")?1:0,(d,i)->{prefs.store.edit().putString("keyboard_mode",values[i]).apply();d.dismiss();build();}).setNegativeButton("取消",null).show();}
    private void styleDialog(){String[] names={"圆角","平整"},values={"classic","flat"};new AlertDialog.Builder(this).setTitle("键盘风格").setSingleChoiceItems(names,prefs.style().equals("flat")?1:0,(d,i)->{prefs.store.edit().putString("style",values[i]).apply();d.dismiss();build();}).setNegativeButton("取消",null).show();}
    private void buildModelManager(){
        modelManagerOpen=true;updateOpen=false;updatePoll.removeCallbacks(pollDownload);editorForInsets=null;activation=null;palette=new Palette(prefs.dark(this));
        getWindow().setStatusBarColor(palette.background);getWindow().setNavigationBarColor(palette.background);getWindow().getDecorView().setSystemUiVisibility(prefs.dark(this)?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(palette.background);scroll.setOnApplyWindowInsetsListener((v,insets)->{if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);}else v.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());return insets;});
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(24),dp(20),dp(24),dp(28));scroll.addView(content);setContentView(scroll);
        button(content,"返回设置",this::build,false);label(content,"翻译模型管理",26,palette.text,Typeface.BOLD,22);
        label(content,"中文与英文词典开箱可用。键盘仅显示本地词义；额外语言和完整句子的模型译文在详情页展示。模型按需下载，需连接 Wi-Fi。",13,palette.secondary,Typeface.NORMAL,12);
        modelStates=new TextView[MODEL_LANGUAGES.length];modelSizes=new TextView[MODEL_LANGUAGES.length];modelDownloads=new Button[MODEL_LANGUAGES.length];modelDeletes=new Button[MODEL_LANGUAGES.length];
        for(int i=0;i<MODEL_LANGUAGES.length;i++){
            String language=MODEL_LANGUAGES[i];LinearLayout entry=new LinearLayout(this);entry.setOrientation(LinearLayout.VERTICAL);entry.setPadding(0,dp(20),0,dp(20));content.addView(entry,new LinearLayout.LayoutParams(-1,-2));View line=new View(this);line.setBackgroundColor(palette.border);content.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));label(entry,"中文 ↔ "+TranslationRepository.languageName(language),18,palette.text,Typeface.BOLD,0);
            modelStates[i]=label(entry,"正在检查本地模型",13,palette.secondary,Typeface.NORMAL,8);modelStates[i].setContentDescription(TranslationRepository.languageName(language)+"模型状态");modelSizes[i]=label(entry,"已安装语言文件：正在读取",12,palette.secondary,Typeface.NORMAL,6);modelSizes[i].setContentDescription(TranslationRepository.languageName(language)+"模型占用");
            if(language.equals("en"))label(entry,"包含中文共享模型。删除后各语言整句翻译暂停，本地中英文词典仍可使用。",12,palette.secondary,Typeface.NORMAL,8);
            LinearLayout actions=new LinearLayout(this);LinearLayout.LayoutParams actionLayout=new LinearLayout.LayoutParams(-1,-2);actionLayout.topMargin=dp(12);entry.addView(actions,actionLayout);
            Button download=modelButton("下载 · "+TranslationRepository.languageName(language),()->translations.ensureModels(language,state->updateModelRows()));modelDownloads[i]=download;actions.addView(download,new LinearLayout.LayoutParams(0,-2,1));
            Button delete=modelButton("删除 · "+TranslationRepository.languageName(language),()->translations.deleteModels(language,state->updateModelRows()));modelDeletes[i]=delete;LinearLayout.LayoutParams deleteLayout=new LinearLayout.LayoutParams(0,-2,1);deleteLayout.leftMargin=dp(8);actions.addView(delete,deleteLayout);
        }
        android.widget.ImageView badge=new android.widget.ImageView(this);badge.setImageResource(prefs.dark(this)?R.drawable.google_translate_badge_dark:R.drawable.google_translate_badge);badge.setContentDescription("powered by Google Translate");badge.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);LinearLayout.LayoutParams badgeLayout=new LinearLayout.LayoutParams(dp(176),dp(16));badgeLayout.topMargin=dp(24);content.addView(badge,badgeLayout);
        label(content,"端侧翻译可能不准确，请结合语境阅读。Google SDK 会发送设备信息、安装标识及运行指标，不发送输入与译文。",12,palette.secondary,Typeface.NORMAL,12);
        updateModelRows();translations.refreshModels("en",state->updateModelRows());
    }
    private Button modelButton(String title,Runnable action){Button button=new Button(this);button.setText(title);button.setContentDescription(title);button.setAllCaps(false);button.setTextSize(13);button.setTextColor(palette.accent);button.setMinHeight(dp(48));button.setPadding(dp(10),dp(10),dp(10),dp(10));button.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.pressed),tint(palette.function,10),tint(android.graphics.Color.WHITE,10)));button.setOnClickListener(v->action.run());return button;}
    private void updateModelRows(){
        if(!modelManagerOpen||isFinishing()||isDestroyed())return;
        long revision=++modelUiRevision;
        boolean busy=false;for(String language:MODEL_LANGUAGES){TranslationRepository.State state=translations.status(language);busy|=state==TranslationRepository.State.DOWNLOADING||state==TranslationRepository.State.DELETING;}
        for(int i=0;i<MODEL_LANGUAGES.length;i++){
            String language=MODEL_LANGUAGES[i];TranslationRepository.State state=translations.status(language);String status=TranslationRepository.stateText(state);
            if(!language.equals("en")&&state==TranslationRepository.State.MISSING)status="未下载或缺少中文共享模型 · 下载后可用";modelStates[i].setText(status);
            modelDownloads[i].setEnabled(!busy&&state!=TranslationRepository.State.READY&&state!=TranslationRepository.State.UNSUPPORTED&&state!=TranslationRepository.State.CHECKING);modelDownloads[i].setText(state==TranslationRepository.State.READY?"已就绪":state==TranslationRepository.State.FAILED?"重新下载":"下载模型");
            modelDeletes[i].setEnabled(false);int index=i;boolean deletingAllowed=!busy&&state!=TranslationRepository.State.UNSUPPORTED;
            TextView sizeView=modelSizes[i];Button deleteButton=modelDeletes[i];translations.modelSize(language,bytes->{if(!modelManagerOpen||revision!=modelUiRevision||sizeView!=modelSizes[index])return;sizeView.setText(bytes<0?"已安装语言文件：SDK 未提供可读取大小":String.format(java.util.Locale.ROOT,"已安装语言文件：%.1f MiB",bytes/1048576d));deleteButton.setEnabled(deletingAllowed&&(bytes>0||state==TranslationRepository.State.READY));});
        }
    }
    private void buildUpdates(boolean check){
        updateOpen=true;modelManagerOpen=false;editorForInsets=null;activation=null;palette=new Palette(prefs.dark(this));
        getWindow().setStatusBarColor(palette.background);getWindow().setNavigationBarColor(palette.background);getWindow().getDecorView().setSystemUiVisibility(prefs.dark(this)?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(palette.background);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);}else v.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());return insets;});
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(24),dp(20),dp(24),dp(30));scroll.addView(content);setContentView(scroll);
        button(content,"返回设置",this::build,false);
        label(content,"检查更新",26,palette.text,Typeface.BOLD,22);
        label(content,"当前版本  "+updates.currentVersion(),13,palette.secondary,Typeface.NORMAL,10);
        updateStatus=label(content,updates.message,16,palette.text,Typeface.NORMAL,24);updateStatus.setId(R.id.update_status);updateStatus.setContentDescription("更新状态");updateStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        updateVersion=label(content,"",22,palette.text,Typeface.BOLD,22);updateVersion.setContentDescription("最新版本");
        updateSize=label(content,"",13,palette.secondary,Typeface.NORMAL,8);updateSize.setContentDescription("安装包大小");
        updateProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);updateProgress.setProgressTintList(ColorStateList.valueOf(palette.accent));updateProgress.setProgressBackgroundTintList(ColorStateList.valueOf(palette.function));updateProgress.setMax(1000);updateProgress.setContentDescription("更新下载进度");LinearLayout.LayoutParams progressLayout=new LinearLayout.LayoutParams(-1,dp(8));progressLayout.topMargin=dp(18);content.addView(updateProgress,progressLayout);
        updateDownload=modelButton("下载更新",updates::download);LinearLayout.LayoutParams actionLayout=new LinearLayout.LayoutParams(-1,-2);actionLayout.topMargin=dp(22);content.addView(updateDownload,actionLayout);
        updateInstall=modelButton("安装更新",this::requestInstall);content.addView(updateInstall,new LinearLayout.LayoutParams(-1,-2));
        updateCancel=modelButton("取消下载",updates::cancel);LinearLayout.LayoutParams cancelLayout=new LinearLayout.LayoutParams(-1,-2);cancelLayout.topMargin=dp(8);content.addView(updateCancel,cancelLayout);
        updateCheck=modelButton("重新检查",updates::check);LinearLayout.LayoutParams checkLayout=new LinearLayout.LayoutParams(-1,-2);checkLayout.topMargin=dp(8);content.addView(updateCheck,checkLayout);
        label(content,"更新说明",16,palette.text,Typeface.BOLD,28);updateNotes=label(content,"获取版本后会在这里显示更新说明。",14,palette.secondary,Typeface.NORMAL,10);updateNotes.setTextIsSelectable(true);updateNotes.setContentDescription("更新说明");
        button(content,"项目主页",this::projectHome,false);
        label(content,"从轻语官方 GitHub Releases 获取安装包。下载后校验版本与签名，再交给 Android 完成覆盖安装。",12,palette.secondary,Typeface.NORMAL,14);
        updateUpdateRows();if(check&&!updates.hasDownload())updates.check();else updates.refresh();if(resumed){updatePoll.removeCallbacks(pollDownload);updatePoll.post(pollDownload);}
    }
    private void updateUpdateRows(){
        if(!updateOpen||isFinishing()||isDestroyed()||updateStatus==null)return;
        updateStatus.setText(updates.verifying?"正在校验安装包…":updates.message);AppUpdate.Release release=updates.release;
        updateVersion.setVisibility(release==null?View.GONE:View.VISIBLE);updateSize.setVisibility(release==null?View.GONE:View.VISIBLE);
        if(release!=null){updateVersion.setText("轻语 "+release.version);String size=String.format(java.util.Locale.ROOT,"安装包  %.2f MiB",release.size/1048576d);if(updates.active())size+=String.format(java.util.Locale.ROOT,"  ·  %.1f / %.1f MiB",updates.downloaded/1048576d,release.size/1048576d);updateSize.setText(size);updateNotes.setText(release.notes.isEmpty()?"暂无更新说明。":release.notes);}
        boolean download=updates.hasDownload(),failed=updates.downloadStatus==DownloadManager.STATUS_FAILED;
        updateProgress.setVisibility(updates.active()?View.VISIBLE:View.GONE);updateProgress.setIndeterminate(updates.total<=0);if(updates.total>0)updateProgress.setProgress((int)Math.min(1000,updates.downloaded*1000/updates.total));
        updateDownload.setVisibility(release!=null&&updates.newer()&&!download?View.VISIBLE:View.GONE);updateDownload.setEnabled(!updates.checking&&!updates.verifying);
        updateInstall.setVisibility(updates.verified?View.VISIBLE:View.GONE);updateInstall.setEnabled(!updates.verifying&&!installStarting);
        updateCancel.setVisibility(download?View.VISIBLE:View.GONE);updateCancel.setText(failed||updates.downloadStatus==DownloadManager.STATUS_SUCCESSFUL?"删除安装包":"取消下载");updateCancel.setEnabled(!updates.verifying);
        updateCheck.setVisibility(download?View.GONE:View.VISIBLE);updateCheck.setEnabled(!updates.checking&&!updates.verifying);updateCheck.setText(updates.checking?"正在检查…":"重新检查");
        if(!updates.verifying&&!updates.verified)installStarting=false;
        if(resumed&&!installStarting&&(updates.consumeAutoInstall()||updates.verified&&updates.installPending()&&getPackageManager().canRequestPackageInstalls()))updatePoll.post(this::requestInstall);
    }
    private void requestInstall(){
        if(installStarting||!updates.hasDownload()||!updates.verified)return;installStarting=true;
        updates.verify(()->{
            if(isFinishing()||isDestroyed())return;
            if(!resumed){updates.installPending(true);installStarting=false;return;}
            try{
                if(!getPackageManager().canRequestPackageInstalls()){
                    updates.installPending(true);updates.message="请允许轻语安装更新，返回后继续安装。";updateUpdateRows();
                    startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())),7204);
                }else{updates.installPending(false);startActivity(AppUpdate.installIntent(this));}
            }catch(android.content.ActivityNotFoundException|SecurityException error){installStarting=false;updates.message="系统无法打开安装页面，请稍后重试。";updateUpdateRows();}
        });
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==7204){installStarting=false;if(getPackageManager().canRequestPackageInstalls())updatePoll.post(this::requestInstall);else{updates.installPending(false);updates.message="尚未允许安装更新，可点「安装更新」重试。";updateUpdateRows();}}}
    private void projectHome(){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(AppUpdate.PROJECT_URL)));}catch(android.content.ActivityNotFoundException error){new AlertDialog.Builder(this).setTitle("项目主页").setMessage(AppUpdate.PROJECT_URL).setPositiveButton("知道了",null).show();}}
    @Override public void onBackPressed(){if(modelManagerOpen||updateOpen){build();return;}super.onBackPressed();}
    private void updateActivation(){
        if(activation==null)return;InputMethodManager imm=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);boolean enabled=false;
        for(InputMethodInfo info:imm.getEnabledInputMethodList())if(info.getPackageName().equals(getPackageName()))enabled=true;
        boolean selected=Settings.Secure.getString(getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD)!=null && Settings.Secure.getString(getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD).startsWith(getPackageName()+"/");
        activation.setText(selected?"已就绪 · 轻语是当前输入法":enabled?"已启用 · 还需切换到轻语":"两步开始使用");
    }
    private void about(){
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("轻语输入法 "+updates.currentVersion()).setMessage("Android 8.0 及以上\n\n轻语自有代码：GNU GPL-3.0-only。完整许可证随应用提供。\n\n中文引擎：AOSP PinyinIME\nApache License 2.0\n来源：android.googlesource.com/platform/packages/inputmethods/PinyinIME\n\n现代中文词库：雾凇拼音 Rime Ice\nGPL-3.0-only；经筛选和词频转换融合到现有引擎。\n来源：https://github.com/iDvel/rime-ice\n\n本地英文释义：CC-CEDICT\nMDBG 与社区贡献者维护\nCC BY-SA 4.0\n来源：https://www.mdbg.net/chinese/dictionary?page=cc-cedict\n简短释义补充了常用表达；详细词典保留原始义项与拼音。\n\n整句翻译：Google Translate · ML Kit 端侧模型\n键盘只展示本地释义，模型译文仅在详情页展示。\n输入与翻译内容在本机处理，不发送至 Google 服务器。模型仅用户主动下载；Google SDK 另会联网获取更新和兼容信息，并发送设备信息、安装标识、语言配置、输入输出长度和运行指标以诊断及改进 SDK。\n\n部分译文由 Google Translate 自动生成，可能不准确；非英语语言会经英语中转。Google 不对译文的准确性、可靠性、适销性、特定用途适用性及不侵权性提供担保。\n翻译服务说明：https://cloud.google.com/translate\nSDK 隐私说明：https://developers.google.com/ml-kit/terms\n\n词频与最近 100 条剪贴板记录仅保存在本机。密码字段不学习、不记录。输入内容不写入应用日志。\n\n完整来源、许可证及转换脚本随项目提供。").setPositiveButton("知道了",null).create();
        dialog.show();TextView message=dialog.findViewById(android.R.id.message);if(message!=null)android.text.util.Linkify.addLinks(message,android.text.util.Linkify.WEB_URLS);
    }
}
