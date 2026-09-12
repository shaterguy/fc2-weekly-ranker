package com.shaterguy.fc2weeklyranker.externalreceiver;

import android.app.Activity;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ExternalStreamReceiverActivity extends Activity {
    private static final String TAG = "FC2ExternalTest";
    private static final String RESULT_ACTION = "com.shaterguy.fc2weeklyranker.EXTERNAL_STREAM_TEST_RESULT";
    private static final String EXTRA_SCENARIO = "scenario";
    private static final String EXTRA_RESULT_PACKAGE = "resultPackage";
    private static final String EXTRA_OK = "ok";
    private static final String EXTRA_ERROR_CODE = "errorCode";
    private static final String EXTRA_ERROR_STAGE = "errorStage";
    private static final String EXTRA_ERRNO = "errno";
    private static final String EXTRA_ERROR_FUNCTION = "errorFunction";
    private static final String EXTRA_ERROR_MESSAGE = "errorMessage";
    private static final String EXTRA_DECODER_WHAT = "decoderWhat";
    private static final String EXTRA_DECODER_EXTRA = "decoderExtra";
    private static final String EXTRA_POSITION_MS = "positionMs";
    private static final String EXTRA_ELAPSED_MS = "elapsedMs";
    private static final String EXTRA_STARTUP_MS = "startupMs";
    private static final String EXTRA_STALL_COUNT = "stallCount";
    private static final String EXTRA_STALL_DURATION_MS = "stallDurationMs";
    private static final String EXTRA_MAX_NO_PROGRESS_MS = "maxNoProgressMs";
    private static final String EXTRA_BUFFERING_COUNT = "bufferingCount";
    private static final String EXTRA_BUFFERING_DURATION_MS = "bufferingDurationMs";
    private static final Pattern CONTENT_URI = Pattern.compile("content://[^\\s\\\"']+");
    private static final int NO_ERRNO = -1;
    private static final long MULTI_CHUNK_OFFSET = 600L * 1024L;
    private static final long DECODER_PREPARE_TIMEOUT_MS = 30_000L;
    private static final long DECODER_STAGE_WATCHDOG_MS = 30_000L;
    private static final long DECODER_WATCHDOG_POLL_MS = 250L;
    private static final long LONG_DECODER_TARGET_MS = 120_000L;
    private static final long LONG_DECODER_TIMEOUT_MS = 155_000L;
    private static final long STALL_THRESHOLD_MS = 1_000L;

    private final AtomicBoolean resultSent = new AtomicBoolean(false);
    private final AtomicInteger decoderWhat = new AtomicInteger(0);
    private final AtomicInteger decoderExtra = new AtomicInteger(0);
    private final AtomicInteger observedPlaybackPositionMs = new AtomicInteger(-1);
    private final AtomicLong firstProgressAtMs = new AtomicLong(-1L);
    private final AtomicLong lastPlaybackProgressAtMs = new AtomicLong(-1L);
    private final AtomicLong stallStartedAtMs = new AtomicLong(-1L);
    private final AtomicInteger stallCount = new AtomicInteger(0);
    private final AtomicLong stallDurationMs = new AtomicLong(0L);
    private final AtomicLong maxNoProgressMs = new AtomicLong(0L);
    private final AtomicLong bufferingStartedAtMs = new AtomicLong(-1L);
    private final AtomicInteger bufferingCount = new AtomicInteger(0);
    private final AtomicLong bufferingDurationMs = new AtomicLong(0L);
    private final CountDownLatch surfaceReady = new CountDownLatch(1);
    private volatile String diagnosticStage = "startup";
    private volatile int lastPositionMs = -1;
    private volatile long scenarioStartedAtMs;
    private volatile SurfaceHolder videoSurfaceHolder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        scenarioStartedAtMs = SystemClock.elapsedRealtime();
        Intent incoming = getIntent();
        String scenario = incoming.getStringExtra(EXTRA_SCENARIO);
        if ("decoder".equals(scenario) || "long-decoder".equals(scenario)) {
            setUpVideoSurface();
            new Thread(() -> executeScenario(incoming), "external-stream-receiver-decoder").start();
            new Thread(() -> watchDecoder(incoming), "external-stream-receiver-watchdog").start();
        } else {
            executeScenario(incoming);
        }
    }

    private void setUpVideoSurface() {
        SurfaceView surfaceView = new SurfaceView(this);
        setContentView(surfaceView);
        SurfaceHolder holder = surfaceView.getHolder();
        holder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder createdHolder) {
                videoSurfaceHolder = createdHolder;
                surfaceReady.countDown();
            }

            @Override
            public void surfaceChanged(SurfaceHolder changedHolder, int format, int width, int height) {
                videoSurfaceHolder = changedHolder;
                surfaceReady.countDown();
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder destroyedHolder) {
                if (videoSurfaceHolder == destroyedHolder) videoSurfaceHolder = null;
            }
        });
        if (holder.getSurface() != null && holder.getSurface().isValid()) {
            videoSurfaceHolder = holder;
            surfaceReady.countDown();
        }
    }

    private void executeScenario(Intent incoming) {
        String scenario = incoming.getStringExtra(EXTRA_SCENARIO);
        boolean ok = false;
        String errorCode = "UNKNOWN";
        int errno = NO_ERRNO;
        String errorFunction = "";
        String errorMessage = "";
        try {
            diagnosticStage = "validate-intent";
            Uri uri = incoming.getData();
            if (uri == null || !"content".equals(uri.getScheme())) {
                throw new IllegalArgumentException("INVALID_URI");
            }
            if ("progressive".equals(scenario)) {
                ok = verifyProgressive(uri);
            } else if ("hls".equals(scenario)) {
                ok = verifyHls(uri);
            } else if ("decoder".equals(scenario)) {
                ok = verifyDecoder(uri);
            } else if ("long-decoder".equals(scenario)) {
                ok = verifyLongDecoder(uri);
            } else {
                throw new IllegalArgumentException("INVALID_SCENARIO");
            }
            errorCode = ok ? "NONE" : "ASSERTION_FAILED";
            if (ok) diagnosticStage = "complete";
        } catch (Throwable throwable) {
            errorCode = throwable.getClass().getSimpleName();
            if (throwable instanceof ErrnoException) {
                ErrnoException errnoException = (ErrnoException) throwable;
                errno = errnoException.errno;
                errorFunction = "pread";
                errorMessage = safeText(errnoException.getMessage());
            } else if (throwable instanceof IllegalArgumentException) {
                errorMessage = safeKnownMessage(throwable.getMessage());
            }
        }
        publishResult(incoming, ok, errorCode, diagnosticStage, errno, errorFunction, errorMessage);
        runOnUiThread(this::finish);
    }

    private void watchDecoder(Intent incoming) {
        String lastStage = diagnosticStage;
        int observedPositionMs = lastPositionMs;
        long lastProgressAt = SystemClock.elapsedRealtime();
        while (!resultSent.get()) {
            SystemClock.sleep(DECODER_WATCHDOG_POLL_MS);
            if (resultSent.get()) return;
            String currentStage = diagnosticStage;
            int currentPositionMs = lastPositionMs;
            if (!currentStage.equals(lastStage) || currentPositionMs > observedPositionMs) {
                lastStage = currentStage;
                observedPositionMs = currentPositionMs;
                lastProgressAt = SystemClock.elapsedRealtime();
                continue;
            }
            if (SystemClock.elapsedRealtime() - lastProgressAt >= DECODER_STAGE_WATCHDOG_MS) {
                publishResult(
                        incoming,
                        false,
                        "DECODER_STAGE_TIMEOUT",
                        currentStage,
                        NO_ERRNO,
                        "",
                        "decoder made no progress within watchdog window"
                );
                runOnUiThread(this::finish);
                return;
            }
        }
    }

    private void publishResult(
            Intent incoming,
            boolean ok,
            String errorCode,
            String errorStage,
            int errno,
            String errorFunction,
            String errorMessage
    ) {
        if (!resultSent.compareAndSet(false, true)) return;
        long nowMs = SystemClock.elapsedRealtime();
        finalizePlaybackMetrics(nowMs);
        String scenario = safeKnownScenario(incoming.getStringExtra(EXTRA_SCENARIO));
        long elapsedMs = Math.max(0L, nowMs - scenarioStartedAtMs);
        long firstProgress = firstProgressAtMs.get();
        long startupMs = firstProgress >= 0L ? Math.max(0L, firstProgress - scenarioStartedAtMs) : -1L;
        Log.i(
                TAG,
                "result scenario=" + scenario
                        + " ok=" + ok
                        + " code=" + safeText(errorCode)
                        + " stage=" + safeText(errorStage)
                        + " decoderWhat=" + decoderWhat.get()
                        + " decoderExtra=" + decoderExtra.get()
                        + " errno=" + errno
                        + " positionMs=" + lastPositionMs
                        + " elapsedMs=" + elapsedMs
                        + " startupMs=" + startupMs
                        + " stallCount=" + stallCount.get()
                        + " stallDurationMs=" + stallDurationMs.get()
                        + " maxNoProgressMs=" + maxNoProgressMs.get()
                        + " bufferingCount=" + bufferingCount.get()
                        + " bufferingDurationMs=" + bufferingDurationMs.get()
        );

        String resultPackage = incoming.getStringExtra(EXTRA_RESULT_PACKAGE);
        if (resultPackage == null || resultPackage.isEmpty()) return;
        Intent result = new Intent(RESULT_ACTION)
                .setPackage(resultPackage)
                .putExtra(EXTRA_SCENARIO, scenario)
                .putExtra(EXTRA_OK, ok)
                .putExtra(EXTRA_ERROR_CODE, errorCode)
                .putExtra(EXTRA_ERROR_STAGE, errorStage)
                .putExtra(EXTRA_ERRNO, errno)
                .putExtra(EXTRA_ERROR_FUNCTION, errorFunction)
                .putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
                .putExtra(EXTRA_DECODER_WHAT, decoderWhat.get())
                .putExtra(EXTRA_DECODER_EXTRA, decoderExtra.get())
                .putExtra(EXTRA_POSITION_MS, lastPositionMs)
                .putExtra(EXTRA_ELAPSED_MS, elapsedMs)
                .putExtra(EXTRA_STARTUP_MS, startupMs)
                .putExtra(EXTRA_STALL_COUNT, stallCount.get())
                .putExtra(EXTRA_STALL_DURATION_MS, stallDurationMs.get())
                .putExtra(EXTRA_MAX_NO_PROGRESS_MS, maxNoProgressMs.get())
                .putExtra(EXTRA_BUFFERING_COUNT, bufferingCount.get())
                .putExtra(EXTRA_BUFFERING_DURATION_MS, bufferingDurationMs.get());
        sendBroadcast(result);
    }

    private boolean verifyProgressive(Uri uri) throws Exception {
        diagnosticStage = "progressive-open-root";
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) return false;
            diagnosticStage = "progressive-read-head";
            byte[] first = pread(pfd, 0L, 24);
            diagnosticStage = "progressive-read-seek";
            byte[] later = pread(pfd, 96L, 24);
            diagnosticStage = "progressive-assert";
            return first.length == 24 && later.length == 24 && !Arrays.equals(first, later);
        }
    }

    private boolean verifyHls(Uri uri) throws Exception {
        String playlist;
        diagnosticStage = "hls-open-root";
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) return false;
            diagnosticStage = "hls-read-playlist";
            playlist = new String(pread(pfd, 0L, 64 * 1024), StandardCharsets.UTF_8);
        }
        diagnosticStage = "hls-validate-playlist";
        if (!playlist.startsWith("#EXTM3U") || playlist.contains("https://") || playlist.contains("http://")) {
            return false;
        }
        Matcher matcher = CONTENT_URI.matcher(playlist);
        if (!matcher.find()) return false;
        Uri child = Uri.parse(matcher.group());
        if (!"content".equals(child.getScheme()) || !uri.getAuthority().equals(child.getAuthority())) return false;
        diagnosticStage = "hls-open-child";
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(child, "r")) {
            if (pfd == null) return false;
            diagnosticStage = "hls-read-child";
            boolean readable = pread(pfd, 0L, 16).length > 0;
            diagnosticStage = "hls-assert";
            return readable;
        }
    }

    private boolean verifyDecoder(Uri uri) throws Exception {
        diagnosticStage = "decoder-open-held-descriptor";
        try (ParcelFileDescriptor held = getContentResolver().openFileDescriptor(uri, "r")) {
            if (held == null) return false;
            diagnosticStage = "decoder-held-read-head";
            if (pread(held, 0L, 64).length != 64) return false;

            MediaPlayer player = new MediaPlayer();
            try {
                if (!prepareDecoder(player, uri, "decoder")) return false;
                diagnosticStage = "decoder-duration";
                int durationMs = player.getDuration();
                if (durationMs < 8_000) return false;

                diagnosticStage = "decoder-start";
                player.start();
                if (!waitForPosition(player, 1_200, 10_000L)) return false;

                diagnosticStage = "decoder-pause";
                player.pause();
                int pausedPosition = player.getCurrentPosition();
                lastPositionMs = pausedPosition;
                SystemClock.sleep(350L);
                if (Math.abs(player.getCurrentPosition() - pausedPosition) > 700) return false;

                int seekTarget = Math.min(
                        Math.max(pausedPosition + 4_000, 5_000),
                        durationMs - 2_500
                );
                if (seekTarget <= pausedPosition) return false;
                diagnosticStage = "decoder-seek-resume";
                player.seekTo(seekTarget);
                player.start();
                if (!waitForPosition(player, seekTarget + 1_000, 12_000L)) return false;
            } finally {
                diagnosticStage = "decoder-release";
                player.release();
            }

            diagnosticStage = "decoder-held-after-peer-close";
            if (pread(held, MULTI_CHUNK_OFFSET, 64).length != 64) return false;
            diagnosticStage = "decoder-close-held-descriptor";
        }

        diagnosticStage = "decoder-reopen-create";
        MediaPlayer reopened = new MediaPlayer();
        try {
            if (!prepareDecoder(reopened, uri, "decoder-reopen")) return false;
            diagnosticStage = "decoder-reopen-start";
            reopened.start();
            if (!waitForPosition(reopened, 800, 10_000L)) return false;
        } finally {
            diagnosticStage = "decoder-reopen-release";
            reopened.release();
        }
        diagnosticStage = "decoder-assert";
        return true;
    }

    private boolean verifyLongDecoder(Uri uri) throws Exception {
        resetPlaybackMetrics();
        MediaPlayer player = new MediaPlayer();
        try {
            diagnosticStage = "long-decoder-prepare";
            if (!prepareDecoder(player, uri, "long-decoder")) return false;
            int durationMs = player.getDuration();
            if (durationMs < LONG_DECODER_TARGET_MS + 5_000L) {
                diagnosticStage = "long-decoder-duration-too-short";
                return false;
            }
            diagnosticStage = "long-decoder-start";
            player.start();
            long deadline = SystemClock.elapsedRealtime() + LONG_DECODER_TIMEOUT_MS;
            int lastProgressSecond = -1;
            while (SystemClock.elapsedRealtime() < deadline) {
                if (decoderWhat.get() != 0 || decoderExtra.get() != 0) {
                    diagnosticStage = "long-decoder-media-error";
                    return false;
                }
                long nowMs = SystemClock.elapsedRealtime();
                int positionMs = player.getCurrentPosition();
                lastPositionMs = positionMs;
                recordPlaybackPosition(positionMs, nowMs);
                int progressSecond = Math.max(0, positionMs / 1000);
                if (progressSecond != lastProgressSecond) {
                    lastProgressSecond = progressSecond;
                    diagnosticStage = "long-decoder-playing-" + progressSecond;
                    if (progressSecond % 5 == 0) {
                        Log.i(TAG, "progress scenario=long-decoder positionMs=" + positionMs
                                + " elapsedMs=" + (nowMs - scenarioStartedAtMs)
                                + " stallCount=" + stallCount.get()
                                + " maxNoProgressMs=" + maxNoProgressMs.get());
                    }
                }
                if (positionMs >= LONG_DECODER_TARGET_MS) {
                    diagnosticStage = "long-decoder-target-reached";
                    return true;
                }
                SystemClock.sleep(200L);
            }
            diagnosticStage = "long-decoder-overall-timeout";
            return false;
        } finally {
            player.release();
        }
    }

    private boolean prepareDecoder(MediaPlayer player, Uri uri, String stagePrefix) throws Exception {
        diagnosticStage = stagePrefix + "-await-surface";
        if (!surfaceReady.await(10L, TimeUnit.SECONDS)) {
            diagnosticStage = stagePrefix + "-surface-timeout";
            return false;
        }
        SurfaceHolder holder = videoSurfaceHolder;
        if (holder == null || holder.getSurface() == null || !holder.getSurface().isValid()) {
            diagnosticStage = stagePrefix + "-surface-invalid";
            return false;
        }
        player.setDisplay(holder);

        CountDownLatch prepared = new CountDownLatch(1);
        AtomicBoolean failed = new AtomicBoolean(false);
        player.setOnPreparedListener(ignored -> prepared.countDown());
        player.setOnInfoListener((ignored, what, extra) -> {
            long nowMs = SystemClock.elapsedRealtime();
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                if (bufferingStartedAtMs.compareAndSet(-1L, nowMs)) bufferingCount.incrementAndGet();
            } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                finishBufferingWindow(nowMs);
            }
            return false;
        });
        player.setOnErrorListener((ignored, what, extra) -> {
            decoderWhat.set(what);
            decoderExtra.set(extra);
            failed.set(true);
            Log.i(TAG, "media-error scenario=" + safeKnownScenario(getIntent().getStringExtra(EXTRA_SCENARIO))
                    + " what=" + what
                    + " extra=" + extra
                    + " positionMs=" + lastPositionMs
                    + " elapsedMs=" + (SystemClock.elapsedRealtime() - scenarioStartedAtMs));
            prepared.countDown();
            return true;
        });
        diagnosticStage = stagePrefix + "-set-data-source";
        player.setDataSource(this, uri);
        player.setVolume(0f, 0f);
        diagnosticStage = stagePrefix + "-prepare";
        player.prepareAsync();
        if (!prepared.await(DECODER_PREPARE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            diagnosticStage = stagePrefix + "-prepare-timeout";
            return false;
        }
        if (failed.get()) {
            diagnosticStage = stagePrefix + "-prepare-error";
            return false;
        }
        return true;
    }

    private void resetPlaybackMetrics() {
        observedPlaybackPositionMs.set(-1);
        firstProgressAtMs.set(-1L);
        lastPlaybackProgressAtMs.set(-1L);
        stallStartedAtMs.set(-1L);
        stallCount.set(0);
        stallDurationMs.set(0L);
        maxNoProgressMs.set(0L);
        bufferingStartedAtMs.set(-1L);
        bufferingCount.set(0);
        bufferingDurationMs.set(0L);
    }

    private void recordPlaybackPosition(int positionMs, long nowMs) {
        int previous = observedPlaybackPositionMs.getAndSet(positionMs);
        if (positionMs > 0 && positionMs > previous) {
            if (firstProgressAtMs.compareAndSet(-1L, nowMs)) {
                lastPlaybackProgressAtMs.set(nowMs);
            } else {
                long previousProgressAt = lastPlaybackProgressAtMs.getAndSet(nowMs);
                if (previousProgressAt >= 0L) {
                    maxNoProgressMs.accumulateAndGet(Math.max(0L, nowMs - previousProgressAt), Math::max);
                }
            }
            long stallStart = stallStartedAtMs.getAndSet(-1L);
            if (stallStart >= 0L) stallDurationMs.addAndGet(Math.max(0L, nowMs - stallStart));
            return;
        }

        long firstProgress = firstProgressAtMs.get();
        long lastProgress = lastPlaybackProgressAtMs.get();
        if (firstProgress < 0L || lastProgress < 0L) return;
        long gapMs = Math.max(0L, nowMs - lastProgress);
        maxNoProgressMs.accumulateAndGet(gapMs, Math::max);
        if (gapMs >= STALL_THRESHOLD_MS && stallStartedAtMs.compareAndSet(-1L, lastProgress)) {
            stallCount.incrementAndGet();
        }
    }

    private void finishBufferingWindow(long nowMs) {
        long bufferingStart = bufferingStartedAtMs.getAndSet(-1L);
        if (bufferingStart >= 0L) bufferingDurationMs.addAndGet(Math.max(0L, nowMs - bufferingStart));
    }

    private void finalizePlaybackMetrics(long nowMs) {
        finishBufferingWindow(nowMs);
        long firstProgress = firstProgressAtMs.get();
        long lastProgress = lastPlaybackProgressAtMs.get();
        if (firstProgress >= 0L && lastProgress >= 0L) {
            maxNoProgressMs.accumulateAndGet(Math.max(0L, nowMs - lastProgress), Math::max);
        }
        long stallStart = stallStartedAtMs.getAndSet(-1L);
        if (stallStart >= 0L) stallDurationMs.addAndGet(Math.max(0L, nowMs - stallStart));
    }

    private boolean waitForPosition(MediaPlayer player, int targetMs, long timeoutMs) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (decoderWhat.get() != 0 || decoderExtra.get() != 0) return false;
            int positionMs = player.getCurrentPosition();
            lastPositionMs = positionMs;
            if (positionMs >= targetMs) return true;
            SystemClock.sleep(100L);
        }
        return false;
    }

    private static byte[] pread(ParcelFileDescriptor pfd, long offset, int maxBytes) throws Exception {
        byte[] buffer = new byte[maxBytes];
        int read = Os.pread(pfd.getFileDescriptor(), buffer, 0, buffer.length, offset);
        if (read <= 0) return new byte[0];
        return Arrays.copyOf(buffer, read);
    }

    private static String safeKnownMessage(String message) {
        if ("INVALID_URI".equals(message) || "INVALID_SCENARIO".equals(message)) return message;
        return "";
    }

    private static String safeKnownScenario(String scenario) {
        if ("progressive".equals(scenario)
                || "hls".equals(scenario)
                || "decoder".equals(scenario)
                || "long-decoder".equals(scenario)) {
            return scenario;
        }
        return "unknown";
    }

    private static String safeText(String value) {
        if (value == null) return "";
        String singleLine = value.replace('\n', ' ').replace('\r', ' ');
        return singleLine.length() <= 160 ? singleLine : singleLine.substring(0, 160);
    }
}
