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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ExternalStreamReceiverActivity extends Activity {
    private static final String RESULT_ACTION = "com.shaterguy.fc2weeklyranker.EXTERNAL_STREAM_TEST_RESULT";
    private static final String EXTRA_SCENARIO = "scenario";
    private static final String EXTRA_RESULT_PACKAGE = "resultPackage";
    private static final String EXTRA_OK = "ok";
    private static final String EXTRA_ERROR_CODE = "errorCode";
    private static final String EXTRA_ERROR_STAGE = "errorStage";
    private static final String EXTRA_ERRNO = "errno";
    private static final String EXTRA_ERROR_FUNCTION = "errorFunction";
    private static final String EXTRA_ERROR_MESSAGE = "errorMessage";
    private static final Pattern CONTENT_URI = Pattern.compile("content://[^\\s\\\"']+");
    private static final int NO_ERRNO = -1;
    private static final long MULTI_CHUNK_OFFSET = 600L * 1024L;

    private volatile String diagnosticStage = "startup";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent incoming = getIntent();
        new Thread(() -> executeScenario(incoming), "external-stream-receiver").start();
    }

    private void executeScenario(Intent incoming) {
        String scenario = incoming.getStringExtra(EXTRA_SCENARIO);
        String resultPackage = incoming.getStringExtra(EXTRA_RESULT_PACKAGE);
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
        if (resultPackage != null && !resultPackage.isEmpty()) {
            Intent result = new Intent(RESULT_ACTION)
                    .setPackage(resultPackage)
                    .putExtra(EXTRA_SCENARIO, scenario)
                    .putExtra(EXTRA_OK, ok)
                    .putExtra(EXTRA_ERROR_CODE, errorCode)
                    .putExtra(EXTRA_ERROR_STAGE, diagnosticStage)
                    .putExtra(EXTRA_ERRNO, errno)
                    .putExtra(EXTRA_ERROR_FUNCTION, errorFunction)
                    .putExtra(EXTRA_ERROR_MESSAGE, errorMessage);
            sendBroadcast(result);
        }
        runOnUiThread(this::finish);
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
                diagnosticStage = "decoder-prepare";
                player.setDataSource(this, uri);
                player.setVolume(0f, 0f);
                player.prepare();
                int durationMs = player.getDuration();
                if (durationMs < 8_000) return false;

                diagnosticStage = "decoder-start";
                player.start();
                if (!waitForPosition(player, 1_200, 10_000L)) return false;

                diagnosticStage = "decoder-pause";
                player.pause();
                int pausedPosition = player.getCurrentPosition();
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
                player.release();
            }

            diagnosticStage = "decoder-held-after-peer-close";
            if (pread(held, MULTI_CHUNK_OFFSET, 64).length != 64) return false;
        }

        diagnosticStage = "decoder-reopen";
        MediaPlayer reopened = new MediaPlayer();
        try {
            reopened.setDataSource(this, uri);
            reopened.setVolume(0f, 0f);
            reopened.prepare();
            reopened.start();
            if (!waitForPosition(reopened, 800, 10_000L)) return false;
        } finally {
            reopened.release();
        }
        diagnosticStage = "decoder-assert";
        return true;
    }

    private static boolean waitForPosition(MediaPlayer player, int targetMs, long timeoutMs) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (player.getCurrentPosition() >= targetMs) return true;
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

    private static String safeText(String value) {
        if (value == null) return "";
        String singleLine = value.replace('\n', ' ').replace('\r', ' ');
        return singleLine.length() <= 160 ? singleLine : singleLine.substring(0, 160);
    }
}
