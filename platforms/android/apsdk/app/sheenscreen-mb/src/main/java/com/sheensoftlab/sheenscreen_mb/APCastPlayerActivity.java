/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sheensoftlab.sheenscreen_mb;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer.DecoderInitializationException;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil.DecoderQueryException;
import com.google.android.exoplayer2.source.BehindLiveWindowException;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector.MappedTrackInfo;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.ui.PlayerControlView;
import com.google.android.exoplayer2.ui.TrackSelectionDialogBuilder;
import com.google.android.exoplayer2.util.DebugTextViewHelper;
import com.google.android.exoplayer2.util.ErrorMessageProvider;
import com.google.android.exoplayer2.util.EventLogger;
import com.google.android.exoplayer2.util.Util;
import com.sheensoftlab.apsdk.AirPlaySession;
import com.sheensoftlab.apsdk.IAirPlayCastingHandler;
import com.sheensoftlab.apsdk.PlaybackInfo;
import com.sheensoftlab.sheenscreen_mb.databinding.ActivityApcastPlayerBinding;

import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;

import static com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_INTERNAL;


public class APCastPlayerActivity extends Activity implements OnClickListener, PlayerControlView.VisibilityListener, IAirPlayCastingHandler {
    public static final String AIRPLAY_SESSION_ID = "airplay_session_id";
    public static final String START_WINDOW_INDEX = "start_window_index";
    public static final String START_POSITION = "start_position";
    private static final String TAG = "APSPlayerActivity";

    private ActivityApcastPlayerBinding binding;

    // Saved instance state keys.
    private static final String KEY_TRACK_SELECTOR_PARAMETERS = "track_selector_parameters";
    private static final String KEY_WINDOW = "window";
    private static final String KEY_POSITION = "position";
    private static final String KEY_AUTO_PLAY = "auto_play";

    private static final CookieManager DEFAULT_COOKIE_MANAGER;
    private static final int PLAYER_PLAY = 1;
    private static final int PLAYER_SCRUB = 2;
    private static final int PLAYER_RATE = 3;

