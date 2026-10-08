package com.qingyu.ime;

import android.app.Instrumentation;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import java.util.ArrayList;
import java.util.List;

/** A real MotionEvent/Handler check, called from the existing IME instrumentation thread. */
final class KeyboardTouchCheck {
    static void run(Instrumentation instrumentation) throws Exception {
        ImePreferences prefs=new ImePreferences(instrumentation.getTargetContext());
        String previous=prefs.keyboardMode();
        prefs.store.edit().putString("keyboard_mode","full").commit();
        KeyboardSurface[] keyboard=new KeyboardSurface[1];
        List<String> output=new ArrayList<>();
        try {
            instrumentation.runOnMainSync(()-> {
                keyboard[0]=new KeyboardSurface(instrumentation.getTargetContext(),prefs,new KeyboardSurface.Listener() {
                    public void key(String value) {
                        if(value.equals("SHIFT"))keyboard[0].shift();
                        else {output.add("KEY_"+value);if(value.length()==1 && Character.isLetter(value.charAt(0)))keyboard[0].consumedLetter();}
                    }
                    public void cursor(int direction){output.add("CURSOR_"+direction);}
                    public void longKey(String value){output.add(value);}
                });
                keyboard[0].configure(false,false,"↵",false);
                layout(keyboard[0]);
            });
            KeyboardSurface view=keyboard[0];float dp=view.getResources().getDisplayMetrics().density;
            instrumentation.runOnMainSync(()-> {
                int half=view.getMeasuredHeight()/2;
                for(int mode:new int[]{View.MeasureSpec.EXACTLY,View.MeasureSpec.AT_MOST}) {
                    view.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(half,mode));
                    view.layout(0,0,view.getMeasuredWidth(),view.getMeasuredHeight());
                    check(view.getHeight()==half,"Keyboard ignored its parent's height limit");
                    for(int id:new int[]{1,11,20,32,34}) {
                        Rect rect=bounds(view,id);
                        check(rect.top>=0 && rect.bottom<=half && rect.height()>0,"Compressed keyboard clipped a key");
                    }
                }
                layout(view);
            });
            instrumentation.runOnMainSync(()-> {
                view.measure(View.MeasureSpec.makeMeasureSpec((int)(240*dp),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
                view.layout(0,0,view.getMeasuredWidth(),view.getMeasuredHeight());
            });
            Rect edgeQ=bounds(view,1),edgeP=bounds(view,10);
            hold(instrumentation,view,edgeQ);popupInside(instrumentation,view);
            float leftX=edgeQ.centerX()*.25f;
            move(instrumentation,view,leftX,edgeQ.centerY());up(instrumentation,view,leftX,edgeQ.centerY());expect(output,"DIRECT_Q");
            hold(instrumentation,view,edgeP);popupInside(instrumentation,view);
            float rightX=edgeP.centerX()+(view.getWidth()-edgeP.centerX())*.75f;
            move(instrumentation,view,rightX,edgeP.centerY());up(instrumentation,view,rightX,edgeP.centerY());expect(output,"DIRECT_0");
            instrumentation.runOnMainSync(()->layout(view));
            Rect a=bounds(view,11),q=bounds(view,1),p=bounds(view,10),shift=bounds(view,20),space=bounds(view,32),delete=bounds(view,28);
            tap(instrumentation,view,a);expect(output,"KEY_a");
            check(view.getAccessibilityNodeProvider().createAccessibilityNodeInfo(20).getContentDescription().toString().equals("拼音分词"),"Chinese full keyboard did not replace Shift with 分词");
            tap(instrumentation,view,shift);tap(instrumentation,view,a);expect(output,"KEY_SPLIT","KEY_a");
            tap(instrumentation,view,shift);tap(instrumentation,view,shift);expect(output,"KEY_SPLIT","KEY_SPLIT");
            check(!view.uppercase(),"Chinese split changed letter case");

            hold(instrumentation,view,a);check(output.isEmpty(),"Letter hold committed before release");
            popupInside(instrumentation,view);up(instrumentation,view,a.centerX(),a.centerY());expect(output,"DIRECT_a");
            hold(instrumentation,view,a);move(instrumentation,view,a.centerX()-20*dp,a.centerY());up(instrumentation,view,a.centerX()-20*dp,a.centerY());expect(output,"DIRECT_A");
            hold(instrumentation,view,a);move(instrumentation,view,a.centerX()+20*dp,a.centerY());up(instrumentation,view,a.centerX()+20*dp,a.centerY());expect(output,"DIRECT_a");
            hold(instrumentation,view,q);popupInside(instrumentation,view);up(instrumentation,view,q.centerX(),q.centerY());expect(output,"DIRECT_q");
            hold(instrumentation,view,q);move(instrumentation,view,q.centerX()+20*dp,q.centerY());up(instrumentation,view,q.centerX()+20*dp,q.centerY());expect(output,"DIRECT_1");
            hold(instrumentation,view,p);popupInside(instrumentation,view);move(instrumentation,view,p.centerX()-20*dp,p.centerY());up(instrumentation,view,p.centerX()-20*dp,p.centerY());expect(output,"DIRECT_P");
            hold(instrumentation,view,a);event(instrumentation,view,MotionEvent.ACTION_CANCEL,a.centerX(),a.centerY());up(instrumentation,view,a.centerX(),a.centerY());expect(output);
            hold(instrumentation,view,q);move(instrumentation,view,-1,q.centerY());up(instrumentation,view,q.centerX(),q.centerY());expect(output);
            hold(instrumentation,view,a);instrumentation.runOnMainSync(view::resetModes);up(instrumentation,view,a.centerX(),a.centerY());expect(output);
            hold(instrumentation,view,a);instrumentation.runOnMainSync(()->view.configure(true,false,"↵",false));up(instrumentation,view,a.centerX(),a.centerY());expect(output);
            check(!view.uppercase(),"Keyboard mode switch retained Chinese uppercase");
            tap(instrumentation,view,a);expect(output,"KEY_a");
            tap(instrumentation,view,shift);tap(instrumentation,view,a);expect(output,"KEY_a");
            check(!view.uppercase(),"English caller cannot consume temporary Shift");
            tap(instrumentation,view,shift);tap(instrumentation,view,shift);tap(instrumentation,view,a);expect(output,"KEY_a");
            check(view.uppercase(),"English double Shift did not lock uppercase");tap(instrumentation,view,shift);check(!view.uppercase(),"English caps lock did not turn off");

            instrumentation.runOnMainSync(()->view.configure(false,false,"↵",false));
            event(instrumentation,view,MotionEvent.ACTION_DOWN,a.centerX(),a.centerY());
            Rect b=bounds(view,25);move(instrumentation,view,b.centerX(),b.centerY());
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout()+60);
            check(output.isEmpty(),"Moving to another key triggered a stale hold");up(instrumentation,view,b.centerX(),b.centerY());expect(output,"KEY_b");
            hold(instrumentation,view,space);move(instrumentation,view,space.centerX()+40*dp,space.centerY());up(instrumentation,view,space.centerX()+40*dp,space.centerY());
            check(!output.isEmpty() && output.get(0).startsWith("CURSOR_"),"Space cursor gesture did not move");output.clear();
            event(instrumentation,view,MotionEvent.ACTION_DOWN,delete.centerX(),delete.centerY());SystemClock.sleep(480);
            up(instrumentation,view,delete.centerX(),delete.centerY());check(output.size()>=2,"Continuous delete stopped repeating");
            for(String value:output)check(value.equals("KEY_⌫"),"Delete gesture emitted another key");output.clear();SystemClock.sleep(100);expect(output);

            prefs.store.edit().putString("keyboard_mode","t9").commit();
            instrumentation.runOnMainSync(()-> {view.configure(false,false,"↵",false);layout(view);});
            Rect two=bounds(view,2);tap(instrumentation,view,two);expect(output,"KEY_2");
            hold(instrumentation,view,two);expect(output,"DIRECT_2");up(instrumentation,view,two.centerX(),two.centerY());expect(output);
        } finally {
            instrumentation.runOnMainSync(()-> {if(keyboard[0]!=null)keyboard[0].cancelTouch();});
            prefs.store.edit().putString("keyboard_mode",previous).commit();
        }
    }
    private static void layout(View view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        view.layout(0,0,view.getMeasuredWidth(),view.getMeasuredHeight());
    }
    private static Rect bounds(KeyboardSurface view,int id) {
        Rect bounds=new Rect();view.getAccessibilityNodeProvider().createAccessibilityNodeInfo(id).getBoundsInParent(bounds);return bounds;
    }
    private static void popupInside(Instrumentation instrumentation,KeyboardSurface view) {
        instrumentation.runOnMainSync(()-> {
            try {
                java.lang.reflect.Field field=KeyboardSurface.class.getDeclaredField("holdBounds");field.setAccessible(true);RectF bounds=(RectF)field.get(view);
                check(bounds.left>=0 && bounds.top>=0 && bounds.right<=view.getWidth() && bounds.bottom<=view.getHeight(),"Hold picker escaped the keyboard");
            } catch(ReflectiveOperationException e){throw new AssertionError(e);}
        });
    }
    private static void event(Instrumentation instrumentation,KeyboardSurface view,int action,float x,float y) {
        instrumentation.runOnMainSync(()-> {
            long now=SystemClock.uptimeMillis();MotionEvent event=MotionEvent.obtain(now,now,action,x,y,0);
            view.onTouchEvent(event);event.recycle();
        });
    }
    private static void tap(Instrumentation i,KeyboardSurface view,Rect bounds){event(i,view,MotionEvent.ACTION_DOWN,bounds.centerX(),bounds.centerY());up(i,view,bounds.centerX(),bounds.centerY());}
    private static void hold(Instrumentation i,KeyboardSurface view,Rect bounds){event(i,view,MotionEvent.ACTION_DOWN,bounds.centerX(),bounds.centerY());SystemClock.sleep(ViewConfiguration.getLongPressTimeout()+60);i.runOnMainSync(()->{});}
    private static void move(Instrumentation i,KeyboardSurface view,float x,float y){event(i,view,MotionEvent.ACTION_MOVE,x,y);}
    private static void up(Instrumentation i,KeyboardSurface view,float x,float y){event(i,view,MotionEvent.ACTION_UP,x,y);}
    private static void expect(List<String> output,String... expected) {
        check(output.equals(java.util.Arrays.asList(expected)),"Keyboard output: "+output+" expected "+java.util.Arrays.toString(expected));output.clear();
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
