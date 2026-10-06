package com.qingyu.ime;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import java.util.Arrays;
import java.util.Collections;

/** Run on the main thread from the existing IME instrumentation. */
final class AttributionCheck {
    static void run(Context context) {
        ImePreferences prefs = new ImePreferences(context);
        CandidateSurface surface = new CandidateSurface(context, prefs, new CandidateSurface.Listener() {
            public void choose(int index) {} public void detail(int index) {} public void translate(int index) {}
            public void expand() {} public void settings() {} public void toggleTranslation() {} public void punctuation(String text) {}
        });
        surface.update("kaifa", Collections.singletonList("开发"), true, "");
        layout(surface);
        check(Math.abs(bounds(surface, 3).left-bounds(surface, 100).right)<=1,"Short-list expand is not immediately after the last candidate");
        surface.update("kaifa", Arrays.asList("开发", "开", "开发者"), true, "");
        layout(surface);
        int height = surface.getMeasuredHeight();
        Rect before = bounds(surface, 100);
        surface.glosses(Collections.singletonMap("开发", "a deliberately long sentence supplied by the model"));
        surface.setGoogleTranslation(true);
        layout(surface);
        check(height == surface.getMeasuredHeight() && before.equals(bounds(surface, 100)), "Model attribution moved a candidate");
        AccessibilityNodeInfo attribution = surface.getAccessibilityNodeProvider().createAccessibilityNodeInfo(4);
        check(attribution != null && !attribution.isClickable(), "Missing readable, noninteractive attribution");
        Rect badge = new Rect(); attribution.getBoundsInParent(badge);
        check(badge.top >= before.bottom && badge.bottom <= height, "Badge overlaps candidates or is clipped");
        surface.setGoogleTranslation(false);
        check(surface.getAccessibilityNodeProvider().createAccessibilityNodeInfo(4) == null, "Local gloss still attributed to Google");

        ImePanels panels = new ImePanels(context, prefs, new View(context), action -> {});
        panels.body.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        panels.detail("开发", "Loading");
        View image = panels.body.findViewById(R.id.translation_attribution);
        Button commit = panels.body.findViewById(R.id.translation_commit);
        panels.detailResult("开发", "develop", "本地短语");
        check(image.getVisibility() == View.INVISIBLE && commit.getText().toString().equals("输入译文"), "Local details have Google branding");
        panels.detailResult("开发", "development", "Google Translate · 端侧翻译");
        check(image.getVisibility() == View.VISIBLE && commit.getText().toString().equals("Translate with Google"), "Model details missing badge/action attribution");
        panels.detailResult("谷歌翻译", "Google Translate", "CC-CEDICT · 英文词典释义\nGoogle Translate is a proper name");
        check(image.getVisibility() == View.INVISIBLE, "A dictionary mention falsely marked as a model result");
        panels.close();
    }
    private static void layout(View view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }
    private static Rect bounds(CandidateSurface surface, int id) {
        Rect rect = new Rect(); surface.getAccessibilityNodeProvider().createAccessibilityNodeInfo(id).getBoundsInParent(rect); return rect;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
