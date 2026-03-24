package com.dlna.receiver;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;

import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.extractor.ExtractorsFactory;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.BandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.util.Util;

public class PlayerActivity extends AppCompatActivity {
    private static final String TAG = "PlayerActivity";
    private PlayerView playerView;
    private SimpleExoPlayer player;
    private TextView tvTime;
    private ImageButton btnPlayPause;
    private SeekBar seekBar;

    private String videoUri;
    private String videoTitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        playerView = findViewById(R.id.playerView);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        seekBar = findViewById(R.id.seekBar);
        tvTime = findViewById(R.id.tvTime);

        Intent intent = getIntent();
        videoUri = intent.getStringExtra("video_uri");
        videoTitle = intent.getStringExtra("video_title");

        if (videoTitle != null) {
            setTitle(videoTitle);
        }

        initializePlayer();
        preparePlayer();
    }

    private void initializePlayer() {
        try {
            // 创建带宽计量器
            BandwidthMeter bandwidthMeter = new DefaultBandwidthMeter();
            // 创建轨道选择器
            TrackSelection.Factory videoTrackSelectionFactory = new AdaptiveTrackSelection.Factory(bandwidthMeter);
            TrackSelector trackSelector = new DefaultTrackSelector(videoTrackSelectionFactory);
            // 创建播放器
            player = ExoPlayerFactory.newSimpleInstance(this, trackSelector);
            playerView.setPlayer(player);

            // 添加播放器监听器
            player.addListener(new SimpleExoPlayer.EventListener() {
                @Override
                public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                    if (playbackState == SimpleExoPlayer.STATE_READY) {
                        btnPlayPause.setImageResource(playWhenReady ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
                        seekBar.setMax((int) player.getDuration());
                        updateTimeDisplay();
                    } else if (playbackState == SimpleExoPlayer.STATE_ENDED) {
                        // 播放结束，通知DlnaService
                        btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
                        DlnaService dlnaService = DlnaService.getInstance();
                        if (dlnaService != null) {
                            dlnaService.stop();
                        }
                    }
                }

                @Override
                public void onPlayerError(com.google.android.exoplayer2.ExoPlaybackException error) {
                    Log.e(TAG, "播放器错误: " + error.getMessage());
                }

                @Override
                public void onPositionDiscontinuity(int reason) {
                }

                @Override
                public void onTimelineChanged(com.google.android.exoplayer2.Timeline timeline, Object manifest, int reason) {
                }

                @Override
                public void onTracksChanged(com.google.android.exoplayer2.source.TrackGroupArray trackGroups, com.google.android.exoplayer2.trackselection.TrackSelectionArray trackSelections) {
                }

                @Override
                public void onLoadingChanged(boolean isLoading) {
                }

                @Override
                public void onRepeatModeChanged(int repeatMode) {
                }

                @Override
                public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
                }

                @Override
                public void onPlaybackParametersChanged(com.google.android.exoplayer2.PlaybackParameters playbackParameters) {
                }

                @Override
                public void onSeekProcessed() {
                }
            });

            // 设置进度条监听器
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        player.seekTo(progress);
                        updateTimeDisplay();
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });

            // 设置播放/暂停按钮监听器
            btnPlayPause.setOnClickListener(v -> {
                if (player != null) {
                    boolean playWhenReady = !player.getPlayWhenReady();
                    player.setPlayWhenReady(playWhenReady);
                    btnPlayPause.setImageResource(playWhenReady ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
                }
            });

            // 启动定时更新进度条和时间显示
            new Thread(() -> {
                while (true) {
                    if (player != null) {
                        runOnUiThread(() -> {
                            if (player != null) {
                                long currentPosition = player.getCurrentPosition();
                                long duration = player.getDuration();
                                seekBar.setProgress((int) currentPosition);
                                updateTimeDisplay();
                                // 定期更新播放位置到DlnaService
                                DlnaService dlnaService = DlnaService.getInstance();
                                if (dlnaService != null && duration > 0) {
                                    Log.d("PlayerActivity", "Updating position: " + currentPosition + ", duration: " + duration);
                                    dlnaService.updatePosition(currentPosition, duration);
                                } else if (dlnaService == null) {
                                    Log.d("PlayerActivity", "DlnaService instance is null");
                                } else if (duration <= 0) {
                                    Log.d("PlayerActivity", "Invalid duration: " + duration);
                                }
                            }
                        });
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "初始化播放器失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void preparePlayer() {
        try {
            if (videoUri != null && player != null) {
                // 创建数据源工厂
                String userAgent = Util.getUserAgent(this, "DLNAReceiver");
                DataSource.Factory dataSourceFactory = new DefaultDataSourceFactory(this, userAgent);
                // 创建提取器工厂
                ExtractorsFactory extractorsFactory = new DefaultExtractorsFactory();
                // 创建媒体源
                MediaSource mediaSource = new ExtractorMediaSource(Uri.parse(videoUri), dataSourceFactory, extractorsFactory, null, null);
                // 准备播放器
                player.prepare(mediaSource);
                player.setPlayWhenReady(true);
            }
        } catch (Exception e) {
            Log.e(TAG, "准备播放器失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void updateTimeDisplay() {
        if (player != null && tvTime != null) {
            long currentPosition = player.getCurrentPosition();
            long duration = player.getDuration();
            tvTime.setText(formatTime(currentPosition) + " / " + formatTime(duration));
        }
    }

    private String formatTime(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        seconds = seconds % 60;
        minutes = minutes % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (Util.SDK_INT > 23) {
            initializePlayer();
            preparePlayer();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if ((Util.SDK_INT <= 23 || player == null)) {
            initializePlayer();
            preparePlayer();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (Util.SDK_INT <= 23) {
            releasePlayer();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (Util.SDK_INT > 23) {
            releasePlayer();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releasePlayer();
    }

    private void releasePlayer() {
        try {
            if (player != null) {
                player.release();
                player = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "释放播放器失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
