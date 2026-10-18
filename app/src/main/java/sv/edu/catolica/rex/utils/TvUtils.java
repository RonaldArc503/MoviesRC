package sv.edu.catolica.rex.utils;

import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;

public final class TvUtils {

    private TvUtils() {
    }

    /**
     * Comprueba de manera robusta y exhaustiva si el dispositivo actual es un Android TV / Google TV.
     * Evalúa UiModeManager, la máscara de configuración del sistema y las características de hardware
     * (FEATURE_LEANBACK y type.television).
     */
    public static boolean isTv(Context context) {
        if (context == null) {
            return false;
        }

        // 1. UiModeManager check
        try {
            UiModeManager uiModeManager = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
            if (uiModeManager != null && uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
                return true;
            }
        } catch (Exception ignored) {}

        // 2. Configuration check
        try {
            Configuration config = context.getResources().getConfiguration();
            if ((config.uiMode & Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION) {
                return true;
            }
        } catch (Exception ignored) {}

        // 3. PackageManager system features check
        try {
            PackageManager pm = context.getPackageManager();
            if (pm != null) {
                if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                    return true;
                }
                if (pm.hasSystemFeature("android.hardware.type.television")) {
                    return true;
                }
            }
        } catch (Exception ignored) {}

        return false;
    }
}
