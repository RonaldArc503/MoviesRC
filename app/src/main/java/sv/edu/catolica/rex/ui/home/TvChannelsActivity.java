package sv.edu.catolica.rex.ui.home;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import sv.edu.catolica.rex.R;
import sv.edu.catolica.rex.ui.player.PlayerContenidoActivity;

public class TvChannelsActivity extends AppCompatActivity {

    private static final String CANAL_4_URL = "https://cdn.jwplayer.com/players/D3kaa3Ky.html";
    private static final String CANAL_2_URL = "https://cdn.jwplayer.com/players/48WUA30M.html";

    // Posters provided by user
    private static final String CANAL_4_POSTER = "https://assets-jpcust.jwpsrv.com/thumbnails/WTdHpl8I-720.jpg";
    private static final String CANAL_2_POSTER = "https://assets-jpcust.jwpsrv.com/thumbnails/dx6qHNrT-720.jpg";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tv_channels);

        View backButton = findViewById(R.id.btn_back);
        View channel4 = findViewById(R.id.card_channel_4);
        View channel2 = findViewById(R.id.card_channel_2);
        TextView subtitle = findViewById(R.id.tv_channels_subtitle);

        if (subtitle != null) {
            subtitle.setText("Selecciona un canal en vivo");
        }

        if (backButton != null) {
            backButton.setOnClickListener(v -> finish());
        }

        ImageView ivChannel4 = findViewById(R.id.iv_channel_4_poster);
        ImageView ivChannel2 = findViewById(R.id.iv_channel_2_poster);

        if (channel4 != null) {
            channel4.setOnClickListener(v -> playChannel("Canal 4", CANAL_4_URL));
            channel4.setOnFocusChangeListener(this::animateFocusCard);
        }

        if (channel2 != null) {
            channel2.setOnClickListener(v -> playChannel("Canal 2", CANAL_2_URL));
            channel2.setOnFocusChangeListener(this::animateFocusCard);
        }

        // Load posters with Glide if available
        try {
            com.bumptech.glide.Glide.with(this).load(CANAL_4_POSTER)
                    .placeholder(R.drawable.placeholder_poster)
                    .into(ivChannel4);
        } catch (Exception ignored) {}

        try {
            com.bumptech.glide.Glide.with(this).load(CANAL_2_POSTER)
                    .placeholder(R.drawable.placeholder_poster)
                    .into(ivChannel2);
        } catch (Exception ignored) {}
    }

    private void playChannel(String title, String url) {
        ArrayList<String> urls = new ArrayList<>();
        urls.add(url);
        PlayerContenidoActivity.start(this, urls, title);
    }

    private void animateFocusCard(View view, boolean hasFocus) {
        if (view == null) {
            return;
        }
        view.animate()
                .scaleX(hasFocus ? 1.03f : 1.0f)
                .scaleY(hasFocus ? 1.03f : 1.0f)
                .setDuration(140L)
                .start();
        view.setSelected(hasFocus);
    }
}
