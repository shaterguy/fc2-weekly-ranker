package com.shaterguy.fc2weeklyranker.externalreceiver;

import android.app.Activity;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Test-only decoder benchmark. It never logs or rebroadcasts the media URI. */
public final class PlaybackBenchmarkActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final String TAG = "FC2PlaybackBench";
    private static final String RESULT_ACTION = "com.shaterguy.fc2weeklyranker.EXTERNAL_PLAYBACK_BENCHMARK_RESULT";
    private static final String EXTRA_MODE = "mode";
    private static final String EXTRA_REQUEST_ID = "requestId";
    private static final String EXTRA_RESULT_PACKAGE = "resultPackage";
    private static final String EXTRA_OK = "ok";
    private static final String EXTRA_FIRST_FRAME_MS = "firstFrameMs";
    private static final String EXTRA_SEEK_RESUME_MS = "seekResumeMs";
    private static final String EXTRA_COMPLETED_SEEKS = "completedSeeks";
    private static final String EXTRA_MEDIA_ERROR_WHAT = "mediaErrorWhat";
    private static final String EXTRA_MEDIA_ERROR_EXTRA = "mediaErrorExtra";
    private static final String EXTRA_BUFFERING_COUNT = "bufferingCount";
    private static final String EXTRA_BUFFERING_DURATION_MS = "bufferingDurationMs";
    private static final String EXTRA_MAX_NO_PROGRESS_MS = "maxNoProgressMs";
    private static final String EXTRA_STALL_COUNT = "stallCount";
    private static final String EXTRA_PLAYED_POSITION_MS = "playedPositionMs";
    private static final String EXTRA_FAILURE_STAGE = "failureStage";

    private static final long SURFACE_TIMEOUT_MS = 10_000L;
    private static final long PREPARE_TIMEOUT_MS = 20_000L;
    private static final long FIRST_FRAME_TIMEOUT_MS = 15_000L;
    private static final long SEEK_TIMEOUT_MS = 15_000L;
    private static final long LONG_TARGET_MEDIA_MS = 120_000L;
    private static final long LONG_TIMEOUT_MS = 155_000L;
    private static final long STALL_THRESHOLD_MS = 1_000L;

    private final CountDownLatch surfaceReady = new CountDownLatch(1);
    private final AtomicLong frameCounter = new AtomicLong(0L);
    private final AtomicLong lastFrameAtMs = new AtomicLong(-1L);
    private final AtomicLong renderingStartAtMs = new AtomicLong(-1L);
    private final CountDownLatch renderingStart = new CountDownLatch(1);
    private final AtomicReference<CountDownLatch> seekComplete = new AtomicReference<>();
    private final AtomicInteger mediaErrorWhat = new AtomicInteger(0);
    private final AtomicInteger mediaErrorExtra = new AtomicInteger(0);
    private final AtomicLong bufferingStartedAtMs = new AtomicLong(-1L);
    private final AtomicInteger bufferingCount = new AtomicInteger(0);
    private final AtomicLong bufferingDurationMs = new AtomicLong(0L);
    private final AtomicBoolean resultSent = new AtomicBoolean(false);

    private volatile Surface surface;
    private volatile String failureStage = "startup";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextureView textureView = new TextureView(this);
        textureView.setSurfaceTextureListener(this);
        setContentView(textureView);
        if (textureView.isAvailable() && textureView.getSurfaceTexture() != null) {
            onSurfaceTextureAvailable(textureView.getSurfaceTexture(), textureView.getWidth(), textureView.getHeight());
        }
        new Thread(this::execute, "playback-benchmark").start();
    }

    @Override
    protected void onDestroy() {
        Surface current = surface;
        surface = null;
        if (current != null) current.release();
        super.onDestroy();
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        if (surface == null) surface = new Surface(texture);
        surfaceReady.countDown();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        lastFrameAtMs.set(SystemClock.elapsedRealtime());
        frameCounter.incrementAndGet();
    }

    private void execute() {
        Intent incoming = getIntent();
        String mode = incoming.getStringExtra(EXTRA_MODE);
        Uri uri = incoming.getData();
        Result metrics = new Result();
        try {
            failureStage = "validate-input";
            if (uri == null || !("content".equals(uri.getScheme()) || "https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
                throw new IllegalArgumentException("invalid media URI");
            }
            if (!("benchmark".equals(mode) || "long".equals(mode))) {
                throw new IllegalArgumentException("invalid benchmark mode");
            }
            failureStage = "await-surface";
            if (!surfaceReady.await(SURFACE_TIMEOUT_MS, TimeUnit.MILLISECONDS) || surface == null) {
                throw new IllegalStateException("surface unavailable");
            }
            metrics = runPlayer(uri, mode);
            failureStage = metrics.ok ? "complete" : failureStage;
        } catch (Throwable failure) {
            metrics.ok = false;
            Log.i(TAG, "failure stage=" + failureStage + " type=" + failure.getClass().getSimpleName());
        }
        publish(incoming, metrics);
        runOnUiThread(this::finish);
    }

    private Result runPlayer(Uri uri, String mode) throws Exception {
        Result result = new Result();
        MediaPlayer player = new MediaPlayer();
        try {
            player.setSurface(surface);
            player.setVolume(0f, 0f);
            CountDownLatch prepared = new CountDownLatch(1);
            AtomicBoolean prepareFailed = new AtomicBoolean(false);
            player.setOnPreparedListener(ignored -> prepared.countDown());
            player.setOnSeekCompleteListener(ignored -> {
                CountDownLatch latch = seekComplete.getAndSet(null);
                if (latch != null) latch.countDown();
            });
            player.setOnInfoListener((ignored, what, extra) -> {
                long now = SystemClock.elapsedRealtime();
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    if (renderingStartAtMs.compareAndSet(-1L, now)) renderingStart.countDown();
                } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    if (bufferingStartedAtMs.compareAndSet(-1L, now)) bufferingCount.incrementAndGet();
                } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    finishBuffering(now);
                }
                return false;
            });
            player.setOnErrorListener((ignored, what, extra) -> {
                mediaErrorWhat.set(what);
                mediaErrorExtra.set(extra);
                prepareFailed.set(true);
                prepared.countDown();
                CountDownLatch latch = seekComplete.getAndSet(null);
                if (latch != null) latch.countDown();
                return true;
            });

            failureStage = "set-data-source";
            player.setDataSource(this, uri);
            failureStage = "prepare";
            player.prepareAsync();
            if (!prepared.await(PREPARE_TIMEOUT_MS, TimeUnit.MILLISECONDS) || prepareFailed.get()) {
                failureStage = "prepare-failed";
                return finishResult(result, player);
            }
            int durationMs = player.getDuration();
            if (durationMs < 130_000) {
                failureStage = "duration-too-short";
                return finishResult(result, player);
            }

            failureStage = "first-frame";
            long frameBeforeStart = frameCounter.get();
            long playStartedAt = SystemClock.elapsedRealtime();
            player.start();
            if (!renderingStart.await(FIRST_FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                failureStage = "rendering-start-timeout";
                return finishResult(result, player);
            }
            if (!waitForFrameAfter(frameBeforeStart, FIRST_FRAME_TIMEOUT_MS)) {
                failureStage = "surface-frame-timeout";
                return finishResult(result, player);
            }
            result.firstFrameMs = Math.max(0L, renderingStartAtMs.get() - playStartedAt);

            if ("benchmark".equals(mode)) {
                failureStage = "seek-sequence";
                int[] targets = new int[] {
                        Math.min(durationMs - 5_000, Math.max(10_000, durationMs * 2 / 3)),
                        Math.min(durationMs - 5_000, Math.max(10_000, durationMs / 4)),
                        Math.min(durationMs - 5_000, Math.max(10_000, durationMs * 3 / 4)),
                };
                long maxSeekResumeMs = 0L;
                for (int target : targets) {
                    long seekStartedAt = SystemClock.elapsedRealtime();
                    CountDownLatch completed = new CountDownLatch(1);
                    seekComplete.set(completed);
                    player.seekTo(target, MediaPlayer.SEEK_CLOSEST);
                    if (!completed.await(SEEK_TIMEOUT_MS, TimeUnit.MILLISECONDS) || mediaErrorWhat.get() != 0) {
                        failureStage = "seek-complete-timeout";
                        return finishResult(result, player);
                    }
                    long frameAfterSeekComplete = frameCounter.get();
                    if (!player.isPlaying()) player.start();
                    if (!waitForFrameAfter(frameAfterSeekComplete, SEEK_TIMEOUT_MS)) {
                        failureStage = "seek-frame-timeout";
                        return finishResult(result, player);
                    }
                    long resumedAt = lastFrameAtMs.get();
                    maxSeekResumeMs = Math.max(maxSeekResumeMs, Math.max(0L, resumedAt - seekStartedAt));
                    result.completedSeeks += 1;
                }
                result.seekResumeMs = maxSeekResumeMs;
                result.playedPositionMs = Math.max(0, player.getCurrentPosition());
                result.ok = result.completedSeeks == targets.length && mediaErrorWhat.get() == 0;
            } else {
                failureStage = "long-playback";
                int startPosition = Math.max(0, player.getCurrentPosition());
                int targetPosition = startPosition + (int) LONG_TARGET_MEDIA_MS;
                long deadline = SystemClock.elapsedRealtime() + LONG_TIMEOUT_MS;
                int lastPosition = startPosition;
                long lastProgressAt = SystemClock.elapsedRealtime();
                while (SystemClock.elapsedRealtime() < deadline && mediaErrorWhat.get() == 0) {
                    int position = Math.max(0, player.getCurrentPosition());
                    result.playedPositionMs = position;
                    long now = SystemClock.elapsedRealtime();
                    if (position > lastPosition) {
                        result.maxNoProgressMs = Math.max(result.maxNoProgressMs, now - lastProgressAt);
                        lastProgressAt = now;
                        lastPosition = position;
                    } else {
                        long gap = now - lastProgressAt;
                        result.maxNoProgressMs = Math.max(result.maxNoProgressMs, gap);
                        if (gap >= STALL_THRESHOLD_MS && !result.inStall) {
                            result.stallCount += 1;
                            result.inStall = true;
                        }
                    }
                    if (position > lastPosition || now - lastProgressAt < STALL_THRESHOLD_MS) result.inStall = false;
                    if (position >= targetPosition) {
                        result.ok = true;
                        break;
                    }
                    SystemClock.sleep(200L);
                }
                if (!result.ok) failureStage = mediaErrorWhat.get() == 0 ? "long-playback-timeout" : "long-playback-media-error";
            }
            return finishResult(result, player);
        } finally {
            player.release();
        }
    }

    private boolean waitForFrameAfter(long baseline, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (mediaErrorWhat.get() != 0) return false;
            if (frameCounter.get() > baseline) return true;
            SystemClock.sleep(20L);
        }
        return false;
    }

    private Result finishResult(Result result, MediaPlayer player) {
        finishBuffering(SystemClock.elapsedRealtime());
        result.mediaErrorWhat = mediaErrorWhat.get();
        result.mediaErrorExtra = mediaErrorExtra.get();
        result.bufferingCount = bufferingCount.get();
        result.bufferingDurationMs = bufferingDurationMs.get();
        if (result.playedPositionMs <= 0) {
            try {
                result.playedPositionMs = Math.max(0, player.getCurrentPosition());
            } catch (Throwable ignored) {
                result.playedPositionMs = 0;
            }
        }
        if (result.mediaErrorWhat != 0) result.ok = false;
        return result;
    }

    private void finishBuffering(long now) {
        long started = bufferingStartedAtMs.getAndSet(-1L);
        if (started >= 0L) bufferingDurationMs.addAndGet(Math.max(0L, now - started));
    }

    private void publish(Intent incoming, Result metrics) {
        if (!resultSent.compareAndSet(false, true)) return;
        String requestId = incoming.getStringExtra(EXTRA_REQUEST_ID);
        String resultPackage = incoming.getStringExtra(EXTRA_RESULT_PACKAGE);
        Log.i(TAG, "result request=" + safeToken(requestId)
                + " ok=" + metrics.ok
                + " firstFrameMs=" + metrics.firstFrameMs
                + " seekResumeMs=" + metrics.seekResumeMs
                + " seeks=" + metrics.completedSeeks
                + " mediaErrorWhat=" + metrics.mediaErrorWhat
                + " bufferingCount=" + metrics.bufferingCount
                + " maxNoProgressMs=" + metrics.maxNoProgressMs
                + " stallCount=" + metrics.stallCount
                + " playedPositionMs=" + metrics.playedPositionMs
                + " stage=" + failureStage);
        if (resultPackage == null || resultPackage.isEmpty()) return;
        Intent result = new Intent(RESULT_ACTION)
                .setPackage(resultPackage)
                .putExtra(EXTRA_REQUEST_ID, safeToken(requestId))
                .putExtra(EXTRA_OK, metrics.ok)
                .putExtra(EXTRA_FIRST_FRAME_MS, metrics.firstFrameMs)
                .putExtra(EXTRA_SEEK_RESUME_MS, metrics.seekResumeMs)
                .putExtra(EXTRA_COMPLETED_SEEKS, metrics.completedSeeks)
                .putExtra(EXTRA_MEDIA_ERROR_WHAT, metrics.mediaErrorWhat)
                .putExtra(EXTRA_MEDIA_ERROR_EXTRA, metrics.mediaErrorExtra)
                .putExtra(EXTRA_BUFFERING_COUNT, metrics.bufferingCount)
                .putExtra(EXTRA_BUFFERING_DURATION_MS, metrics.bufferingDurationMs)
                .putExtra(EXTRA_MAX_NO_PROGRESS_MS, metrics.maxNoProgressMs)
                .putExtra(EXTRA_STALL_COUNT, metrics.stallCount)
                .putExtra(EXTRA_PLAYED_POSITION_MS, metrics.playedPositionMs)
                .putExtra(EXTRA_FAILURE_STAGE, failureStage);
        sendBroadcast(result);
    }

    private static String safeToken(String value) {
        if (value == null) return "";
        String sanitized = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.length() <= 80 ? sanitized : sanitized.substring(0, 80);
    }

    private static final class Result {
        boolean ok;
        boolean inStall;
        long firstFrameMs = -1L;
        long seekResumeMs = -1L;
        int completedSeeks;
        int mediaErrorWhat;
        int mediaErrorExtra;
        int bufferingCount;
        long bufferingDurationMs;
        long maxNoProgressMs;
        int stallCount;
        int playedPositionMs;
    }
}
