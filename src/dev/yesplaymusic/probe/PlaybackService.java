package dev.yesplaymusic.probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.media.MediaBrowserService;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Owns streaming playback and the system media session independently of the Activity. */
public final class PlaybackService extends MediaBrowserService
        implements AudioManager.OnAudioFocusChangeListener {
    public static final String TAG = "YPMPlayback";
    public static final String EXTRA_COMMAND = "command";
    public static final String EXTRA_PAYLOAD = "payload";

    private static final String ROOT_ID = "yesplaymusic";
    private static final String STATE_PREFS = "player_state";
    private static final String STATE_KEY = "json";
    private static final String SETTINGS_PREFS = "mobile_settings";
    private static final String CHANNEL_ID = "ypm_playback";
    private static final int NOTIFICATION_ID = 4107;
    private static final int MAX_QUEUE_SIZE = 500;
    private static final int MAX_ART_BYTES = 8 * 1024 * 1024;
    private static final float DUCK_FACTOR = 0.20f;
    private static final int REPEAT_MODE_NONE = 0;
    private static final int REPEAT_MODE_ALL = 1;
    private static final int REPEAT_MODE_ONE = 2;
    private static final String CUSTOM_ACTION_REPEAT = "dev.yesplaymusic.probe.action.REPEAT";
    private static final String CUSTOM_ACTION_SHUFFLE = "dev.yesplaymusic.probe.action.SHUFFLE";
    private static final long STATE_PERSIST_INTERVAL_MS = 10_000L;
    private static final long POSITION_DRIFT_TOLERANCE_MS = 1_500L;
    private static final long BLUETOOTH_RETURN_WINDOW_MS = 10_000L;

    /** Latest state for the in-process UI bridge; progress ticks update it without disk writes. */
    private static volatile String publishedStateJson;
    /** Queue payload ({"version","queue"}); the UI fetches it only when queueVersion changes. */
    private static volatile String publishedQueueJson;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService resolverExecutor = Executors.newFixedThreadPool(2);
    private final ExecutorService artworkExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService lyricsExecutor = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final ArrayList<Track> queue = new ArrayList<>();
    private final ArrayList<Integer> shuffleOrder = new ArrayList<>();
    private final android.util.LruCache<String, Bitmap> artworkCache =
            new android.util.LruCache<String, Bitmap>(8);

    private AudioManager audioManager;
    private AudioAttributes audioAttributes;
    private AudioFocusRequest audioFocusRequest;
    private MediaSession mediaSession;
    private MediaPlayer player;
    private Track currentTrack;
    private Bitmap currentArtwork;
    private String currentArtworkUrl = "";
    private String currentSource;
    private String lastError;
    private int currentIndex = -1;
    private int shuffleCursor;
    private int repeatMode = REPEAT_MODE_NONE;
    private int bufferingPercent;
    private long currentDurationMs;
    private long pendingSeekMs;
    private long playbackGeneration;
    private float volume = 1.0f;
    private boolean playerPrepared;
    private boolean buffering;
    private boolean playWhenReady;
    private boolean focusHeld;
    private boolean resumeOnFocusGain;
    private boolean ducked;
    private boolean shuffleEnabled;
    private boolean foregroundStarted;
    private boolean noisyReceiverRegistered;
    private boolean destroyed;
    private boolean stopped;
    private boolean savedWasPlaying;
    private boolean fallbackAttempted;
    private Future<?> resolverTask;
    private Future<?> artworkTask;
    private String queueJsonCache;
    // Seeded per service instance so a recreated service never reuses a version the UI cached.
    private long queueVersion = SystemClock.elapsedRealtime();
    private long lastStatePersistAt;
    private int sessionStateCode = -1;
    private long sessionStatePosition;
    private long sessionStateUpdatedAt;
    private float sessionStateSpeed;
    private long noisyPausedAt;
    private Future<?> lyricsTask;
    private String lyricsKey = "";
    private LrcLines lyrics = LrcLines.EMPTY;
    /** Lyric line shown as the session title, or -1 to show the song title. */
    private int lyricIndex = -1;
    private SharedPreferences settingsPrefs;

    private final Runnable lyricTick = this::syncLyricLine;

    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener =
            (prefs, key) -> {
                if ("statusLyrics".equals(key)) handler.post(this::syncLyricLine);
            };

    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            if (isPlayerPlaying() || buffering) {
                // Controllers (notification, lock screen, Bluetooth AVRCP) extrapolate the
                // position from the last PlaybackState. Re-publishing it every second makes
                // every listener, including the headset link, process a redundant update.
                syncSessionStateIfChanged();
                publishProgress();
                handler.postDelayed(this, 1000L);
            }
        }
    };

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())
                    && (isPlayerPlaying() || playWhenReady)) {
                pausePlayback("audio-becoming-noisy", true);
                noisyPausedAt = SystemClock.elapsedRealtime();
            }
        }
    };

    /** Resumes after a short Bluetooth dropout, e.g. a weak link that reconnects by itself. */
    private final AudioDeviceCallback bluetoothReturnCallback = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            if (noisyPausedAt == 0L || added == null) return;
            if (SystemClock.elapsedRealtime() - noisyPausedAt > BLUETOOTH_RETURN_WINDOW_MS) {
                noisyPausedAt = 0L;
                return;
            }
            for (AudioDeviceInfo device : added) {
                if (device != null && device.isSink() && isBluetoothMediaOutput(device.getType())) {
                    noisyPausedAt = 0L;
                    Log.i(TAG, "bluetooth-output-returned type=" + device.getType());
                    playCurrent("bluetooth-returned");
                    return;
                }
            }
        }
    };

    private final MediaSession.Callback sessionCallback = new MediaSession.Callback() {
        @Override public void onPlay() {
            logControllerAction("play");
            playCurrent("session-play");
        }
        @Override public void onPause() {
            logControllerAction("pause");
            pausePlayback("session-pause", true);
        }
        @Override public void onStop() {
            logControllerAction("stop");
            stopPlaybackAndService("session-stop");
        }
        @Override public void onSkipToNext() {
            logControllerAction("next");
            playNext("session-next");
        }
        @Override public void onSkipToPrevious() {
            logControllerAction("previous");
            playPrevious("session-previous");
        }
        @Override public void onSeekTo(long position) {
            logControllerAction("seek");
            seekTo(position, "session-seek");
        }
        @Override public void onSkipToQueueItem(long id) {
            logControllerAction("queue-item");
            if (id >= 0 && id < queue.size()) {
                playIndex((int) id, true, 0L, "session-queue-item");
            }
        }
        @Override public void onPlayFromMediaId(String mediaId, Bundle extras) {
            logControllerAction("play-from-media-id");
            int index = indexForMediaId(mediaId);
            if (index >= 0) playIndex(index, true, 0L, "session-play-from-media-id");
        }
        @Override public void onCustomAction(String action, Bundle extras) {
            if (CUSTOM_ACTION_REPEAT.equals(action)) {
                if (extras != null && extras.containsKey("mode")) {
                    setRepeatMode(extras.getString("mode", "off"), "session-repeat");
                } else {
                    int next = repeatMode == REPEAT_MODE_NONE ? REPEAT_MODE_ALL
                            : repeatMode == REPEAT_MODE_ALL ? REPEAT_MODE_ONE : REPEAT_MODE_NONE;
                    setRepeatMode(next, "session-repeat");
                }
            } else if (CUSTOM_ACTION_SHUFFLE.equals(action)) {
                boolean enabled = extras != null && extras.containsKey("enabled")
                        ? extras.getBoolean("enabled") : !shuffleEnabled;
                setShuffleEnabled(enabled, "session-shuffle");
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes).setAcceptsDelayedFocusGain(true)
                .setWillPauseWhenDucked(false).setOnAudioFocusChangeListener(this, handler).build();

        createNotificationChannel();
        settingsPrefs = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE);
        settingsPrefs.registerOnSharedPreferenceChangeListener(settingsListener);
        restoreSavedState();
        mediaSession = new MediaSession(this, "YesPlayMusic");
        mediaSession.setCallback(sessionCallback, handler);
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);

        mediaSession.setActive(!queue.isEmpty());
        setSessionToken(mediaSession.getSessionToken());
        registerNoisyReceiver();
        if (audioManager != null) audioManager.registerAudioDeviceCallback(bluetoothReturnCallback, handler);
        refreshQueue();
        updateMetadata();
        updatePlaybackState();
        publishState("service-created");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String command = intent == null ? null : intent.getStringExtra(EXTRA_COMMAND);
        if (command == null || command.isEmpty()) {
            if (intent == null && savedWasPlaying && currentIndex >= 0 && !queue.isEmpty()) {
                playIndex(currentIndex, true, pendingSeekMs, "service-restart");
            } else {
                publishState("service-start");
            }
            return START_STICKY;
        }
        if ("json".equals(command)) {
            // MainActivity uses startForegroundService for every bridge command, including
            // state-only commands such as seek/repeat and requests against an empty queue.
            // Promote before parsing or dispatch so this path always meets the FGS deadline.
            boolean wasForeground = foregroundStarted;
            ensureForeground();
            handleJsonCommand(intent.getStringExtra(EXTRA_PAYLOAD));
            // A temporary promotion must not leave an idle service with a misleading
            // playback notification. Keep it only when this command actually started or
            // continued playback; an already-running paused session retains its FGS.
            if (!wasForeground && !hasActivePlayback()) {
                removeForegroundAndStop(startId);
            }
            return START_STICKY;
        }
        switch (command) {
            case "play": playCurrent("intent-play"); break;
            case "pause": pausePlayback("intent-pause", true); break;
            case "toggle":
                if (playWhenReady || isPlayerPlaying()) {
                    pausePlayback("intent-toggle-pause", true);
                } else playCurrent("intent-toggle-play");
                break;
            case "next": playNext("intent-next"); break;
            case "previous": playPrevious("intent-previous"); break;
            case "seek": seekTo(intent.getLongExtra("position", 0L), "intent-seek"); break;
            case "stop": stopPlaybackAndService("intent-stop"); break;
            default:
                lastError = "unknown_command";
                publishState("unknown-command");
                break;
        }
        return START_STICKY;
    }

    @Override public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle hints) {
        Log.i(TAG, "browser-root package=" + clientPackageName + " uid=" + clientUid);
        return new BrowserRoot(ROOT_ID, null);
    }

    @Override public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result) {
        ArrayList<MediaBrowser.MediaItem> items = new ArrayList<>();
        if (ROOT_ID.equals(parentId)) {
            for (int i = 0; i < queue.size(); i++) {
                items.add(new MediaBrowser.MediaItem(buildDescription(i),
                        MediaBrowser.MediaItem.FLAG_PLAYABLE));
            }
        }
        result.sendResult(items);
    }

    @Override public void onAudioFocusChange(int change) {
        Log.i(TAG, "audio-focus-change=" + change + " playing=" + isPlayerPlaying());
        switch (change) {
            case AudioManager.AUDIOFOCUS_GAIN:
                focusHeld = true;
                if (ducked) {
                    ducked = false;
                    applyVolume();
                }
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false;
                    playWhenReady = true;
                    startPreparedPlayer("focus-gain");
                } else {
                    updatePlaybackState();
                    publishState("focus-gain");
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (isPlayerPlaying()) {
                    ducked = true;
                    applyVolume();
                    publishState("focus-duck");
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                resumeOnFocusGain = playWhenReady || isPlayerPlaying() || buffering;
                playWhenReady = false;
                pausePlayerOnly();
                focusHeld = false;
                buffering = false;
                updatePlaybackState();
                updateNotification();
                publishState("focus-loss-transient");
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                resumeOnFocusGain = false;
                playWhenReady = false;
                pausePlayerOnly();
                buffering = false;
                abandonAudioFocus();
                updatePlaybackState();
                updateNotification();
                publishState("focus-loss");
                break;
            default: break;
        }
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        Log.i(TAG, "task-removed playing=" + isPlayerPlaying()
                + " index=" + currentIndex + " positionMs=" + safePosition());
        super.onTaskRemoved(rootIntent);
    }

    @Override public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println("PlaybackService");
        writer.println("  package=" + getPackageName());
        writer.println("  queueSize=" + queue.size());
        writer.println("  index=" + currentIndex);
        writer.println("  title=" + (currentTrack == null ? "" : currentTrack.title));
        writer.println("  source=" + currentSource);
        writer.println("  playing=" + isPlayerPlaying());
        writer.println("  buffering=" + buffering);
        writer.println("  positionMs=" + safePosition());
        writer.println("  durationMs=" + safeDuration());
        writer.println("  focusHeld=" + focusHeld);
        writer.println("  foreground=" + foregroundStarted);
        writer.println("  error=" + lastError);
        writer.flush();
    }

    @Override public void onDestroy() {
        destroyed = true;
        publishedStateJson = null;
        playbackGeneration++;
        cancelNetworkTasks();
        handler.removeCallbacks(progressTick);
        handler.removeCallbacks(lyricTick);
        if (settingsPrefs != null) settingsPrefs.unregisterOnSharedPreferenceChangeListener(settingsListener);
        if (noisyReceiverRegistered) {
            try { unregisterReceiver(noisyReceiver); } catch (RuntimeException ignored) { }
            noisyReceiverRegistered = false;
        }
        if (audioManager != null) audioManager.unregisterAudioDeviceCallback(bluetoothReturnCallback);
        releasePlayer();
        abandonAudioFocus();
        if (mediaSession != null) {
            mediaSession.setCallback(null);
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        resolverExecutor.shutdownNow();
        artworkExecutor.shutdownNow();
        lyricsExecutor.shutdownNow();
        super.onDestroy();
    }

    private void handleJsonCommand(String payload) {
        if (payload == null || payload.length() > 512 * 1024) {
            lastError = "invalid_command";
            publishState("invalid-command");
            return;
        }
        try {
            JSONObject command = new JSONObject(payload);
            switch (command.optString("type", "")) {
                case "play": {
                    JSONArray requested = command.optJSONArray("queue");
                    int index = command.optInt("index", currentIndex < 0 ? 0 : currentIndex);
                    if (requested != null && !replaceQueue(requested, index)) return;
                    if (queue.isEmpty()) {
                        lastError = "empty_queue";
                        publishState("empty-queue");
                        return;
                    }
                    if (index < 0 || index >= queue.size()) index = 0;
                    playIndex(index, true, 0L, "command-play");
                    break;
                }
                case "toggle":
                    if (playWhenReady || isPlayerPlaying()) {
                        pausePlayback("command-toggle-pause", true);
                    } else playCurrent("command-toggle-play");
                    break;
                case "pause": pausePlayback("command-pause", true); break;
                case "next": playNext("command-next"); break;
                case "previous": playPrevious("command-previous", command.optBoolean("skipRestart", false)); break;
                case "seek": seekTo(command.optLong("position", 0L), "command-seek"); break;
                case "repeat":
                    setRepeatMode(command.optString("mode", "off"), "command-repeat");
                    break;
                case "shuffle":
                    setShuffleEnabled(command.optBoolean("enabled", false), "command-shuffle");
                    break;
                case "volume":
                    setVolume((float) command.optDouble("value", 1.0), "command-volume");
                    break;
                case "stop": stopPlaybackAndService("command-stop"); break;
                default:
                    lastError = "unknown_command";
                    publishState("unknown-command");
                    break;
            }
        } catch (JSONException | RuntimeException error) {
            Log.w(TAG, "invalid-command type=" + error.getClass().getSimpleName());
            lastError = "invalid_command";
            publishState("invalid-command");
        }
    }

    private boolean replaceQueue(JSONArray requested, int startIndex) {
        if (requested.length() > MAX_QUEUE_SIZE) {
            lastError = "queue_too_large";
            publishState("queue-too-large");
            return false;
        }
        ArrayList<Track> parsed = new ArrayList<>();
        for (int i = 0; i < requested.length(); i++) {
            Track track = Track.fromJson(requested.optJSONObject(i));
            if (track != null) parsed.add(track);
        }
        playbackGeneration++;
        cancelNetworkTasks();
        releasePlayer();
        abandonAudioFocus();
        queue.clear();
        queue.addAll(parsed);
        // The shuffle order is rebuilt around the current song, so select it first.
        currentIndex = queue.isEmpty() ? -1
                : startIndex >= 0 && startIndex < queue.size() ? startIndex : 0;
        currentTrack = queue.isEmpty() ? null : queue.get(currentIndex);
        currentArtwork = null;
        currentArtworkUrl = null;
        currentSource = null;
        currentDurationMs = currentTrack == null ? 0L : currentTrack.duration;
        pendingSeekMs = 0L;
        playWhenReady = false;
        buffering = false;
        stopped = false;
        fallbackAttempted = false;
        lastError = queue.isEmpty() ? "empty_queue" : null;
        rebuildShuffleOrder();
        refreshQueue();
        updateMetadata();
        updatePlaybackState();
        return !queue.isEmpty();
    }

    private void playCurrent(String event) {
        if (queue.isEmpty()) {
            lastError = "empty_queue";
            publishState(event);
            return;
        }
        if (currentIndex < 0 || currentIndex >= queue.size()) currentIndex = 0;
        // Pressing play after the queue finished restarts the last song instead of its end.
        boolean finished = stopped && currentDurationMs > 0L
                && pendingSeekMs >= currentDurationMs - 500L;
        playIndex(currentIndex, true, finished ? 0L : pendingSeekMs, event);
    }

    private void playIndex(int index, boolean autoplay, long seekPosition, String event) {
        if (index < 0 || index >= queue.size()) {
            lastError = "track_unavailable";
            publishState("invalid-index");
            return;
        }
        if (currentIndex == index && currentTrack != null) {
            if (buffering) {
                playWhenReady = autoplay;
                publishState(event);
                return;
            }
            if (playerPrepared) {
                playWhenReady = autoplay;
                if (seekPosition > 0L) seekTo(seekPosition, event);
                if (autoplay) startPreparedPlayer(event);
                else publishState(event);
                return;
            }
        }

        playbackGeneration++;
        final long requestGeneration = playbackGeneration;
        cancelNetworkTasks();
        releasePlayer();
        noisyPausedAt = 0L;
        Track nextTrack = queue.get(index);
        if (currentTrack == null || !currentTrack.id.equals(nextTrack.id)) clearLyrics();
        currentIndex = index;
        syncShuffleCursor(index);
        currentTrack = nextTrack;
        currentArtwork = null;
        currentArtworkUrl = null;
        currentSource = null;
        currentDurationMs = currentTrack.duration;
        pendingSeekMs = Math.max(0L, seekPosition);
        playWhenReady = autoplay;
        buffering = true;
        bufferingPercent = 0;
        stopped = false;
        fallbackAttempted = false;
        lastError = null;
        if (mediaSession != null) mediaSession.setActive(true);
        ensureForeground();
        refreshQueue();
        updateMetadata();
        updatePlaybackState();
        updateNotification();
        publishState(event);
        resolveStreamAsync(requestGeneration);
        loadArtworkAsync(requestGeneration, currentTrack);
        scheduleProgressUpdates();
    }

    private void resolveStreamAsync(final long requestGeneration) {
        final Track track = currentTrack;
        final String preference = sourcePreference();
        resolverTask = resolverExecutor.submit(() -> {
            Resolution result = null;
            String failure = "stream_unavailable";
            try {
                result = resolveTrack(track, preference);
            } catch (StreamException error) {
                failure = error.code;
            } catch (Exception error) {
                Log.w(TAG, "stream-resolution-failed type="
                        + error.getClass().getSimpleName());
            }
            final Resolution resolved = result;
            final String errorCode = failure;
            handler.post(() -> {
                if (destroyed || requestGeneration != playbackGeneration) return;
                resolverTask = null;
                if (resolved == null) failPlayback(errorCode, "resolve-failed");
                else {
                    fallbackAttempted = resolved.usedFallback;
                    prepareResolvedStream(requestGeneration, resolved);
                }
            });
        });
    }

    private Resolution resolveTrack(Track track, String preference) throws Exception {
        if ("qq".equals(preference)
                || ("auto".equals(preference) && "qq".equals(track.provider))) {
            return resolveQQ(track, false);
        }
        try {
            JSONObject response = MusicApi.get(getApplicationContext())
                    .resolveTrackUrl(track.id);
            boolean trial = response != null && response.optBoolean("trial", false);
            if (response == null || trial) {
                throw new StreamException(trial ? "trial_unavailable" : "stream_unavailable");
            }
            String url = normalizeStreamUrl(response.optString("url", ""), "netease");
            if (url.isEmpty()) throw new StreamException("stream_unavailable");
            long duration = positiveDuration(response.optLong("duration", track.duration),
                    track.duration);
            return new Resolution(url, "netease", duration, false, "");
        } catch (Exception error) {
            if (!"auto".equals(preference)) throw error;
            Log.i(TAG, "netease-resolution-fallback type="
                    + error.getClass().getSimpleName());
            return resolveQQ(track, true);
        }
    }

    private Resolution resolveQQ(Track track, boolean fallback) throws Exception {
        JSONObject response = QQSource.get(getApplicationContext()).resolve(track.toJson(false));
        if (response == null) throw new StreamException("stream_unavailable");
        String url = normalizeStreamUrl(response.optString("url", ""), "qq");
        if (url.isEmpty()) throw new StreamException("stream_unavailable");
        long duration = positiveDuration(response.optLong("duration", track.duration),
                track.duration);
        return new Resolution(url, "qq", duration, fallback, response.optString("qqMid", ""));
    }

    private void prepareResolvedStream(long generation, Resolution resolution) {
        if (generation != playbackGeneration || currentTrack == null) return;
        currentSource = resolution.source;
        currentTrack = currentTrack.withQqMid(resolution.qqMid);
        if (currentIndex >= 0 && currentIndex < queue.size()) {
            queue.set(currentIndex, currentTrack);
            refreshQueue();
        }
        currentDurationMs = positiveDuration(resolution.durationMs, currentTrack.duration);
        buffering = true;
        loadLyricsAsync();
        try {
            final MediaPlayer nextPlayer = new MediaPlayer();
            player = nextPlayer;
            nextPlayer.setAudioAttributes(audioAttributes);
            nextPlayer.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            nextPlayer.setOnPreparedListener(mp -> {
                if (destroyed || generation != playbackGeneration || player != mp) {
                    mp.release();
                    return;
                }
                playerPrepared = true;
                buffering = false;
                currentDurationMs = positiveDuration(safeDuration(), currentTrack.duration);
                if (pendingSeekMs > 0L) {
                    try { mp.seekTo((int) Math.min(pendingSeekMs, Integer.MAX_VALUE)); }
                    catch (IllegalStateException ignored) { }
                }
                // Consumed: a later play/resume must continue from the player position
                // instead of jumping back to this restore point.
                pendingSeekMs = 0L;
                updateMetadata();
                updatePlaybackState();
                updateNotification();
                if (playWhenReady) startPreparedPlayer("stream-prepared");
                else publishState("stream-prepared-paused");
            });
            nextPlayer.setOnCompletionListener(mp -> {
                if (generation == playbackGeneration && player == mp) handleCompletion();
            });
            nextPlayer.setOnErrorListener((mp, what, extra) -> {
                if (generation == playbackGeneration && player == mp) {
                    handlePlayerError(generation, what, extra);
                }
                return true;
            });
            nextPlayer.setOnInfoListener((mp, what, extra) -> {
                if (generation == playbackGeneration && player == mp) {
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                        buffering = true;
                        updatePlaybackState();
                        updateNotification();
                        publishState("stream-buffering");
                    } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                        buffering = false;
                        updatePlaybackState();
                        updateNotification();
                        publishState("stream-buffered");
                    }
                }
                return false;
            });
            nextPlayer.setOnBufferingUpdateListener((mp, percent) -> {
                if (generation == playbackGeneration && player == mp) bufferingPercent = percent;
            });
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "YesPlayMusic Android/0.2");
            nextPlayer.setDataSource(this, Uri.parse(resolution.url), headers);
            nextPlayer.prepareAsync();
            updateMetadata();
            updatePlaybackState();
            publishState("stream-opening");
        } catch (Exception error) {
            Log.w(TAG, "stream-open-failed type=" + error.getClass().getSimpleName());
            handlePlayerError(generation, 0, 0);
        }
    }

    private void handlePlayerError(long generation, int what, int extra) {
        if (generation != playbackGeneration) return;
        long resumeAt = safePosition();
        Log.w(TAG, "stream-error what=" + what + " extra=" + extra
                + " source=" + currentSource + " atMs=" + resumeAt);
        releasePlayer();
        pendingSeekMs = resumeAt;
        buffering = true;
        if ("auto".equals(sourcePreference()) && "netease".equals(currentSource)
                && !fallbackAttempted) {
            fallbackAttempted = true;
            resolveQQFallback(generation);
        } else failPlayback("stream_unavailable", "stream-error");
    }

    private void resolveQQFallback(final long generation) {
        final Track track = currentTrack;
        resolverTask = resolverExecutor.submit(() -> {
            Resolution result = null;
            try { result = resolveQQ(track, true); }
            catch (Exception error) {
                Log.w(TAG, "qq-fallback-failed type=" + error.getClass().getSimpleName());
            }
            final Resolution resolved = result;
            handler.post(() -> {
                if (destroyed || generation != playbackGeneration) return;
                resolverTask = null;
                if (resolved == null) failPlayback("stream_unavailable", "qq-fallback-failed");
                else prepareResolvedStream(generation, resolved);
            });
        });
        updatePlaybackState();
        updateNotification();
        publishState("qq-fallback");
    }

    private void loadArtworkAsync(final long generation, final Track track) {
        String safeUrl = normalizeArtworkUrl(track.artwork);
        currentArtworkUrl = safeUrl;
        if (safeUrl.isEmpty()) {
            currentArtwork = null;
            updateMetadata();
            updateNotification();
            return;
        }
        Bitmap cached = artworkCache.get(safeUrl);
        if (cached != null) {
            currentArtwork = cached;
            updateMetadata();
            updateNotification();
            return;
        }
        if (artworkTask != null) artworkTask.cancel(true);
        artworkTask = artworkExecutor.submit(() -> {
            Bitmap bitmap = downloadArtwork(safeUrl);
            if (bitmap != null) artworkCache.put(safeUrl, bitmap);
            final Bitmap result = bitmap;
            handler.post(() -> {
                if (destroyed || generation != playbackGeneration || currentTrack != track) return;
                artworkTask = null;
                currentArtwork = result;
                updateMetadata();
                updateNotification();
                publishState("artwork-loaded");
            });
        });
    }

    private Bitmap downloadArtwork(String initialUrl) {
        String target = initialUrl;
        for (int redirect = 0; redirect < 4; redirect++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(target).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(6000);
                connection.setReadTimeout(8000);
                connection.setRequestProperty("Accept", "image/avif,image/webp,image/*");
                connection.setRequestProperty("User-Agent", "YesPlayMusic Android/0.2");
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) return null;
                    target = normalizeArtworkUrl(new URL(new URL(target), location).toString());
                    if (target.isEmpty()) return null;
                    continue;
                }
                if (status != HttpURLConnection.HTTP_OK) return null;
                int length = connection.getContentLength();
                if (length > MAX_ART_BYTES) return null;
                try (InputStream input = connection.getInputStream();
                     ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] block = new byte[16 * 1024];
                    int total = 0;
                    int count;
                    while ((count = input.read(block)) != -1) {
                        if (Thread.currentThread().isInterrupted()) return null;
                        total += count;
                        if (total > MAX_ART_BYTES) return null;
                        output.write(block, 0, count);
                    }
                    byte[] bytes = output.toByteArray();
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
                    int largest = Math.max(bounds.outWidth, bounds.outHeight);
                    int sample = 1;
                    while (largest / sample > 768) sample *= 2;
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = sample;
                    options.inPreferredConfig = Bitmap.Config.RGB_565;
                    return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                }
            } catch (Exception ignored) {
                return null;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        return null;
    }

    private void playNext(String event) {
        if (queue.isEmpty()) {
            lastError = "empty_queue";
            publishState(event);
            return;
        }
        int next = -1;
        if (shuffleEnabled && shuffleOrder.size() == queue.size()) {
            if (shuffleCursor + 1 < shuffleOrder.size()) next = shuffleOrder.get(++shuffleCursor);
            else if (repeatMode == REPEAT_MODE_ALL) {
                rebuildShuffleOrder();
                if (shuffleOrder.size() > 1 && shuffleOrder.get(0) == currentIndex) {
                    Collections.swap(shuffleOrder, 0, 1);
                }
                shuffleCursor = 0;
                next = shuffleOrder.get(0);
            }
        } else if (currentIndex + 1 < queue.size()) next = currentIndex + 1;
        else if (repeatMode == REPEAT_MODE_ALL) next = 0;

        if (next < 0) finishQueue(event);
        else playIndex(next, true, 0L, event);
    }

    private void playPrevious(String event) {
        playPrevious(event, false);
    }

    private void playPrevious(String event, boolean skipRestart) {
        if (queue.isEmpty()) {
            lastError = "empty_queue";
            publishState(event);
            return;
        }
        if (!skipRestart && safePosition() > 3000L) {
            seekTo(0L, event);
            return;
        }
        int previous;
        if (shuffleEnabled && shuffleOrder.size() == queue.size()) {
            previous = shuffleCursor > 0 ? shuffleOrder.get(--shuffleCursor) : currentIndex;
        } else if (currentIndex > 0) previous = currentIndex - 1;
        else if (repeatMode == REPEAT_MODE_ALL) previous = queue.size() - 1;
        else previous = currentIndex;
        playIndex(previous, true, 0L, event);
    }

    private void handleCompletion() {
        if (repeatMode == REPEAT_MODE_ONE) {
            pendingSeekMs = 0L;
            playWhenReady = true;
            try {
                player.seekTo(0);
                startPreparedPlayer("repeat-one");
            } catch (IllegalStateException error) {
                playIndex(currentIndex, true, 0L, "repeat-one-reload");
            }
        } else playNext("track-complete");
    }

    private void finishQueue(String event) {
        pendingSeekMs = currentDurationMs;
        playWhenReady = false;
        buffering = false;
        stopped = true;
        releasePlayer();
        abandonAudioFocus();
        updatePlaybackState();
        updateNotification();
        publishState(event);
    }

    private void startPreparedPlayer(String event) {
        if (currentTrack == null) return;
        if (mediaSession != null && !mediaSession.isActive()) mediaSession.setActive(true);
        ensureForeground();
        playWhenReady = true;
        stopped = false;
        if (player == null || !playerPrepared) {
            if (!buffering) playCurrent("resolve-again");
            else publishState(event);
            return;
        }
        if (!requestAudioFocus()) {
            if (!resumeOnFocusGain) playWhenReady = false;
            buffering = false;
            updatePlaybackState();
            updateNotification();
            publishState("audio-focus-not-granted");
            return;
        }
        try {
            if (ducked) {
                ducked = false;
                applyVolume();
            }
            noisyPausedAt = 0L;
            boolean startedNow = false;
            if (!isPlayerPlaying()) {
                player.start();
                startedNow = true;
            }
            if (startedNow && currentTrack != null) {
                try {
                    RecentPlaybackStore.get(getApplicationContext()).record(currentTrack);
                } catch (RuntimeException error) {
                    Log.w(TAG, "recent-history-write-failed type="
                            + error.getClass().getSimpleName());
                }
            }
            buffering = false;
            updatePlaybackState();
            updateNotification();
            scheduleProgressUpdates();
            syncLyricLine();
            publishState(event);
        } catch (IllegalStateException error) {
            Log.w(TAG, "player-start-failed");
            handlePlayerError(playbackGeneration, 0, 0);
        }
    }

    private void pausePlayerOnly() {
        if (playerPrepared && player != null && isPlayerPlaying()) {
            try { player.pause(); } catch (IllegalStateException ignored) { }
        }
        handler.removeCallbacks(progressTick);
        handler.removeCallbacks(lyricTick);
    }

    private void pausePlayback(String event, boolean abandonFocus) {
        noisyPausedAt = 0L;
        playWhenReady = false;
        resumeOnFocusGain = false;
        if (buffering && !playerPrepared && resolverTask != null) {
            playbackGeneration++;
            cancelNetworkTasks();
            buffering = false;
        }
        if (playerPrepared && isPlayerPlaying()) {
            try { player.pause(); } catch (IllegalStateException ignored) { }
        }
        if (abandonFocus) abandonAudioFocus();
        if (!buffering) handler.removeCallbacks(progressTick);
        handler.removeCallbacks(lyricTick);
        updatePlaybackState();
        updateNotification();
        publishState(event);
    }

    private void seekTo(long requested, String event) {
        long duration = safeDuration();
        long target = Math.max(0L, requested);
        if (duration > 0L) target = Math.min(target, duration);
        pendingSeekMs = target;
        if (playerPrepared && player != null) {
            try {
                player.seekTo((int) Math.min(target, Integer.MAX_VALUE));
                pendingSeekMs = 0L;
            } catch (IllegalStateException ignored) { }
        }
        updatePlaybackState();
        syncLyricLine();
        publishState(event);
    }

    private void setRepeatMode(int mode, String event) {
        if (mode != REPEAT_MODE_NONE && mode != REPEAT_MODE_ALL
                && mode != REPEAT_MODE_ONE) mode = REPEAT_MODE_NONE;
        repeatMode = mode;
        updatePlaybackState();
        publishState(event);
    }

    private void setRepeatMode(String value, String event) {
        setRepeatMode(parseRepeat(value), event);
    }

    private void setShuffleEnabled(boolean enabled, String event) {
        if (shuffleEnabled != enabled) {
            shuffleEnabled = enabled;
            rebuildShuffleOrder();
            refreshQueue();
            updatePlaybackState();
        }
        publishState(event);
    }

    private String repeatActionTitle() {
        if (repeatMode == REPEAT_MODE_ALL) return "Repeat all";
        if (repeatMode == REPEAT_MODE_ONE) return "Repeat one";
        return "Repeat off";
    }

    private void setVolume(float value, String event) {
        volume = Math.max(0.0f, Math.min(1.0f, value));
        applyVolume();
        publishState(event);
    }

    private void rebuildShuffleOrder() {
        shuffleOrder.clear();
        for (int i = 0; i < queue.size(); i++) shuffleOrder.add(i);
        Collections.shuffle(shuffleOrder, random);
        if (currentIndex >= 0 && currentIndex < queue.size()) {
            shuffleOrder.remove(Integer.valueOf(currentIndex));
            shuffleOrder.add(0, currentIndex);
        }
        shuffleCursor = 0;
    }

    /** Keeps the shuffle history consistent when a song is picked directly from the queue. */
    private void syncShuffleCursor(int index) {
        if (!shuffleEnabled || shuffleOrder.size() != queue.size()) return;
        if (shuffleCursor >= 0 && shuffleCursor < shuffleOrder.size()
                && shuffleOrder.get(shuffleCursor) == index) return;
        int at = shuffleOrder.indexOf(index);
        if (at < 0) return;
        shuffleOrder.remove(at);
        if (at <= shuffleCursor) shuffleCursor--;
        int insert = Math.min(Math.max(0, shuffleCursor + 1), shuffleOrder.size());
        shuffleOrder.add(insert, index);
        shuffleCursor = insert;
    }

    private void stopPlaybackAndService(String event) {
        noisyPausedAt = 0L;
        pendingSeekMs = safePosition();
        playbackGeneration++;
        cancelNetworkTasks();
        playWhenReady = false;
        buffering = false;
        stopped = true;
        releasePlayer();
        abandonAudioFocus();
        if (mediaSession != null) {
            mediaSession.setPlaybackState(new PlaybackState.Builder()
                    .setActions(availableActions())
                    .setState(PlaybackState.STATE_STOPPED, pendingSeekMs, 0.0f,
                            SystemClock.elapsedRealtime()).build());
            mediaSession.setActive(false);
        }
        updateNotification();
        publishState(event);
        if (foregroundStarted) {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
            foregroundStarted = false;
        }
        stopSelf();
    }

    private void releasePlayer() {
        if (player == null) {
            playerPrepared = false;
            return;
        }
        MediaPlayer old = player;
        player = null;
        playerPrepared = false;
        try {
            old.setOnPreparedListener(null);
            old.setOnCompletionListener(null);
            old.setOnErrorListener(null);
            old.setOnInfoListener(null);
            old.setOnBufferingUpdateListener(null);
            old.release();
        } catch (RuntimeException ignored) { }
    }

    private void abandonAudioFocus() {
        if (audioManager != null && audioFocusRequest != null && focusHeld) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        }
        focusHeld = false;
        resumeOnFocusGain = false;
        ducked = false;
        applyVolume();
    }

    private boolean requestAudioFocus() {
        if (focusHeld) return true;
        if (audioManager == null || audioFocusRequest == null) return false;
        int result = audioManager.requestAudioFocus(audioFocusRequest);
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusHeld = true;
            resumeOnFocusGain = false;
            return true;
        }
        resumeOnFocusGain = result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED;
        return false;
    }

    private void applyVolume() {
        if (player != null && playerPrepared) {
            float effective = volume * (ducked ? DUCK_FACTOR : 1.0f);
            try { player.setVolume(effective, effective); }
            catch (IllegalStateException ignored) { }
        }
    }

    private void failPlayback(String code, String event) {
        playWhenReady = false;
        buffering = false;
        stopped = false;
        lastError = code == null ? "stream_unavailable" : code;
        releasePlayer();
        abandonAudioFocus();
        updatePlaybackState();
        updateNotification();
        publishState(event);
    }

    private void cancelNetworkTasks() {
        if (resolverTask != null) {
            resolverTask.cancel(true);
            resolverTask = null;
        }
        if (artworkTask != null) {
            artworkTask.cancel(true);
            artworkTask = null;
        }
    }

    private void refreshQueue() {
        queueJsonCache = null;
        queueVersion++;
        if (mediaSession == null) return;
        ArrayList<MediaSession.QueueItem> items = new ArrayList<>();
        for (int i = 0; i < queue.size(); i++) {
            items.add(new MediaSession.QueueItem(buildDescription(i), i));
        }
        mediaSession.setQueueTitle("YesPlayMusic");
        mediaSession.setQueue(items);
    }

    private MediaDescription buildDescription(int index) {
        Track track = queue.get(index);
        MediaDescription.Builder description = new MediaDescription.Builder()
                .setMediaId("index:" + index)
                .setTitle(track.title)
                .setSubtitle(track.artist)
                .setDescription(track.album);
        String uri = normalizeArtworkUrl(track.artwork);
        if (!uri.isEmpty()) description.setIconUri(Uri.parse(uri));
        if (index == currentIndex && currentArtwork != null) {
            description.setIconBitmap(currentArtwork);
        }
        return description.build();
    }

    private void updateMetadata() {
        if (mediaSession == null) return;
        if (currentTrack == null) {
            mediaSession.setMetadata(new MediaMetadata.Builder().build());
            return;
        }
        String title = sessionTitle();
        String artist = sessionArtist();
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, currentTrack.id)
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, currentTrack.album)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, safeDuration())
                .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, queue.size());
        if (currentArtworkUrl != null && !currentArtworkUrl.isEmpty()) {
            metadata.putString(MediaMetadata.METADATA_KEY_ART_URI, currentArtworkUrl);
            metadata.putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, currentArtworkUrl);
        }
        if (currentArtwork != null) {
            metadata.putBitmap(MediaMetadata.METADATA_KEY_ART, currentArtwork);
            metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, currentArtwork);
            metadata.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, currentArtwork);
        }
        mediaSession.setMetadata(metadata.build());
    }

    /**
     * Like QQ Music, the current lyric line replaces the title and "song-artist" the artist,
     * so the lock screen, notification and status-bar island show synced lyrics.
     */
    private String sessionTitle() {
        String line = lyrics.text(lyricIndex);
        return line != null ? line : currentTrack.title;
    }

    private String sessionArtist() {
        if (lyrics.text(lyricIndex) == null) return currentTrack.artist;
        return currentTrack.artist.isEmpty() ? currentTrack.title
                : currentTrack.title + "-" + currentTrack.artist;
    }

    private boolean statusLyricsEnabled() {
        return settingsPrefs == null || settingsPrefs.getBoolean("statusLyrics", true);
    }

    private void clearLyrics() {
        if (lyricsTask != null) {
            lyricsTask.cancel(true);
            lyricsTask = null;
        }
        handler.removeCallbacks(lyricTick);
        lyricsKey = "";
        lyrics = LrcLines.EMPTY;
        lyricIndex = -1;
    }

    /** Fetches lyrics from the provider that is actually streaming, as the player UI does. */
    private void loadLyricsAsync() {
        final Track track = currentTrack;
        if (track == null) return;
        final boolean qq = "qq".equals(currentSource) || "qq".equals(track.provider);
        final String key = (qq ? "qq:" : "netease:") + track.id + ":" + track.qqMid;
        if (key.equals(lyricsKey)) return;
        clearLyrics();
        lyricsKey = key;
        updateMetadata();
        updateNotification();
        final Context context = getApplicationContext();
        lyricsTask = lyricsExecutor.submit(() -> {
            LrcLines parsed = LrcLines.EMPTY;
            try {
                JSONObject args = new JSONObject().put("id", track.id)
                        .put("mid", track.qqMid.isEmpty() ? track.id : track.qqMid);
                JSONObject response = qq ? QQSource.get(context).execute("qqLyrics", args)
                        : MusicApi.get(context).execute("lyrics", args);
                JSONObject lrc = response == null ? null : response.optJSONObject("lrc");
                parsed = LrcLines.parse(lrc != null ? lrc.optString("lyric", "")
                        : response == null ? "" : response.optString("lyric", ""));
            } catch (Exception error) {
                Log.w(TAG, "lyrics-load-failed type=" + error.getClass().getSimpleName());
            }
            final LrcLines result = parsed;
            handler.post(() -> {
                if (destroyed || !key.equals(lyricsKey)) return;
                lyricsTask = null;
                lyrics = result;
                lyricIndex = -1;
                syncLyricLine();
            });
        });
    }

    /** Shows the line at the playback position and wakes up again when the next line starts. */
    private void syncLyricLine() {
        handler.removeCallbacks(lyricTick);
        if (destroyed || currentTrack == null) return;
        boolean enabled = statusLyricsEnabled();
        long position = safePosition();
        int index = enabled ? lyrics.indexAt(position) : -1;
        if (index != lyricIndex) {
            lyricIndex = index;
            updateMetadata();
            updateNotification();
        }
        if (!enabled || lyrics.isEmpty() || !isPlayerPlaying()) return;
        long next = lyrics.nextTime(index);
        if (next < 0L) return;
        handler.postDelayed(lyricTick, Math.max(50L, Math.min(next - position + 20L, 10_000L)));
    }

    private int currentSessionStateCode() {
        if (buffering) return PlaybackState.STATE_BUFFERING;
        if (isPlayerPlaying()) return PlaybackState.STATE_PLAYING;
        if (stopped) return PlaybackState.STATE_STOPPED;
        if (currentTrack != null) return PlaybackState.STATE_PAUSED;
        return PlaybackState.STATE_NONE;
    }

    /** Publishes a session update only when the state changed or the position drifted. */
    private void syncSessionStateIfChanged() {
        int state = currentSessionStateCode();
        if (state != sessionStateCode) {
            updatePlaybackState();
            updateNotification();
            return;
        }
        long expected = sessionStatePosition + (long) ((SystemClock.elapsedRealtime()
                - sessionStateUpdatedAt) * sessionStateSpeed);
        if (Math.abs(safePosition() - expected) > POSITION_DRIFT_TOLERANCE_MS) updatePlaybackState();
    }

    private void updatePlaybackState() {
        if (mediaSession == null) return;
        int state = currentSessionStateCode();
        float speed = state == PlaybackState.STATE_PLAYING ? 1.0f : 0.0f;
        long position = safePosition();
        long updatedAt = SystemClock.elapsedRealtime();
        sessionStateCode = state;
        sessionStatePosition = position;
        sessionStateUpdatedAt = updatedAt;
        sessionStateSpeed = speed;
        Bundle extras = new Bundle();
        extras.putString("repeat", repeatName(repeatMode));
        extras.putBoolean("shuffle", shuffleEnabled);
        if (currentSource != null) extras.putString("source", currentSource);
        PlaybackState.Builder stateBuilder = new PlaybackState.Builder()
                .setActions(availableActions())
                .setState(state, position, speed, updatedAt)
                .setActiveQueueItemId(currentIndex >= 0 ? currentIndex
                        : MediaSession.QueueItem.UNKNOWN_ID)
                .setBufferedPosition(safeDuration() * Math.max(0, bufferingPercent) / 100L)
                .setExtras(extras)
                .addCustomAction(CUSTOM_ACTION_REPEAT, repeatActionTitle(),
                        android.R.drawable.ic_menu_rotate)
                .addCustomAction(CUSTOM_ACTION_SHUFFLE,
                        shuffleEnabled ? "Shuffle on" : "Shuffle off",
                        android.R.drawable.ic_menu_sort_by_size);
        mediaSession.setPlaybackState(stateBuilder.build());
    }

    private long availableActions() {
        return PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID
                | PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM;
    }

    static String publishedState() {
        return publishedStateJson;
    }

    static String publishedQueue() {
        return publishedQueueJson;
    }

    private void publishState(String event) {
        persistState(publishSnapshot(), SystemClock.elapsedRealtime());
        Log.i(TAG, "state event=" + event + " index=" + currentIndex
                + " playing=" + isPlayerPlaying() + " buffering=" + buffering
                + " source=" + currentSource + " positionMs=" + safePosition());
    }

    /** Progress ticks refresh the in-memory copy; the saved copy only every few seconds. */
    private void publishProgress() {
        String fields = publishSnapshot();
        long now = SystemClock.elapsedRealtime();
        if (now - lastStatePersistAt >= STATE_PERSIST_INTERVAL_MS) persistState(fields, now);
    }

    /** Publishes the queue before the state that names its version, so readers never see it stale. */
    private String publishSnapshot() {
        queueJson();
        String fields = buildStateFields();
        publishedStateJson = fields;
        return fields;
    }

    private void persistState(String fields, long now) {
        lastStatePersistAt = now;
        // The saved copy embeds the queue so a restarted service can restore it.
        String json = "{\"queue\":" + queueJson()
                + (fields.length() > 2 ? "," + fields.substring(1) : "}");
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit().putString(STATE_KEY, json).apply();
    }

    private String queueJson() {
        if (queueJsonCache == null) {
            JSONArray tracks = new JSONArray();
            for (Track track : queue) tracks.put(track.toJson(false));
            queueJsonCache = tracks.toString();
            publishedQueueJson = "{\"version\":" + queueVersion + ",\"queue\":" + queueJsonCache + "}";
        }
        return queueJsonCache;
    }

    private String buildStateFields() {
        JSONObject state = new JSONObject();
        try {
            state.put("track", currentTrack == null
                    ? JSONObject.NULL : currentTrack.toJson(false));
            state.put("index", currentIndex);
            state.put("playing", isPlayerPlaying());
            state.put("buffering", buffering);
            state.put("position", safePosition());
            state.put("duration", safeDuration());
            state.put("repeat", repeatName(repeatMode));
            state.put("shuffle", shuffleEnabled);
            state.put("volume", volume);
            state.put("error", lastError == null ? JSONObject.NULL : lastError);
            state.put("source", currentSource == null ? JSONObject.NULL : currentSource);
            state.put("bufferingPercent", bufferingPercent);
            state.put("queueVersion", queueVersion);
            // Lets the UI extrapolate the position between the once-per-second updates.
            state.put("updatedAt", System.currentTimeMillis());
        } catch (JSONException ignored) { }
        return state.toString();
    }

    private void restoreSavedState() {
        String saved = getSharedPreferences(STATE_PREFS, MODE_PRIVATE)
                .getString(STATE_KEY, null);
        if (saved == null || saved.isEmpty()) return;
        try {
            JSONObject state = new JSONObject(saved);
            JSONArray items = state.optJSONArray("queue");
            if (items != null) {
                for (int i = 0; i < Math.min(items.length(), MAX_QUEUE_SIZE); i++) {
                    Track track = Track.fromJson(items.optJSONObject(i));
                    if (track != null) queue.add(track);
                }
            }
            if (!queue.isEmpty()) {
                currentIndex = state.optInt("index", 0);
                if (currentIndex < 0 || currentIndex >= queue.size()) currentIndex = 0;
                currentTrack = queue.get(currentIndex);
                currentDurationMs = positiveDuration(
                        state.optLong("duration", currentTrack.duration), currentTrack.duration);
                pendingSeekMs = Math.max(0L, state.optLong("position", 0L));
                repeatMode = parseRepeat(state.optString("repeat", "off"));
                shuffleEnabled = state.optBoolean("shuffle", false);
                volume = (float) Math.max(0.0, Math.min(1.0, state.optDouble("volume", 1.0)));
                String source = state.optString("source", "");
                currentSource = "netease".equals(source) || "qq".equals(source) ? source : null;
                savedWasPlaying = state.optBoolean("playing", false);
                rebuildShuffleOrder();
            }
        } catch (JSONException | RuntimeException error) {
            Log.w(TAG, "saved-state-invalid type=" + error.getClass().getSimpleName());
            queue.clear();
            currentIndex = -1;
            currentTrack = null;
        }
    }

    private void scheduleProgressUpdates() {
        handler.removeCallbacks(progressTick);
        handler.postDelayed(progressTick, 1000L);
    }

    private void ensureForeground() {
        if (foregroundStarted) return;
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else startForeground(NOTIFICATION_ID, notification);
        foregroundStarted = true;
    }

    private boolean hasActivePlayback() {
        return isPlayerPlaying() || buffering || playWhenReady;
    }

    private void removeForegroundAndStop(int startId) {
        if (foregroundStarted) {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
            foregroundStarted = false;
        }
        stopSelfResult(startId);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "YesPlayMusic 播放", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("播放控制和当前歌曲");
        channel.setShowBadge(false);
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        boolean playing = isPlayerPlaying();
        String title = currentTrack == null ? "YesPlayMusic" : sessionTitle();
        String subtitle = currentTrack == null ? "准备播放" : sessionArtist();
        if (buffering) subtitle = "正在缓冲 · " + subtitle;
        else if (lastError != null) subtitle = "无法播放 · " + subtitle;
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(getResources().getIdentifier("ic_status_music", "drawable", getPackageName()))
                .setColor(0xFFFA3157)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setSubText(currentSource == null ? "YesPlayMusic" : currentSource)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(playing || buffering)
                .setOnlyAlertOnce(true)
                .setShowWhen(false);
        if (currentArtwork != null) builder.setLargeIcon(currentArtwork);

        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            PendingIntent content = PendingIntent.getActivity(this, 0, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.setContentIntent(content);
        }
        builder.addAction(android.R.drawable.ic_media_previous, "上一首",
                serviceAction("previous", 1));
        boolean wantsPlay = playing || playWhenReady;
        builder.addAction(wantsPlay ? android.R.drawable.ic_media_pause
                        : android.R.drawable.ic_media_play,
                wantsPlay ? "暂停" : "播放",
                serviceAction(wantsPlay ? "pause" : "play", 2));
        builder.addAction(android.R.drawable.ic_media_next, "下一首",
                serviceAction("next", 3));
        builder.setStyle(new Notification.MediaStyle()
                .setMediaSession(mediaSession == null ? null : mediaSession.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2));
        return builder.build();
    }

    private PendingIntent serviceAction(String command, int requestCode) {
        Intent intent = new Intent(this, PlaybackService.class);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_COMMAND, command);
        return PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void updateNotification() {
        if (!foregroundStarted || destroyed) return;
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private void registerNoisyReceiver() {
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else registerReceiver(noisyReceiver, filter);
        noisyReceiverRegistered = true;
    }

    private void logControllerAction(String action) {
        String caller = "unknown";
        if (Build.VERSION.SDK_INT >= 28 && mediaSession != null) {
            try {
                if (mediaSession.getCurrentControllerInfo() != null) {
                    caller = mediaSession.getCurrentControllerInfo().getPackageName();
                }
            } catch (RuntimeException ignored) { }
        }
        Log.i(TAG, "controller-action=" + action + " caller=" + caller
                + " index=" + currentIndex + " playing=" + isPlayerPlaying());
    }

    private boolean isPlayerPlaying() {
        if (player == null || !playerPrepared) return false;
        try { return player.isPlaying(); }
        catch (IllegalStateException ignored) { return false; }
    }

    private long safePosition() {
        if (player != null && playerPrepared) {
            try { return Math.max(0L, player.getCurrentPosition()); }
            catch (IllegalStateException ignored) { }
        }
        return Math.max(0L, pendingSeekMs);
    }

    private long safeDuration() {
        if (player != null && playerPrepared) {
            try {
                int actual = player.getDuration();
                if (actual > 0) return actual;
            } catch (IllegalStateException ignored) { }
        }
        return Math.max(0L, currentDurationMs);
    }

    private int indexForMediaId(String mediaId) {
        if (mediaId == null) return -1;
        if (mediaId.startsWith("index:")) {
            try {
                int index = Integer.parseInt(mediaId.substring(6));
                return index >= 0 && index < queue.size() ? index : -1;
            } catch (NumberFormatException ignored) { return -1; }
        }
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).id.equals(mediaId)) return i;
        }
        return -1;
    }

    private String sourcePreference() {
        String value = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
                .getString("sourcePreference", "auto");
        if ("qq".equals(value) || "netease".equals(value)) return value;
        return "auto";
    }

    private static String normalizeStreamUrl(String value, String source) {
        return normalizeProviderUrl(value, source, false);
    }

    private static String normalizeArtworkUrl(String value) {
        return normalizeProviderUrl(value, "", true);
    }

    private static String normalizeProviderUrl(String value, String source, boolean artwork) {
        if (value == null || value.isEmpty() || value.length() > 8192) return "";
        try {
            URL url = new URL(value.trim());
            String protocol = url.getProtocol().toLowerCase(Locale.ROOT);
            if (!"https".equals(protocol) && !"http".equals(protocol)) return "";
            String host = url.getHost().toLowerCase(Locale.ROOT);
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            if (host.isEmpty() || url.getUserInfo() != null
                    || (url.getPort() != -1 && url.getPort() != 80 && url.getPort() != 443)) {
                return "";
            }
            boolean allowed = artwork ? isArtworkHost(host) : isStreamHost(host, source);
            if (!allowed) return "";
            String path = url.getFile();
            if (path == null || path.isEmpty()) path = "/";
            return "https://" + host + path;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean isArtworkHost(String host) {
        return domainIs(host, "music.126.net") || domainIs(host, "music.163.com")
                || domainIs(host, "gtimg.cn");
    }

    private static boolean isStreamHost(String host, String source) {
        if ("qq".equals(source)) {
            return domainIs(host, "qqmusic.qq.com")
                    || domainIs(host, "tc.qq.com")
                    || domainIs(host, "gtimg.cn");
        }
        return domainIs(host, "music.126.net") || domainIs(host, "music.163.com");
    }

    private static boolean isBluetoothMediaOutput(int type) {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || type == AudioDeviceInfo.TYPE_BLE_HEADSET
                || type == AudioDeviceInfo.TYPE_BLE_SPEAKER;
    }

    private static boolean domainIs(String host, String suffix) {
        return host.equals(suffix) || host.endsWith("." + suffix);
    }

    private static long positiveDuration(long value, long fallback) {
        if (value > 0L && value <= 24L * 60L * 60L * 1000L) return value;
        if (fallback > 0L && fallback <= 24L * 60L * 60L * 1000L) return fallback;
        return 0L;
    }

    private static int parseRepeat(String value) {
        if ("all".equalsIgnoreCase(value)) return REPEAT_MODE_ALL;
        if ("one".equalsIgnoreCase(value)) return REPEAT_MODE_ONE;
        return REPEAT_MODE_NONE;
    }

    private static String repeatName(int mode) {
        if (mode == REPEAT_MODE_ALL) return "all";
        if (mode == REPEAT_MODE_ONE) return "one";
        return "off";
    }

    private static final class Resolution {
        final String url;
        final String source;
        final long durationMs;
        final boolean usedFallback;
        final String qqMid;
        Resolution(String url, String source, long durationMs, boolean usedFallback, String qqMid) {
            this.url = url;
            this.source = source;
            this.durationMs = durationMs;
            this.usedFallback = usedFallback;
            this.qqMid = qqMid == null ? "" : qqMid;
        }
    }

    private static final class StreamException extends Exception {
        final String code;
        StreamException(String code) {
            super(code);
            this.code = code;
        }
    }
}
