package sv.edu.catolica.rex.ui.auth;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreSettings;

import java.util.ArrayList;
import java.util.List;

public class AuthManager {

    private static final String TAG = "AuthManager";
    private static final int RC_SIGN_IN = 9001;

    private static AuthManager instance;

    private final FirebaseAuth firebaseAuth;
    private final FirebaseFirestore firestore;
    private GoogleSignInClient googleSignInClient;
    private final List<AuthStateListener> listeners = new ArrayList<>();

    public interface AuthStateListener {
        void onAuthStateChanged(@Nullable FirebaseUser user);
    }

    private AuthManager() {
        firebaseAuth = FirebaseAuth.getInstance();
        firestore = FirebaseFirestore.getInstance();
        FirebaseFirestoreSettings settings = new FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(true)
                .setCacheSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                .build();
        firestore.setFirestoreSettings(settings);

        firebaseAuth.addAuthStateListener(firebaseAuth1 -> {
            FirebaseUser user = firebaseAuth1.getCurrentUser();
            for (AuthStateListener l : listeners) {
                l.onAuthStateChanged(user);
            }
        });
    }

    public static synchronized AuthManager getInstance() {
        if (instance == null) {
            instance = new AuthManager();
        }
        return instance;
    }

    public FirebaseAuth getFirebaseAuth() {
        return firebaseAuth;
    }

    public FirebaseFirestore getFirestore() {
        return firestore;
    }

    @Nullable
    public FirebaseUser getCurrentUser() {
        return firebaseAuth.getCurrentUser();
    }

    public boolean isSignedIn() {
        return firebaseAuth.getCurrentUser() != null;
    }

    public void addAuthStateListener(AuthStateListener listener) {
        listeners.add(listener);
    }

    public void removeAuthStateListener(AuthStateListener listener) {
        listeners.remove(listener);
    }

    public void loginWithEmail(String email, String password, final AuthCallback callback) {
        firebaseAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        callback.onSuccess(task.getResult().getUser());
                    } else {
                        callback.onError(task.getException());
                    }
                });
    }

    public void registerWithEmail(String email, String password, String displayName, final AuthCallback callback) {
        firebaseAuth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        FirebaseUser user = task.getResult().getUser();
                        if (user != null && displayName != null && !displayName.isEmpty()) {
                            com.google.firebase.auth.UserProfileChangeRequest profileUpdates =
                                    new com.google.firebase.auth.UserProfileChangeRequest.Builder()
                                            .setDisplayName(displayName)
                                            .build();
                            user.updateProfile(profileUpdates);
                        }
                        callback.onSuccess(user);
                    } else {
                        callback.onError(task.getException());
                    }
                });
    }

    public void signInWithGoogle(Activity activity) {
        try {
            if (googleSignInClient == null) {
                GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                        .requestIdToken(getGoogleClientId(activity))
                        .requestEmail()
                        .build();
                googleSignInClient = GoogleSignIn.getClient(activity, gso);
            }
            Intent signInIntent = googleSignInClient.getSignInIntent();
            activity.startActivityForResult(signInIntent, RC_SIGN_IN);
        } catch (Exception e) {
            Log.e(TAG, "signInWithGoogle error", e);
        }
    }

    public void handleGoogleResult(Activity activity, Intent data, AuthCallback callback) {
        Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
        try {
            GoogleSignInAccount account = task.getResult(ApiException.class);
            if (account == null) {
                callback.onError(new Exception("No se obtuvo cuenta de Google"));
                return;
            }
            String idToken = account.getIdToken();
            if (idToken == null || idToken.isEmpty()) {
                Log.w(TAG, "Google ID token is null. default_web_client_id=" + getGoogleClientId(activity));
                callback.onError(new Exception("No se pudo obtener el token de Google (revisa default_web_client_id)"));
                return;
            }
            AuthCredential credential = GoogleAuthProvider.getCredential(idToken, null);
            firebaseAuth.signInWithCredential(credential)
                    .addOnCompleteListener(activity, authTask -> {
                        if (authTask.isSuccessful()) {
                            callback.onSuccess(authTask.getResult().getUser());
                        } else {
                            Log.w(TAG, "signInWithCredential failed", authTask.getException());
                            callback.onError(authTask.getException());
                        }
                    });
        } catch (ApiException e) {
            // 10 = STATUS_DEVELOPER_ERROR (config), 12500 = ERROR, etc.
            Log.e(TAG, "Google sign-in API error, status=" + e.getStatusCode(), e);
            callback.onError(e);
        } catch (Exception e) {
            Log.e(TAG, "handleGoogleResult error", e);
            callback.onError(e);
        }
    }

    public void logout() {
        firebaseAuth.signOut();
        if (googleSignInClient != null) {
            googleSignInClient.signOut();
        }
    }

    public void sendPasswordReset(String email, final AuthCallback callback) {
        if (email == null || email.trim().isEmpty()) {
            if (callback != null) callback.onError(new Exception("Ingresa tu correo electrónico"));
            return;
        }
        firebaseAuth.sendPasswordResetEmail(email.trim())
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        if (callback != null) callback.onSuccess(firebaseAuth.getCurrentUser());
                    } else if (callback != null) {
                        callback.onError(task.getException());
                    }
                });
    }

    public interface AuthCallback {
        void onSuccess(FirebaseUser user);
        void onError(Exception e);
    }

    public String getGoogleClientId(Activity activity) {
        try {
            int id = activity.getResources().getIdentifier(
                    "default_web_client_id", "string", activity.getPackageName());
            return id != 0 ? activity.getString(id) : "";
        } catch (Exception e) {
            Log.w(TAG, "Could not find default_web_client_id", e);
            return "";
        }
    }
}
