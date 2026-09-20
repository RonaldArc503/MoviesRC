package sv.edu.catolica.rex.ui.home;

import android.content.res.Configuration;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import sv.edu.catolica.rex.R;
import sv.edu.catolica.rex.models.TvChannel;
import sv.edu.catolica.rex.repository.TvChannelsRepository;
import sv.edu.catolica.rex.ui.player.FootballWebPlayerActivity;
import sv.edu.catolica.rex.ui.player.PlayerExoActivity;

public class TvChannelsActivity extends AppCompatActivity {

    private RecyclerView rvChannels;
    private TvChannelAdapter adapter;
    private TextView tvSubtitle;
    private View layoutEmptyState;
    private EditText etSearch;
    private ImageView btnClearSearch;

    private TextView chipAll;
    private TextView chipNacionales;
    private TextView chipNoticias;
    private TextView chipMovies;
    private TextView chipReligion;
    private TextView chipInternacional;

    private String currentCategory = TvChannelsRepository.CATEGORY_ALL;
    private String currentSearchQuery = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tv_channels);

        View backButton = findViewById(R.id.btn_back);
        if (backButton != null) {
            backButton.setOnClickListener(v -> finish());
        }

        tvSubtitle = findViewById(R.id.tv_channels_subtitle);
        layoutEmptyState = findViewById(R.id.layout_empty_state);
        rvChannels = findViewById(R.id.rv_tv_channels);
        etSearch = findViewById(R.id.et_search_channel);
        btnClearSearch = findViewById(R.id.btn_clear_search);

        setupSearch();
        setupChips();
        setupRecyclerView();

        selectCategory(TvChannelsRepository.CATEGORY_ALL);
    }

    private void setupSearch() {
        if (btnClearSearch != null && etSearch != null) {
            btnClearSearch.setOnClickListener(v -> {
                etSearch.setText("");
                etSearch.clearFocus();
            });
        }

        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    currentSearchQuery = s != null ? s.toString().trim() : "";
                    if (btnClearSearch != null) {
                        btnClearSearch.setVisibility(currentSearchQuery.isEmpty() ? View.GONE : View.VISIBLE);
                    }
                    applyFilters();
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });
        }
    }

    private void setupChips() {
        chipAll = findViewById(R.id.chip_cat_all);
        chipNacionales = findViewById(R.id.chip_cat_nacionales);
        chipNoticias = findViewById(R.id.chip_cat_noticias);
        chipMovies = findViewById(R.id.chip_cat_movies);
        chipReligion = findViewById(R.id.chip_cat_religion);
        chipInternacional = findViewById(R.id.chip_cat_internacional);

        bindChip(chipAll, TvChannelsRepository.CATEGORY_ALL);
        bindChip(chipNacionales, TvChannelsRepository.CATEGORY_NACIONALES);
        bindChip(chipNoticias, TvChannelsRepository.CATEGORY_NOTICIAS);
        bindChip(chipMovies, TvChannelsRepository.CATEGORY_MOVIES);
        bindChip(chipReligion, TvChannelsRepository.CATEGORY_RELIGION);
        bindChip(chipInternacional, TvChannelsRepository.CATEGORY_INTERNACIONAL);
    }

    private void bindChip(TextView chip, String category) {
        if (chip == null) return;
        chip.setOnClickListener(v -> selectCategory(category));
        chip.setOnFocusChangeListener((v, hasFocus) -> {
            v.animate()
                    .scaleX(hasFocus ? 1.08f : 1.0f)
                    .scaleY(hasFocus ? 1.08f : 1.0f)
                    .setDuration(120L)
                    .start();
            if (hasFocus) {
                selectCategory(category);
            }
        });
    }

    private void selectCategory(String category) {
        this.currentCategory = category;

        updateChipSelectedState(chipAll, TvChannelsRepository.CATEGORY_ALL.equalsIgnoreCase(category));
        updateChipSelectedState(chipNacionales, TvChannelsRepository.CATEGORY_NACIONALES.equalsIgnoreCase(category));
        updateChipSelectedState(chipNoticias, TvChannelsRepository.CATEGORY_NOTICIAS.equalsIgnoreCase(category));
        updateChipSelectedState(chipMovies, TvChannelsRepository.CATEGORY_MOVIES.equalsIgnoreCase(category));
        updateChipSelectedState(chipReligion, TvChannelsRepository.CATEGORY_RELIGION.equalsIgnoreCase(category));
        updateChipSelectedState(chipInternacional, TvChannelsRepository.CATEGORY_INTERNACIONAL.equalsIgnoreCase(category));

        applyFilters();
    }

    private void applyFilters() {
        List<TvChannel> base = TvChannelsRepository.getChannelsByCategory(currentCategory);
        List<TvChannel> result = new ArrayList<>();
        String query = currentSearchQuery.toLowerCase();

        for (TvChannel channel : base) {
            if (query.isEmpty()
                    || channel.getName().toLowerCase().contains(query)
                    || (channel.getCategory() != null && channel.getCategory().toLowerCase().contains(query))) {
                result.add(channel);
            }
        }

        if (adapter != null) {
            adapter.setChannels(result);
        }

        if (layoutEmptyState != null) {
            layoutEmptyState.setVisibility(result.isEmpty() ? View.VISIBLE : View.GONE);
        }

        if (tvSubtitle != null) {
            if (query.isEmpty()) {
                int count = result.size();
                String label = TvChannelsRepository.CATEGORY_ALL.equalsIgnoreCase(currentCategory)
                        ? count + " canales disponibles en vivo"
                        : count + " canales en " + currentCategory;
                tvSubtitle.setText(label);
            } else {
                tvSubtitle.setText(result.size() + " canales encontrados para \"" + currentSearchQuery + "\"");
            }
        }
    }

    private void updateChipSelectedState(TextView chip, boolean selected) {
        if (chip != null) {
            chip.setSelected(selected);
        }
    }

    private void setupRecyclerView() {
        if (rvChannels == null) return;

        int orientation = getResources().getConfiguration().orientation;
        int widthDp = getResources().getConfiguration().screenWidthDp;
        int spanCount = 2;
        if (widthDp >= 840) {
            spanCount = 4;
        } else if (widthDp >= 540 || orientation == Configuration.ORIENTATION_LANDSCAPE) {
            spanCount = 3;
        }

        GridLayoutManager gridLayoutManager = new GridLayoutManager(this, spanCount);
        rvChannels.setLayoutManager(gridLayoutManager);
        rvChannels.setHasFixedSize(true);

        adapter = new TvChannelAdapter(new ArrayList<>(), this::playChannel);
        rvChannels.setAdapter(adapter);
    }

    private void playChannel(TvChannel channel) {
        if (channel == null) return;

        if (channel.isYouTube()) {
            FootballWebPlayerActivity.start(
                    this,
                    channel.getHlsUrl(),
                    "https://teleon.tv/",
                    channel.getName()
            );
            return;
        }

        ArrayList<String> streams = channel.getStreamList();
        if (streams.isEmpty()) {
            Toast.makeText(this, "Canal no disponible por el momento", Toast.LENGTH_SHORT).show();
            return;
        }

        PlayerExoActivity.start(this, streams, channel.getName(), true, true);
    }
}
