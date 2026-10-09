package com.qingyu.ime;

import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.widget.EditText;

final class EditorHistoryCheck {
    static void run(EditText editor){
        EditorHistory history=new EditorHistory();InputConnection connection=editor.onCreateInputConnection(new EditorInfo());
        editor.setText("原文😊\n123");editor.setSelection(editor.length());String original=editor.getText().toString();
        check(EditorHistory.read(connection,true)!=null,"Full editable snapshot unavailable");
        history.compose(connection,"ni");history.compose(connection,"nihao");history.change(connection,()->connection.commitText("你好",1));
        check(history.undo(connection)&&original.contentEquals(editor.getText()),"Composition undo restored raw pinyin instead of original text");
        history.change(connection,()->connection.commitText("X",1));
        history.change(connection,()->connection.deleteSurroundingTextInCodePoints(1,0));
        check(history.undo(connection)&&(original+"X").contentEquals(editor.getText()),"Delete undo failed");
        check(history.undo(connection)&&original.contentEquals(editor.getText()),"Input undo order failed");
        connection.setSelection(0,2);history.change(connection,()->connection.commitText("替换",1));
        check(history.undo(connection)&&original.contentEquals(editor.getText())&&editor.getSelectionStart()==0&&editor.getSelectionEnd()==2,"Replacement undo lost selection or text");
        connection.setSelection(2,4);history.change(connection,()->connection.commitText("😃",1));
        check(history.undo(connection)&&original.contentEquals(editor.getText()),"Surrogate-safe undo failed");
        connection.setSelection(editor.length(),editor.length());history.change(connection,()->connection.commitText("尾",1));
        editor.append("外部");String external=editor.getText().toString();
        check(!history.undo(connection)&&external.contentEquals(editor.getText())&&!history.canUndo(),"External modification was overwritten");
        editor.setText("中文，42😊\nEnglish");editor.setSelection(0,2);EditorHistory.Snapshot anchor=EditorHistory.read(connection,true);
        check(history.replace(connection,anchor,0,2,"Chinese")&&"Chinese，42😊\nEnglish".contentEquals(editor.getText()),"Anchored replacement appended or changed protected suffix");
        check(history.undo(connection)&&"中文，42😊\nEnglish".contentEquals(editor.getText()),"Translation replacement undo failed");
        anchor=EditorHistory.read(connection,true);editor.setSelection(editor.length());
        check(!history.replace(connection,anchor,0,2,"wrong")&&"中文，42😊\nEnglish".contentEquals(editor.getText()),"Moved selection accepted stale translation");
        editor.setText("中文😊原文");editor.setSelection(editor.length());history.clear();String protectedText=editor.getText().toString();
        for(boolean throwsFailure:new boolean[]{false,true}){
            InputConnection refusing=new InputConnectionWrapper(connection,false){@Override public boolean commitText(CharSequence text,int cursor){if(throwsFailure)throw new IllegalStateException("Host refused the edit");return false;}};
            anchor=EditorHistory.read(connection,true);
            check(!history.replace(refusing,anchor,0,protectedText.length(),"wrong")&&protectedText.contentEquals(editor.getText())&&editor.getSelectionStart()==protectedText.length()&&editor.getSelectionEnd()==protectedText.length(),"Rejected translation retained a dangerous full-field selection");
            history.change(connection,()->connection.commitText("X",1));int cursor=editor.length();
            check(!history.undo(refusing)&&(protectedText+"X").contentEquals(editor.getText())&&editor.getSelectionStart()==cursor&&editor.getSelectionEnd()==cursor,"Rejected undo changed text or left its replacement range selected");
            check(history.canUndo()&&history.undo(connection)&&protectedText.contentEquals(editor.getText()),"Temporary host refusal discarded valid undo history");
        }
        InputConnection changing=new InputConnectionWrapper(connection,false){@Override public boolean commitText(CharSequence text,int cursor){editor.setText("应用修改");editor.setSelection(1);throw new IllegalStateException("Host changed the field");}};
        anchor=EditorHistory.read(connection,true);
        check(!history.replace(changing,anchor,0,protectedText.length(),"wrong")&&"应用修改".contentEquals(editor.getText())&&editor.getSelectionStart()==1&&editor.getSelectionEnd()==1&&!history.canUndo(),"Rejected translation restored a stale selection or recorded an external modification as an IME edit");
        editor.setText(protectedText);editor.setSelection(editor.length());history.clear();history.change(connection,()->connection.commitText("X",1));
        check(!history.undo(changing)&&"应用修改".contentEquals(editor.getText())&&editor.getSelectionStart()==1&&editor.getSelectionEnd()==1&&!history.canUndo(),"Rejected undo changed the host's later text or selection");
        editor.setText(protectedText);editor.setSelection(editor.length());history.clear();
        InputConnection chromium=new InputConnectionWrapper(connection,false){@Override public android.view.inputmethod.ExtractedText getExtractedText(android.view.inputmethod.ExtractedTextRequest request,int flags){android.view.inputmethod.ExtractedText value=super.getExtractedText(request,flags);if(value!=null){value.partialStartOffset=-1;value.partialEndOffset=value.text.length();}return value;}};
        check(EditorHistory.read(chromium,true)!=null,"Chromium full extraction with a nonnegative partialEndOffset was rejected");
        history.change(chromium,()->chromium.commitText("X",1));check(history.canUndo()&&history.undo(chromium)&&protectedText.contentEquals(editor.getText()),"Chromium full extraction failed change/undo");
        InputConnection partial=new InputConnectionWrapper(chromium,false){@Override public android.view.inputmethod.ExtractedText getExtractedText(android.view.inputmethod.ExtractedTextRequest request,int flags){android.view.inputmethod.ExtractedText value=super.getExtractedText(request,flags);if(value!=null)value.partialStartOffset=0;return value;}};
        check(EditorHistory.read(partial,false)==null&&EditorHistory.read(partial,true)==null,"Actual partial extraction was accepted as a full field");
        history.change(partial,()->partial.commitText("X",1));check(!history.canUndo()&&(protectedText+"X").contentEquals(editor.getText()),"Partial extraction exposed unsafe undo or blocked normal input");
        InputConnection invalidPartial=new InputConnectionWrapper(chromium,false){@Override public android.view.inputmethod.ExtractedText getExtractedText(android.view.inputmethod.ExtractedTextRequest request,int flags){android.view.inputmethod.ExtractedText value=super.getExtractedText(request,flags);if(value!=null)value.partialStartOffset=-2;return value;}};
        check(EditorHistory.read(invalidPartial,false)==null&&EditorHistory.read(invalidPartial,true)==null,"Invalid negative partial extraction marker accepted");
        editor.setText("");editor.setSelection(0);history.clear();
        for(int i=0;i<300;i++)history.change(connection,()->connection.commitText("a",1));
        int count=0;while(history.undo(connection))count++;
        check(count==256&&editor.length()==44,"History budget or undo chain failed");
        editor.setText(new String(new char[EditorHistory.MAX_TEXT+1]).replace('\0','a'));editor.setSelection(editor.length());
        check(EditorHistory.read(connection,true)==null,"Oversized extraction accepted");history.clear();
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
