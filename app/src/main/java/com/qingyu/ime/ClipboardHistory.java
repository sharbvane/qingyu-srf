package com.qingyu.ime;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.util.AtomicFile;
import org.json.JSONArray;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Bounded local text history. Disk work stays off the input and decoder threads. */
final class ClipboardHistory implements ClipboardManager.OnPrimaryClipChangedListener {
    static final int LIMIT=100;
    private final ClipboardManager clipboard;
    private final Handler io, main=new Handler(android.os.Looper.getMainLooper());
    private final AtomicFile file;
    private final BooleanSupplier privateInput;
    private final Runnable changed;
    private final List<String> items=new ArrayList<>();
    private boolean closed, cleared;
    private final Runnable persist=this::persist;
    private void persist(){
        List<String> snapshot=snapshot();JSONArray json=new JSONArray(snapshot);FileOutputStream out=null;
        try{out=file.startWrite();out.write(json.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}
        catch(Exception error){if(out!=null)file.failWrite(out);}
    }
    ClipboardHistory(Context context,Handler io,BooleanSupplier privateInput,Runnable changed){
        this.io=io;this.privateInput=privateInput;this.changed=changed;
        clipboard=(ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE);
        file=new AtomicFile(new File(context.getFilesDir(),"clipboard-history.json"));
        io.post(()->{
            List<String> saved=new ArrayList<>();
            try{JSONArray json=new JSONArray(new String(file.readFully(),StandardCharsets.UTF_8));for(int i=0;i<json.length()&&saved.size()<LIMIT;i++){String s=json.optString(i,"");if(!s.isEmpty())saved.add(s);}}catch(Exception ignored){}
            synchronized(items){if(!cleared)for(String s:saved)if(!items.contains(s)&&items.size()<LIMIT)items.add(s);}
            main.post(()->{if(!closed)changed.run();});
        });
        clipboard.addPrimaryClipChangedListener(this);
    }
    @Override public void onPrimaryClipChanged(){if(!closed&&!privateInput.getAsBoolean())record(currentText());}
    String currentText(){
        try{
            ClipData clip=clipboard.getPrimaryClip();if(clip==null||clip.getItemCount()==0)return "";
            android.os.PersistableBundle extras=clip.getDescription().getExtras();
            if(extras!=null&&extras.getBoolean("android.content.extra.IS_SENSITIVE",false))return "";
            CharSequence text=clip.getItemAt(0).getText();return text==null?"":text.toString();
        }catch(SecurityException error){return "";}
    }
    void record(String text){
        if(closed||text==null||text.isEmpty()||privateInput.getAsBoolean())return;
        // ponytail: text clips over 256 KiB are not retained; add streamed storage if users need large documents.
        if(text.length()>262144)return;
        synchronized(items){if(!items.isEmpty()&&items.get(0).equals(text))return;items.remove(text);items.add(0,text);while(items.size()>LIMIT)items.remove(items.size()-1);}
        io.removeCallbacks(persist);io.postDelayed(persist,250);changed.run();
    }
    List<String> snapshot(){synchronized(items){return new ArrayList<>(items);}}
    void clear(){synchronized(items){cleared=true;items.clear();}io.removeCallbacks(persist);io.post(persist);changed.run();}
    void remove(String text){synchronized(items){items.remove(text);}io.removeCallbacks(persist);io.post(persist);changed.run();}
    void copy(String text){if(text!=null&&!text.isEmpty())clipboard.setPrimaryClip(ClipData.newPlainText("轻语",text));}
    void close(){closed=true;clipboard.removePrimaryClipChangedListener(this);io.removeCallbacks(persist);io.post(persist);}
}
