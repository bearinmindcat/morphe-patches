package com.miui.newhome.view.gestureview;

/**
 * Stand-in (.github/scripts/xiaomi-launcher/overrides): Xiaomi's feed view (the minus-one screen of Xiaomi's "NewHome"
 * app), a view the launcher adds to its feed container. Off HyperOS that app is absent and the launcher never makes one
 * (it loads the class from that app); this is a FrameLayout so the launcher's code verifies. The view's own Android
 * members (visibility, translation, key events) are FrameLayout's. Names only, no Xiaomi code.
 */
public class NewHomeView extends android.widget.FrameLayout {
    public NewHomeView(android.content.Context context, android.util.AttributeSet attrs) {
        super(context, attrs);
    }
    public void changeState(com.miui.newhome.view.gestureview.NewHomeState p0) {
    }
    public static com.miui.newhome.view.gestureview.NewHomeState getNewHomeState() {
        return null;
    }
    public boolean isAppBarLayoutScroll() {
        return false;
    }
    public boolean isViewPagerScorll() {
        return false;
    }
    public boolean onBackPressed() {
        return false;
    }
    public void onDestory() {
    }
    public void onNewIntent(android.content.Intent p0) {
    }
    public void onPause() {
    }
    public void onResume() {
    }
    public void onTransProgress(float p0) {
    }
    public void setFeedActionListener(com.miui.newhome.view.gestureview.FeedActionListener p0) {
    }
    public boolean shouldContainerScroll(android.view.MotionEvent p0) {
        return false;
    }
}
