package android.hardware.input;

/**
 * Stand-in for One UI's InputManager.SemOnPointerIconChangedListener (no Samsung code): the screen recorder implements
 * it to draw the mouse pointer into recordings; off One UI nothing calls it (registering it answers "absent").
 */
public interface InputManager$SemOnPointerIconChangedListener {
    void onPointerIconChanged(int type, android.graphics.Bitmap icon, float hotSpotX, float hotSpotY);
}
