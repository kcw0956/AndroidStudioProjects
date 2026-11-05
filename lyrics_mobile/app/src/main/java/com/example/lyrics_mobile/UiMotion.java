package com.example.lyrics_mobile;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.StateListAnimator;
import android.provider.Settings;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Short, input-driven transitions; respect the system animation scale. */
final class UiMotion {
    static boolean enabled(View view, boolean preference) {
        return preference && Settings.Global.getFloat(view.getContext().getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
    }
    static void enter(View view, int distanceDp, long delay, boolean preference) {
        if (!enabled(view, preference)) return;
        view.setAlpha(0f); view.setTranslationY(distanceDp * view.getResources().getDisplayMetrics().density);
        view.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(200)
                .setInterpolator(new DecelerateInterpolator()).start();
    }
    static void press(View view, boolean preference) {
        if (!enabled(view, preference)) return;
        StateListAnimator states = new StateListAnimator();
        AnimatorSet down = new AnimatorSet();
        down.playTogether(ObjectAnimator.ofFloat(view, "scaleX", .975f), ObjectAnimator.ofFloat(view, "scaleY", .975f)); down.setDuration(90);
        AnimatorSet up = new AnimatorSet();
        up.playTogether(ObjectAnimator.ofFloat(view, "scaleX", 1f), ObjectAnimator.ofFloat(view, "scaleY", 1f)); up.setDuration(140);
        states.addState(new int[]{android.R.attr.state_pressed, android.R.attr.state_enabled}, down);
        states.addState(new int[]{}, up); view.setStateListAnimator(states);
    }
}
