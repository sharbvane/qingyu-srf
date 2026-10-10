package com.qingyu.ime;

import android.os.SystemClock;

/** Focused translation checks through the installed, R8-optimized IME; models must be installed. */
public final class EditorV71Instrumentation extends ImeSmokeInstrumentation {
    @Override protected String successMarker(){return "ALL_V71_EDITOR_UI_CHECKS_PASS";}
    @Override protected void runChecks()throws Exception {
        translate("你好こんにちは 123🙂\r\n",false);awaitTranslated("helloこんにちは 123🙂\r\n");undo("你好こんにちは 123🙂\r\n");
        translate("开发こんにちは ABC",true);awaitTranslated("developこんにちは ABC");undo("开发こんにちは ABC");
        translate("今日は友達と映画を見ます。🙂2026",false);awaitNodeText("edit_status","没有可翻译");awaitText("今日は友達と映画を見ます。🙂2026");
        pass("real editor translates identified glued Chinese, keeps native Japanese unchanged and undoes replacements");
        String[] languages={"en","ja","fr","de","ru","es"};
        String[] samples={"I would like to watch short videos with my friends.","今日は友達と映画を見に行きます。","Cette application permet de rédiger des messages facilement.","Diese Tastatur hilft mir jeden Tag beim Schreiben von Nachrichten.","Эта клавиатура помогает мне каждый день писать сообщения друзьям.","Este teclado me ayuda a escribir mensajes a mis amigos todos los días."};
        for(int i=0;i<samples.length;i++){
            String original=samples[i]+" 123🙂\r\n";translate(original,true);long until=SystemClock.uptimeMillis()+30000;
            while(text().equals(original)&&SystemClock.uptimeMillis()<until)SystemClock.sleep(50);
            String result=text();check(!result.equals(original)&&result.endsWith(" 123🙂\r\n")&&result.codePoints().anyMatch(c->c>=0x3400&&c<=0x9fff),"Foreign selection did not translate safely: "+languages[i]+" "+result);
            if(i==0)check(result.contains("朋友")&&(result.contains("视频")||result.contains("短片")),"Whole English sentence lost its meaning: "+result);
            undo(original);pass("installed "+languages[i]+" selection identifies language, translates naturally to Chinese, preserves suffix and undoes");
        }
        closePanel();
    }
    private void translate(String original,boolean selected){if(find("edit_translate")!=null)closePanel();setText(original);if(selected)runOnMainSync(()->editor.setSelection(0,original.length()));nodeClick("toolbar_edit");SystemClock.sleep(160);nodeClick("edit_translate");}
    private void awaitTranslated(String expected){long until=SystemClock.uptimeMillis()+30000;while(!text().equals(expected)&&SystemClock.uptimeMillis()<until)SystemClock.sleep(50);awaitText(expected);}
    private void undo(String original){nodeClick("edit_undo");awaitText(original);}
}
