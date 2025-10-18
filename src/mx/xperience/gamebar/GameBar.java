/*
 * Copyright (C) 2025 kenway214
 * Copyright (C) 2025 The XPerience Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package mx.xperience.gamebar;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.BounceInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import mx.xperience.gamebar.R;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class GameBar {

    private static GameBar sInstance;
    public static synchronized GameBar getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new GameBar(context.getApplicationContext());
        }
        return sInstance;
    }
    
    public static synchronized void destroyInstance() {
        if (sInstance != null) {
            sInstance.cleanup();
            sInstance = null;
        }
    }

    private static final String FPS_PATH          = "/sys/class/drm/sde-crtc-0/measured_fps";
    private static final String[] BATTERY_TEMP_PATHS = new String[] {
        "/sys/class/power_supply/battery/temp",
        "/sys/class/power_supply/battery/batt_temp",
        "/sys/class/power_supply/bms/temp",
        // Paths específicos de OnePlus
        "/sys/class/oplus_chg/battery/temp",
        "/sys/class/oplus_chg/battery/batt_temp",
        "/sys/class/oplus_chg/battery/temperature",
        "/sys/class/oplus_chg/bq27541/temp",
        "/sys/class/thermal/thermal_zone0/temp"
    };

    private static final String PREF_KEY_X = "game_bar_x";
    private static final String PREF_KEY_Y = "game_bar_y";

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final Handler mHandler;

    private View mOverlayView;
    private LinearLayout mRootLayout;
    private WindowManager.LayoutParams mLayoutParams;
    private boolean mIsShowing = false;

    private int mTextSizeSp       = 14;
    private int mBackgroundAlpha  = 128;
    private int mCornerRadius     = 90;
    private int mPaddingDp        = 8;
    private String mTitleColorHex = "#FFFFFF";
    private String mValueColorHex = "#FFFFFF";
    private String mOverlayFormat = "full";
    private String mPosition      = "top_center";
    private String mSplitMode     = "side_by_side";
    private int mUpdateIntervalMs = 1000;
    private boolean mDraggable    = false;

    private boolean mShowBatteryTemp = false;
    private boolean mShowCpuUsage    = true;
    private boolean mShowCpuClock    = false;
    private boolean mShowCpuTemp     = false;
    private boolean mShowRam         = false;
    private boolean mShowFps         = true;

    private boolean mShowGpuUsage    = true;
    private boolean mShowGpuClock    = false;
    private boolean mShowGpuTemp     = false;

    private boolean mLongPressEnabled      = false;
    private long mLongPressThresholdMs = 500;
    private boolean mPressActive           = false;
    private float mDownX, mDownY;
    private static final float TOUCH_SLOP = 30f;

    private GestureDetector mGestureDetector;
    private boolean mDoubleTapCaptureEnabled = true;
    private boolean mSingleTapToggleEnabled  = true;
    private GradientDrawable mBgDrawable;

    private int mItemSpacingDp = 8;

    private boolean mShowRamSpeed = false;
    private boolean mShowRamTemp = false;
    
    // Track if layout needs refresh
    private boolean mLayoutChanged = false;

    private String mAnimationStyle = "bubble";

    private final Runnable mLongPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (mPressActive) {
                openOverlaySettings();
                mPressActive = false;
            }
        }
    };

    private final Runnable mUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (mIsShowing) {
                updateStats();
                mHandler.postDelayed(this, mUpdateIntervalMs);
            }
        }
    };

    private GameBar(Context context) {
        mContext = context;
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mHandler = new Handler(Looper.getMainLooper());

        mBgDrawable = new GradientDrawable();
        applyBackgroundStyle();

        mGestureDetector = new GestureDetector(mContext, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (mDoubleTapCaptureEnabled) {
                    if (GameDataExport.getInstance().isCapturing()) {
                        GameDataExport.getInstance().stopCapture();
                        Toast.makeText(mContext, "Capture Stopped", Toast.LENGTH_SHORT).show();
                    } else {
                        GameDataExport.getInstance().startCapture();
                        Toast.makeText(mContext, "Capture Started", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
                return super.onDoubleTap(e);
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (mSingleTapToggleEnabled) {
                    mOverlayFormat = "full".equals(mOverlayFormat) ? "minimal" : "full";
                    PreferenceManager.getDefaultSharedPreferences(mContext)
                        .edit()
                        .putString("game_bar_format", mOverlayFormat)
                        .apply();
                    Toast.makeText(mContext, "Overlay Format: " + mOverlayFormat, Toast.LENGTH_SHORT).show();
                    updateStats();
                    return true;
                }
                return super.onSingleTapConfirmed(e);
            }
        });
    }

    public void applyPreferences() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);

        mAnimationStyle  = prefs.getString("game_bar_animation_style", "bubble");
        mShowFps         = prefs.getBoolean("game_bar_fps_enable", true);
        mShowBatteryTemp = prefs.getBoolean("game_bar_temp_enable", false);
        mShowCpuUsage    = prefs.getBoolean("game_bar_cpu_usage_enable", true);
        mShowCpuClock    = prefs.getBoolean("game_bar_cpu_clock_enable", false);
        mShowCpuTemp     = prefs.getBoolean("game_bar_cpu_temp_enable", false);
        mShowRam         = prefs.getBoolean("game_bar_ram_enable", false);

        mShowGpuUsage    = prefs.getBoolean("game_bar_gpu_usage_enable", true);
        mShowGpuClock    = prefs.getBoolean("game_bar_gpu_clock_enable", false);
        mShowGpuTemp     = prefs.getBoolean("game_bar_gpu_temp_enable", false);

        mShowRamSpeed    = prefs.getBoolean("game_bar_ram_speed_enable", false);
        mShowRamTemp     = prefs.getBoolean("game_bar_ram_temp_enable", false);

        mDoubleTapCaptureEnabled = prefs.getBoolean("game_bar_doubletap_capture", true);
        mSingleTapToggleEnabled  = prefs.getBoolean("game_bar_single_tap_toggle", true);

        updateSplitMode(prefs.getString("game_bar_split_mode", "side_by_side"));
        updateTextSize(prefs.getInt("game_bar_text_size", 14));
        updateBackgroundAlpha(prefs.getInt("game_bar_background_alpha", 128));
        updateCornerRadius(prefs.getInt("game_bar_corner_radius", 90));
        updatePadding(prefs.getInt("game_bar_padding", 8));
        updateTitleColor(prefs.getString("game_bar_title_color", "#FFFFFF"));
        updateValueColor(prefs.getString("game_bar_value_color", "#4CAF50"));
        updateOverlayFormat(prefs.getString("game_bar_format", "full"));
        updateUpdateInterval(prefs.getString("game_bar_update_interval", "1000"));
        updatePosition(prefs.getString("game_bar_position", "top_center"));

        int spacing = prefs.getInt("game_bar_item_spacing", 8);
        updateItemSpacing(spacing);

        mLongPressEnabled = prefs.getBoolean("game_bar_longpress_enable", true);
        String lpTimeoutStr = prefs.getString("game_bar_longpress_timeout", "500");
        try {
            long lpt = Long.parseLong(lpTimeoutStr);
            setLongPressThresholdMs(lpt);
        } catch (NumberFormatException ignored) {}
    }

    public void show() {
        if (mIsShowing) return;

        applyPreferences();

        mLayoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        if ("draggable".equals(mPosition)) {
            mDraggable = true;
            loadSavedPosition(mLayoutParams);
            if (mLayoutParams.x == 0 && mLayoutParams.y == 0) {
                mLayoutParams.gravity = Gravity.TOP | Gravity.START;
                mLayoutParams.x = 0;
                mLayoutParams.y = 100;
            }
        } else {
            mDraggable = false;
            applyPosition(mLayoutParams, mPosition);
        }

        mOverlayView = new LinearLayout(mContext);
        mOverlayView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        mRootLayout = (LinearLayout) mOverlayView;
        applySplitMode();
        applyBackgroundStyle();
        applyPadding();

        mOverlayView.setOnTouchListener((v, event) -> {
            if (mGestureDetector != null && mGestureDetector.onTouchEvent(event)) {
                return true;
            }
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (mDraggable) {
                        initialX = mLayoutParams.x;
                        initialY = mLayoutParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                    }
                    if (mLongPressEnabled) {
                        mPressActive = true;
                        mDownX = event.getRawX();
                        mDownY = event.getRawY();
                        mHandler.postDelayed(mLongPressRunnable, mLongPressThresholdMs);
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (mLongPressEnabled && mPressActive) {
                        float dx = Math.abs(event.getRawX() - mDownX);
                        float dy = Math.abs(event.getRawY() - mDownY);
                        if (dx > TOUCH_SLOP || dy > TOUCH_SLOP) {
                            mPressActive = false;
                            mHandler.removeCallbacks(mLongPressRunnable);
                        }
                    }
                    if (mDraggable) {
                        int deltaX = (int) (event.getRawX() - initialTouchX);
                        int deltaY = (int) (event.getRawY() - initialTouchY);
                        mLayoutParams.x = initialX + deltaX;
                        mLayoutParams.y = initialY + deltaY;
                        mWindowManager.updateViewLayout(mOverlayView, mLayoutParams);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (mLongPressEnabled && mPressActive) {
                        mPressActive = false;
                        mHandler.removeCallbacks(mLongPressRunnable);
                    }
                    if (mDraggable) {
                        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
                        prefs.edit()
                                .putInt(PREF_KEY_X, mLayoutParams.x)
                                .putInt(PREF_KEY_Y, mLayoutParams.y)
                                .apply();
                    }
                    return true;
            }
            return false;
        });

        mWindowManager.addView(mOverlayView, mLayoutParams);
        mIsShowing = true;

        applyEntryAnimation();

        startUpdates();

        // Start the FPS meter if using the new API method.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            GameBarFpsMeter.getInstance(mContext).start();
        }
    }

    /**
     * Applies the selected entry animation based on user preference.
     * Available styles: "bubble", "particle", "fade"
     */
    private void applyEntryAnimation() {
        switch (mAnimationStyle) {
            case "bubble":
                animateBubbleEntry();
                break;
            case "particle":
                animateParticleExplosionEntry();
                break;
            case "vortex":
                animateVortexEntry();
                break;
            case "fade":
            default:
                animateFadeEntry();
                break;
        }
    }

    /**
     * Real particle explosion: Creates actual particle elements that 
     * fly out from center in all directions.
     */
    private void animateParticleExplosionEntry() {
        if (mOverlayView == null || mWindowManager == null) return;

        // Ocultar vista principal temporalmente
        mOverlayView.setAlpha(0f);
        mOverlayView.setScaleX(0.1f);
        mOverlayView.setScaleY(0.1f);

        // Crear partículas directamente en el WindowManager
        int[] colors = {Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, 
                    Color.BLUE, Color.MAGENTA, Color.WHITE, Color.parseColor("#FF9800")};
        
        final List<View> particles = new ArrayList<>();
        
        for (int i = 0; i < 8; i++) {
            View particle = new View(mContext);
            particle.setBackgroundColor(colors[i]);
            
            // Tamaño de partícula
            int size = dpToPx(mContext, 8);
            
            // Layout params para WindowManager
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            );
            
            // Posicionar en el centro (donde está el overlay)
            if (mLayoutParams != null) {
                params.gravity = Gravity.TOP | Gravity.START;
                params.x = mLayoutParams.x + (mOverlayView.getWidth() / 2) - (size / 2);
                params.y = mLayoutParams.y + (mOverlayView.getHeight() / 2) - (size / 2);
            } else {
                params.gravity = Gravity.CENTER;
            }
            
            particle.setLayoutParams(params);
            
            // Añadir al WindowManager
            mWindowManager.addView(particle, params);
            particles.add(particle);
            
            // Calcular dirección de explosión
            double angle = Math.PI * 2 * i / 8;
            float distance = dpToPx(mContext, 100);
            float endX = (float) (Math.cos(angle) * distance);
            float endY = (float) (Math.sin(angle) * distance);
            
            // Animación de partícula
            AnimatorSet particleAnim = new AnimatorSet();
            particleAnim.playTogether(
                ObjectAnimator.ofFloat(particle, "translationX", 0f, endX),
                ObjectAnimator.ofFloat(particle, "translationY", 0f, endY),
                ObjectAnimator.ofFloat(particle, "scaleX", 1f, 0.5f),
                ObjectAnimator.ofFloat(particle, "scaleY", 1f, 0.5f),
                ObjectAnimator.ofFloat(particle, "alpha", 1f, 0f)
            );
            particleAnim.setDuration(800);
            particleAnim.setStartDelay(i * 50L); // Efecto escalonado
            
            // Remover partícula después de la animación
            particleAnim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    try {
                        mWindowManager.removeView(particle);
                    } catch (Exception e) {
                        // Ignorar si ya fue removida
                    }
                }
            });
            particleAnim.start();
        }

        // Mostrar vista principal después de las partículas
        new Handler().postDelayed(() -> {
            AnimatorSet mainAppear = new AnimatorSet();
            mainAppear.playTogether(
                ObjectAnimator.ofFloat(mOverlayView, "alpha", 0f, 1f),
                ObjectAnimator.ofFloat(mOverlayView, "scaleX", 0.1f, 1f),
                ObjectAnimator.ofFloat(mOverlayView, "scaleY", 0.1f, 1f),
                ObjectAnimator.ofFloat(mOverlayView, "rotation", 0f, 360f)
            );
            mainAppear.setDuration(600);
            mainAppear.setInterpolator(new OvershootInterpolator(1.2f));
            mainAppear.start();
        }, 400);
    }

    /**
    * Slow-motion bubble animation: Elegant bubble formation with smooth,
    * graceful movements that can be fully appreciated.
    * Perfect for showing off the beautiful bubble effect.
    */
    private void animateBubbleEntry() {
        if (mOverlayView == null) return;

        // Initial state - tiny invisible dot (bubble seed)
        mOverlayView.setScaleX(0f);
        mOverlayView.setScaleY(0f);
        mOverlayView.setAlpha(0f);
        mOverlayView.setRotation(0f);

        AnimatorSet bubbleSequence = new AnimatorSet();

        // Phase 1: Slow bubble formation (1 second)
        ObjectAnimator inflateX = ObjectAnimator.ofFloat(mOverlayView, "scaleX", 0f, 1.15f);
        ObjectAnimator inflateY = ObjectAnimator.ofFloat(mOverlayView, "scaleY", 0f, 1.15f);
        ObjectAnimator fadeIn = ObjectAnimator.ofFloat(mOverlayView, "alpha", 0f, 0.8f);

        inflateX.setDuration(1000);
        inflateY.setDuration(1000);
        fadeIn.setDuration(800);

        // Phase 2: Gentle contraction and stabilization (0.8 seconds)
        ObjectAnimator stabilizeX = ObjectAnimator.ofFloat(mOverlayView, "scaleX", 1.15f, 0.92f);
        ObjectAnimator stabilizeY = ObjectAnimator.ofFloat(mOverlayView, "scaleY", 1.15f, 0.92f);
        ObjectAnimator fadeFull = ObjectAnimator.ofFloat(mOverlayView, "alpha", 0.8f, 0.95f);

        stabilizeX.setDuration(800);
        stabilizeY.setDuration(800);
        fadeFull.setDuration(600);

        // Phase 3: Delicate wobble sequence (1.2 seconds total)
        // First gentle wobble
        ObjectAnimator wobble1X = ObjectAnimator.ofFloat(mOverlayView, "scaleX", 0.92f, 1.05f);
        ObjectAnimator wobble1Y = ObjectAnimator.ofFloat(mOverlayView, "scaleY", 0.92f, 0.96f);
        ObjectAnimator rotate1 = ObjectAnimator.ofFloat(mOverlayView, "rotation", 0f, -3f);

        wobble1X.setDuration(400);
        wobble1Y.setDuration(400);
        rotate1.setDuration(400);

        // Second counter-wobble
        ObjectAnimator wobble2X = ObjectAnimator.ofFloat(mOverlayView, "scaleX", 1.05f, 0.98f);
        ObjectAnimator wobble2Y = ObjectAnimator.ofFloat(mOverlayView, "scaleY", 0.96f, 1.03f);
        ObjectAnimator rotate2 = ObjectAnimator.ofFloat(mOverlayView, "rotation", -3f, 2f);

        wobble2X.setDuration(400);
        wobble2Y.setDuration(400);
        rotate2.setDuration(400);

        // Final perfect alignment
        ObjectAnimator finalX = ObjectAnimator.ofFloat(mOverlayView, "scaleX", 0.98f, 1f);
        ObjectAnimator finalY = ObjectAnimator.ofFloat(mOverlayView, "scaleY", 1.03f, 1f);
        ObjectAnimator finalRotate = ObjectAnimator.ofFloat(mOverlayView, "rotation", 2f, 0f);
        ObjectAnimator finalFade = ObjectAnimator.ofFloat(mOverlayView, "alpha", 0.95f, 1f);

        finalX.setDuration(400);
        finalY.setDuration(400);
        finalRotate.setDuration(400);
        finalFade.setDuration(400);

        // Combine wobble phases
        AnimatorSet firstWobble = new AnimatorSet();
        firstWobble.playTogether(wobble1X, wobble1Y, rotate1);

        AnimatorSet secondWobble = new AnimatorSet();
        secondWobble.playTogether(wobble2X, wobble2Y, rotate2);

        AnimatorSet finalWobble = new AnimatorSet();
        finalWobble.playTogether(finalX, finalY, finalRotate, finalFade);

        // Complete slow-motion sequence
        bubbleSequence.play(inflateX).with(inflateY).with(fadeIn);
        bubbleSequence.play(stabilizeX).with(stabilizeY).with(fadeFull).after(1000);
        bubbleSequence.play(firstWobble).after(1800);
        bubbleSequence.play(secondWobble).after(2200);
        bubbleSequence.play(finalWobble).after(2600);

        bubbleSequence.start();
    }

    /**
    * Vortex animation: Creates a spinning vortex effect that expands
    * and then settles into position. More like a transforming whirlwind
    * than particle explosion.
    */
    private void animateVortexEntry() {
        if (mOverlayView == null) return;

        // Initial state - small and centered
        mOverlayView.setScaleX(0.1f);
        mOverlayView.setScaleY(0.1f);
        mOverlayView.setAlpha(0f);
        mOverlayView.setRotation(0f);

        AnimatorSet explosion = new AnimatorSet();

        // Phase 1: Initial explosion ("particles" fly out)
        ObjectAnimator vortexX = ObjectAnimator.ofFloat(mOverlayView, "scaleX", 0.1f, 1.3f);
        ObjectAnimator vortexY = ObjectAnimator.ofFloat(mOverlayView, "scaleY", 0.1f, 1.3f);
        ObjectAnimator vortexRotate = ObjectAnimator.ofFloat(mOverlayView, "rotation", 0f, 360f);
        ObjectAnimator vortexFade = ObjectAnimator.ofFloat(mOverlayView, "alpha", 0f, 0.7f);

        vortexX.setDuration(300);
        vortexY.setDuration(300);
        vortexRotate.setDuration(400);
        vortexFade.setDuration(200);

        // Phase 2: Gentle contraction to normal size
        ObjectAnimator settleX = ObjectAnimator.ofFloat(mOverlayView, "scaleX", 1.3f, 1f);
        ObjectAnimator settleY = ObjectAnimator.ofFloat(mOverlayView, "scaleY", 1.3f, 1f);
        ObjectAnimator settleFade = ObjectAnimator.ofFloat(mOverlayView, "alpha", 0.7f, 1f);
        ObjectAnimator settleRotate = ObjectAnimator.ofFloat(mOverlayView, "rotation", 360f, 0f);

        settleX.setDuration(200);
        settleY.setDuration(200);
        settleFade.setDuration(150);
        settleRotate.setDuration(250);

        // Sequence: explosion → settlement
        explosion.play(vortexX).with(vortexY).with(vortexRotate).with(vortexFade);
        explosion.play(settleX).with(settleY).with(settleFade).with(settleRotate).after(300);

        explosion.setInterpolator(new OvershootInterpolator(1.5f));
        explosion.start();
    }

    /**
     * Fade animation: Simple fade-in with slight scale effect.
     * The view fades in while gently scaling up from 90% to 100% size.
     * Uses DecelerateInterpolator for smooth entry.
     */
    private void animateFadeEntry() {
        if (mOverlayView == null) return;

        mOverlayView.setAlpha(0f);
        mOverlayView.setScaleX(0.9f);
        mOverlayView.setScaleY(0.9f);

        AnimatorSet fadeIn = new AnimatorSet();
        fadeIn.playTogether(
            ObjectAnimator.ofFloat(mOverlayView, "alpha", 0f, 1f),
            ObjectAnimator.ofFloat(mOverlayView, "scaleX", 0.9f, 1f),
            ObjectAnimator.ofFloat(mOverlayView, "scaleY", 0.9f, 1f)
        );
        fadeIn.setDuration(650);
        fadeIn.setInterpolator(new DecelerateInterpolator());
        fadeIn.start();
    }

    /* ==============================Finish animations */

    private int initialX, initialY;
    private float initialTouchX, initialTouchY;

    public void hide() {
        if (!mIsShowing || mWindowManager == null) return;

        // Stop updates immediately but DON'T remove view yet
        stopUpdates();

        // Apply exit animation and remove view when animation completes
        applyExitAnimation(() -> {
            try {
                if (mOverlayView != null) {
                    mWindowManager.removeView(mOverlayView);
                }
            } catch (Exception e) {
                // Ignore exceptions during removal
            } finally {
                // Cleanup resources
                mOverlayView = null;
                mRootLayout = null;
                mIsShowing = false;

                // Stop FPS meter
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    GameBarFpsMeter.getInstance(mContext).stop();
                }
            }
        });
    }

    /**
     * Applies the selected exit animation based on user preference.
     * Available styles: "bubble", "particle", "vortex", "fade"
     * @param onComplete Callback to execute when animation completes
     */
    private void applyExitAnimation(Runnable onComplete) {
        switch (mAnimationStyle) {
            case "bubble":
                animateBubbleExit(onComplete);
                break;
            case "particle":
                animateRealParticleExplosionExit(onComplete);
                break;
            case "vortex":
                animateVortexExit(onComplete);
                break;
            case "fade":
            default:
                animateFadeExit(onComplete);
                break;
        }
    }

    /**
    * Slow graceful bubble exit: Bubble gently floats away and disappears
    */
    private void animateBubbleExit(Runnable onComplete) {
        AnimatorSet floatAway = new AnimatorSet();

        // Gentle float-up and fade away
        floatAway.playTogether(
            ObjectAnimator.ofFloat(mOverlayView, "translationY", 0f, -120f),
            ObjectAnimator.ofFloat(mOverlayView, "alpha", 1f, 0f),
            ObjectAnimator.ofFloat(mOverlayView, "scaleX", 1f, 1.1f),
            ObjectAnimator.ofFloat(mOverlayView, "scaleY", 1f, 1.1f),
            ObjectAnimator.ofFloat(mOverlayView, "rotation", 0f, 8f)
        );
        floatAway.setDuration(1200);
        floatAway.setInterpolator(new DecelerateInterpolator());
        floatAway.addListener(createAnimationListener(onComplete));
        floatAway.start();
    }

    /**
     * Particle vortex animation: Reverse of particle explosion.
     * The view shrinks rapidly with rotation, simulating particles collapsing inward.
     * Uses AccelerateInterpolator for quick disappearance.
     */
    private void animateVortexExit(Runnable onComplete) {
        AnimatorSet implosion = new AnimatorSet();
        implosion.playTogether(
            ObjectAnimator.ofFloat(mOverlayView, "scaleX", 1f, 0.1f),
            ObjectAnimator.ofFloat(mOverlayView, "scaleY", 1f, 0.1f),
            ObjectAnimator.ofFloat(mOverlayView, "alpha", 1f, 0f),
            ObjectAnimator.ofFloat(mOverlayView, "rotation", 0f, -180f)
        );
        implosion.setDuration(300);
        implosion.setInterpolator(new AccelerateInterpolator());
        implosion.addListener(createAnimationListener(onComplete));
        implosion.start();
    }

    /**
     * Fade exit animation: Simple fade-out with slight scale effect.
     * The view fades out while gently scaling down to 80% size.
     * Smooth disappearing effect.
     */
    private void animateFadeExit(Runnable onComplete) {
        AnimatorSet fadeOut = new AnimatorSet();
        fadeOut.playTogether(
            ObjectAnimator.ofFloat(mOverlayView, "alpha", 1f, 0f),
            ObjectAnimator.ofFloat(mOverlayView, "scaleX", 1f, 0.8f),
            ObjectAnimator.ofFloat(mOverlayView, "scaleY", 1f, 0.8f)
        );
        fadeOut.setDuration(300);
        fadeOut.addListener(createAnimationListener(onComplete));
        fadeOut.start();
    }

    /**
     * Real particle implosion: Particles fly back to center and disappear.
     */
    private void animateRealParticleExplosionExit(Runnable onComplete) {
        if (mOverlayView == null || mWindowManager == null) {
            onComplete.run();
            return;
        }

        // Ocultar vista principal
        mOverlayView.setAlpha(0f);

        // Crear partículas que vuelvan al centro
        int[] colors = {Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, 
                    Color.BLUE, Color.MAGENTA, Color.WHITE, Color.parseColor("#FF9800")};
        
        final List<View> particles = new ArrayList<>();
        
        for (int i = 0; i < 8; i++) {
            View particle = new View(mContext);
            particle.setBackgroundColor(colors[i]);
            
            int size = dpToPx(mContext, 8);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            );
            
            // Posición inicial (explotada)
            double angle = Math.PI * 2 * i / 8;
            float distance = dpToPx(mContext, 100);
            float startX = (float) (Math.cos(angle) * distance);
            float startY = (float) (Math.sin(angle) * distance);
            
            if (mLayoutParams != null) {
                params.gravity = Gravity.TOP | Gravity.START;
                params.x = mLayoutParams.x + (mOverlayView.getWidth() / 2) - (size / 2) + (int)startX;
                params.y = mLayoutParams.y + (mOverlayView.getHeight() / 2) - (size / 2) + (int)startY;
            } else {
                params.gravity = Gravity.CENTER;
            }
            
            particle.setLayoutParams(params);
            particle.setTranslationX(0); // Reset para animación
            particle.setTranslationY(0);
            
            mWindowManager.addView(particle, params);
            particles.add(particle);
            
            // Animación de implosión (vuelven al centro)
            AnimatorSet implosion = new AnimatorSet();
            implosion.playTogether(
                ObjectAnimator.ofFloat(particle, "translationX", startX, 0f),
                ObjectAnimator.ofFloat(particle, "translationY", startY, 0f),
                ObjectAnimator.ofFloat(particle, "scaleX", 1f, 0f),
                ObjectAnimator.ofFloat(particle, "scaleY", 1f, 0f),
                ObjectAnimator.ofFloat(particle, "alpha", 1f, 0f)
            );
            implosion.setDuration(600);
            implosion.setStartDelay(i * 40L);
            implosion.start();
            
            implosion.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    try {
                        mWindowManager.removeView(particle);
                    } catch (Exception e) {
                        // Ignorar
                    }
                }
            });
        }

        // Ejecutar completion después
        new Handler().postDelayed(onComplete, 800);
    }

     /**
     * Creates an animation listener that executes the completion callback.
     * @param onComplete Callback to run when animation ends
     * @return AnimatorListener that handles animation completion
     */
    private Animator.AnimatorListener createAnimationListener(Runnable onComplete) {
        return new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                onComplete.run();
            }
        };
    }

    private void stopUpdates() {
        if (mHandler != null) {
            mHandler.removeCallbacks(mUpdateRunnable);
            mHandler.removeCallbacks(mLongPressRunnable);
            mHandler.removeCallbacksAndMessages(null);
        }
    }

    public void cleanup() {
        hide();
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
        }
        mGestureDetector = null;
        mBgDrawable = null;
        mLayoutParams = null;
    }

    private void updateStats() {
        if (!mIsShowing || mRootLayout == null) return;

        // Always clear views to prevent duplication
        mRootLayout.removeAllViews();
        mLayoutChanged = false;

        // Create fresh views each time
        List<View> statViews = new ArrayList<>(10);

        // 1) FPS
        float fpsVal = GameBarFpsMeter.getInstance(mContext).getFps();
        String fpsStr = fpsVal >= 0 ? String.format(Locale.getDefault(), "%.0f", fpsVal) : "N/A";
        if (mShowFps) {
            statViews.add(createStatLine("FPS", fpsStr));
        }

        // 2) Battery temp
        String batteryTempStr = "N/A";
        if (mShowBatteryTemp) {
            
            //Get battery temp from sys path
            batteryTempStr = readBatteryTemperature();

            //fallback  if sys fails or returned N/A
            if (batteryTempStr.equals("N/A")) {
                String apiTemp = getBatteryTemperatureString(mContext);
                // Only update if the API returned a valid value
                if (apiTemp != null && !apiTemp.equals("N/A")) {
                    batteryTempStr = apiTemp;
                }
            }

            statViews.add(createStatLine("Temp", batteryTempStr));

        }

        // 3) CPU usage
        String cpuUsageStr = "N/A";
        if (mShowCpuUsage) {
            cpuUsageStr = GameBarCpuInfo.getCpuUsage();
            String display = "N/A".equals(cpuUsageStr) ? "N/A" : cpuUsageStr + "%";
            statViews.add(createStatLine("CPU", display));
        }

        // 4) CPU freq
        if (mShowCpuClock) {
            List<String> freqs = GameBarCpuInfo.getCpuFrequencies();
            if (!freqs.isEmpty()) {
                statViews.add(buildCpuFreqView(freqs));
            }
        }

        // 5) CPU temp
        String cpuTempStr = "N/A";
        if (mShowCpuTemp) {
            cpuTempStr = GameBarCpuInfo.getCpuTemp();
            statViews.add(createStatLine("CPU Temp", "N/A".equals(cpuTempStr) ? "N/A" : cpuTempStr + "°C"));
        }

        // 6) RAM usage
        String ramStr = "N/A";
        if (mShowRam) {
            ramStr = GameBarMemInfo.getRamUsage();
            statViews.add(createStatLine("RAM", "N/A".equals(ramStr) ? "N/A" : ramStr + " MB"));
        }

        // 6.1) RAM speed
        if (mShowRamSpeed) {
            String ramSpeedStr = GameBarMemInfo.getRamSpeed();
            statViews.add(createStatLine("RAM Freq", ramSpeedStr));
        }

        // 6.2) RAM temp
        if (mShowRamTemp) {
            String ramTempStr = GameBarMemInfo.getRamTemp();
            statViews.add(createStatLine("RAM Temp", ramTempStr));
        }

        // 7) GPU usage
        String gpuUsageStr = "N/A";
        if (mShowGpuUsage) {
            gpuUsageStr = GameBarGpuInfo.getGpuUsage();
            statViews.add(createStatLine("GPU", "N/A".equals(gpuUsageStr) ? "N/A" : gpuUsageStr + "%"));
        }

        // 8) GPU clock
        String gpuClockStr = "N/A";
        if (mShowGpuClock) {
            gpuClockStr = GameBarGpuInfo.getGpuClock();
            statViews.add(createStatLine("GPU Freq", "N/A".equals(gpuClockStr) ? "N/A" : gpuClockStr + "MHz"));
        }

        // 9) GPU temp
        String gpuTempStr = "N/A";
        if (mShowGpuTemp) {
            gpuTempStr = GameBarGpuInfo.getGpuTemp();
            statViews.add(createStatLine("GPU Temp", "N/A".equals(gpuTempStr) ? "N/A" : gpuTempStr + "°C"));
        }

        if ("side_by_side".equals(mSplitMode)) {
            mRootLayout.setOrientation(LinearLayout.HORIZONTAL);
            if ("minimal".equals(mOverlayFormat)) {
                for (int i = 0; i < statViews.size(); i++) {
                    mRootLayout.addView(statViews.get(i));
                    if (i < statViews.size() - 1) {
                        mRootLayout.addView(createDotView());
                    }
                }
            } else {
                for (View view : statViews) {
                    mRootLayout.addView(view);
                }
            }
        } else {
            mRootLayout.setOrientation(LinearLayout.VERTICAL);
            for (View view : statViews) {
                mRootLayout.addView(view);
            }
        }

        if (GameDataExport.getInstance().isCapturing()) {
            String dateTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            String pkgName = ForegroundAppDetector.getForegroundPackageName(mContext);

            GameDataExport.getInstance().addOverlayData(
                    dateTime,
                    pkgName,
                    fpsStr,
                    batteryTempStr,
                    cpuUsageStr,
                    cpuTempStr,
                    gpuUsageStr,
                    gpuClockStr,
                    gpuTempStr
            );
        }

        if (mLayoutParams != null && mOverlayView != null && mWindowManager != null) {
            try {
                mWindowManager.updateViewLayout(mOverlayView, mLayoutParams);
            } catch (Exception e) {
                // View might be in invalid state, ignore
            }
        }
    }

    private View buildCpuFreqView(List<String> freqs) {
        LinearLayout freqContainer = new LinearLayout(mContext);
        freqContainer.setOrientation(LinearLayout.HORIZONTAL);

        int spacingPx = dpToPx(mContext, mItemSpacingDp);
        LinearLayout.LayoutParams outerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        outerLp.setMargins(spacingPx, spacingPx / 2, spacingPx, spacingPx / 2);
        freqContainer.setLayoutParams(outerLp);

        if ("full".equals(mOverlayFormat)) {
            TextView labelTv = new TextView(mContext);
            labelTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, mTextSizeSp);
            try {
                labelTv.setTextColor(Color.parseColor(mTitleColorHex));
            } catch (Exception e) {
                labelTv.setTextColor(Color.WHITE);
            }
            labelTv.setText("CPU Freq ");
            freqContainer.addView(labelTv);
        }

        LinearLayout verticalFreqs = new LinearLayout(mContext);
        verticalFreqs.setOrientation(LinearLayout.VERTICAL);

        for (String freqLine : freqs) {
            LinearLayout lineLayout = new LinearLayout(mContext);
            lineLayout.setOrientation(LinearLayout.HORIZONTAL);

            TextView freqTv = new TextView(mContext);
            freqTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, mTextSizeSp);
            try {
                freqTv.setTextColor(Color.parseColor(mValueColorHex));
            } catch (Exception e) {
                freqTv.setTextColor(Color.WHITE);
            }
            freqTv.setText(freqLine);

            lineLayout.addView(freqTv);

            LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lineLp.setMargins(spacingPx, spacingPx / 4, spacingPx, spacingPx / 4);
            lineLayout.setLayoutParams(lineLp);

            verticalFreqs.addView(lineLayout);
        }

        freqContainer.addView(verticalFreqs);
        return freqContainer;
    }

    private LinearLayout createStatLine(String title, String rawValue) {
        LinearLayout lineLayout = new LinearLayout(mContext);
        lineLayout.setOrientation(LinearLayout.HORIZONTAL);

        if ("full".equals(mOverlayFormat)) {
            TextView tvTitle = new TextView(mContext);
            tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, mTextSizeSp);
            try {
                tvTitle.setTextColor(Color.parseColor(mTitleColorHex));
            } catch (Exception e) {
                tvTitle.setTextColor(Color.WHITE);
            }
            tvTitle.setText(title.isEmpty() ? "" : title + " ");

            TextView tvValue = new TextView(mContext);
            tvValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, mTextSizeSp);
            try {
                tvValue.setTextColor(Color.parseColor(mValueColorHex));
            } catch (Exception e) {
                tvValue.setTextColor(Color.WHITE);
            }
            tvValue.setText(rawValue);

            lineLayout.addView(tvTitle);
            lineLayout.addView(tvValue);
        } else {
            TextView tvMinimal = new TextView(mContext);
            tvMinimal.setTextSize(TypedValue.COMPLEX_UNIT_SP, mTextSizeSp);
            try {
                tvMinimal.setTextColor(Color.parseColor(mValueColorHex));
            } catch (Exception e) {
                tvMinimal.setTextColor(Color.WHITE);
            }
            tvMinimal.setText(rawValue);
            lineLayout.addView(tvMinimal);
        }

        int spacingPx = dpToPx(mContext, mItemSpacingDp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(spacingPx, spacingPx / 2, spacingPx, spacingPx / 2);
        lineLayout.setLayoutParams(lp);

        return lineLayout;
    }

    private View createDotView() {
        TextView dotView = new TextView(mContext);
        dotView.setTextSize(TypedValue.COMPLEX_UNIT_SP, mTextSizeSp);
        try {
            dotView.setTextColor(Color.parseColor(mValueColorHex));
        } catch (Exception e) {
            dotView.setTextColor(Color.WHITE);
        }
        dotView.setText(" . ");
        return dotView;
    }

    public void setShowBatteryTemp(boolean show) { mShowBatteryTemp = show; }
    public void setShowCpuUsage(boolean show)    { mShowCpuUsage = show; }
    public void setShowCpuClock(boolean show)    { mShowCpuClock = show; }
    public void setShowCpuTemp(boolean show)     { mShowCpuTemp = show; }
    public void setShowRam(boolean show)         { mShowRam = show; }
    public void setShowFps(boolean show)         { mShowFps = show; }

    public void setShowGpuUsage(boolean show)    { mShowGpuUsage = show; }
    public void setShowGpuClock(boolean show)    { mShowGpuClock = show; }
    public void setShowGpuTemp(boolean show)     { mShowGpuTemp = show; }

    public void setShowRamSpeed(boolean show) { mShowRamSpeed = show; }
    public void setShowRamTemp(boolean show) { mShowRamTemp = show; }

    public void updateTextSize(int sp) {
        mTextSizeSp = sp;
    }

    public void updateCornerRadius(int radius) {
        mCornerRadius = radius;
        applyBackgroundStyle();
    }

    public void updateBackgroundAlpha(int alpha) {
        mBackgroundAlpha = alpha;
        applyBackgroundStyle();
    }

    public void updatePadding(int dp) {
        mPaddingDp = dp;
        applyPadding();
    }

    public void updateTitleColor(String hex) {
        mTitleColorHex = hex;
    }

    public void updateValueColor(String hex) {
        mValueColorHex = hex;
    }

    public void updateOverlayFormat(String format) {
        mOverlayFormat = format;
        if (mIsShowing) {
            updateStats();
        }
    }

    public void updateItemSpacing(int dp) {
        mItemSpacingDp = dp;
        if (mIsShowing) {
            updateStats();
        }
    }

    private void applyBackgroundStyle() {
        int color = Color.argb(mBackgroundAlpha, 0, 0, 0);
        mBgDrawable.setColor(color);
        mBgDrawable.setCornerRadius(mCornerRadius);

        if (mOverlayView != null) {
            mOverlayView.setBackground(mBgDrawable);
        }
    }

    private void applyPadding() {
        if (mRootLayout != null) {
            int px = dpToPx(mContext, mPaddingDp);
            mRootLayout.setPadding(px, px, px, px);
        }
    }

    public void updatePosition(String pos) {
        mPosition = pos;
        if (mIsShowing && mOverlayView != null && mLayoutParams != null) {
            if ("draggable".equals(mPosition)) {
                mDraggable = true;
                loadSavedPosition(mLayoutParams);
                if (mLayoutParams.x == 0 && mLayoutParams.y == 0) {
                    mLayoutParams.gravity = Gravity.TOP | Gravity.START;
                    mLayoutParams.x = 0;
                    mLayoutParams.y = 100;
                }
            } else {
                mDraggable = false;
                applyPosition(mLayoutParams, mPosition);
            }
            mWindowManager.updateViewLayout(mOverlayView, mLayoutParams);
        }
    }

    public void updateSplitMode(String mode) {
        mSplitMode = mode;
        if (mIsShowing && mOverlayView != null) {
            applySplitMode();
            updateStats();
        }
    }

    public void updateUpdateInterval(String intervalStr) {
        try {
            mUpdateIntervalMs = Integer.parseInt(intervalStr);
        } catch (NumberFormatException e) {
            mUpdateIntervalMs = 1000;
        }
        if (mIsShowing) {
            startUpdates();
        }
    }

    public void setLongPressEnabled(boolean enabled) {
        mLongPressEnabled = enabled;
    }
    public void setLongPressThresholdMs(long ms) {
        mLongPressThresholdMs = ms;
    }

    public void setDoubleTapCaptureEnabled(boolean enabled) {
        mDoubleTapCaptureEnabled = enabled;
    }

    public void setSingleTapToggleEnabled(boolean enabled) {
        mSingleTapToggleEnabled = enabled;
    }

    private void startUpdates() {
        mHandler.removeCallbacksAndMessages(null);
        mHandler.post(mUpdateRunnable);
    }

    private void applySplitMode() {
        if (mRootLayout == null) return;
        if ("side_by_side".equals(mSplitMode)) {
            mRootLayout.setOrientation(LinearLayout.HORIZONTAL);
        } else {
            mRootLayout.setOrientation(LinearLayout.VERTICAL);
        }
    }

    private void loadSavedPosition(WindowManager.LayoutParams lp) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        int savedX = prefs.getInt(PREF_KEY_X, Integer.MIN_VALUE);
        int savedY = prefs.getInt(PREF_KEY_Y, Integer.MIN_VALUE);
        if (savedX != Integer.MIN_VALUE && savedY != Integer.MIN_VALUE) {
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = savedX;
            lp.y = savedY;
        }
    }

    private void applyPosition(WindowManager.LayoutParams lp, String pos) {
        switch (pos) {
            case "top_left":
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.x = 0;
                lp.y = 100;
                break;
            case "top_center":
                lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                lp.y = 100;
                break;
            case "top_right":
                lp.gravity = Gravity.TOP | Gravity.END;
                lp.x = 0;
                lp.y = 100;
                break;
            case "bottom_left":
                lp.gravity = Gravity.BOTTOM | Gravity.START;
                lp.x = 0;
                lp.y = 100;
                break;
            case "bottom_center":
                lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                lp.y = 100;
                break;
            case "bottom_right":
                lp.gravity = Gravity.BOTTOM | Gravity.END;
                lp.x = 0;
                lp.y = 100;
                break;
            default:
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.x = 0;
                lp.y = 100;
                break;
        }
    }

    private String readLine(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            return br.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private void openOverlaySettings() {
        try {
            Intent intent = new Intent(mContext, GameBarSettingsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(intent);
        } catch (Exception e) {
            // Exception ignored
        }
    }

    private static int dpToPx(Context context, int dp) {
        float scale = context.getResources().getDisplayMetrics().density;
        return Math.round(dp * scale);
    }

    /**
     * Converts raw temperature values from various Android devices to Celsius.
     * Different manufacturers use different formats and units:
     * - OnePlus: Often uses microvolts (µV) or custom scales
     * - Xiaomi: Typically uses deci-degrees (tenths of °C) or direct °C
     * - Motorola: Usually follows standard Android (deci-degrees)
     * - Samsung: Generally uses deci-degrees
     * 
     * @param raw The raw integer value read from the sysfs file
     * @param path The sysfs path where the value was read from (for debugging)
     * @return Temperature in Celsius, or Float.NaN if conversion fails
     */
    private float convertUniversalBatteryTemperature(int raw, String path) {
        // Log raw value for debugging
        Log.d("TempConversion", "Converting raw value: " + raw + " from path: " + path);
        
        // First, check for obviously invalid values
        if (raw == 0 || raw == -1 || raw == 255 || raw == 65535) {
            return Float.NaN;
        }
        
        // 1. Very large values (likely microvolts - common in OnePlus and some custom ROMs)
        if (raw > 100000 && raw < 1000000) {
            // OnePlus style conversion: (raw / 1000 - 273)
            float test1 = (raw / 1000f - 273f);
            if (isReasonableTemperature(test1)) {
                Log.d("TempConversion", "Detected µV format: " + raw + " -> " + test1 + "°C");
                return test1;
            }
            
            // Alternative conversion for different sensor types
            float test2 = (raw - 500000) / 1000f;
            if (isReasonableTemperature(test2)) {
                Log.d("TempConversion", "Detected µV format (alt): " + raw + " -> " + test2 + "°C");
                return test2;
            }
        }
        
        // 2. Standard Android format: deci-degrees Celsius (tenths of °C)
        // This works for Motorola, Samsung, most Xiaomi, and stock Android devices
        // Typical range: 200-400 (20.0°C - 40.0°C)
        if (raw >= 150 && raw <= 600) { // 15°C to 60°C in deci-degrees
            float deciCelsius = raw / 10f;
            if (isReasonableTemperature(deciCelsius)) {
                Log.d("TempConversion", "Detected deci-°C: " + raw + " -> " + deciCelsius + "°C");
                return deciCelsius;
            }
        }
        
        // 3. Direct Celsius (some Xiaomi and custom kernels)
        // Typical range: 15-50 (direct degrees Celsius)
        if (raw >= 10 && raw <= 80) {
            Log.d("TempConversion", "Detected direct °C: " + raw + " -> " + raw + "°C");
            return raw;
        }
        
        // 4. Moderate values that might be millivolts or custom scales
        if (raw > 1000 && raw < 100000) {
            // Try millivolt to Celsius conversion
            float test1 = raw / 1000f;
            if (isReasonableTemperature(test1)) {
                Log.d("TempConversion", "Detected mV format: " + raw + " -> " + test1 + "°C");
                return test1;
            }
            
            // Try alternative scaling
            float test2 = raw / 100f;
            if (isReasonableTemperature(test2)) {
                Log.d("TempConversion", "Detected /100 format: " + raw + " -> " + test2 + "°C");
                return test2;
            }
        }
        
        Log.d("TempConversion", "No valid conversion found for: " + raw);
        return Float.NaN;
    }

    /**
     * Checks if a temperature value is within reasonable battery temperature range
     */
    private boolean isReasonableTemperature(float celsius) {
        return celsius >= 0 && celsius <= 80;
    }

    private String readBatteryTemperature() {
        for (String path : BATTERY_TEMP_PATHS) {
            String temp = readLine(path);
            if (temp != null && !temp.isEmpty()) {
                try {
                    int raw = Integer.parseInt(temp.trim());
                    float celsius = convertUniversalBatteryTemperature(raw, path);
                    
                    if (!Float.isNaN(celsius)) {
                        return String.format(Locale.getDefault(), "%.1f", celsius) + "°C";
                    }
                } catch (NumberFormatException e) {
                    continue;
                }
            }
        }
        return "N/A";
    }

    public String getBatteryTemperatureString(Context context) {
        
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        
        // Registering the Receiver with “null” returns the current “sticky” Intent 
        // with battery information, without the need for a persistent Receiver.
        Intent batteryStatus = context.registerReceiver(null, ifilter);

        if (batteryStatus != null) {
            // Obtain the value of EXTRA_TEMPERATURE
            int rawTemp = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);

            if (rawTemp >= 0) {
                // The value is in tenths of a degree Celsius.
                float c = rawTemp / 10f;
                return String.format(Locale.getDefault(), "%.1f", c) + "°C";
            }
        }
        return "N/A";
    }

    /**
     * Debug method to log all available battery temperature paths and their raw values
     * This helps identify which paths are accessible and what format they use
     */
    private void debugBatteryTemperature() {
        Log.d("BatteryDebug", "=== Battery Temperature Debug ===");
        
        for (String path : BATTERY_TEMP_PATHS) {
            try {
                String value = readLine(path);
                if (value != null && !value.isEmpty()) {
                    int raw = Integer.parseInt(value.trim());
                    float converted = convertUniversalBatteryTemperature(raw, path);
                    
                    Log.d("BatteryDebug", 
                        "Path: " + path + 
                        " | Raw: " + raw + 
                        " | Converted: " + (!Float.isNaN(converted) ? String.format("%.1f °C", converted) : "N/A") +
                        " | Hex: 0x" + Integer.toHexString(raw));
                } else {
                    Log.d("BatteryDebug", "Path: " + path + " | No data or inaccessible");
                }
            } catch (Exception e) {
                Log.d("BatteryDebug", "Path: " + path + " | Error: " + e.getMessage());
            }
        }
        
        Log.d("BatteryDebug", "=== End Debug ===");
    }
}
