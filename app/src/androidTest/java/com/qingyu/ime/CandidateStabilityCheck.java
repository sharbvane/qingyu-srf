package com.qingyu.ime;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.OverScroller;
import com.qingyu.core.Candidate;
import com.qingyu.core.EngineSnapshot;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Pending input must retain the visible row without interrupting its gestures. */
final class CandidateStabilityCheck {
    static void run(Instrumentation test,Activity activity) {
        CandidateSurface[] surface={null};ViewGroup[] parent={null};int[] actions={0,0,0};
        List<String> words=new ArrayList<>(Arrays.asList("开发","项目","设计"));
        for(int i=3;i<60;i++)words.add("候选"+i);
        CandidateSurface.Listener listener=new CandidateSurface.Listener(){
            public void choose(int index){check(index==0,"Refresh selected another candidate");actions[0]++;}
            public void detail(int index){check(index==0,"Refresh opened another candidate");actions[1]++;}
            public void translate(int index){check(index==0,"Refresh translated another candidate");actions[2]++;}
            public void expand(){}public void settings(){}public void toggleTranslation(){}public void punctuation(String text){}
        };
        main(test,()->{
            immediateSnapshot(activity);
            parent[0]=activity.findViewById(android.R.id.content);
            CandidateSurface row=new CandidateSurface(activity,new ImePreferences(activity),listener);surface[0]=row;
            row.update("kaifa",words,true,"");row.glosses(Collections.singletonMap("开发","develop"));
            parent[0].addView(row,new ViewGroup.LayoutParams(Math.round(dp(activity,360)),Math.round(dp(activity,100))));
        });
        try {
            test.waitForIdleSync();CandidateSurface row=surface[0];
            main(test,()->{
                Rect before=bounds(row,100);long now=SystemClock.uptimeMillis();
                event(row,now,now,MotionEvent.ACTION_DOWN,before.centerX(),before.centerY());
                row.update("kaifaz",words,true,"");row.glosses(Collections.singletonMap("开发","development"));
                check(bounds(row,100).equals(before),"Preedit/annotation moved a compact candidate");
                event(row,now,now+20,MotionEvent.ACTION_UP,before.centerX(),before.centerY());
                check(Arrays.equals(actions,new int[]{1,0,0}),"Same-word preedit refresh canceled a tap");
            });
            long held=SystemClock.uptimeMillis();
            main(test,()->{
                Rect box=bounds(row,100);event(row,held,held,MotionEvent.ACTION_DOWN,box.centerX(),box.centerY());
                row.update("kaifazx",words,true,"");
            });
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout()+120L);
            main(test,()->{
                Rect box=bounds(row,100);event(row,held,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,box.centerX(),box.centerY());
                check(Arrays.equals(actions,new int[]{1,1,0}),"Same-word preedit refresh canceled/duplicated long press");
                long now=SystemClock.uptimeMillis();event(row,now,now,MotionEvent.ACTION_DOWN,box.centerX(),box.centerY());
                row.update("kaifazxc",words,true,"");float y=box.centerY()-dp(activity,32);
                event(row,now,now+20,MotionEvent.ACTION_MOVE,box.centerX(),y);
                event(row,now,now+40,MotionEvent.ACTION_UP,box.centerX(),y);
                check(Arrays.equals(actions,new int[]{1,1,1}),"Same-word preedit refresh canceled upward translation");
                now=SystemClock.uptimeMillis();event(row,now,now,MotionEvent.ACTION_DOWN,box.centerX(),box.centerY());
                List<String> replacement=new ArrayList<>(words);replacement.set(0,"你好");row.update("nihao",replacement,true,"");
                event(row,now,now+20,MotionEvent.ACTION_UP,box.centerX(),box.centerY());
                check(Arrays.equals(actions,new int[]{1,1,1}),"A changed result selected the word under an old finger");
                row.setEmbeddedGrid(true);row.update("kaifa",words,true,"");
                ViewGroup.LayoutParams params=row.getLayoutParams();params.height=Math.round(dp(activity,198));row.setLayoutParams(params);
            });
            test.waitForIdleSync();
            float[] flingStart={0};
            main(test,()->{
                long now=SystemClock.uptimeMillis(),down=now-96;float x=dp(activity,60),y=row.getHeight()-dp(activity,15);
                event(row,down,down,MotionEvent.ACTION_DOWN,x,y);
                MotionEvent move=MotionEvent.obtain(down,down+16,MotionEvent.ACTION_MOVE,x,y-dp(activity,24),0);
                move.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                for(int step=2;step<=5;step++)move.addBatch(down+step*16,x,y-dp(activity,24*step),1,1,0);
                row.onTouchEvent(move);move.recycle();
                float dragged=((Number)field(row,"scroll")).floatValue();check(dragged>0,"Grid drag did not scroll");
                List<String> viewport=new ArrayList<>(row.visibleWords());row.update("kaifag",words,true,"");
                check(row.visibleWords().equals(viewport),"Same-word preedit moved a dragged viewport");
                event(row,down,now,MotionEvent.ACTION_UP,x,y-dp(activity,144));
                OverScroller scroller=(OverScroller)field(row,"scroller");check(!scroller.isFinished(),"Native gesture did not start a fling");
                int index=words.indexOf(row.visibleWords().get(0));Rect before=bounds(row,100+index);
                row.update("kaifagong",words,true,"");
                row.glosses(Collections.singletonMap(words.get(index),"A long arriving translation that needs an expanded row and must wait for the current gesture to finish."));
                check(!scroller.isFinished()&&bounds(row,100+index).equals(before),"Preedit/gloss stopped or moved a candidate fling");
                flingStart[0]=((Number)field(row,"scroll")).floatValue();
                check(Arrays.equals(actions,new int[]{1,1,1}),"Browsing candidates invoked a word action");
            });
            SystemClock.sleep(48);
            main(test,()->{
                row.computeScroll();check(((Number)field(row,"scroll")).floatValue()>flingStart[0],"Refreshed fling did not continue");
                row.cancelTouch();row.update("",words,true,"");
                check(((Number)field(row,"scroll")).floatValue()==0,"Ending composition retained old scroll state");
                Rect box=bounds(row,100);long now=SystemClock.uptimeMillis();
                event(row,now,now,MotionEvent.ACTION_DOWN,box.centerX(),box.centerY());row.update("new",words,true,"");
                event(row,now,now+20,MotionEvent.ACTION_UP,box.centerX(),box.centerY());
                check(Arrays.equals(actions,new int[]{1,1,1}),"A new composition inherited a previous touch");
            });
        } finally {main(test,()->{surface[0].cancelTouch();parent[0].removeView(surface[0]);});}
    }

    private static void immediateSnapshot(Activity activity) {
        QingyuImeService service=new QingyuImeService();ImePreferences prefs=new ImePreferences(activity);
        boolean hadMode=prefs.store.contains("keyboard_mode");String oldMode=prefs.keyboardMode();
        EngineSnapshot prior=new EngineSnapshot("kaifa","kaifa",Collections.singletonList(new Candidate(0,"开发")),"");
        try {
            set(service,"prefs",prefs);set(service,"visibleRevision",41L);set(service,"revision",42L);
            Method refresh=QingyuImeService.class.getDeclaredMethod("showImmediate");refresh.setAccessible(true);
            for(String mode:new String[]{"pinyin","nine","english"}){
                prefs.store.edit().putString("keyboard_mode",mode.equals("nine")?"t9":"full").commit();
                set(service,"english",mode.equals("english"));set(service,"candidateMode",mode);set(service,"preview","kaifaz");set(service,"visible",prior);
                refresh.invoke(service);
                check(field(service,"visible")==prior,"Pending "+mode+" input blanked its previous candidates");
                check(((Number)field(service,"visibleRevision")).longValue()==41,"Pending input promoted old candidate IDs to a new revision");
            }
            set(service,"english",false);prefs.store.edit().putString("keyboard_mode","full").commit();
            for(String mode:new String[]{"predict_zh","english"}){
                set(service,"candidateMode",mode);set(service,"preview","n");set(service,"visible",prior);refresh.invoke(service);
                check(((EngineSnapshot)field(service,"visible")).candidates.isEmpty(),"New composition retained "+mode+" candidates");
            }
            set(service,"candidateMode","pinyin");set(service,"preview","");set(service,"visible",prior);refresh.invoke(service);
            check(((EngineSnapshot)field(service,"visible")).candidates.isEmpty(),"Empty composition retained stale candidates");
        }catch(ReflectiveOperationException error){throw new AssertionError(error);}
        finally{android.content.SharedPreferences.Editor edit=prefs.store.edit();if(hadMode)edit.putString("keyboard_mode",oldMode);else edit.remove("keyboard_mode");edit.commit();}
    }
    private static Object field(Object object,String name){try{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}catch(ReflectiveOperationException error){throw new AssertionError(error);}}
    private static void set(Object object,String name,Object value){try{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);}catch(ReflectiveOperationException error){throw new AssertionError(error);}}
    private static float dp(Activity activity,float value){return value*activity.getResources().getDisplayMetrics().density;}
    private static void event(CandidateSurface row,long down,long time,int action,float x,float y){MotionEvent event=MotionEvent.obtain(down,time,action,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);row.onTouchEvent(event);event.recycle();}
    private static Rect bounds(CandidateSurface row,int id){AccessibilityNodeInfo node=row.getAccessibilityNodeProvider().createAccessibilityNodeInfo(id);check(node!=null,"Missing visible candidate "+id);Rect box=new Rect();node.getBoundsInParent(box);node.recycle();return box;}
    private static void main(Instrumentation test,Runnable work){Throwable[] error={null};test.runOnMainSync(()->{try{work.run();}catch(Throwable failure){error[0]=failure;}});if(error[0]!=null)throw new AssertionError(error[0]);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
