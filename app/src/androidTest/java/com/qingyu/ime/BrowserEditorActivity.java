package com.qingyu.ime;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Test-only browser host in the test APK's own process, separate from the IME. */
public final class BrowserEditorActivity extends Activity {
    private WebView browser;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(20,100,20,20);
        TextView value=new TextView(this);value.setId(android.R.id.text1);value.setContentDescription("Qingyu browser state");value.setText("loading");root.addView(value,new LinearLayout.LayoutParams(-1,100));
        browser=new WebView(this);browser.getSettings().setJavaScriptEnabled(true);root.addView(browser,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        browser.addJavascriptInterface(new Object(){@JavascriptInterface public void changed(String text){runOnUiThread(()->value.setText("browser: "+text));}},"fixture");
        String text="translate".equals(getIntent().getStringExtra("fixture"))?"开发，123😊\nEnglish":"";
        browser.loadDataWithBaseURL("https://qingyu.invalid/","<meta name='viewport' content='width=device-width,initial-scale=1'><textarea id='e' style='width:90%;height:140px;font-size:24px'></textarea><script>var e=document.getElementById('e');e.value="+org.json.JSONObject.quote(text)+";function report(){fixture.changed(e.value)};e.addEventListener('input',report);report();</script>","text/html","UTF-8",null);
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);if("close".equals(intent.getStringExtra("fixture")))finish();else if("external".equals(intent.getStringExtra("fixture")))browser.evaluateJavascript("var e=document.getElementById('e');e.value+='外部';report()",null);}
    @Override protected void onDestroy(){if(browser!=null)browser.destroy();super.onDestroy();}
}
