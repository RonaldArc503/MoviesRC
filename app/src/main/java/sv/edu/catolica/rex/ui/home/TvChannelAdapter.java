package sv.edu.catolica.rex.ui.home;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import java.util.ArrayList;
import java.util.List;
import sv.edu.catolica.rex.R;
import sv.edu.catolica.rex.models.TvChannel;

public class TvChannelAdapter extends RecyclerView.Adapter<TvChannelAdapter.ChannelViewHolder> {

    public interface OnChannelClickListener {
        void onChannelClick(TvChannel channel);
    }

    private final List<TvChannel> channels = new ArrayList<>();
    private final OnChannelClickListener listener;

    public TvChannelAdapter(List<TvChannel> channelList, OnChannelClickListener listener) {
        this.listener = listener;
        if (channelList != null) {
            this.channels.addAll(channelList);
        }
    }

    public void setChannels(List<TvChannel> newChannels) {
        channels.clear();
        if (newChannels != null) {
            channels.addAll(newChannels);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ChannelViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_tv_channel, parent, false);
        return new ChannelViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ChannelViewHolder holder, int position) {
        TvChannel channel = channels.get(position);
        holder.bind(channel, listener);
    }

    @Override
    public int getItemCount() {
        return channels.size();
    }

    static class ChannelViewHolder extends RecyclerView.ViewHolder {

        private final ImageView ivLogo;
        private final TextView tvTitle;
        private final TextView tvCategory;
        private final TextView tvBadgeLive;
        private final TextView tvSourceType;
        private final ImageView ivPlay;

        public ChannelViewHolder(@NonNull View itemView) {
            super(itemView);
            ivLogo = itemView.findViewById(R.id.iv_channel_logo);
            tvTitle = itemView.findViewById(R.id.tv_channel_title);
            tvCategory = itemView.findViewById(R.id.tv_channel_category);
            tvBadgeLive = itemView.findViewById(R.id.tv_channel_badge_live);
            tvSourceType = itemView.findViewById(R.id.tv_channel_source_type);
            ivPlay = itemView.findViewById(R.id.iv_play_indicator);

            itemView.setOnFocusChangeListener((v, hasFocus) -> {
                v.animate()
                        .scaleX(hasFocus ? 1.04f : 1.0f)
                        .scaleY(hasFocus ? 1.04f : 1.0f)
                        .setDuration(140L)
                        .start();
                v.setSelected(hasFocus);
                if (ivPlay != null) {
                    ivPlay.setColorFilter(hasFocus ? 0xFFFFFFFF : 0xFFE63540);
                }
            });
        }

        public void bind(TvChannel channel, OnChannelClickListener listener) {
            if (channel == null) return;

            tvTitle.setText(channel.getName());

            if (channel.getCategory() != null && !channel.getCategory().isEmpty()) {
                tvCategory.setText(channel.getCategory());
                tvCategory.setVisibility(View.VISIBLE);
            } else {
                tvCategory.setVisibility(View.GONE);
            }

            if (tvSourceType != null) {
                if (channel.isYouTube()) {
                    tvSourceType.setText("WEB");
                } else {
                    tvSourceType.setText("HD");
                }
                tvSourceType.setVisibility(View.VISIBLE);
            }

            // Load poster / logo: prioritize local bundled drawable (instant, offline, no 429)
            Context context = itemView.getContext();
            int localResId = 0;
            if (channel.getId() != null && !channel.getId().isEmpty()) {
                String cleanId = channel.getId().toLowerCase().replace('-', '_');
                localResId = context.getResources().getIdentifier("logo_" + cleanId, "drawable", context.getPackageName());
            }

            // Cancel any pending Glide request on this recycled view
            Glide.with(context).clear(ivLogo);

            if (localResId != 0) {
                // Direct synchronous set: 0ms, exact native bounds, no placeholder or crossfade scale glitches
                ivLogo.setImageResource(localResId);
            } else {
                String poster = channel.getPosterUrl();
                if (poster != null && !poster.trim().isEmpty()) {
                    GlideUrl glideUrl = new GlideUrl(poster, new LazyHeaders.Builder()
                            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                            .addHeader("Referer", "https://teleon.tv/")
                            .build());

                    Glide.with(context)
                            .load(glideUrl)
                            .fitCenter()
                            .into(ivLogo);
                } else {
                    ivLogo.setImageDrawable(null);
                }
            }

            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onChannelClick(channel);
                }
            });
        }
    }
}
