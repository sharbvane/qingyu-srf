package com.qingyu.ime;

import android.content.Context;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.List;
import java.util.function.Consumer;

/** Five quiet navigation actions; tools occupy the existing keyboard area. */
final class ImePanels {
    private final Context context;
    private final ImePreferences prefs;
    private final Consumer<String> action;
    final LinearLayout toolbar;
    final FrameLayout body;
    private final View keyboard;
    private Palette colors;
    private String active="";
    private TextView detailText,detailSource,detailMeta,detailNote,detailExplanation,detailExample,detailExampleTranslation,detailExampleHeading,modelStatus;
    private ImageView detailBadge,detailExampleBadge;
    private Button detailCommit,detailCopy;
    private String detailTranslation="";
    private ValueAnimator outgoing;
    private Bitmap transitionBitmap;
    private Drawable transitionImage;
    private Runnable panelChanged=()->{};
    private final Handler touchTimer=new Handler(Looper.getMainLooper());
    private Button editDelete,editUndo,editTranslate,editSelect,pressedDelete;
    private TextView editStatus;
    private boolean editSecure,canUndo,translateBusy,deleteTouchClick;
    private String editMessage="撤回仅作用于当前输入框";
    private final Runnable deleteRepeat=new Runnable(){
        @Override public void run(){
            if(pressedDelete==null||!active.equals("edit")||!pressedDelete.isShown()){cancelTouch();return;}
            action.accept("DELETE");if(pressedDelete!=null&&active.equals("edit"))touchTimer.postDelayed(this,48);
        }
    };
    ImePanels(Context context,ImePreferences prefs,View keyboard,Consumer<String> action){
        this.context=context;this.prefs=prefs;this.keyboard=keyboard;this.action=action;
        colors=new Palette(prefs.dark(context));toolbar=new LinearLayout(context);toolbar.setGravity(Gravity.CENTER_VERTICAL);
        body=new FrameLayout(context){
            @Override protected void onDetachedFromWindow(){cancelTouch();super.onDetachedFromWindow();}
            @Override protected void onVisibilityChanged(View changed,int visibility){super.onVisibilityChanged(changed,visibility);if(visibility!=View.VISIBLE)cancelTouch();}
            @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility!=View.VISIBLE)cancelTouch();}
            @Override protected void onMeasure(int widthSpec,int heightSpec){
                keyboard.measure(widthSpec,View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
                int height=keyboard.getMeasuredHeight();
                if(height==0){boolean landscape=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;height=dp((landscape?136:244)*prefs.height()+8);}
                if(View.MeasureSpec.getMode(heightSpec)!=View.MeasureSpec.UNSPECIFIED)height=Math.min(height,View.MeasureSpec.getSize(heightSpec));
                super.onMeasure(widthSpec,View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
                setMeasuredDimension(View.MeasureSpec.getSize(widthSpec),height);
            }
        };body.setId(R.id.panel_host);body.setClipChildren(true);body.setClipToPadding(true);body.addView(keyboard,new FrameLayout.LayoutParams(-1,-1));
        String[] names={"更多","文本编辑","Emoji","键盘模式","收起输入法"};String[] commands={"more","edit","emoji","mode","hide"};
        int[] ids={R.id.toolbar_more,R.id.toolbar_edit,R.id.toolbar_emoji,R.id.toolbar_mode,R.id.toolbar_hide};
        for(int i=0;i<names.length;i++){
            ImageButton button=new ImageButton(context);button.setId(ids[i]);button.setContentDescription(names[i]);button.setBackground(new RippleDrawable(ColorStateList.valueOf((colors.accent&0x00ffffff)|0x24000000),null,null));button.setImageDrawable(new NavIcon(i,colors.secondary,dp(23)));button.setPadding(dp(10),dp(8),dp(10),dp(8));
            String command=commands[i];button.setOnClickListener(v->action.accept(owner().equals(command)?"keyboard":command));toolbar.addView(button,new LinearLayout.LayoutParams(0,dp(48),1));
        }
    }
    private int dp(float n){return (int)(n*context.getResources().getDisplayMetrics().density+0.5f);}
    String active(){return active;}
    String owner(){if(active.equals("clipboard"))return "edit";if(active.equals("languages")||active.equals("height")||active.equals("style")||active.equals("detail"))return "more";return active;}
    boolean isOpen(){return !active.isEmpty();}
    void onPanelChanged(Runnable callback){panelChanged=callback;}
    void refresh(){colors=new Palette(prefs.dark(context));toolbar.setBackgroundColor(colors.background);String[] commands={"more","edit","emoji","mode","hide"};for(int i=0;i<toolbar.getChildCount();i++){ImageButton button=(ImageButton)toolbar.getChildAt(i);boolean selected=owner().equals(commands[i]);button.setSelected(selected);button.setImageDrawable(new NavIcon(i,selected?colors.accent:colors.secondary,dp(23)));}body.setBackgroundColor(colors.background);body.requestLayout();panelChanged.run();}
    void close(){cancelTouch();if(active.isEmpty()){keyboard.setVisibility(View.VISIBLE);return;}snapshot();active="";detailTranslation="";removePanel();keyboard.setVisibility(View.VISIBLE);refresh();animateIncoming(keyboard);}
    private void removePanel(){cancelTouch();if(body.getChildCount()>1)body.removeViewAt(1);editDelete=null;editUndo=null;editTranslate=null;editSelect=null;editStatus=null;}
    void showCandidates(View grid){snapshot();removePanel();active="candidates";keyboard.setVisibility(View.INVISIBLE);if(grid.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)grid.getParent()).removeView(grid);body.addView(grid,new FrameLayout.LayoutParams(-1,-1));refresh();animateIncoming(grid);}
    private void snapshot(){
        if(outgoing!=null)outgoing.cancel();if(transitionImage!=null)body.getOverlay().remove(transitionImage);if(transitionBitmap!=null)transitionBitmap.recycle();transitionImage=null;transitionBitmap=null;
        if(body.getWidth()==0||body.getHeight()==0||!body.isAttachedToWindow()||!ValueAnimator.areAnimatorsEnabled())return;
        try{transitionBitmap=Bitmap.createBitmap(body.getWidth(),body.getHeight(),Bitmap.Config.ARGB_8888);body.draw(new Canvas(transitionBitmap));transitionImage=new BitmapDrawable(context.getResources(),transitionBitmap);transitionImage.setBounds(0,0,body.getWidth(),body.getHeight());body.getOverlay().add(transitionImage);}catch(OutOfMemoryError ignored){transitionImage=null;if(transitionBitmap!=null)transitionBitmap.recycle();transitionBitmap=null;}
    }
    private void animateIncoming(View view){
        if(!ValueAnimator.areAnimatorsEnabled()){view.animate().cancel();view.setAlpha(1f);view.setTranslationY(0);return;}
        view.animate().cancel();view.setAlpha(.82f);view.setTranslationY(dp(3));view.animate().alpha(1f).translationY(0).setDuration(140).setInterpolator(new DecelerateInterpolator()).start();
        if(transitionImage==null)return;Drawable image=transitionImage;Bitmap bitmap=transitionBitmap;outgoing=ValueAnimator.ofInt(255,0);outgoing.setDuration(140);outgoing.addUpdateListener(animation->{image.setAlpha((int)animation.getAnimatedValue());if(animation.getAnimatedFraction()==1f){body.getOverlay().remove(image);if(transitionImage==image){transitionImage=null;transitionBitmap=null;}bitmap.recycle();}});outgoing.start();
    }
    private LinearLayout begin(String name,String title){
        snapshot();removePanel();active=name;detailTranslation="";keyboard.setVisibility(View.INVISIBLE);refresh();
        LinearLayout panel=new LinearLayout(context);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(10),dp(4),dp(10),dp(6));body.addView(panel,new FrameLayout.LayoutParams(-1,-1));
        TextView heading=text(title,16,colors.text);heading.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);heading.setPadding(dp(10),0,0,0);panel.addView(heading,new LinearLayout.LayoutParams(-1,dp(32)));animateIncoming(panel);return panel;
    }
    private TextView text(String value,int size,int color){TextView v=new TextView(context);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private Button button(String title,Runnable click){
        Button b=new Button(context);b.setText(title);b.setTextSize(13);b.setAllCaps(false);b.setTextColor(colors.text);b.setContentDescription(title);GradientDrawable background=new GradientDrawable();background.setColor(colors.key);background.setCornerRadius(dp(10));b.setBackground(new RippleDrawable(ColorStateList.valueOf((colors.accent&0x00ffffff)|0x24000000),background,null));b.setPadding(dp(5),0,dp(5),0);b.setOnClickListener(v->click.run());return b;
    }
    private void row(LinearLayout parent,String[] names,String[] commands){
        LinearLayout row=new LinearLayout(context);for(int i=0;i<names.length;i++){String cmd=commands[i];Button b=button(names[i],()->action.accept(cmd));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,0,1);lp.height=-1;lp.setMargins(dp(3),dp(3),dp(3),dp(3));row.addView(b,lp);}parent.addView(row,new LinearLayout.LayoutParams(-1,0,1));
    }
    private LinearLayout scrolling(LinearLayout panel){ScrollView scroll=new ScrollView(context);scroll.setFillViewport(false);LinearLayout list=new LinearLayout(context);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));return list;}
    private void listAction(LinearLayout list,String title,String command){
        Button b=button(title,()->action.accept(command));b.setTextSize(14);b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);b.setPadding(dp(12),0,dp(10),0);b.setBackground(new RippleDrawable(ColorStateList.valueOf((colors.accent&0x00ffffff)|0x18000000),null,null));b.setCompoundDrawablesWithIntrinsicBounds(null,null,new NavIcon(5,colors.secondary,dp(18)),null);list.addView(b,new LinearLayout.LayoutParams(-1,dp(48)));
    }
    void more(){
        LinearLayout list=scrolling(begin("more","更多"));listAction(list,"释义显示语言 · "+languageName(),"languages");listAction(list,"键盘高度","height");listAction(list,"键盘风格","style");listAction(list,"打字震动 · "+(prefs.haptic()?"开":"关"),"haptic");
        View separation=new View(context);list.addView(separation,new LinearLayout.LayoutParams(-1,dp(8)));listAction(list,"设置","settings");listAction(list,"检查更新","check_updates");listAction(list,"项目主页","project_home");
    }
    String languageName(){return TranslationRepository.languageName(prefs.glossLanguage());}
    void languages(String status){
        LinearLayout list=scrolling(begin("languages","释义显示语言"));String[] languages=TranslationRepository.glossLanguages();
        for(int i=0;i<languages.length;i+=2){LinearLayout row=new LinearLayout(context);for(int j=i;j<Math.min(i+2,languages.length);j++){String code=languages[j];Button b=button(TranslationRepository.languageName(code),()->action.accept("language_"+code));boolean selected=code.equals(prefs.glossLanguage());b.setSelected(selected);b.setTextColor(selected?colors.accent:colors.text);GradientDrawable fill=new GradientDrawable();fill.setColor(selected?colors.function:colors.key);fill.setCornerRadius(dp(10));b.setBackground(new RippleDrawable(ColorStateList.valueOf((colors.accent&0x00ffffff)|0x24000000),fill,null));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(48),1);lp.setMargins(dp(3),dp(3),dp(3),dp(3));row.addView(b,lp);}list.addView(row);}
        list.addView(text("键盘显示本地释义；长按或上滑查看完整翻译。",12,colors.secondary));listAction(list,prefs.translation()?"隐藏释义":"显示释义","toggle_gloss");listAction(list,"翻译模型管理","model_manager");modelStatus=text(status,12,colors.secondary);modelStatus.setPadding(dp(12),dp(8),dp(12),dp(8));list.addView(modelStatus,new LinearLayout.LayoutParams(-1,-2));
    }
    void modelStatus(String value){if(modelStatus!=null&&active.equals("languages"))modelStatus.setText(value);}
    void height(){
        LinearLayout p=begin("height","键盘高度");TextView value=text("左右滑动调节 · "+Math.round(prefs.height()*100)+"%",13,colors.secondary);p.addView(value,new LinearLayout.LayoutParams(-1,dp(48)));
        SeekBar seek=new SeekBar(context);seek.setId(R.id.height_slider);seek.setContentDescription("键盘高度");seek.setMax(1000);seek.setProgress(Math.round((prefs.height()-.78f)/.46f*1000));p.addView(seek,new LinearLayout.LayoutParams(-1,dp(54)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar v){}public void onStopTrackingTouch(SeekBar v){action.accept("height_changed");}public void onProgressChanged(SeekBar v,int n,boolean user){if(!user)return;prefs.store.edit().putFloat("height",.78f+.46f*n/1000f).apply();value.setText("左右滑动调节 · "+Math.round(prefs.height()*100)+"%");keyboard.requestLayout();body.requestLayout();}});
        TextView note=text("紧凑 78%                                      宽松 124%",11,colors.secondary);p.addView(note,new LinearLayout.LayoutParams(-1,dp(32)));
    }
    void styles(){LinearLayout p=begin("style","键盘风格");row(p,new String[]{"柔和圆角","清简平面"},new String[]{"style_classic","style_flat"});p.addView(text("保持墨绿色，仅调整键帽与间距",12,colors.secondary),new LinearLayout.LayoutParams(-1,dp(38)));}
    void modes(){LinearLayout p=begin("mode","键盘模式");row(p,new String[]{"中文 · 全键拼音","中文 · 九键拼音"},new String[]{"mode_full","mode_t9"});row(p,new String[]{"English"},new String[]{"mode_english"});}
    void edit(boolean secure,boolean selecting){
        editSecure=secure;
        if(active.equals("edit")&&editSelect!=null){setEditSelecting(selecting);updateEditState();return;}
        LinearLayout p=begin("edit","文本编辑");
        editStatus=text("",11,colors.secondary);editStatus.setId(R.id.edit_status);editStatus.setPadding(dp(5),0,dp(5),0);editStatus.setSingleLine(true);editStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);editStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);p.addView(editStatus,new LinearLayout.LayoutParams(-1,dp(24)));
        ScrollView scroll=new ScrollView(context);scroll.setId(R.id.edit_actions);scroll.setFillViewport(true);LinearLayout grid=new LinearLayout(context);grid.setOrientation(LinearLayout.VERTICAL);scroll.addView(grid,new ScrollView.LayoutParams(-1,-2));p.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        editRow(grid,new String[]{"←","→","行首","行尾"},new String[]{"left","right","home","end"},new int[]{R.id.edit_left,R.id.edit_right,R.id.edit_home,R.id.edit_end});
        editRow(grid,new String[]{"选择","全选","复制","剪切"},new String[]{"select","select_all","copy","cut"},new int[]{R.id.edit_select,R.id.edit_select_all,R.id.edit_copy,R.id.edit_cut});
        editRow(grid,new String[]{"粘贴","剪贴板","撤回","翻译","退格"},new String[]{"paste","clipboard","undo","edit_translate","DELETE"},new int[]{R.id.edit_paste,R.id.edit_clipboard,R.id.edit_undo,R.id.edit_translate,R.id.edit_delete});
        editDelete=p.findViewById(R.id.edit_delete);editUndo=p.findViewById(R.id.edit_undo);editTranslate=p.findViewById(R.id.edit_translate);editSelect=p.findViewById(R.id.edit_select);editDelete.setOnTouchListener(this::deleteTouch);setEditSelecting(selecting);updateEditState();
    }
    private void editRow(LinearLayout parent,String[] names,String[] commands,int[] ids){
        LinearLayout row=new LinearLayout(context);row.setMinimumHeight(dp(54));
        for(int i=0;i<names.length;i++){
            String command=commands[i];Button b=button(names[i],()->{if(!command.equals("DELETE")||!deleteTouchClick)action.accept(command);});b.setId(ids[i]);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setMaxLines(2);
            if(command.equals("left"))b.setContentDescription("向左移动光标");else if(command.equals("right"))b.setContentDescription("向右移动光标");
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);lp.setMargins(dp(3),dp(3),dp(3),dp(3));row.addView(b,lp);
        }
        parent.addView(row,new LinearLayout.LayoutParams(-1,0,1));
    }
    private void setEditSelecting(boolean selecting){editSelect.setText(selecting?"结束选择":"选择");editSelect.setContentDescription(editSelect.getText());editSelect.setSelected(selecting);editSelect.setTextColor(selecting?colors.accent:colors.text);}
    void editState(boolean undoAvailable,boolean busy,String status){canUndo=undoAvailable;translateBusy=busy;editMessage=status==null||status.isEmpty()?"撤回仅作用于当前输入框":status;if(active.equals("edit"))updateEditState();}
    private void updateEditState(){
        if(editUndo==null)return;
        for(int id:new int[]{R.id.edit_select_all,R.id.edit_copy,R.id.edit_cut,R.id.edit_paste,R.id.edit_clipboard})enabled(body.findViewById(id),!editSecure);
        enabled(editUndo,canUndo&&!editSecure);enabled(editTranslate,!translateBusy&&!editSecure);editTranslate.setText(translateBusy?"翻译中":"翻译");editTranslate.setContentDescription(translateBusy?"正在翻译，原文保持不变":"翻译当前文字");
        String status=editSecure?"密码内容不记录、不翻译":translateBusy?"正在翻译，原文保持不变":editMessage;editStatus.setText(status);editStatus.setContentDescription(status);
    }
    private void enabled(View view,boolean enabled){view.setEnabled(enabled);view.setAlpha(enabled?1f:.42f);}
    private boolean deleteTouch(View view,MotionEvent event){
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                cancelTouch();if(!view.isEnabled()||!active.equals("edit"))return true;pressedDelete=(Button)view;view.setPressed(true);if(prefs.haptic())view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);action.accept("DELETE");if(pressedDelete!=null&&active.equals("edit"))touchTimer.postDelayed(deleteRepeat,380);return true;
            case MotionEvent.ACTION_MOVE:
                if(event.getX()<0||event.getX()>=view.getWidth()||event.getY()<0||event.getY()>=view.getHeight())cancelTouch();return true;
            case MotionEvent.ACTION_UP:
                boolean clicked=pressedDelete==view;cancelTouch();if(clicked){deleteTouchClick=true;try{view.performClick();}finally{deleteTouchClick=false;}}return true;
            case MotionEvent.ACTION_CANCEL:case MotionEvent.ACTION_POINTER_DOWN:cancelTouch();return true;
            default:return true;
        }
    }
    void cancelTouch(){touchTimer.removeCallbacks(deleteRepeat);if(pressedDelete!=null)pressedDelete.setPressed(false);pressedDelete=null;}
    void clipboard(List<String> entries,Consumer<String> paste,Consumer<String> remove){
        LinearLayout p=begin("clipboard","剪贴板 · "+entries.size()+" / 100");Button clear=button("清空历史",()->action.accept("clear_clipboard"));p.addView(clear,new LinearLayout.LayoutParams(-1,dp(32)));
        ScrollView scroll=new ScrollView(context);LinearLayout list=new LinearLayout(context);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);p.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(entries.isEmpty())list.addView(text("复制或剪切后，最近内容会保存在这里。\n内容仅在本机保存，长按条目可删除。",12,colors.secondary));
        for(String entry:entries){String label=entry.length()>100?entry.substring(0,100)+"…":entry;Button b=button(label,()->paste.accept(entry));b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);b.setMaxLines(2);b.setPadding(dp(12),0,dp(12),0);b.setOnLongClickListener(v->{remove.accept(entry);return true;});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(48));lp.topMargin=dp(4);list.addView(b,lp);}
    }
    void emoji(){showEmoji("常用");}
    private void showEmoji(String category){
        LinearLayout p=begin("emoji","Emoji");LinearLayout tabs=new LinearLayout(context);for(String name:new String[]{"常用","表情","自然","生活"}){Button b=button(name,()->showEmoji(name));tabs.addView(b,new LinearLayout.LayoutParams(0,dp(30),1));}p.addView(tabs);
        String source=category.equals("表情")?"😀 😃 😄 😁 😆 😅 😂 🤣 😊 😇 🙂 🙃 😉 😌 😍 🥰 😘 😋 😎 🤩 🥳 😏 😒 😞 😔 😢 😭 😤 😠 🤔 🫡 🤫 🤗 🥺 😴 🤤 😷 🤒 🤕 😵":category.equals("自然")?"🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🦋 🐝 🐢 🐬 🐳 🦄 🌸 🌷 🌹 🌻 🌿 🍀 🌱 🌳 🌲 ☀️ 🌤️ 🌧️ ⛈️ 🌈 ⭐ 🌙 🔥 💧 ❄️":category.equals("生活")?"🍎 🍊 🍋 🍌 🍉 🍇 🍓 🍒 🥑 🍔 🍕 🍜 🍚 🍣 🍰 ☕ 🍵 🥤 🚗 🚕 🚌 🚲 🚄 ✈️ 🚀 🏠 🏢 🎉 🎁 🎈 🎂 ⚽ 🏀 🎮 🎵 📚 💻 📱 💡 ⏰":"😊 😂 🥰 😍 😎 🤔 😭 🥳 👍 👎 👏 🙌 🤝 🙏 💪 👌 ✌️ 🤞 ❤️ 💚 💙 💛 💜 💖 💯 ✅ ❌ ⭐ 🔥 🎉 🌹 ☀️ ☕ 🍀 🫶 🤗 😅 😴 👀 💐";
        ScrollView scroll=new ScrollView(context);LinearLayout grid=new LinearLayout(context);grid.setOrientation(LinearLayout.VERTICAL);scroll.addView(grid);p.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));String[] emojis=source.split(" ");
        for(int i=0;i<emojis.length;i+=8){LinearLayout row=new LinearLayout(context);for(int j=i;j<Math.min(i+8,emojis.length);j++){String emoji=emojis[j];Button b=button(emoji,()->action.accept("emoji_"+emoji));b.setTextSize(23);row.addView(b,new LinearLayout.LayoutParams(0,dp(43),1));}grid.addView(row);}
    }
    void detail(String source,String subtitle){
        LinearLayout p=begin("detail","释义");ScrollView scroll=new ScrollView(context);scroll.setFillViewport(false);LinearLayout reading=new LinearLayout(context);reading.setOrientation(LinearLayout.VERTICAL);reading.setPadding(dp(8),dp(6),dp(8),dp(12));
        detailSource=text(source,18,colors.text);detailSource.setId(R.id.translation_source);detailSource.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);detailSource.setTextIsSelectable(true);reading.addView(detailSource,new LinearLayout.LayoutParams(-1,-2));
        detailText=text(subtitle,20,colors.accent);detailText.setId(R.id.translation_detail);detailText.setTextIsSelectable(true);detailText.setPadding(0,dp(6),0,0);reading.addView(detailText,new LinearLayout.LayoutParams(-1,-2));
        detailBadge=detailBadge(reading,"powered by Google Translate");detailBadge.setId(R.id.translation_attribution);
        detailMeta=text("",12,colors.secondary);detailMeta.setId(R.id.translation_meta);detailMeta.setPadding(0,dp(8),0,dp(6));reading.addView(detailMeta,new LinearLayout.LayoutParams(-1,-2));
        detailNote=text("",11,colors.secondary);detailNote.setId(R.id.translation_note);detailNote.setPadding(0,0,0,dp(8));reading.addView(detailNote,new LinearLayout.LayoutParams(-1,-2));
        detailExplanation=text("",14,colors.text);detailExplanation.setId(R.id.translation_explanation);detailExplanation.setTextIsSelectable(true);detailExplanation.setLineSpacing(dp(3),1f);reading.addView(detailExplanation,new LinearLayout.LayoutParams(-1,-2));
        detailExampleHeading=text("例句",12,colors.secondary);detailExampleHeading.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);detailExampleHeading.setPadding(0,dp(16),0,dp(5));reading.addView(detailExampleHeading,new LinearLayout.LayoutParams(-1,-2));
        detailExample=text("",14,colors.text);detailExample.setId(R.id.translation_example);detailExample.setTextIsSelectable(true);detailExample.setLineSpacing(dp(3),1f);reading.addView(detailExample,new LinearLayout.LayoutParams(-1,-2));
        detailExampleTranslation=text("",13,colors.secondary);detailExampleTranslation.setId(R.id.translation_example_translation);detailExampleTranslation.setTextIsSelectable(true);detailExampleTranslation.setPadding(0,dp(5),0,0);reading.addView(detailExampleTranslation,new LinearLayout.LayoutParams(-1,-2));
        detailExampleBadge=detailBadge(reading,"例句，powered by Google Translate");detailExampleBadge.setId(R.id.translation_example_attribution);
        for(View hidden:new View[]{detailMeta,detailNote,detailExplanation,detailExampleHeading,detailExample,detailExampleTranslation})hidden.setVisibility(View.GONE);
        scroll.addView(reading);p.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout row=new LinearLayout(context);row.setPadding(0,dp(6),0,0);detailCommit=button("输入译文",()->action.accept("commit_translation"));detailCommit.setId(R.id.translation_commit);detailCommit.setTextColor(colors.accentText);GradientDrawable primary=new GradientDrawable();primary.setColor(colors.accent);primary.setCornerRadius(dp(10));detailCommit.setBackground(new RippleDrawable(ColorStateList.valueOf((colors.accentText&0x00ffffff)|0x24000000),primary,null));
        detailCopy=button("复制译文",()->{if(!detailTranslation.isEmpty())action.accept("copy_translation");});detailCopy.setId(R.id.translation_copy);LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,dp(48),1);left.rightMargin=dp(4);row.addView(detailCommit,left);LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,dp(48),1);right.leftMargin=dp(4);row.addView(detailCopy,right);p.addView(row);detailActions(false);
    }
    private ImageView detailBadge(LinearLayout parent,String description){ImageView image=new ImageView(context);image.setContentDescription(description);image.setScaleType(ImageView.ScaleType.FIT_START);image.setImageResource(prefs.dark(context)?R.drawable.google_translate_badge_dark:R.drawable.google_translate_badge);image.setVisibility(View.GONE);LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(dp(176),dp(16));layout.topMargin=dp(4);layout.bottomMargin=dp(4);parent.addView(image,layout);return image;}
    private void detailActions(boolean enabled){detailCommit.setEnabled(enabled);detailCopy.setEnabled(enabled);detailCommit.setAlpha(enabled?1f:.45f);detailCopy.setAlpha(enabled?1f:.45f);}
    void detailResult(String source,String translation,String note){
        detailResult(source,translation,note,"","",prefs.glossLanguage(),"","","");
    }
    void detailResult(String source,String translation,String note,String explanation,String pos,String target,String example,String exampleTranslation){detailResult(source,translation,note,explanation,pos,target,example,exampleTranslation,"");}
    void detailResult(String source,String translation,String note,String explanation,String pos,String target,String example,String exampleTranslation,String exampleNote){
        if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()){body.post(()->detailResult(source,translation,note,explanation,pos,target,example,exampleTranslation,exampleNote));return;}
        if(!active.equals("detail"))return;detailTranslation=translation==null?"":translation;detailSource.setText(source);detailText.setText(detailTranslation.isEmpty()?note:detailTranslation);detailText.setTextColor(detailTranslation.isEmpty()?colors.secondary:colors.accent);
        boolean google=!detailTranslation.isEmpty()&&note!=null&&note.startsWith("Google Translate");detailBadge.setImageResource(prefs.dark(context)?R.drawable.google_translate_badge_dark:R.drawable.google_translate_badge);detailBadge.setVisibility(google?View.VISIBLE:View.GONE);
        String role=partOfSpeech(pos),language=TranslationRepository.languageName(target),metadata=(role.isEmpty()?"":role+" · ")+language;
        detailMeta.setText(metadata);detailMeta.setVisibility(View.VISIBLE);detailNote.setText(note);detailNote.setVisibility(note!=null&&!note.isEmpty()&&!detailTranslation.isEmpty()?View.VISIBLE:View.GONE);detailExplanation.setText(explanation);detailExplanation.setVisibility(explanation==null||explanation.isEmpty()?View.GONE:View.VISIBLE);
        boolean realExample=example!=null&&!example.isEmpty();detailExampleHeading.setVisibility(realExample?View.VISIBLE:View.GONE);detailExample.setText(example);detailExample.setVisibility(realExample?View.VISIBLE:View.GONE);detailExampleTranslation.setText(exampleTranslation);detailExampleTranslation.setVisibility(realExample&&exampleTranslation!=null&&!exampleTranslation.isEmpty()?View.VISIBLE:View.GONE);
        boolean modelExample=realExample&&exampleTranslation!=null&&!exampleTranslation.isEmpty()&&exampleNote!=null&&exampleNote.startsWith("Google Translate");detailExampleBadge.setImageResource(prefs.dark(context)?R.drawable.google_translate_badge_dark:R.drawable.google_translate_badge);detailExampleBadge.setVisibility(modelExample?View.VISIBLE:View.GONE);detailCommit.setText(google?"Translate with Google":"输入译文");detailCommit.setContentDescription(detailCommit.getText());detailActions(!detailTranslation.isEmpty());
    }
    private String partOfSpeech(String tag){if(tag==null||tag.isEmpty())return "";switch(tag.charAt(0)){case 'n':return "名词";case 'v':return "动词";case 'a':return "形容词";case 'd':return "副词";case 'r':return "代词";case 'p':return "介词";case 'c':return "连词";case 'u':return "助词";case 'm':return "数词";case 'q':return "量词";case 'e':return "叹词";case 'y':return "语气词";default:return tag;}}
    private static final class NavIcon extends Drawable {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final int kind,size;
        NavIcon(int kind,int color,int size){this.kind=kind;this.size=size;paint.setColor(color);paint.setStrokeWidth(1.65f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStyle(Paint.Style.STROKE);}
        @Override public void draw(Canvas canvas){canvas.save();canvas.translate(getBounds().left,getBounds().top);canvas.scale(getBounds().width()/24f,getBounds().height()/24f);
            if(kind==0){paint.setStyle(Paint.Style.FILL);for(int y=6;y<=18;y+=6)for(int x=6;x<=18;x+=6)canvas.drawCircle(x,y,1.35f,paint);paint.setStyle(Paint.Style.STROKE);}
            if(kind==1){canvas.drawLine(7,4,17,4,paint);canvas.drawLine(7,20,17,20,paint);canvas.drawLine(12,4,12,20,paint);canvas.drawLine(4,9,4,15,paint);canvas.drawLine(20,9,20,15,paint);}
            if(kind==2){canvas.drawCircle(12,12,9,paint);paint.setStyle(Paint.Style.FILL);canvas.drawCircle(9,9,1,paint);canvas.drawCircle(15,9,1,paint);paint.setStyle(Paint.Style.STROKE);canvas.drawArc(7,9,17,17,25,130,false,paint);}
            if(kind==3){canvas.drawRoundRect(2,5,22,19,3,3,paint);for(int y=9;y<=12;y+=3)for(int x=6;x<=18;x+=4)canvas.drawPoint(x,y,paint);canvas.drawLine(7,16,17,16,paint);}
            if(kind==4){canvas.drawLine(5,9,12,16,paint);canvas.drawLine(12,16,19,9,paint);}
            if(kind==5){canvas.drawLine(9,6,15,12,paint);canvas.drawLine(15,12,9,18,paint);}
            canvas.restore();}
        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);}@Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}@Override public int getIntrinsicWidth(){return size;}@Override public int getIntrinsicHeight(){return size;}
    }
}
