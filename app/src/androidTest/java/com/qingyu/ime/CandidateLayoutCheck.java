package com.qingyu.ime;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Arrays;
import java.util.Collections;

/** Existing instrumentation calls this on its main thread; no product test hooks. */
final class CandidateLayoutCheck {
    static void run(Context context) {
        int[] clears={0};
        CandidateSurface.Listener listener=new CandidateSurface.Listener(){
            public void choose(int index){} public void detail(int index){} public void translate(int index){}
            public void expand(){} public void settings(){} public void toggleTranslation(){} public void punctuation(String text){}
            public void clearCandidates(){clears[0]++;}
        };
        ImePreferences prefs=new ImePreferences(context);
        CandidateSurface header=new CandidateSurface(context,prefs,listener);
        header.update("",Collections.emptyList(),true,"");layout(header,1080,0);
        int fixedHeight=header.getHeight();
        header.update("kaifa",Arrays.asList("开发","项目","非常长的候选词用于边界检查"),true,"");layout(header,1080,0);
        Rect first=bounds(header,100);header.setExpandIndicator(true);layout(header,1080,0);
        check(header.getHeight()==fixedHeight&&bounds(header,100).equals(first),"Expand arrow changed header geometry");
        header.update("",Arrays.asList("人民","文化","语言","后续候选词"),true,"");header.setInteractiveComposition(false);header.setPredicting(true);layout(header,1080,0);
        Rect clear=bounds(header,5);
        check(header.getHeight()==fixedHeight&&clear.right==1080,"Prediction/X changed reserved space");
        check(header.getAccessibilityNodeProvider().createAccessibilityNodeInfo(3)==null,"Prediction still has expand control");
        for(int index=0;index<header.words().size();index++){
            AccessibilityNodeInfo node=header.getAccessibilityNodeProvider().createAccessibilityNodeInfo(100+index);
            if(node!=null){Rect word=new Rect();node.getBoundsInParent(word);check(word.right<=clear.left&&word.top>=0&&word.bottom<=fixedHeight,"Word extends into X/outside header: word="+word+", X="+clear+", height="+fixedHeight);}
        }
        long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,clear.centerX(),clear.centerY(),0);
        MotionEvent up=MotionEvent.obtain(now,now+30,MotionEvent.ACTION_UP,clear.centerX(),clear.centerY(),0);
        header.onTouchEvent(down);header.onTouchEvent(up);down.recycle();up.recycle();check(clears[0]==1,"Prediction X did not clear once");
        header.update("",Collections.emptyList(),true,"");layout(header,1080,0);check(header.getHeight()==fixedHeight&&header.getAccessibilityNodeProvider().createAccessibilityNodeInfo(5)==null,"Cleared header moved/retained X");
        for(boolean landscape:new boolean[]{false,true}){
            Configuration config=new Configuration(context.getResources().getConfiguration());config.orientation=landscape?Configuration.ORIENTATION_LANDSCAPE:Configuration.ORIENTATION_PORTRAIT;
            Context oriented=context.createConfigurationContext(config);
            CandidateSurface grid=new CandidateSurface(oriented,new ImePreferences(oriented),listener);grid.setEmbeddedGrid(true);
            grid.update("ignored",Arrays.asList("一","二","三","四","五","六","七","八","九","十","十一","十二","十三"),true,"");
            java.util.Map<String,String> glosses=new java.util.HashMap<>();for(String word:grid.words())glosses.put(word,"annotation");grid.glosses(glosses);
            int exactHeight=Math.round((landscape?140:198)*oriented.getResources().getDisplayMetrics().density);
            layout(grid,1080,exactHeight);check(grid.getHeight()==exactHeight,"Grid ignored exact body height");
            check(grid.visibleWords().size()==(landscape?6:12)&&bounds(grid,100).top==0,"Wrong grid slots or extra composition row");
            for(int i=0;i<grid.visibleWords().size();i++){Rect box=bounds(grid,100+i);check(box.left>=0&&box.right<=1080&&box.bottom<grid.getHeight(),"Grid candidate outside body");}
            Bitmap bitmap=Bitmap.createBitmap(1080,exactHeight,Bitmap.Config.ARGB_8888);
            grid.draw(new Canvas(bitmap){
                @Override public void drawText(String value,float x,float baseline,Paint paint){
                    int index=grid.words().indexOf(value);
                    if(index>=0||value.equals("annotation")){
                        int row=index>=0?index/3:Math.min((landscape?2:4)-1,(int)(baseline/(bounds(grid,100).height())));
                        Rect cell=bounds(grid,100+row*3);Paint.FontMetrics metrics=paint.getFontMetrics();
                        check(baseline+metrics.ascent>=cell.top-1&&baseline+metrics.descent<=cell.bottom+1,"Minimum-height grid text crossed a row/footer");
                    }
                    super.drawText(value,x,baseline,paint);
                }
            });bitmap.recycle();
        }
        for(boolean dark:new boolean[]{false,true}){
            Palette palette=new Palette(dark);java.util.Set<Integer> tones=new java.util.HashSet<>();
            for(String tag:Arrays.asList("n","v","a","d","uj")){
                int color=palette.partOfSpeech(tag);tones.add(color);
                for(int bg:new int[]{palette.background,palette.function,palette.pressed})check(contrast(color,bg)>=4.5,"POS color loses readable contrast");
            }
            check(tones.size()==5&&palette.partOfSpeech(null)==palette.text&&palette.partOfSpeech("eng")==palette.text,"Fake POS or missing coordinated tones");
        }
    }
    private static void layout(View view,int width,int exactHeight){view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(exactHeight,exactHeight==0?View.MeasureSpec.UNSPECIFIED:View.MeasureSpec.EXACTLY));view.layout(0,0,view.getMeasuredWidth(),view.getMeasuredHeight());}
    private static Rect bounds(CandidateSurface view,int id){AccessibilityNodeInfo node=view.getAccessibilityNodeProvider().createAccessibilityNodeInfo(id);check(node!=null,"Missing candidate control "+id);Rect rect=new Rect();node.getBoundsInParent(rect);return rect;}
    private static double luminance(int color){double value=0;int[] channels={Color.red(color),Color.green(color),Color.blue(color)};double[] weights={.2126,.7152,.0722};for(int i=0;i<3;i++){double c=channels[i]/255.0;value+=weights[i]*(c<=.04045?c/12.92:Math.pow((c+.055)/1.055,2.4));}return value;}
    private static double contrast(int a,int b){double x=luminance(a),y=luminance(b);return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
