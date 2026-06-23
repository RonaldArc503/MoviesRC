package sv.edu.catolica.rex.ui.auth;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
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
        btnLogin = findViewById(R.id.btn_login);
        btnGoogle = findViewById(R.id.btn_google);

        btnLogin.setOnClickListener(v -> performAuth());
        btnGoogle.setOnClickListener(v -> authManager.signInWithGoogle(this));

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
                    showError(e != null ? e.getLocalizedMessage() : "Error con Google Sign-In");
                }
            });
        }
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
