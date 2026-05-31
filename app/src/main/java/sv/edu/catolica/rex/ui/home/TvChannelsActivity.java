package sv.edu.catolica.rex.ui.home;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import sv.edu.catolica.rex.R;
import sv.edu.catolica.rex.ui.player.PlayerExoActivity;

public class TvChannelsActivity extends AppCompatActivity {

    private static final String CANAL_4_HLS = "https://cdn.jwplayer.com/live/broadcast/D3kaa3Ky.m3u8";
    private static final String CANAL_4_DASH = "https://cdn.jwplayer.com/live/broadcast/D3kaa3Ky.mpd";
    private static final String CANAL_2_HLS = "https://cdn.jwplayer.com/live/broadcast/48WUA30M.m3u8";
    private static final String CANAL_2_DASH = "https://cdn.jwplayer.com/live/broadcast/48WUA30M.mpd";
    private static final String CANAL_10_HLS = "https://streamingcws30.com/tves/videotves/chunks.m3u8";
    private static final String CANAL_12_HLS = "https://signal.teleon.live/stream/sv8_canal12.m3u8?token=free";

    // Posters provided by user
    private static final String CANAL_4_POSTER = "https://assets-jpcust.jwpsrv.com/thumbnails/WTdHpl8I-720.jpg";
    private static final String CANAL_2_POSTER = "https://assets-jpcust.jwpsrv.com/thumbnails/dx6qHNrT-720.jpg";
    private static final String CANAL_10_POSTER = "https://yt3.googleusercontent.com/463ZErwFd6NkeWDpIew5J5DCHsFe3_jnN4Z7a923LfPOFSSYXaU8qC4yz3-XkLLJMYBx7j-rKA=s900-c-k-c0x00ffffff-no-rj";
    private static final String CANAL_12_POSTER = "https://i.ytimg.com/vi/u-IHi7fXqyU/maxresdefault.jpg";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tv_channels);

        View backButton = findViewById(R.id.btn_back);
        View channel4 = findViewById(R.id.card_channel_4);
        View channel2 = findViewById(R.id.card_channel_2);
        View channel10 = findViewById(R.id.card_channel_10);
        View channel12 = findViewById(R.id.card_channel_12);
        TextView subtitle = findViewById(R.id.tv_channels_subtitle);

        if (subtitle != null) {
            subtitle.setText("Selecciona un canal en vivo");
        }

        if (backButton != null) {
            backButton.setOnClickListener(v -> finish());
        }

        ImageView ivChannel4 = findViewById(R.id.iv_channel_4_poster);
        ImageView ivChannel2 = findViewById(R.id.iv_channel_2_poster);
        ImageView ivChannel10 = findViewById(R.id.iv_channel_10_poster);
        ImageView ivChannel12 = findViewById(R.id.iv_channel_12_poster);

        if (channel4 != null) {
            channel4.setOnClickListener(v -> playChannel("Canal 4", CANAL_4_HLS, CANAL_4_DASH));
            channel4.setOnFocusChangeListener(this::animateFocusCard);
        }

        if (channel2 != null) {
            channel2.setOnClickListener(v -> playChannel("Canal 2", CANAL_2_HLS, CANAL_2_DASH));
            channel2.setOnFocusChangeListener(this::animateFocusCard);
        }

        if (channel10 != null) {
            channel10.setOnClickListener(v -> playChannel("Canal 10", CANAL_10_HLS, null));
            channel10.setOnFocusChangeListener(this::animateFocusCard);
        }

        if (channel12 != null) {
            channel12.setOnClickListener(v -> playChannel("Canal 12", CANAL_12_HLS, null));
            channel12.setOnFocusChangeListener(this::animateFocusCard);
        }

        loadPoster(ivChannel4, CANAL_4_POSTER);
        loadPoster(ivChannel2, CANAL_2_POSTER);
        loadPoster(ivChannel10, CANAL_10_POSTER);
        loadPoster(ivChannel12, CANAL_12_POSTER);
    }

    private void loadPoster(ImageView target, String posterUrl) {
        if (target == null) {
            return;
        }
        com.bumptech.glide.Glide.with(this)
                .load(posterUrl)
                .placeholder(R.drawable.placeholder_poster)
                .error(R.drawable.placeholder_poster)
                .centerCrop()
                .into(target);
    }

    private void playChannel(String title, String hlsUrl, String dashUrl) {
        ArrayList<String> streams = new ArrayList<>();
        streams.add(hlsUrl);
        if (dashUrl != null && !dashUrl.trim().isEmpty()) {
            streams.add(dashUrl);
        }
        PlayerExoActivity.start(this, streams, title, true);
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
