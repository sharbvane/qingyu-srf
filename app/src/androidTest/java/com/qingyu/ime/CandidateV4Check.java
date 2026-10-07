package com.qingyu.ime;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Native gesture and viewport regression check; call from the instrumentation worker. */
final class CandidateV4Check {
    static void run(Instrumentation test,Activity activity) {
        CandidateSurface[] surface={null};int[] actions={0,0,0};ViewGroup[] parent={null};
        CandidateSurface.Listener listener=new CandidateSurface.Listener(){
            public void choose(int index){actions[0]++;}public void detail(int index){actions[1]++;}public void translate(int index){actions[2]++;}
            public void expand(){}public void settings(){}public void toggleTranslation(){}public void punctuation(String text){}
        };
        List<String> words=new ArrayList<>();for(int i=0;i<60;i++)words.add("候选"+i);
        main(test,()->{
            parent[0]=activity.findViewById(android.R.id.content);
            CandidateSurface grid=new CandidateSurface(activity,new ImePreferences(activity),listener);surface[0]=grid;grid.setEmbeddedGrid(true);grid.update("ignored",words,true,"");
            parent[0].addView(grid,new ViewGroup.LayoutParams(720,Math.round(198*activity.getResources().getDisplayMetrics().density)));
        });
        try {
            test.waitForIdleSync();SystemClock.sleep(80);
            CandidateSurface grid=surface[0];List<String> initial=new ArrayList<>(grid.visibleWords());
            main(test,()->check(grid.getWidth()==720&&grid.getHeight()==grid.getLayoutParams().height,"Attached candidate view did not finish its parent layout"));
            int[] flingNode={0},flingTop={0};
            main(test,()->{
                // Sensor timestamps remain regular when Android delivers input in a batch.
                // Separate runOnMainSync calls under load can exceed VelocityTracker's 40ms stop threshold.
                long now=SystemClock.uptimeMillis(),down=now-96;VelocityTracker samples=VelocityTracker.obtain();
                MotionEvent press=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,120,300,0);press.setSource(InputDevice.SOURCE_TOUCHSCREEN);samples.addMovement(press);grid.onTouchEvent(press);press.recycle();
                MotionEvent move=MotionEvent.obtain(down,down+16,MotionEvent.ACTION_MOVE,120,258,0);move.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                for(int step=2;step<=5;step++)move.addBatch(down+step*16,120,300-step*42,1f,1f,0);
                samples.addMovement(move);grid.onTouchEvent(move);move.recycle();
                MotionEvent up=MotionEvent.obtain(down,now,MotionEvent.ACTION_UP,120,90,0);up.setSource(InputDevice.SOURCE_TOUCHSCREEN);samples.addMovement(up);samples.computeCurrentVelocity(1000);
                float velocity=-samples.getYVelocity();samples.recycle();check(velocity>=ViewConfiguration.get(activity).getScaledMinimumFlingVelocity(),"Invalid native fling samples: velocity="+velocity);
                grid.onTouchEvent(up);up.recycle();check(!grid.visibleWords().equals(initial),"Vertical drag did not browse candidates");check(Arrays.equals(actions,new int[]{0,0,0}),"Grid drag selected or translated a candidate");List<String> visible=grid.visibleWords();flingNode[0]=100+words.indexOf(visible.get(Math.min(3,visible.size()-1)));flingTop[0]=bounds(grid,flingNode[0]).top;
            });
            settle(test,grid,350);
            main(test,()->{AccessibilityNodeInfo node=grid.getAccessibilityNodeProvider().createAccessibilityNodeInfo(flingNode[0]);if(node!=null){Rect after=new Rect();node.getBoundsInParent(after);check(after.top<flingTop[0],"Native fling did not continue after release: before="+flingTop[0]+", after="+after.top);}});
            main(test,()->{
                check(grid.getAccessibilityNodeProvider().createAccessibilityNodeInfo(100)==null,"Offscreen candidate still exposes a node");
                check(!grid.getAccessibilityNodeProvider().performAction(100,AccessibilityNodeInfo.ACTION_CLICK,null),"Offscreen candidate could be selected");
                for(String word:grid.visibleWords()){int index=words.indexOf(word);Rect box=bounds(grid,100+index);check(box.top>=0&&box.bottom<=grid.getHeight()&&box.left>=0&&box.right<=grid.getWidth(),"Scrolled virtual node escaped viewport");}
                check(grid.getAccessibilityNodeProvider().performAction(AccessibilityNodeProvider.HOST_VIEW_ID,AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null),"TalkBack forward scroll missing");
            });
            List<String> beforeAccess=new ArrayList<>(grid.visibleWords());settle(test,grid,230);check(!grid.visibleWords().equals(beforeAccess),"Accessible scroll did not change viewport");
            main(test,()->{grid.update("reset",words,true,"");long t=SystemClock.uptimeMillis();event(grid,t,t,MotionEvent.ACTION_DOWN,100,50);event(grid,t,SystemClock.uptimeMillis(),MotionEvent.ACTION_CANCEL,100,50);});
            SystemClock.sleep(650);check(Arrays.equals(actions,new int[]{0,0,0}),"Canceled gesture fired a long press");
            long held=SystemClock.uptimeMillis();main(test,()->event(grid,held,SystemClock.uptimeMillis(),MotionEvent.ACTION_DOWN,100,50));SystemClock.sleep(650);
            main(test,()->{event(grid,held,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,100,50);check(Arrays.equals(actions,new int[]{0,1,0}),"Grid long press did not show detail exactly once");});
            main(test,()->{long t=SystemClock.uptimeMillis();event(grid,t,t,MotionEvent.ACTION_DOWN,100,50);event(grid,t,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,100,50);check(Arrays.equals(actions,new int[]{1,1,0}),"Grid tap did not select exactly once");});
            main(test,()->{grid.update("new",words,true,"");check(grid.visibleWords().equals(initial),"New composition retained an old scroll position");grid.setEmbeddedGrid(false);ViewGroup.LayoutParams params=grid.getLayoutParams();params.height=ViewGroup.LayoutParams.WRAP_CONTENT;grid.setLayoutParams(params);});test.waitForIdleSync();SystemClock.sleep(80);
            main(test,()->{int expected=activity.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE?44:52;check(grid.getHeight()==Math.round(expected*activity.getResources().getDisplayMetrics().density),"Wrong compact idle header height");float y=bounds(grid,100).centerY();long t=SystemClock.uptimeMillis();event(grid,t,t,MotionEvent.ACTION_DOWN,100,y);event(grid,t,SystemClock.uptimeMillis(),MotionEvent.ACTION_MOVE,100,y-80);event(grid,t,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,100,y-80);check(actions[2]==1,"Collapsed header lost upward translation gesture");});
        } finally {main(test,()->parent[0].removeView(surface[0]));}
    }
    private static void settle(Instrumentation test,CandidateSurface grid,int ms){long until=SystemClock.uptimeMillis()+ms;while(SystemClock.uptimeMillis()<until){SystemClock.sleep(16);main(test,grid::computeScroll);}}
    private static void event(CandidateSurface grid,long down,long time,int action,float x,float y){MotionEvent event=MotionEvent.obtain(down,time,action,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);grid.onTouchEvent(event);event.recycle();}
    private static Rect bounds(CandidateSurface grid,int id){AccessibilityNodeInfo node=grid.getAccessibilityNodeProvider().createAccessibilityNodeInfo(id);check(node!=null,"Missing visible candidate "+id);Rect rect=new Rect();node.getBoundsInParent(rect);return rect;}
    private static void main(Instrumentation test,Runnable work){Throwable[] error={null};test.runOnMainSync(()->{try{work.run();}catch(Throwable failure){error[0]=failure;}});if(error[0]!=null)throw new AssertionError(error[0]);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