    static {
        DEFAULT_COOKIE_MANAGER = new CookieManager();
        DEFAULT_COOKIE_MANAGER.setCookiePolicy(CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    }

    private final PlaybackInfo playbackInfo = new PlaybackInfo();
    private SimpleExoPlayer player;
    private DefaultTrackSelector trackSelector;
    private DefaultTrackSelector.Parameters trackSelectorParameters;
    // Fields used only for ad playback. The ads loader is loaded via reflection.
    private DebugTextViewHelper debugViewHelper;
    private TrackGroupArray lastSeenTrackGroupArray;
    private long sessionId;
    private Uri location;
    private boolean startAutoPlay;
    private int startWindow;
    private long startPosition;
    private long durationInSeconds;
    private long currentPositionInSeconds;
    private boolean isPaused;
    private AirPlaySession session;
    private Handler playerClientHandler;

    private static boolean isBehindLiveWindow(ExoPlaybackException e) {
        if (e.type != ExoPlaybackException.TYPE_SOURCE) {
            return false;
        }
        Throwable cause = e.getSourceException();
        while (cause != null) {
            if (cause instanceof BehindLiveWindowException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    // region Private Methods
    private void prepareSessionHandler() {
        Runnable updatePlaybackInfo = new Runnable() {
            @Override
            public void run() {
                if (null == player) return;
                durationInSeconds = player.getDuration() / 1000;
                currentPositionInSeconds = player.getCurrentPosition() / 1000;
                isPaused =
                        ((!player.getPlayWhenReady()) && (Player.STATE_READY == player.getPlaybackState()));
                playerClientHandler.postDelayed(this, 500);
            }
        };

        playerClientHandler = new Handler(Looper.getMainLooper(), msg -> {
            switch (msg.what) {
                case PLAYER_PLAY:
                    initializePlayer();
                    break;
                case PLAYER_SCRUB:
                    if (null == player)
                        break;
                    // Seek to new position
                    long position = ((Float) msg.obj).longValue();
                    player.seekTo(player.getCurrentWindowIndex(), position);
                    break;
                case PLAYER_RATE:
                    if (null == player)
                        break;
                    // Change the playback speed
                    float rate = (float) msg.obj;
                    if (rate > 0) {
                        PlaybackParameters speed = new PlaybackParameters(rate);
                        player.setPlaybackParameters(speed);
                        player.setPlayWhenReady(true);
                    } else {
                        // If the playback speed is 0 then pause it
                        player.setPlayWhenReady(false);
                    }
                    break;
                default:
                    break;
            }
            return false;
        });
        playerClientHandler.postDelayed(updatePlaybackInfo, 500);
        session.setCastHandler(this);

        synchronized (session) {
            session.notifyAll();
        }
    }

    private void initializePlayer() {
        if (player == null) {
            lastSeenTrackGroupArray = null;

            trackSelector = new DefaultTrackSelector(this);
            trackSelector.setParameters(trackSelectorParameters);

            player = new SimpleExoPlayer.Builder(this)
                    .setTrackSelector(trackSelector)
                    .build();
            player.addListener(new PlayerEventListener());
            player.setPlayWhenReady(startAutoPlay);
            player.addAnalyticsListener(new EventLogger(trackSelector));

            binding.playerView.setPlayer(player);

            debugViewHelper = new DebugTextViewHelper(player, binding.debugInfoView);
            debugViewHelper.start();
        }

        if (location != null) {
            player.seekTo(0, startPosition);
            player.setMediaItem(MediaItem.fromUri(location), false);
            player.prepare();
        }
        updateButtonVisibilities();
    }

    private void releasePlayer() {
        if (player != null) {
            updateTrackSelectorParameters();
            updateStartPosition();
            debugViewHelper.stop();
            debugViewHelper = null;
            player.release();
            player = null;
            trackSelector = null;
        }
    }

    private void updateTrackSelectorParameters() {
        if (trackSelector != null) {
            trackSelectorParameters = trackSelector.getParameters();
        }
    }

    private void updateStartPosition() {
        if (player != null) {
            startAutoPlay = player.getPlayWhenReady();
            startWindow = player.getCurrentWindowIndex();
            startPosition = Math.max(0, player.getContentPosition());
        }
    }

    private void clearStartPosition() {
        startAutoPlay = true;
        startWindow = C.INDEX_UNSET;
        startPosition = C.TIME_UNSET;
    }

    private void updateButtonVisibilities() {
        binding.playerControlView.removeAllViews();
        if (player == null) {
            return;
        }

        MappedTrackInfo mappedTrackInfo = trackSelector.getCurrentMappedTrackInfo();
        if (mappedTrackInfo == null) {
            return;
        }

        for (int i = 0; i < mappedTrackInfo.getRendererCount(); i++) {
            TrackGroupArray trackGroups = mappedTrackInfo.getTrackGroups(i);
            if (trackGroups.length != 0) {
                Button button = new Button(this);
                int label;
                switch (player.getRendererType(i)) {
                    case C.TRACK_TYPE_AUDIO:
                        label = R.string.exo_track_selection_title_audio;
                        break;
                    case C.TRACK_TYPE_VIDEO:
                        label = R.string.exo_track_selection_title_video;
                        break;
                    case C.TRACK_TYPE_TEXT:
                        label = R.string.exo_track_selection_title_text;
                        break;
                    default:
                        continue;
                }
                button.setText(label);
                button.setTag(i);
                button.setOnClickListener(this);
                binding.playerControlView.addView(button);
            }
        }
    }

    private void showControls() {
        binding.playerControlView.setVisibility(View.VISIBLE);
    }

    private void showToast(int messageId) {
        showToast(getString(messageId));
    }

    private void showToast(String message) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
    }
    // endregion

    // region Methods implementation of Activity
    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate: ");
        super.onCreate(savedInstanceState);

        binding = ActivityApcastPlayerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.rootView.setOnClickListener(this);
        binding.playerView.setControllerVisibilityListener(this);
        binding.playerView.setErrorMessageProvider(new PlayerErrorMessageProvider());
        binding.playerView.requestFocus();

        sessionId = getIntent().getLongExtra(AIRPLAY_SESSION_ID, 0);
        session = SheenScreenMBApplication.getInstance().getSession(sessionId);
        location = getIntent().getData();

        if (savedInstanceState != null) {
            startAutoPlay = savedInstanceState.getBoolean(KEY_AUTO_PLAY);
            startWindow = savedInstanceState.getInt(KEY_WINDOW);
            startPosition = savedInstanceState.getLong(KEY_POSITION);
            trackSelectorParameters = savedInstanceState.getParcelable(KEY_TRACK_SELECTOR_PARAMETERS);
        } else {
            startAutoPlay = true;
            startWindow = getIntent().getIntExtra(START_WINDOW_INDEX, C.INDEX_UNSET);
            startPosition = (int) getIntent().getFloatExtra(START_POSITION, C.TIME_UNSET);
            trackSelectorParameters = new DefaultTrackSelector.ParametersBuilder(this).build();
        }

        if (CookieHandler.getDefault() != DEFAULT_COOKIE_MANAGER) {
            CookieHandler.setDefault(DEFAULT_COOKIE_MANAGER);
        }

        prepareSessionHandler();
    }

    @Override
    public void onNewIntent(Intent intent) {
        Log.d(TAG, "onNewIntent: ");
        releasePlayer();
        clearStartPosition();
        setIntent(intent);
    }

    @Override
    public void onStart() {
        Log.d(TAG, "onStart: ");
        super.onStart();
        if (Util.SDK_INT > 23) {
            initializePlayer();
            if (binding.playerView != null) {
                binding.playerView.onResume();
            }
        }
    }

    @Override
    public void onResume() {
        Log.d(TAG, "onResume: ");
        super.onResume();
        if (Util.SDK_INT <= 23 || player == null) {
            initializePlayer();
            if (binding.playerView != null) {
                binding.playerView.onResume();
            }
        }
    }

    @Override
    public void onPause() {
        Log.d(TAG, "onPause: ");
        super.onPause();
        if (Util.SDK_INT <= 23) {
            if (binding.playerView != null) {
                binding.playerView.onPause();
            }
            releasePlayer();
        }
    }

    @Override
    public void onStop() {
        Log.d(TAG, "onStop: ");
        super.onStop();
        if (Util.SDK_INT > 23) {
            if (binding.playerView != null) {
                binding.playerView.onPause();
            }
            releasePlayer();
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy: ");
        super.onDestroy();
        if (session != null) {
            session.disconnect();
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        updateTrackSelectorParameters();
        updateStartPosition();
        outState.putParcelable(KEY_TRACK_SELECTOR_PARAMETERS, trackSelectorParameters);
        outState.putBoolean(KEY_AUTO_PLAY, startAutoPlay);
        outState.putInt(KEY_WINDOW, startWindow);
        outState.putLong(KEY_POSITION, startPosition);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // See whether the player view wants to handle media or DPAD keys events.
        return binding.playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event);
    }
    // endregion

    // region Methods implementation of View.OnClickListener
    @Override
    public void onClick(View view) {
        if (view.getParent() == binding.playerControlView) {
            MappedTrackInfo mappedTrackInfo = trackSelector.getCurrentMappedTrackInfo();
            if (mappedTrackInfo != null) {
                CharSequence title = ((Button) view).getText();
                int rendererIndex = (int) view.getTag();
                int rendererType = mappedTrackInfo.getRendererType(rendererIndex);
                boolean allowAdaptiveSelections =
                        rendererType == C.TRACK_TYPE_VIDEO
                                || (rendererType == C.TRACK_TYPE_AUDIO
                                && mappedTrackInfo.getTypeSupport(C.TRACK_TYPE_VIDEO)
                                == MappedTrackInfo.RENDERER_SUPPORT_NO_TRACKS);

                new TrackSelectionDialogBuilder(this, title, trackSelector, rendererIndex)
                        .setShowDisableOption(true)
                        .setAllowAdaptiveSelections(allowAdaptiveSelections)
                        .build()
                        .show();
            }
        }
    }
    // endregion

    // region Methods implementation of PlaybackControlView.VisibilityListener
    @Override
    public void onVisibilityChange(int visibility) {
        binding.playerControlView.setVisibility(visibility);
    }
    // endregion

    // region Methods implementation of IAirPlayCastingHandler
    @Override
    public void on_video_play(long session, String location, float position) {
        Log.d(TAG, String.format("on_video_play: location = %s, start_pos = %f", location, position));
        if (sessionId != session) {
            Log.e(TAG, "Invalid session id " + session);
            return;
        }

        if (null != playerClientHandler) {
            APCastPlayerActivity.this.startPosition = ((Float) position).longValue();
            APCastPlayerActivity.this.location = Uri.parse(location);
            Message msg = Message.obtain();
            msg.what = PLAYER_PLAY;
            msg.obj = position;
            playerClientHandler.sendMessage(msg);
        }
    }

    @Override
    public void on_video_scrub(long session, float position) {
        Log.i(TAG, String.format("on_video_scrub: position = %f", position));
        if (sessionId != session) {
            Log.e(TAG, "Invalid session id " + session);
            return;
        }

        if (null != playerClientHandler) {
            Message msg = Message.obtain();
            msg.what = PLAYER_SCRUB;
            msg.obj = position;
            playerClientHandler.sendMessage(msg);
        }
    }

    @Override
    public void on_video_rate(long session, float value) {
        Log.i(TAG, String.format("on_video_rate: value = %f", value));
        if (sessionId != session) {
            Log.e(TAG, "Invalid session id " + session);
            return;
        }

        if (null != playerClientHandler) {
            Message msg = Message.obtain();
            msg.what = PLAYER_RATE;
            msg.obj = value;
            playerClientHandler.sendMessage(msg);
        }
    }

    @Override
    public void on_video_stop(long session) {
        Log.i(TAG, "on_video_stop: ");
        if (sessionId != session) {
            Log.e(TAG, "Invalid session id " + session);
            return;
        }

        if (null != playerClientHandler) {
            playerClientHandler.removeCallbacksAndMessages(null);
            APCastPlayerActivity.this.finish();
        }
    }

    @Override
    public PlaybackInfo get_playback_info(long session) {
        if (sessionId != session) {
            Log.e(TAG, "Invalid session id " + session);
            return playbackInfo;
        }

        playbackInfo.duration = durationInSeconds;
        playbackInfo.position = currentPositionInSeconds;
        playbackInfo.rate = isPaused ? 0 : 1;
        playbackInfo.stallCount = 0;
        Log.d(TAG, String.format("get_playback_info: duration = %f, position = %f", playbackInfo.duration, playbackInfo.position));
        return playbackInfo;
    }
    // endregion

    private class PlayerEventListener implements Player.Listener {
        @Override
        public void onPlaybackStateChanged(@Player.State int state) {
            if (state == Player.STATE_ENDED) {
                showControls();
            }
            updateButtonVisibilities();
        }

        //@Override
        //public void onTimelineChanged(Timeline timeline, @Nullable Object manifest, @Player.TimelineChangeReason int reason) {
        //    Log.w(TAG, "onTimelineChanged: ");
        //}

        @Override
        public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition, @NonNull Player.PositionInfo newPosition, @Player.DiscontinuityReason int reason) {
            if (DISCONTINUITY_REASON_INTERNAL != reason) {
                // The user has performed a seek whilst in the error state. Update the resume position so
                // that if the user then retries, playback resumes from the position to which they seeked.
                updateStartPosition();
            }
        }

        @Override
        public void onPlayerError(@NonNull ExoPlaybackException e) {
            if (isBehindLiveWindow(e)) {
                clearStartPosition();
                initializePlayer();
            } else {
                updateStartPosition();
                updateButtonVisibilities();
                showControls();
            }
        }

        @Override
        @SuppressWarnings("ReferenceEquality")
        public void onTracksChanged(@NonNull TrackGroupArray trackGroups, @NonNull TrackSelectionArray trackSelections) {
            updateButtonVisibilities();
            if (trackGroups != lastSeenTrackGroupArray) {
                MappedTrackInfo mappedTrackInfo = trackSelector.getCurrentMappedTrackInfo();
                if (mappedTrackInfo != null) {
                    if (mappedTrackInfo.getTypeSupport(C.TRACK_TYPE_VIDEO)
                            == MappedTrackInfo.RENDERER_SUPPORT_UNSUPPORTED_TRACKS) {
                        showToast(R.string.error_unsupported_video);
                    }
                    if (mappedTrackInfo.getTypeSupport(C.TRACK_TYPE_AUDIO)
                            == MappedTrackInfo.RENDERER_SUPPORT_UNSUPPORTED_TRACKS) {
                        showToast(R.string.error_unsupported_audio);
                    }
                }
                lastSeenTrackGroupArray = trackGroups;
            }
        }
    }

    private class PlayerErrorMessageProvider implements ErrorMessageProvider<ExoPlaybackException> {
        @Override
        public Pair<Integer, String> getErrorMessage(ExoPlaybackException e) {
            String errorString = getString(R.string.error_generic);
            if (e.type == ExoPlaybackException.TYPE_RENDERER) {
                Exception cause = e.getRendererException();
                if (cause instanceof DecoderInitializationException) {
                    // Special case for decoder initialization failures.
                    DecoderInitializationException decoderInitializationException =
                            (DecoderInitializationException) cause;
                    if (decoderInitializationException.codecInfo == null) {
                        if (decoderInitializationException.getCause() instanceof DecoderQueryException) {
                            errorString = getString(R.string.error_querying_decoders);
                        } else if (decoderInitializationException.secureDecoderRequired) {
                            errorString =
                                    getString(
                                            R.string.error_no_secure_decoder, decoderInitializationException.mimeType);
                        } else {
                            errorString =
                                    getString(R.string.error_no_decoder, decoderInitializationException.mimeType);
                        }
                    } else {
                        errorString =
                                getString(
                                        R.string.error_instantiating_decoder,
                                        decoderInitializationException.codecInfo.name);
                    }
                }
            }
            return Pair.create(0, errorString);
        }
    }
}
