package com.qingyu.ime;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Checks the edit controls with real touch events while attached to an Android window. */
final class EditPanelCheck {
    static void run(Instrumentation instrumentation,Activity activity) throws Exception {
        ImePanels[] holder=new ImePanels[1];ViewGroup[] parent=new ViewGroup[1];List<String> actions=new ArrayList<>();
        instrumentation.runOnMainSync(()->{
            ImePreferences prefs=new ImePreferences(instrumentation.getTargetContext());float density=activity.getResources().getDisplayMetrics().density;
            View keyboard=new View(activity){@Override protected void onMeasure(int width,int height){setMeasuredDimension(MeasureSpec.getSize(width),resolveSize(Math.round(252*density),height));}};
            holder[0]=new ImePanels(activity,prefs,keyboard,actions::add);parent[0]=activity.findViewById(android.R.id.content);parent[0].addView(holder[0].body,new ViewGroup.LayoutParams(Math.round(360*density),Math.round(252*density)));holder[0].edit(false,false);
        });
        instrumentation.waitForIdleSync();ImePanels panels=holder[0];
        try {
            instrumentation.runOnMainSync(()->{
                require(!panels.body.findViewById(R.id.edit_undo).isEnabled(),"Empty edit history left Undo enabled");
                View delete=panels.body.findViewById(R.id.edit_delete),translate=panels.body.findViewById(R.id.edit_translate),status=panels.body.findViewById(R.id.edit_status),selection=panels.body.findViewById(R.id.edit_select);
                panels.editState(true,true,"");require(panels.body.findViewById(R.id.edit_undo).isEnabled()&&!translate.isEnabled(),"Translation disabled Undo or left duplicate translation enabled");
                require(((TextView)status).getText().toString().equals("正在翻译，原文保持不变"),"Translation loading state is missing");
                panels.editState(true,false,"翻译失败，请重试");panels.edit(false,true);
                require(delete==panels.body.findViewById(R.id.edit_delete)&&translate==panels.body.findViewById(R.id.edit_translate)&&status==panels.body.findViewById(R.id.edit_status)&&selection==panels.body.findViewById(R.id.edit_select),"Edit-state updates rebuilt the controls");
                require(((Button)selection).getText().toString().equals("结束选择"),"Selection state failed to update in place");
                require(((TextView)status).getText().toString().equals("翻译失败，请重试"),"Translation failure disappeared on selection change");
                panels.body.findViewById(R.id.edit_undo).performClick();translate.performClick();delete.performClick();
                require(actions.toString().equals("[undo, edit_translate, DELETE]"),"Edit actions changed their service contract");actions.clear();
                float dp=panels.body.getResources().getDisplayMetrics().density;
                for(int width:new int[]{320,480})for(int height:new int[]{252,112,90}){
                    panels.body.measure(View.MeasureSpec.makeMeasureSpec(Math.round(width*dp),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(Math.round(height*dp),View.MeasureSpec.AT_MOST));panels.body.layout(0,0,panels.body.getMeasuredWidth(),panels.body.getMeasuredHeight());
                    require(panels.body.getHeight()<=Math.round(height*dp),"Edit panel escaped the keyboard's height");
                    View scroll=panels.body.findViewById(R.id.edit_actions);require(scroll.getBottom()<=panels.body.getHeight(),"Edit tools overflowed the fixed panel");
                    for(int id:new int[]{R.id.edit_left,R.id.edit_select,R.id.edit_clipboard,R.id.edit_undo,R.id.edit_translate,R.id.edit_delete})require(panels.body.findViewById(id).getMeasuredHeight()>=Math.round(48*dp),"An edit control lost its touch target in a short keyboard");
                }
            });
            instrumentation.runOnMainSync(()->{float dp=panels.body.getResources().getDisplayMetrics().density;panels.body.measure(View.MeasureSpec.makeMeasureSpec(Math.round(360*dp),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(Math.round(252*dp),View.MeasureSpec.EXACTLY));panels.body.layout(0,0,panels.body.getMeasuredWidth(),panels.body.getMeasuredHeight());});
            View delete=panels.body.findViewById(R.id.edit_delete);
            event(instrumentation,delete,MotionEvent.ACTION_DOWN,delete.getWidth()/2f,delete.getHeight()/2f);SystemClock.sleep(500);event(instrumentation,delete,MotionEvent.ACTION_UP,delete.getWidth()/2f,delete.getHeight()/2f);
            instrumentation.runOnMainSync(()->{require(actions.size()>=2,"Edit Delete did not repeat while held");for(String action:actions)require(action.equals("DELETE"),"Holding edit Delete emitted another action");actions.clear();});SystemClock.sleep(110);instrumentation.runOnMainSync(()->require(actions.isEmpty(),"Edit Delete continued after release"));
            stopped(instrumentation,delete,actions,()->event(instrumentation,delete,MotionEvent.ACTION_CANCEL,0,0));
            stopped(instrumentation,delete,actions,()->event(instrumentation,delete,MotionEvent.ACTION_MOVE,-1,0));
            stopped(instrumentation,delete,actions,()->instrumentation.runOnMainSync(panels::cancelTouch));
            stopped(instrumentation,delete,actions,()->instrumentation.runOnMainSync(()->panels.body.setVisibility(View.INVISIBLE)));instrumentation.runOnMainSync(()->panels.body.setVisibility(View.VISIBLE));
            stopped(instrumentation,delete,actions,()->instrumentation.runOnMainSync(panels::more));
            instrumentation.runOnMainSync(()->{
                panels.edit(true,false);panels.editState(true,false,"");
                for(int id:new int[]{R.id.edit_select_all,R.id.edit_copy,R.id.edit_cut,R.id.edit_paste,R.id.edit_clipboard,R.id.edit_undo,R.id.edit_translate})require(!panels.body.findViewById(id).isEnabled(),"Password field exposed a sensitive edit action");
                require(((TextView)panels.body.findViewById(R.id.edit_status)).getText().toString().equals("密码内容不记录、不翻译"),"Password edit status describes unavailable selection controls incorrectly");
                require(panels.body.findViewById(R.id.edit_delete).isEnabled(),"Password field lost Delete");
                panels.toolbar.findViewById(R.id.toolbar_edit).performClick();require(actions.get(actions.size()-1).equals("keyboard"),"Edit panel cannot toggle through its navigation icon");actions.clear();
            });
            instrumentation.waitForIdleSync();
            View secureDelete=panels.body.findViewById(R.id.edit_delete);
            stopped(instrumentation,secureDelete,actions,()->instrumentation.runOnMainSync(()->parent[0].removeView(panels.body)));
        } finally {instrumentation.runOnMainSync(()->{panels.cancelTouch();panels.close();if(panels.body.getParent()==parent[0])parent[0].removeView(panels.body);});}
    }
    private static void stopped(Instrumentation instrumentation,View delete,List<String> actions,CheckedAction stop) throws Exception {
        event(instrumentation,delete,MotionEvent.ACTION_DOWN,delete.getWidth()/2f,delete.getHeight()/2f);instrumentation.runOnMainSync(()->require(actions.toString().equals("[DELETE]"),"Edit Delete did not fire immediately on press"));stop.run();instrumentation.runOnMainSync(actions::clear);SystemClock.sleep(470);instrumentation.runOnMainSync(()->require(actions.isEmpty(),"Edit Delete repeated after cancellation, hiding or navigation"));
    }
    private static void event(Instrumentation instrumentation,View view,int action,float x,float y){instrumentation.runOnMainSync(()->{long now=SystemClock.uptimeMillis();MotionEvent event=MotionEvent.obtain(now,now,action,x,y,0);view.dispatchTouchEvent(event);event.recycle();});}
    private interface CheckedAction{void run() throws Exception;}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
