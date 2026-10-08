package com.qingyu.ime;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.StaticLayout;
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
        int defaultHeight=context.getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE?44:52;
        check(fixedHeight==Math.round(defaultHeight*context.getResources().getDisplayMetrics().density),"Wrong compact idle candidate height");
        header.update("kaifa",Arrays.asList("开发","项目","非常长的候选词用于边界检查"),true,"");layout(header,1080,0);
        Bitmap headerBitmap=Bitmap.createBitmap(1080,fixedHeight,Bitmap.Config.ARGB_8888);
        header.draw(new Canvas(headerBitmap){@Override public void drawText(String value,float x,float baseline,Paint paint){check(!value.contains("kaifa"),"Raw pinyin is still drawn inside candidates");Paint.FontMetrics metrics=paint.getFontMetrics();check(baseline+metrics.ascent>=-1&&baseline+metrics.descent<=fixedHeight+1,"Header text escaped reserved space");super.drawText(value,x,baseline,paint);}});headerBitmap.recycle();
        int activeHeight=Math.round((defaultHeight+48)*context.getResources().getDisplayMetrics().density);layout(header,1080,activeHeight);
        Bitmap activeBitmap=Bitmap.createBitmap(1080,activeHeight,Bitmap.Config.ARGB_8888);header.draw(new Canvas(activeBitmap){@Override public void drawRoundRect(float left,float top,float right,float bottom,float rx,float ry,Paint paint){check(bottom-top<=52*context.getResources().getDisplayMetrics().density+.5f,"Active header highlight became a tall color block");super.drawRoundRect(left,top,right,bottom,rx,ry,paint);}});activeBitmap.recycle();layout(header,1080,0);
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
            check(grid.visibleWords().size()>0&&grid.visibleWords().size()<=12&&bounds(grid,100).top==0,"Wrong grid slots or extra composition row");
            for(int i=0;i<grid.visibleWords().size();i++){Rect box=bounds(grid,100+i);check(box.left>=0&&box.right<=1080&&box.bottom<=grid.getHeight(),"Grid candidate outside body");}
            check(grid.getAccessibilityNodeProvider().createAccessibilityNodeInfo(1000)==null&&grid.getAccessibilityNodeProvider().createAccessibilityNodeInfo(1001)==null,"Legacy paging controls remain");
            Bitmap bitmap=Bitmap.createBitmap(1080,exactHeight,Bitmap.Config.ARGB_8888);
            grid.draw(new Canvas(bitmap){
                @Override public void drawText(String value,float x,float baseline,Paint paint){
                    int index=grid.words().indexOf(value);
                    if(index>=0||value.equals("annotation")){
                        int row=index>=0?index/3:Math.max(0,(int)(baseline/(bounds(grid,100).height())));
                        Rect cell=bounds(grid,100+row*3);Paint.FontMetrics metrics=paint.getFontMetrics();
                        if(cell.height()==bounds(grid,100).height())check(baseline+metrics.ascent>=cell.top-1&&baseline+metrics.descent<=cell.bottom+1,"Minimum-height grid text crossed a full row");
                    }
                    super.drawText(value,x,baseline,paint);
                }
            });bitmap.recycle();
            Configuration large=new Configuration(config);large.fontScale=1.3f;Context enlarged=context.createConfigurationContext(large);
            CandidateSurface scaled=new CandidateSurface(enlarged,new ImePreferences(enlarged),listener);scaled.update("kaifa",Arrays.asList("开发","很长的中文候选用于截断"),true,"");scaled.glosses(Collections.singletonMap("开发","develop"));layout(scaled,1080,0);
            int scaledHeight=scaled.getHeight();Bitmap scaledBitmap=Bitmap.createBitmap(1080,scaledHeight,Bitmap.Config.ARGB_8888);
            scaled.draw(new Canvas(scaledBitmap){@Override public void drawText(String value,float x,float baseline,Paint paint){Paint.FontMetrics metrics=paint.getFontMetrics();check(baseline+metrics.ascent>=-1&&baseline+metrics.descent<=scaledHeight+1,"1.3 font scale escaped header");super.drawText(value,x,baseline,paint);}});scaledBitmap.recycle();
        }
        for(boolean dark:new boolean[]{false,true}){
            Palette palette=new Palette(dark);java.util.Set<Integer> tones=new java.util.HashSet<>();
            for(String tag:Arrays.asList("n","v","a","d","uj")){
                int color=palette.partOfSpeech(tag);tones.add(color);
                for(int bg:new int[]{palette.background,palette.function,palette.pressed})check(contrast(color,bg)>=4.5,"POS color loses readable contrast");
            }
            check(tones.size()==5&&palette.partOfSpeech(null)==palette.text&&palette.partOfSpeech("eng")==palette.text,"Fake POS or missing coordinated tones");
        }
        sentenceRows(context);
    }
    private static void sentenceRows(Context context){
        Configuration config=new Configuration(context.getResources().getConfiguration());config.fontScale=1.3f;config.orientation=Configuration.ORIENTATION_PORTRAIT;
        Context large=context.createConfigurationContext(config);float density=large.getResources().getDisplayMetrics().density;
        int[] chosen={-1};CandidateSurface grid=new CandidateSurface(large,new ImePreferences(large),new CandidateSurface.Listener(){
            public void choose(int index){chosen[0]=index;}public void detail(int index){}public void translate(int index){}public void expand(){}public void settings(){}public void toggleTranslation(){}public void punctuation(String value){}
        });grid.setEmbeddedGrid(true);
        String sentence="我今天想和朋友一起去公园散步然后回家吃晚饭，明天我们还可以再去看电影。";
        String translation="Today I would like to take a walk in the park with my friends and then return home for dinner. Tomorrow we can go to see a film together.";
        grid.update("wojintian",Arrays.asList(sentence,"今天","朋友","公园","散步","回家","晚饭","明天","电影"),true,"");
        grid.glosses(Collections.singletonMap(sentence,translation));grid.modelWords(Collections.singleton(sentence));layout(grid,Math.round(360*density),Math.round(210*density));
        int fixed=grid.getHeight();Rect first=bounds(grid,100);check(first.left==0&&first.right==grid.getWidth(),"Long sentence is still squeezed into a narrow column");
        try{
            Object cell=((java.util.List<?>)field(grid,"gridCells")).get(0);
            StaticLayout word=(StaticLayout)field(cell,"word"),gloss=(StaticLayout)field(cell,"gloss");
            check(word.getLineCount()>1&&gloss.getLineCount()>1,"Expanded sentence/translation did not wrap");
            fullText(word,sentence);fullText(gloss,translation);
            RectF box=(RectF)field(cell,"bounds");check(box.height()>=word.getHeight()+gloss.getHeight(),"Dynamic sentence row clips its text");
            check(grid.getHeight()==fixed&&first.bottom<=fixed,"Sentence rows expanded the IME window");
            check(!grid.getAccessibilityNodeProvider().createAccessibilityNodeInfo(100).getContentDescription().toString().contains("Google Translate"),"Expanded sentence still exposes candidate branding");
        }catch(ReflectiveOperationException error){throw new AssertionError(error);}

        // A late translation can change expanded row height, but may not move
        // the touched cell or cancel the tap that was already in progress.
        grid.update("jintian",Arrays.asList("今天","朋友","公园","散步","回家","晚饭","明天","电影","你好"),true,"");grid.glosses(Collections.emptyMap());grid.modelWords(Collections.emptySet());
        grid.glosses(Collections.singletonMap("今天","today"));Rect shortModel=bounds(grid,100);grid.modelWords(Collections.singleton("今天"));check(bounds(grid,100).equals(shortModel)&&shortModel.right<grid.getWidth(),"Model branding metadata changed a short candidate layout");
        grid.modelWords(Collections.emptySet());check(bounds(grid,100).right<grid.getWidth(),"Local short words lost the compact three-column layout");grid.glosses(Collections.emptyMap());
        Rect before=bounds(grid,100);long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,before.centerX(),before.centerY(),0);grid.onTouchEvent(down);down.recycle();
        grid.glosses(Collections.singletonMap("今天",translation));grid.modelWords(Collections.singleton("今天"));check(bounds(grid,100).equals(before),"Asynchronous translation moved a live candidate touch");
        MotionEvent up=MotionEvent.obtain(now,now+30,MotionEvent.ACTION_UP,before.centerX(),before.centerY(),0);grid.onTouchEvent(up);up.recycle();check(chosen[0]==0,"Late translation canceled/changed candidate selection");
        check(bounds(grid,100).right==grid.getWidth()&&grid.getHeight()==fixed,"Deferred translation reflow changed the keyboard footprint");

        try{
            android.widget.OverScroller scroller=(android.widget.OverScroller)field(grid,"scroller");scroller.startScroll(0,0,0,20,180);
            Rect during=bounds(grid,100);grid.glosses(Collections.singletonMap("今天",translation+" We enjoy this quiet afternoon."));
            check(!scroller.isFinished()&&bounds(grid,100).equals(during),"Annotation interrupted candidate scrolling");scroller.abortAnimation();grid.computeScroll();
        }catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }
    private static Object field(Object object,String name)throws ReflectiveOperationException{java.lang.reflect.Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static void fullText(StaticLayout layout,String expected){check(layout.getText().toString().equals(expected)&&layout.getLineEnd(layout.getLineCount()-1)==expected.length(),"Expanded text was shortened");for(int line=0;line<layout.getLineCount();line++)check(layout.getEllipsisCount(line)==0,"Expanded text still uses ellipsis");}
    private static void layout(View view,int width,int exactHeight){view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(exactHeight,exactHeight==0?View.MeasureSpec.UNSPECIFIED:View.MeasureSpec.EXACTLY));view.layout(0,0,view.getMeasuredWidth(),view.getMeasuredHeight());}
    private static Rect bounds(CandidateSurface view,int id){AccessibilityNodeInfo node=view.getAccessibilityNodeProvider().createAccessibilityNodeInfo(id);check(node!=null,"Missing candidate control "+id);Rect rect=new Rect();node.getBoundsInParent(rect);return rect;}
    private static double luminance(int color){double value=0;int[] channels={Color.red(color),Color.green(color),Color.blue(color)};double[] weights={.2126,.7152,.0722};for(int i=0;i<3;i++){double c=channels[i]/255.0;value+=weights[i]*(c<=.04045?c/12.92:Math.pow((c+.055)/1.055,2.4));}return value;}
    private static double contrast(int a,int b){double x=luminance(a),y=luminance(b);return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
