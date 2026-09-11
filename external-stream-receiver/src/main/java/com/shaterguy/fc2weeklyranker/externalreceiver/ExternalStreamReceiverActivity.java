package com.shaterguy.fc2weeklyranker.externalreceiver;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
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
    private static final Pattern CONTENT_URI = Pattern.compile("content://[^\\s\\\"']+");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent incoming = getIntent();
        String scenario = incoming.getStringExtra(EXTRA_SCENARIO);
        String resultPackage = incoming.getStringExtra(EXTRA_RESULT_PACKAGE);
        boolean ok = false;
        String errorCode = "UNKNOWN";
        try {
            Uri uri = incoming.getData();
            if (uri == null || !"content".equals(uri.getScheme())) {
                throw new IllegalArgumentException("INVALID_URI");
            }
            if ("progressive".equals(scenario)) {
                ok = verifyProgressive(uri);
            } else if ("hls".equals(scenario)) {
                ok = verifyHls(uri);
            } else {
                throw new IllegalArgumentException("INVALID_SCENARIO");
            }
            errorCode = ok ? "NONE" : "ASSERTION_FAILED";
        } catch (Throwable throwable) {
            errorCode = throwable.getClass().getSimpleName();
        }
        if (resultPackage != null && !resultPackage.isEmpty()) {
            Intent result = new Intent(RESULT_ACTION)
                    .setPackage(resultPackage)
                    .putExtra(EXTRA_SCENARIO, scenario)
                    .putExtra(EXTRA_OK, ok)
                    .putExtra(EXTRA_ERROR_CODE, errorCode);
            sendBroadcast(result);
        }
        finish();
    }

    private boolean verifyProgressive(Uri uri) throws Exception {
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) return false;
            byte[] first = pread(pfd, 0L, 24);
            byte[] later = pread(pfd, 96L, 24);
            return first.length == 24 && later.length == 24 && !Arrays.equals(first, later);
        }
    }

    private boolean verifyHls(Uri uri) throws Exception {
        String playlist;
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) return false;
            playlist = new String(pread(pfd, 0L, 64 * 1024), StandardCharsets.UTF_8);
        }
        if (!playlist.startsWith("#EXTM3U") || playlist.contains("https://") || playlist.contains("http://")) {
            return false;
        }
        Matcher matcher = CONTENT_URI.matcher(playlist);
        if (!matcher.find()) return false;
        Uri child = Uri.parse(matcher.group());
        if (!"content".equals(child.getScheme()) || !uri.getAuthority().equals(child.getAuthority())) return false;
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(child, "r")) {
            return pfd != null && pread(pfd, 0L, 16).length > 0;
        }
    }

    private static byte[] pread(ParcelFileDescriptor pfd, long offset, int maxBytes) throws Exception {
        byte[] buffer = new byte[maxBytes];
        int read = Os.pread(pfd.getFileDescriptor(), buffer, 0, buffer.length, offset);
        if (read <= 0) return new byte[0];
        return Arrays.copyOf(buffer, read);
    }
}
