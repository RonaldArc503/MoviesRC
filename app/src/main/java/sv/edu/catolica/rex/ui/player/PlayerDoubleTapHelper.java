package sv.edu.catolica.rex.ui.player;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.function.Supplier;

import sv.edu.catolica.rex.R;

/**
 * Controlador de doble toque tipo Netflix / YouTube para retroceder y adelantar 10s.
 * Diseñado exclusivamente para dispositivos móviles (se desactiva en Android TV).
 */
public class PlayerDoubleTapHelper {

    public interface SeekCallback {
        void onSeek(long deltaMs);
    }

    public interface SingleTapCallback {
        void onSingleTap();
    }

    private static final int DOUBLE_TAP_TIMEOUT = 280;
    private static final int CUMULATIVE_TIMEOUT = 650;

    private final Activity activity;
    private final boolean isTelevision;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private View rootOverlay;
    private View layoutLeft;
    private TextView tvLeftSeconds;
    private ImageView ivLeftIcon;

    private View layoutRight;
    private TextView tvRightSeconds;
    private ImageView ivRightIcon;

    private View btnSkipBack;
    private View btnSkipForward;

    private SeekCallback seekCallback;
    private SingleTapCallback singleTapCallback;
    private Supplier<Boolean> areControlsVisibleSupplier;
    private Runnable resetAutoHideRunnable;

    private float downX = 0f;
    private float downY = 0f;
    private boolean isMoving = false;

    private int activeZone = 0; // -1: izquierda, 1: derecha, 0: ninguno
    private long lastTapTime = 0L;
    private int accumulatedSecondsLeft = 0;
    private int accumulatedSecondsRight = 0;

    private Runnable singleTapRunnable;
    private Runnable hideLeftRunnable;
    private Runnable hideRightRunnable;

    public PlayerDoubleTapHelper(@NonNull Activity activity) {
        this.activity = activity;
        this.isTelevision = checkIsTelevision(activity);
    }

