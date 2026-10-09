package com.qingyu.ime;

import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import java.util.ArrayDeque;

/** Session-only deltas; an editor change outside the IME invalidates the undo chain. */
final class EditorHistory {
    static final int MAX_TEXT = 65536;
    private static final int MAX_EDITS = 256, MAX_HISTORY_CHARS = 262144;
    static final class Snapshot {
        final String text;
        final int start, end;
        Snapshot(String text,int start,int end){this.text=text;this.start=start;this.end=end;}
        boolean matches(Snapshot other,boolean selection){return other!=null&&text.equals(other.text)&&(!selection||start==other.start&&end==other.end);}
    }
    private static final class Edit {
        final int offset,start,end;
        final String removed,inserted;
        Edit(Snapshot before,Snapshot after){
            int left=0,right=0;
            while(left<before.text.length()&&left<after.text.length()&&before.text.charAt(left)==after.text.charAt(left))left++;
            if(left>0&&left<before.text.length()&&Character.isHighSurrogate(before.text.charAt(left-1))&&Character.isLowSurrogate(before.text.charAt(left)))left--;
            while(right<before.text.length()-left&&right<after.text.length()-left&&before.text.charAt(before.text.length()-1-right)==after.text.charAt(after.text.length()-1-right))right++;
            if(right>0&&right<before.text.length()&&Character.isLowSurrogate(before.text.charAt(before.text.length()-right)))right--;
            offset=left;removed=before.text.substring(left,before.text.length()-right);inserted=after.text.substring(left,after.text.length()-right);start=before.start;end=before.end;
        }
        int size(){return removed.length()+inserted.length();}
    }
    private final ArrayDeque<Edit> edits=new ArrayDeque<>();
    private Snapshot expected,compositionStart;
    private int storedChars;
    static Snapshot read(InputConnection connection,boolean complete){
        if(connection==null)return null;
        try{
            ExtractedTextRequest request=new ExtractedTextRequest();request.hintMaxChars=MAX_TEXT+1;request.hintMaxLines=MAX_TEXT+1;
            ExtractedText value=connection.getExtractedText(request,0);
            // Full extraction uses partialStartOffset=-1; Chromium keeps partialEndOffset=text length.
            if(value==null||value.text==null||value.startOffset!=0||value.partialStartOffset!=-1)return null;
            String text=value.text.toString();int start=value.selectionStart,end=value.selectionEnd;
            if(text.length()>MAX_TEXT||start<0||end<0||start>text.length()||end>text.length())return null;
            if(complete){
                int left=Math.min(start,end),right=Math.max(start,end);
                CharSequence before=connection.getTextBeforeCursor(MAX_TEXT+1,0),after=connection.getTextAfterCursor(MAX_TEXT+1,0),selected=left==right?"":connection.getSelectedText(0);
                if(before==null||after==null||selected==null||!text.substring(0,left).contentEquals(before)||!text.substring(left,right).contentEquals(selected)||!text.substring(right).contentEquals(after))return null;
            }
            return new Snapshot(text,start,end);
        }catch(RuntimeException failure){return null;}
    }
    void clear(){edits.clear();storedChars=0;expected=null;compositionStart=null;}
    void observe(Snapshot current){
        if(current==null||expected!=null&&!expected.matches(current,false))clear();
        expected=current;
    }
    boolean canUndo(){return !edits.isEmpty()||compositionStart!=null&&expected!=null&&!compositionStart.text.equals(expected.text);}
    private void record(Snapshot before,Snapshot after){
        compositionStart=null;
        if(before==null||after==null){clear();return;}
        expected=after;
        if(before.text.equals(after.text))return;
        Edit edit=new Edit(before,after);
        if(edit.size()>MAX_HISTORY_CHARS){edits.clear();storedChars=0;return;}
        edits.addLast(edit);storedChars+=edit.size();
        // shortcut: keep 256 operations within 512 KiB of text deltas; increase only after memory profiling.
        while(edits.size()>MAX_EDITS||storedChars>MAX_HISTORY_CHARS)storedChars-=edits.removeFirst().size();
    }
    void change(InputConnection connection,Runnable action){
        Snapshot current=read(connection,false);observe(current);Snapshot before=compositionStart==null?current:compositionStart;
        try{action.run();}finally{record(before,read(connection,false));}
    }
    void compose(InputConnection connection,String value){
        Snapshot before=read(connection,false);observe(before);
        if(compositionStart==null)compositionStart=before;
        connection.setComposingText(value,1);Snapshot after=read(connection,false);
        if(after==null){clear();return;}expected=after;
    }
    void finishComposition(InputConnection connection){if(compositionStart==null)connection.finishComposingText();else change(connection,connection::finishComposingText);}
    boolean replace(InputConnection connection,Snapshot anchor,int from,int to,String value){
        Snapshot current=read(connection,true);
        if(anchor==null||!anchor.matches(current,true)||from<0||to<from||to>anchor.text.length()){observe(current);return false;}
        boolean[] applied={false},selectionAttempted={false};
        change(connection,()->{
            try{
                connection.beginBatchEdit();
                try{
                    if(!anchor.matches(read(connection,true),true))return;
                    selectionAttempted[0]=true;if(!connection.setSelection(from,to))return;
                    applied[0]=connection.commitText(value,1);
                }finally{connection.endBatchEdit();}
            }catch(RuntimeException rejected){
                // Editors can reject a commit after accepting its temporary replacement selection.
            }finally{if(!applied[0]&&selectionAttempted[0])restoreSelection(connection,anchor);}
        });
        if(!applied[0]&&!anchor.matches(read(connection,true),false))clear();
        return applied[0];
    }
    boolean undo(InputConnection connection){
        Snapshot current=read(connection,true);observe(current);
        if(current==null||edits.isEmpty())return false;
        Edit edit=edits.peekLast();
        if(edit.offset+edit.inserted.length()>current.text.length()||!current.text.regionMatches(edit.offset,edit.inserted,0,edit.inserted.length())){clear();return false;}
        String restored=current.text.substring(0,edit.offset)+edit.removed+current.text.substring(edit.offset+edit.inserted.length());
        boolean applied=false,selectionAttempted=false;
        try{
            connection.beginBatchEdit();
            try{
                if(!current.matches(read(connection,true),true))return false;
                selectionAttempted=true;if(!connection.setSelection(edit.offset,edit.offset+edit.inserted.length()))return false;
                applied=connection.commitText(edit.removed,1);
                if(applied)connection.setSelection(edit.start,edit.end);
            }finally{connection.endBatchEdit();}
        }catch(RuntimeException rejected){
            // A failed host operation must not leave the next keystroke selecting unrelated text.
        }finally{if(!applied&&selectionAttempted)restoreSelection(connection,current);}
        if(!applied){Snapshot unchanged=read(connection,true);if(current.matches(unchanged,false))expected=unchanged;else clear();return false;}
        Snapshot after=read(connection,false);
        if(after==null||!restored.equals(after.text)){clear();return false;}
        edits.removeLast();storedChars-=edit.size();expected=after;compositionStart=null;return true;
    }
    private static void restoreSelection(InputConnection connection,Snapshot before){
        if(!before.matches(read(connection,true),false))return;
        try{connection.setSelection(before.start,before.end);}catch(RuntimeException rejected){}
    }
}
