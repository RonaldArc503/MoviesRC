package sv.edu.catolica.rex.ui.auth;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.FirebaseUser;

import sv.edu.catolica.rex.R;
import sv.edu.catolica.rex.ui.home.HomeActivity;

public class LoginActivity extends AppCompatActivity {

    private static final int RC_GOOGLE_SIGN_IN = 9001;

    private AuthManager authManager;
    private TextInputLayout inputEmail;
    private TextInputLayout inputPassword;
    private TextInputLayout inputNameLayout;
    private TextView tvError;
    private TextView tvToggleMode;
    private TextView tvForgotPassword;
    private Button btnLogin;
    private Button btnGoogle;
    private View progressOverlay;
    private boolean isRegisterMode = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        authManager = AuthManager.getInstance();

        if (authManager.isSignedIn()) {
            startHomeActivity();
            return;
        }

        setContentView(R.layout.activity_login);

        inputEmail = findViewById(R.id.input_email);
        inputPassword = findViewById(R.id.input_password);
        inputNameLayout = findViewById(R.id.input_name_layout);
        tvError = findViewById(R.id.tv_error);
        tvToggleMode = findViewById(R.id.tv_toggle_mode);
        tvForgotPassword = findViewById(R.id.tv_forgot_password);
        btnLogin = findViewById(R.id.btn_login);
        btnGoogle = findViewById(R.id.btn_google);

        btnLogin.setOnClickListener(v -> performAuth());
        btnGoogle.setOnClickListener(v -> {
            try {
                authManager.signInWithGoogle(this);
            } catch (Exception e) {
                Log.w("GoogleSignIn", "Error al iniciar Google", e);
                showError("Error con Google: " + (e.getMessage() != null ? e.getMessage() : "desconocido"));
            }
        });
        tvForgotPassword.setOnClickListener(v -> showForgotPasswordDialog());

        logGoogleConfigDiagnostics();

        tvToggleMode.setOnClickListener(v -> {
            isRegisterMode = !isRegisterMode;
            inputNameLayout.setVisibility(isRegisterMode ? View.VISIBLE : View.GONE);
            btnLogin.setText(isRegisterMode ? "Crear cuenta" : "Iniciar sesión");
            tvToggleMode.setText(isRegisterMode
                    ? "¿Ya tienes cuenta? Inicia sesión"
                    : "¿No tienes cuenta? Regístrate");
        });
    }

    private void performAuth() {
        String email = inputEmail.getEditText() != null
                ? inputEmail.getEditText().getText().toString().trim() : "";
        String password = inputPassword.getEditText() != null
                ? inputPassword.getEditText().getText().toString().trim() : "";
        String name = inputNameLayout.getEditText() != null
                ? inputNameLayout.getEditText().getText().toString().trim() : "";

        if (TextUtils.isEmpty(email) || TextUtils.isEmpty(password)) {
            showError("Completa todos los campos");
            return;
        }

        if (password.length() < 6) {
            showError("La contraseña debe tener al menos 6 caracteres");
            return;
        }

        if (isRegisterMode && TextUtils.isEmpty(name)) {
            showError("Ingresa tu nombre");
            return;
        }

        hideError();
        btnLogin.setEnabled(false);

        if (isRegisterMode) {
            authManager.registerWithEmail(email, password, name, new AuthManager.AuthCallback() {
                @Override
                public void onSuccess(FirebaseUser user) {
                    btnLogin.setEnabled(true);
                    showToast("Cuenta creada exitosamente");
                    startHomeActivity();
                }

                @Override
                public void onError(Exception e) {
                    btnLogin.setEnabled(true);
                    showError(e != null ? e.getLocalizedMessage() : "Error al registrarse");
                }
            });
        } else {
            authManager.loginWithEmail(email, password, new AuthManager.AuthCallback() {
                @Override
                public void onSuccess(FirebaseUser user) {
                    btnLogin.setEnabled(true);
                    startHomeActivity();
                }

                @Override
                public void onError(Exception e) {
                    btnLogin.setEnabled(true);
                    showError(e != null ? e.getLocalizedMessage() : "Error al iniciar sesión");
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_GOOGLE_SIGN_IN) {
            authManager.handleGoogleResult(this, data, new AuthManager.AuthCallback() {
                @Override
                public void onSuccess(FirebaseUser user) {
                    showToast("Bienvenido");
                    startHomeActivity();
                }

                @Override
                public void onError(Exception e) {
                    String msg = e != null ? e.getLocalizedMessage() : "Error con Google Sign-In";
                    if (e instanceof com.google.android.gms.common.api.ApiException) {
                        int code = ((com.google.android.gms.common.api.ApiException) e).getStatusCode();
                        // 10 = STATUS_DEVELOPER_ERROR: SHA-1/paquete no registrado en Firebase
                        if (code == 10) {
                            msg = "Configuración de Google incorrecta (10). Registra el SHA-1 de la firma en Firebase Console.";
                        } else {
                            msg = "Error de Google (" + code + ")";
                        }
                    }
                    showError(msg);
                }
            });
        }
    }

    /** Registra en Logcat el client_id y el SHA-1 del certificado de firma,
     *  para verificar que coincidan con los registrados en Firebase Console. */
    private void logGoogleConfigDiagnostics() {
        try {
            String clientId = authManager.getGoogleClientId(this);
            android.util.Log.d("GoogleSignIn", "default_web_client_id=" + clientId);

            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(
                    getPackageName(), android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
            if (info.signingInfo != null) {
                for (android.content.pm.Signature sig : info.signingInfo.getApkContentsSigners()) {
                    java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
                    byte[] digest = md.digest(sig.toByteArray());
                    StringBuilder sb = new StringBuilder();
                    for (byte b : digest) {
                        sb.append(String.format(java.util.Locale.US, "%02X:", b));
                    }
                    if (sb.length() > 0) sb.setLength(sb.length() - 1);
                    android.util.Log.d("GoogleSignIn", "SHA-1 (" + getPackageName() + "): " + sb);
                }
            }
        } catch (Exception e) {
            android.util.Log.w("GoogleSignIn", "diagnostics failed", e);
        }
    }

    private void showForgotPasswordDialog() {
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
        builder.setTitle("Recuperar contraseña");

        EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        input.setHint("Correo electrónico");
        if (inputEmail.getEditText() != null && !inputEmail.getEditText().getText().toString().trim().isEmpty()) {
            input.setText(inputEmail.getEditText().getText().toString().trim());
        }
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        builder.setView(input);

        builder.setPositiveButton("Enviar", (dialog, which) -> {
            String email = input.getText().toString().trim();
            if (TextUtils.isEmpty(email)) {
                showError("Ingresa tu correo para recuperar la contraseña");
                return;
            }
            authManager.sendPasswordReset(email, new AuthManager.AuthCallback() {
                @Override
                public void onSuccess(com.google.firebase.auth.FirebaseUser user) {
                    showToast("Revisa tu correo para restablecer tu contraseña");
                }

                @Override
                public void onError(Exception e) {
                    showError(e != null ? e.getLocalizedMessage() : "No se pudo enviar el correo");
                }
            });
        });
        builder.setNegativeButton("Cancelar", null);
        builder.show();
    }

    private void startHomeActivity() {
        Intent intent = new Intent(this, HomeActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void showError(String msg) {
        tvError.setText(msg);
        tvError.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        tvError.setVisibility(View.GONE);
    }

    private void showToast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
