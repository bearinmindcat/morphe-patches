package android.widget;

/**
 * Stand-in for One UI's SeekBar.SemOnSeekBarHoverListener (in dex a class named SeekBar$SemOnSeekBarHoverListener),
 * which Samsung Music implements: names and signatures only, no Samsung code.
 */
public interface SeekBar$SemOnSeekBarHoverListener {
    void onHoverChanged(SeekBar seekBar, int progress, boolean fromUser);

    void onStartTrackingHover(SeekBar seekBar, int progress);

    void onStopTrackingHover(SeekBar seekBar);
}