    private static boolean checkIsTelevision(Context context) {
        if (context == null) return false;
        UiModeManager uiModeManager = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
        if (uiModeManager != null && uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true;
        }
        int uiMode = context.getResources().getConfiguration().uiMode;
        return (uiMode & Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    public boolean isTelevision() {
        return isTelevision;
    }

    public void initViews(
            @Nullable View btnSkipBack,
            @Nullable View btnSkipForward,
            @NonNull SeekCallback seekCallback,
            @NonNull SingleTapCallback singleTapCallback,
            @NonNull Supplier<Boolean> areControlsVisibleSupplier,
            @Nullable Runnable resetAutoHideRunnable) {

        this.btnSkipBack = btnSkipBack;
        this.btnSkipForward = btnSkipForward;
        this.seekCallback = seekCallback;
        this.singleTapCallback = singleTapCallback;
        this.areControlsVisibleSupplier = areControlsVisibleSupplier;
        this.resetAutoHideRunnable = resetAutoHideRunnable;

        rootOverlay = activity.findViewById(R.id.player_double_tap_overlay);
        layoutLeft = activity.findViewById(R.id.layout_double_tap_left);
        tvLeftSeconds = activity.findViewById(R.id.tv_double_tap_left_seconds);
        ivLeftIcon = activity.findViewById(R.id.iv_double_tap_left_icon);

        layoutRight = activity.findViewById(R.id.layout_double_tap_right);
        tvRightSeconds = activity.findViewById(R.id.tv_double_tap_right_seconds);
        ivRightIcon = activity.findViewById(R.id.iv_double_tap_right_icon);

        if (layoutLeft != null) {
            layoutLeft.setVisibility(View.GONE);
            layoutLeft.setClickable(false);
            layoutLeft.setFocusable(false);
        }
        if (layoutRight != null) {
            layoutRight.setVisibility(View.GONE);
            layoutRight.setClickable(false);
            layoutRight.setFocusable(false);
        }
        if (rootOverlay != null) {
            rootOverlay.setClickable(false);
            rootOverlay.setFocusable(false);
        }
    }

    /**
     * Intercepta toques en la actividad para detectar doble tap en los extremos.
     * En Android TV retorna false inmediatamente sin interferir.
     */
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (isTelevision || ev == null || seekCallback == null) {
            return false;
        }

        int action = ev.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            downX = ev.getX();
            downY = ev.getY();
            isMoving = false;

            int zone = getTargetZone(downX, downY);
            if ((zone == -1 && accumulatedSecondsLeft > 0) || (zone == 1 && accumulatedSecondsRight > 0)) {
                // Secuencia activa de doble tap acumulativo: consumir DOWN para evitar clicks accidentales
                return true;
            }
            return false;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            float touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
            if (Math.abs(ev.getX() - downX) > touchSlop || Math.abs(ev.getY() - downY) > touchSlop) {
                isMoving = true;
                cancelSingleTap();
            }
            return false;
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            isMoving = false;
            cancelSingleTap();
            return false;
        }

        if (action == MotionEvent.ACTION_UP) {
            if (isMoving) {
                isMoving = false;
                return false;
            }

            float upX = ev.getX();
            float upY = ev.getY();
            int zone = getTargetZone(upX, upY);

            if (zone == 0) {
                // Zona central o fuera de las zonas laterales: cancelar esperas pendientes
                cancelSingleTap();
                return false;
            }

            long now = System.currentTimeMillis();

            // ── Zona Izquierda: Retroceder 10s ──────────────────────────────
            if (zone == -1) {
                boolean isDoubleTap = (activeZone == -1 && (now - lastTapTime) <= DOUBLE_TAP_TIMEOUT);
                boolean isConsecutive = (accumulatedSecondsLeft > 0 && (now - lastTapTime) <= CUMULATIVE_TIMEOUT);

                if (isDoubleTap || isConsecutive) {
                    cancelSingleTap();
                    activeZone = -1;
                    lastTapTime = now;
                    accumulatedSecondsLeft += 10;
                    accumulatedSecondsRight = 0;

                    seekCallback.onSeek(-10000);
                    showLeftAnimation(accumulatedSecondsLeft);
                    if (resetAutoHideRunnable != null) {
                        resetAutoHideRunnable.run();
                    }
                    return true;
                } else {
                    // Primer tap en la zona izquierda: registrar y esperar confirmación
                    cancelSingleTap();
                    activeZone = -1;
                    lastTapTime = now;
                    accumulatedSecondsLeft = 0;

                    final boolean controlsWereVisible = areControlsVisibleSupplier != null && areControlsVisibleSupplier.get();
                    final boolean tappedOnSkipButton = isPointInsideView(btnSkipBack, upX, upY);

                    singleTapRunnable = () -> {
                        activeZone = 0;
                        accumulatedSecondsLeft = 0;
                        if (controlsWereVisible) {
                            if (tappedOnSkipButton) {
                                seekCallback.onSeek(-10000);
                                showLeftAnimation(10);
                            } else if (singleTapCallback != null) {
                                singleTapCallback.onSingleTap();
                            }
                        } else if (singleTapCallback != null) {
                            singleTapCallback.onSingleTap();
                        }
                    };
                    handler.postDelayed(singleTapRunnable, DOUBLE_TAP_TIMEOUT);
                    return true;
                }
            }

            // ── Zona Derecha: Adelantar 10s ─────────────────────────────────
            if (zone == 1) {
                boolean isDoubleTap = (activeZone == 1 && (now - lastTapTime) <= DOUBLE_TAP_TIMEOUT);
                boolean isConsecutive = (accumulatedSecondsRight > 0 && (now - lastTapTime) <= CUMULATIVE_TIMEOUT);

                if (isDoubleTap || isConsecutive) {
                    cancelSingleTap();
                    activeZone = 1;
                    lastTapTime = now;
                    accumulatedSecondsRight += 10;
                    accumulatedSecondsLeft = 0;

                    seekCallback.onSeek(10000);
                    showRightAnimation(accumulatedSecondsRight);
                    if (resetAutoHideRunnable != null) {
                        resetAutoHideRunnable.run();
                    }
                    return true;
                } else {
                    // Primer tap en la zona derecha: registrar y esperar confirmación
                    cancelSingleTap();
                    activeZone = 1;
                    lastTapTime = now;
                    accumulatedSecondsRight = 0;

                    final boolean controlsWereVisible = areControlsVisibleSupplier != null && areControlsVisibleSupplier.get();
                    final boolean tappedOnSkipButton = isPointInsideView(btnSkipForward, upX, upY);

                    singleTapRunnable = () -> {
                        activeZone = 0;
                        accumulatedSecondsRight = 0;
                        if (controlsWereVisible) {
                            if (tappedOnSkipButton) {
                                seekCallback.onSeek(10000);
                                showRightAnimation(10);
                            } else if (singleTapCallback != null) {
                                singleTapCallback.onSingleTap();
                            }
                        } else if (singleTapCallback != null) {
                            singleTapCallback.onSingleTap();
                        }
                    };
                    handler.postDelayed(singleTapRunnable, DOUBLE_TAP_TIMEOUT);
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Determina en qué zona horizontal se encuentra el punto:
     * -1: Zona izquierda (desde el botón atrasar 10s hasta el extremo izquierdo)
     *  1: Zona derecha (desde el botón adelantar 10s hasta el extremo derecho)
     *  0: Zona central o fuera de límites (top bar / seekbar)
     */
    private int getTargetZone(float x, float y) {
        if (activity == null || activity.isFinishing()) return 0;
        View decor = activity.getWindow().getDecorView();
        int width = decor.getWidth();
        int height = decor.getHeight();
        if (width <= 0 || height <= 0) return 0;

        float density = activity.getResources().getDisplayMetrics().density;

        // Excluir barra superior (60dp) y barra inferior de seekbar (70dp)
        if (y < 55 * density || y > height - 70 * density) {
            return 0;
        }

        // Límite derecho de la zona izquierda
        float leftLimit;
        if (btnSkipBack != null && btnSkipBack.getWidth() > 0) {
            int[] loc = new int[2];
            btnSkipBack.getLocationOnScreen(loc);
            leftLimit = loc[0] + btnSkipBack.getWidth() + (16 * density);
        } else {
            leftLimit = width * 0.44f;
        }

        if (x <= leftLimit) {
            return -1;
        }

        // Límite izquierdo de la zona derecha
        float rightLimit;
        if (btnSkipForward != null && btnSkipForward.getWidth() > 0) {
            int[] loc = new int[2];
            btnSkipForward.getLocationOnScreen(loc);
            rightLimit = loc[0] - (16 * density);
        } else {
            rightLimit = width * 0.56f;
        }

        if (x >= rightLimit) {
            return 1;
        }

        return 0;
    }

    private boolean isPointInsideView(@Nullable View v, float x, float y) {
        if (v == null || v.getVisibility() != View.VISIBLE) return false;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return x >= loc[0] && x <= loc[0] + v.getWidth()
                && y >= loc[1] && y <= loc[1] + v.getHeight();
    }

    public void showLeftFeedback(int seconds) {
        if (isTelevision) return;
        showLeftAnimation(seconds);
    }

    public void showRightFeedback(int seconds) {
        if (isTelevision) return;
        showRightAnimation(seconds);
    }

    private void showLeftAnimation(int seconds) {
        if (layoutLeft == null || tvLeftSeconds == null) return;
        if (hideLeftRunnable != null) {
            handler.removeCallbacks(hideLeftRunnable);
        }

        tvLeftSeconds.setText(seconds + " segundos");

        if (layoutLeft.getVisibility() != View.VISIBLE) {
            layoutLeft.setVisibility(View.VISIBLE);
            layoutLeft.setAlpha(0f);
            layoutLeft.setScaleX(0.9f);
            layoutLeft.setScaleY(0.9f);
            layoutLeft.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(160)
                    .start();
        } else {
            // Efecto de pulso en pulsaciones consecutivas
            layoutLeft.animate()
                    .scaleX(1.08f)
                    .scaleY(1.08f)
                    .setDuration(90)
                    .withEndAction(() -> layoutLeft.animate().scaleX(1f).scaleY(1f).setDuration(90).start())
                    .start();
        }

        if (ivLeftIcon != null) {
            ivLeftIcon.animate()
                    .translationX(-16f)
                    .setDuration(100)
                    .withEndAction(() -> ivLeftIcon.animate().translationX(0f).setDuration(100).start())
                    .start();
        }

        hideLeftRunnable = () -> {
            if (layoutLeft != null) {
                layoutLeft.animate()
                        .alpha(0f)
                        .setDuration(220)
                        .withEndAction(() -> {
                            if (layoutLeft != null) layoutLeft.setVisibility(View.GONE);
                            accumulatedSecondsLeft = 0;
                            if (activeZone == -1) activeZone = 0;
                        })
                        .start();
            }
        };
        handler.postDelayed(hideLeftRunnable, CUMULATIVE_TIMEOUT);
    }

    private void showRightAnimation(int seconds) {
        if (layoutRight == null || tvRightSeconds == null) return;
        if (hideRightRunnable != null) {
            handler.removeCallbacks(hideRightRunnable);
        }

        tvRightSeconds.setText(seconds + " segundos");

        if (layoutRight.getVisibility() != View.VISIBLE) {
            layoutRight.setVisibility(View.VISIBLE);
            layoutRight.setAlpha(0f);
            layoutRight.setScaleX(0.9f);
            layoutRight.setScaleY(0.9f);
            layoutRight.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(160)
                    .start();
        } else {
            // Efecto de pulso en pulsaciones consecutivas
            layoutRight.animate()
                    .scaleX(1.08f)
                    .scaleY(1.08f)
                    .setDuration(90)
                    .withEndAction(() -> layoutRight.animate().scaleX(1f).scaleY(1f).setDuration(90).start())
                    .start();
        }

        if (ivRightIcon != null) {
            ivRightIcon.animate()
                    .translationX(16f)
                    .setDuration(100)
                    .withEndAction(() -> ivRightIcon.animate().translationX(0f).setDuration(100).start())
                    .start();
        }

        hideRightRunnable = () -> {
            if (layoutRight != null) {
                layoutRight.animate()
                        .alpha(0f)
                        .setDuration(220)
                        .withEndAction(() -> {
                            if (layoutRight != null) layoutRight.setVisibility(View.GONE);
                            accumulatedSecondsRight = 0;
                            if (activeZone == 1) activeZone = 0;
                        })
                        .start();
            }
        };
        handler.postDelayed(hideRightRunnable, CUMULATIVE_TIMEOUT);
    }

    private void cancelSingleTap() {
        if (singleTapRunnable != null) {
            handler.removeCallbacks(singleTapRunnable);
            singleTapRunnable = null;
        }
    }

    public void onDestroy() {
        cancelSingleTap();
        if (hideLeftRunnable != null) handler.removeCallbacks(hideLeftRunnable);
        if (hideRightRunnable != null) handler.removeCallbacks(hideRightRunnable);
        if (layoutLeft != null) layoutLeft.setVisibility(View.GONE);
        if (layoutRight != null) layoutRight.setVisibility(View.GONE);
    }
}
