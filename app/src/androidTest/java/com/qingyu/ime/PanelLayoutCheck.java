package com.qingyu.ime;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;

/** Small main-thread check for the shared body: tools never change the keyboard's footprint. */
final class PanelLayoutCheck {
    static void run(Context context){
        ImePreferences prefs=new ImePreferences(context);boolean stored=prefs.store.contains("height");float previous=prefs.height();
        View keyboard=new View(context){@Override protected void onMeasure(int width,int height){int desired=(int)((244*prefs.height()+8)*context.getResources().getDisplayMetrics().density);setMeasuredDimension(MeasureSpec.getSize(width),resolveSize(desired,height));}};
        ArrayList<String> actions=new ArrayList<>();ImePanels panels=new ImePanels(context,prefs,keyboard,actions::add);panels.body.setLayoutParams(new LinearLayout.LayoutParams(-1,-2));
        try{
            layout(panels.body,4000);int height=panels.body.getMeasuredHeight();
            panels.more();check(panels,keyboard,height,"more");
            panels.languages("模型未下载");check(panels,keyboard,height,"more");
            panels.toolbar.findViewById(R.id.toolbar_more).performClick();require(actions.get(actions.size()-1).equals("keyboard"),"Language subpanel did not toggle via More");
            panels.height();check(panels,keyboard,height,"more");View slider=panels.body.findViewById(R.id.height_slider);prefs.store.edit().putFloat("height",.78f).apply();layout(panels.body,4000);require(panels.body.findViewById(R.id.height_slider)==slider,"Height adjustment replaced the active slider");require(panels.body.getMeasuredHeight()==keyboard.getMeasuredHeight(),"Height panel diverged from keyboard measurement");
            prefs.store.edit().putFloat("height",previous).apply();layout(panels.body,4000);height=panels.body.getMeasuredHeight();
            panels.styles();check(panels,keyboard,height,"more");panels.detail("开发","develop");check(panels,keyboard,height,"more");
            String longExample="这是一段用于核对例句完整换行和固定面板滚动的较长文本，所有真实例句都应当可以完整阅读，不应因为键盘高度受到限制而被省略。";
            panels.detailResult("开发","develop","本地短语","to create, improve or make something grow","v","en",longExample,"This example remains fully readable in the same fixed panel even when several lines are needed.","本地词典");check(panels,keyboard,height,"more");
            TextView example=panels.body.findViewById(R.id.translation_example);require(example.getText().toString().equals(longExample)&&example.getLineCount()>1&&example.getEllipsize()==null,"Details truncated a real example");
            for(int id:new int[]{R.id.translation_commit,R.id.translation_copy})require(panels.body.findViewById(id).getMeasuredHeight()>=Math.round(48*context.getResources().getDisplayMetrics().density),"Translation action is too small to touch");
            panels.detailResult("开发","","等待本地模型下载","","","en","","","");require(!panels.body.findViewById(R.id.translation_commit).isEnabled()&&!panels.body.findViewById(R.id.translation_copy).isEnabled(),"Missing translation leaves active copy/input actions");
            panels.toolbar.findViewById(R.id.toolbar_more).performClick();require(actions.get(actions.size()-1).equals("keyboard"),"Detail did not close through the same More icon");
            panels.edit(false,false);check(panels,keyboard,height,"edit");panels.clipboard(Collections.singletonList("最近内容"),value->{},value->{});check(panels,keyboard,height,"edit");
            panels.toolbar.findViewById(R.id.toolbar_edit).performClick();require(actions.get(actions.size()-1).equals("keyboard"),"Clipboard did not toggle via Edit");
            panels.emoji();check(panels,keyboard,height,"emoji");panels.modes();check(panels,keyboard,height,"mode");
            panels.showCandidates(new TextView(context));check(panels,keyboard,height,"candidates");require(panels.body.getChildCount()==2,"Candidate grid detached the keyboard from its measurement host");
            layout(panels.body,90);require(panels.body.getMeasuredHeight()==90&&keyboard.getMeasuredHeight()==90,"Body ignores the parent window height limit");
            panels.close();layout(panels.body,4000);require(keyboard.getVisibility()==View.VISIBLE&&panels.body.getMeasuredHeight()==height&&!panels.isOpen(),"Closing failed to restore the keyboard footprint");
        }finally{android.content.SharedPreferences.Editor editor=prefs.store.edit();if(stored)editor.putFloat("height",previous);else editor.remove("height");editor.apply();panels.close();}
    }
    private static void check(ImePanels panels,View keyboard,int height,String owner){layout(panels.body,4000);require(panels.body.getMeasuredHeight()==height&&keyboard.getVisibility()==View.INVISIBLE,"Panel changed keyboard footprint");require(panels.owner().equals(owner),"Incorrect navigation owner");require(!containsBack(panels.body),"Panel contains a separate return-to-keyboard button");for(int i=0;i<4;i++)require(panels.toolbar.getChildAt(i).isSelected()==owner.equals(new String[]{"more","edit","emoji","mode"}[i]),"Active navigation icon is not selected");}
    private static boolean containsBack(View view){if(view instanceof TextView&&((TextView)view).getText().toString().contains("返回键盘"))return true;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(containsBack(((ViewGroup)view).getChildAt(i)))return true;return false;}
    private static void layout(View view,int maximum){view.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(maximum,View.MeasureSpec.AT_MOST));view.layout(0,0,view.getMeasuredWidth(),view.getMeasuredHeight());}
    private static void require(boolean condition,String text){if(!condition)throw new AssertionError(text);}
}
