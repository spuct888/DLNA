package com.dlna.receiver;

import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.util.Util;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;

public class MainActivity extends android.support.v7.app.AppCompatActivity implements DlnaService.OnDlnaEventListener {

    private static final String TAG = "MainActivity";
    private TextView tvDeviceName;
    private TextView tvStatus;
    private TextView tvInstruction;
    private android.widget.LinearLayout infoLayout;
    private PlayerView playerView;
    private SimpleExoPlayer player;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Handler statusHandler = new Handler(Looper.getMainLooper());
    private Runnable statusCheckRunnable;
    private int currentRotation = 0; // 0, 90, 180, 270
    private float currentScale = 1.0f; // 缩放比例，1.0为原始大小
    private static final float SCALE_STEP = 0.1f; // 每次缩放的步长
    private static final float MIN_SCALE = 0.5f; // 最小缩放比例
    private static final float MAX_SCALE = 3.0f; // 最大缩放比例

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);

            tvDeviceName = findViewById(R.id.tvDeviceName);
            tvStatus = findViewById(R.id.tvStatus);
            tvInstruction = findViewById(R.id.tvInstruction);
            infoLayout = findViewById(R.id.infoLayout);
            playerView = findViewById(R.id.playerView);

            // 显示设备名称和IP
            String deviceName = getDeviceName();
            String ipAddress = getLocalIpAddress();
            tvDeviceName.setText("设备: " + deviceName + "\nIP: " + ipAddress);
            tvStatus.setText("准备就绪");

            // 初始化ExoPlayer
            initializePlayer();

            // 启动DLNA服务
            startDlnaService();
            
            // 设置DLNA事件监听器
            DlnaService service = DlnaService.getInstance();
            if (service != null) {
                service.setOnDlnaEventListener(this);
            }
            
            // 启动服务状态检查
            startServiceStatusCheck();
            
            // 启动定时更新播放位置到DlnaService
            startPositionUpdate();
        } catch (Exception e) {
            Log.e(TAG, "初始化失败: " + e.getMessage());
            e.printStackTrace();
            // 即使初始化失败，也显示基本信息
            if (tvDeviceName != null) {
                tvDeviceName.setText("设备: 未知\nIP: 未知");
            }
            if (tvStatus != null) {
                tvStatus.setText("初始化失败");
            }
        }
    }

    private void initializePlayer() {
        if (player == null) {
            TrackSelection.Factory adaptiveTrackSelectionFactory = new AdaptiveTrackSelection.Factory(null);
            TrackSelector trackSelector = new DefaultTrackSelector(adaptiveTrackSelectionFactory);
            player = ExoPlayerFactory.newSimpleInstance(this, trackSelector);
            playerView.setPlayer(player);
            
            // 设置播放状态监听
            player.addListener(new com.google.android.exoplayer2.Player.DefaultEventListener() {
                @Override
                public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                    Log.d(TAG, "播放器状态变化: playWhenReady=" + playWhenReady + ", playbackState=" + playbackState);
                    switch (playbackState) {
                        case com.google.android.exoplayer2.Player.STATE_READY:
                            Log.d(TAG, "视频准备完成，开始播放");
                            if (tvStatus != null && playWhenReady) {
                                tvStatus.setText("正在播放");
                                tvStatus.setTextColor(getResources().getColor(android.R.color.holo_green_light));
                                // 播放开始后隐藏所有状态信息
                                if (infoLayout != null) {
                                    infoLayout.setVisibility(View.GONE);
                                }
                                if (tvInstruction != null) {
                                    tvInstruction.setVisibility(View.GONE);
                                }
                            }
                            DlnaService service = DlnaService.getInstance();
                            if (service != null) {
                                service.setCurrentState(1); // PLAYING
                            }
                            break;
                        case com.google.android.exoplayer2.Player.STATE_ENDED:
                            Log.d(TAG, "视频播放完成");
                            if (tvStatus != null) {
                                tvStatus.setText("播放完成");
                                tvStatus.setTextColor(getResources().getColor(android.R.color.holo_blue_light));
                                // 视频结束后重新显示状态信息
                                if (infoLayout != null) {
                                    infoLayout.setVisibility(View.VISIBLE);
                                }
                                if (tvInstruction != null) {
                                    tvInstruction.setVisibility(View.VISIBLE);
                                }
                            }
                            DlnaService service2 = DlnaService.getInstance();
                            if (service2 != null) {
                                service2.setCurrentState(0); // STOPPED
                            }
                            break;
                        case com.google.android.exoplayer2.Player.STATE_BUFFERING:
                            Log.d(TAG, "视频缓冲中...");
                            break;
                        case com.google.android.exoplayer2.Player.STATE_IDLE:
                            Log.d(TAG, "播放器空闲");
                            // 播放器空闲时重新显示状态信息
                            if (infoLayout != null) {
                                infoLayout.setVisibility(View.VISIBLE);
                            }
                            if (tvInstruction != null) {
                                tvInstruction.setVisibility(View.VISIBLE);
                            }
                            if (tvStatus != null) {
                                tvStatus.setText("准备就绪");
                                tvStatus.setTextColor(getResources().getColor(android.R.color.holo_blue_light));
                            }
                            break;
                    }
                }

                @Override
                public void onPlayerError(com.google.android.exoplayer2.ExoPlaybackException error) {
                    Log.e(TAG, "视频播放错误: " + error.getMessage());
                    error.printStackTrace();
                    if (tvStatus != null) {
                        tvStatus.setText("播放错误: " + error.getMessage());
                        tvStatus.setTextColor(getResources().getColor(android.R.color.holo_red_light));
                    }
                    DlnaService service = DlnaService.getInstance();
                    if (service != null) {
                        service.setCurrentState(0); // STOPPED
                    }
                }
            });
        }
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void startServiceStatusCheck() {
        statusCheckRunnable = new Runnable() {
            @Override
            public void run() {
                checkServiceStatus();
                statusHandler.postDelayed(this, 1000); // 每秒检查一次
            }
        };
        statusHandler.post(statusCheckRunnable);
    }
    
    private void startPositionUpdate() {
        new Thread(() -> {
            while (true) {
                if (player != null) {
                    runOnUiThread(() -> {
                        if (player != null) {
                            long currentPosition = player.getCurrentPosition();
                            long duration = player.getDuration();
                            // 定期更新播放位置到DlnaService
                            DlnaService dlnaService = DlnaService.getInstance();
                            if (dlnaService != null && duration > 0) {
                                Log.d(TAG, "Updating position: " + currentPosition + ", duration: " + duration);
                                dlnaService.updatePosition(currentPosition, duration);
                            } else if (dlnaService == null) {
                                Log.d(TAG, "DlnaService instance is null");
                            } else if (duration <= 0) {
                                Log.d(TAG, "Invalid duration: " + duration);
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
    }

    private void checkServiceStatus() {
        try {
            DlnaService service = DlnaService.getInstance();
            if (service != null) {
                // 服务已经启动，只在初始状态或特定状态下更新
                String currentText = tvStatus != null ? tvStatus.getText().toString() : "";
                // 只在准备就绪、服务未启动或已停止状态下更新为"服务已启动"
                if (tvStatus != null && (currentText.equals("准备就绪") || 
                    currentText.equals("DLNA服务未启动") || 
                    currentText.equals("已停止"))) {
                    runOnUiThread(() -> {
                        try {
                            tvStatus.setText("DLNA服务已启动");
                            tvStatus.setTextColor(getResources().getColor(android.R.color.holo_green_light));
                        } catch (Exception e) {
                            Log.e(TAG, "更新状态失败: " + e.getMessage());
                        }
                    });
                }
            } else {
                // 服务未启动
                if (tvStatus != null && tvStatus.getText().toString().equals("准备就绪")) {
                    runOnUiThread(() -> {
                        try {
                            tvStatus.setText("DLNA服务未启动");
                            tvStatus.setTextColor(getResources().getColor(android.R.color.holo_red_light));
                        } catch (Exception e) {
                            Log.e(TAG, "更新状态失败: " + e.getMessage());
                        }
                    });
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "检查服务状态失败: " + e.getMessage());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            // 启动DLNA服务
            startDlnaService();
            
            // 延迟一下确保服务完全启动
            new Handler().postDelayed(() -> {
                // 设置DLNA事件监听器
                DlnaService service = DlnaService.getInstance();
                if (service != null) {
                    service.setOnDlnaEventListener(this);
                    Log.d(TAG, "已设置DLNA事件监听器");
                    if (tvStatus != null && !tvStatus.getText().toString().contains("已启动")) {
                        tvStatus.setText("DLNA服务已连接");
                    }
                } else {
                    Log.d(TAG, "DLNA服务未启动，无法设置监听器");
                    if (tvStatus != null && !tvStatus.getText().toString().contains("未启动")) {
                        tvStatus.setText("DLNA服务未启动");
                    }
                }
            }, 1000);
        } catch (Exception e) {
            Log.e(TAG, "设置监听器失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 不移除监听器，确保后台时也能接收播放事件
    }





    private void startDlnaService() {
        try {
            Intent serviceIntent = new Intent(this, DlnaService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            if (tvStatus != null) {
                tvStatus.setText("DLNA服务启动中...");
            }
        } catch (Exception e) {
            Log.e(TAG, "启动服务失败: " + e.getMessage());
            e.printStackTrace();
            if (tvStatus != null) {
                tvStatus.setText("服务启动失败");
            }
        }
    }

    private String getDeviceName() {
        try {
            String deviceName = Settings.Global.getString(getContentResolver(), "device_name");
            if (deviceName == null || deviceName.isEmpty()) {
                deviceName = android.os.Build.MODEL;
            }
            return deviceName;
        } catch (Exception e) {
            Log.e(TAG, "获取设备名称失败: " + e.getMessage());
            e.printStackTrace();
            return "Unknown Device";
        }
    }

    private String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface intf : Collections.list(interfaces)) {
                Enumeration<InetAddress> addrs = intf.getInetAddresses();
                for (InetAddress addr : Collections.list(addrs)) {
                    if (!addr.isLoopbackAddress() && addr.getHostAddress().indexOf(':') < 0) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取IP地址失败: " + e.getMessage());
            e.printStackTrace();
        }
        return "Unknown";
    }

    @Override
    public void onPlay(String uri, String title) {
        try {
            Log.d(TAG, "收到播放事件，URI: " + uri + "，标题: " + title);
            handler.post(() -> {
                try {
                    if (tvStatus != null) {
                        tvStatus.setText("正在加载: " + title);
                        tvStatus.setTextColor(getResources().getColor(android.R.color.holo_orange_light));
                    }
                    
                    // 解码HTML实体（如 &amp; -> &）
                    String decodedUri = android.text.Html.fromHtml(uri).toString();
                    // 进一步处理URL，确保格式正确
                    decodedUri = decodedUri.replace("&amp;", "&");
                    Log.d(TAG, "解码后的URI: " + decodedUri);
                    
                    // 使用ExoPlayer播放视频
                    Log.d(TAG, "准备播放视频，URI: " + decodedUri);
                    playerView.setVisibility(View.VISIBLE);
                    
                    // 创建带有User-Agent的DataSource
                    String userAgent = "Mozilla/5.0 (Linux; Android 8.0.0; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/69.0.3497.100 Safari/537.36";
                    DefaultHttpDataSourceFactory httpDataSourceFactory = new DefaultHttpDataSourceFactory(userAgent, null, 
                        60000, 60000, true);
                    
                    // 添加额外的HTTP Headers
                    httpDataSourceFactory.setDefaultRequestProperty("Accept", "*/*");
                    httpDataSourceFactory.setDefaultRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
                    httpDataSourceFactory.setDefaultRequestProperty("Connection", "keep-alive");
                    httpDataSourceFactory.setDefaultRequestProperty("Cache-Control", "no-cache");
                    httpDataSourceFactory.setDefaultRequestProperty("Pragma", "no-cache");
                    httpDataSourceFactory.setDefaultRequestProperty("Origin", "https://www.douyin.com");
                    httpDataSourceFactory.setDefaultRequestProperty("Referer", "https://www.douyin.com/");
                    httpDataSourceFactory.setDefaultRequestProperty("Sec-Fetch-Dest", "video");
                    httpDataSourceFactory.setDefaultRequestProperty("Sec-Fetch-Mode", "no-cors");
                    httpDataSourceFactory.setDefaultRequestProperty("Sec-Fetch-Site", "cross-site");
                    
                    DataSource.Factory dataSourceFactory = new DefaultDataSourceFactory(this, null, httpDataSourceFactory);
                    
                    // 根据URL类型创建不同的MediaSource
                    Uri videoUri = Uri.parse(decodedUri);
                    MediaSource videoSource;
                    
                    // 检查是否是直播流或HLS流
                    if (decodedUri.contains(".m3u8") || decodedUri.contains("hls") || decodedUri.contains("live")) {
                        Log.d(TAG, "检测到直播流或HLS流，使用HlsMediaSource");
                        videoSource = new HlsMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(videoUri);
                    } else {
                        Log.d(TAG, "使用ExtractorMediaSource");
                        videoSource = new ExtractorMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(videoUri);
                    }
                    
                    Log.d(TAG, "准备加载视频，URI: " + decodedUri);
                    
                    // 准备新的视频源（不调用stop，避免进入IDLE状态）
                    player.prepare(videoSource, true, true);
                    player.setPlayWhenReady(true);
                    
                    Log.d(TAG, "视频加载中...");
                } catch (Exception e) {
                    Log.e(TAG, "播放视频失败: " + e.getMessage());
                    e.printStackTrace();
                    if (tvStatus != null) {
                        tvStatus.setText("播放失败: " + e.getMessage());
                        tvStatus.setTextColor(getResources().getColor(android.R.color.holo_red_light));
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "处理播放事件失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void onDlnaPause() {
        try {
            handler.post(() -> {
                try {
                    if (tvStatus != null) {
                        tvStatus.setText("已暂停");
                        tvStatus.setTextColor(getResources().getColor(android.R.color.holo_orange_light));
                    }
                    if (player != null && player.getPlayWhenReady()) {
                        player.setPlayWhenReady(false);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "处理暂停事件失败: " + e.getMessage());
                    e.printStackTrace();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "处理暂停事件失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void onDlnaStop() {
        try {
            handler.post(() -> {
                try {
                    // 延迟更新状态，避免切换视频时状态闪烁
                    handler.postDelayed(() -> {
                        // 检查是否已经有新视频在播放
                        DlnaService service = DlnaService.getInstance();
                        if (service != null && service.getCurrentState() == 0) {
                            // 确实已经停止，没有新视频播放
                            if (tvStatus != null && !tvStatus.getText().toString().contains("正在播放")) {
                                tvStatus.setText("已停止");
                                tvStatus.setTextColor(getResources().getColor(android.R.color.darker_gray));
                                // 停止播放后重新显示状态信息
                                if (infoLayout != null) {
                                    infoLayout.setVisibility(View.VISIBLE);
                                }
                                if (tvInstruction != null) {
                                    tvInstruction.setVisibility(View.VISIBLE);
                                }
                            }
                            if (player != null) {
                                player.stop();
                            }
                            playerView.setVisibility(View.GONE);
                        }
                    }, 500); // 延迟500毫秒
                } catch (Exception e) {
                    Log.e(TAG, "处理停止事件失败: " + e.getMessage());
                    e.printStackTrace();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "处理停止事件失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void onSeek(long position) {
        try {
            handler.post(() -> {
                try {
                    if (player != null) {
                        player.seekTo(position);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "处理seek事件失败: " + e.getMessage());
                    e.printStackTrace();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "处理seek事件失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (player == null) {
            initializePlayer();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 不释放播放器，允许在后台继续播放
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releasePlayer();
        if (statusHandler != null && statusCheckRunnable != null) {
            statusHandler.removeCallbacks(statusCheckRunnable);
        }
    }

    @Override
    public void onSetVolume(int volume) {
        // 可以在这里调整系统音量
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_MENU) {
            rotateVideo();
            return true;
        } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
            scaleVideo(true); // 放大
            return true;
        } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
            scaleVideo(false); // 缩小
            return true;
        } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
            fastForward(); // 快进
            return true;
        } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
            rewind(); // 快退
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
    
    private void fastForward() {
        if (player != null) {
            long currentPosition = player.getCurrentPosition();
            long newPosition = currentPosition + 10000; // 快进10秒
            long duration = player.getDuration();
            if (duration > 0 && newPosition < duration) {
                player.seekTo(newPosition);
                Toast.makeText(this, "快进10秒", Toast.LENGTH_SHORT).show();
            } else if (duration > 0) {
                player.seekTo(duration - 1000);
                Toast.makeText(this, "已到视频末尾", Toast.LENGTH_SHORT).show();
            }
        }
    }
    
    private void rewind() {
        if (player != null) {
            long currentPosition = player.getCurrentPosition();
            long newPosition = currentPosition - 10000; // 快退10秒
            if (newPosition < 0) {
                newPosition = 0;
            }
            player.seekTo(newPosition);
            Toast.makeText(this, "快退10秒", Toast.LENGTH_SHORT).show();
        }
    }

    private void rotateVideo() {
        if (playerView != null) {
            currentRotation = (currentRotation + 90) % 360;
            playerView.setRotation(currentRotation);
            Log.d(TAG, "视频旋转角度: " + currentRotation + "度");
            Toast.makeText(this, "视频已旋转 " + currentRotation + " 度", Toast.LENGTH_SHORT).show();
        }
    }

    private void scaleVideo(boolean isZoomIn) {
        if (playerView != null) {
            if (isZoomIn) {
                // 放大
                if (currentScale < MAX_SCALE) {
                    currentScale += SCALE_STEP;
                }
            } else {
                // 缩小
                if (currentScale > MIN_SCALE) {
                    currentScale -= SCALE_STEP;
                }
            }
            // 设置缩放比例，保持等比例缩放
            playerView.setScaleX(currentScale);
            playerView.setScaleY(currentScale);
            Log.d(TAG, "视频缩放比例: " + currentScale);
            Toast.makeText(this, "视频缩放比例: " + String.format("%.1f", currentScale), Toast.LENGTH_SHORT).show();
        }
    }
}
