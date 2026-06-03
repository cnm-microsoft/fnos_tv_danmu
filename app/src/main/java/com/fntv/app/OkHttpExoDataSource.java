package com.fntv.app;

import android.net.Uri;
import com.google.android.exoplayer2.upstream.BaseDataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class OkHttpExoDataSource extends BaseDataSource {

    private static final String TAG = "OkHttpDS";
    private final OkHttpClient client;
    private Response response;
    private BufferedInputStream bufferedInput;
    private long bytesRead;
    private boolean transferStarted;

    public OkHttpExoDataSource(OkHttpClient client) {
        super(true);
        this.client = client;
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(dataSpec.uri.toString());

        // Range 请求（首次/seek 都发）
        builder.header("Range", "bytes=" + dataSpec.position + "-");

        if (dataSpec.httpRequestHeaders != null) {
            for (String key : dataSpec.httpRequestHeaders.keySet()) {
                builder.header(key, dataSpec.httpRequestHeaders.get(key));
            }
        }

        response = client.newCall(builder.build()).execute();

        if (response.code() != 200 && response.code() != 206) {
            response.close();
            throw new IOException("HTTP " + response.code());
        }

        InputStream rawIn = response.body().byteStream();
        bufferedInput = new BufferedInputStream(rawIn, 65536);

        bytesRead = 0;
        transferStarted(dataSpec);
        transferStarted = true;
        return response.body().contentLength();
    }

    @Override
    public int read(byte[] buffer, int offset, int readLength) throws IOException {
        if (bufferedInput == null) return -1;
        int n = bufferedInput.read(buffer, offset, readLength);
        if (n > 0) {
            bytesRead += n;
            bytesTransferred(n);
        }
        return n;
    }

    @Override
    public Uri getUri() {
        return response != null && response.request() != null
                ? Uri.parse(response.request().url().toString()) : null;
    }

    @Override
    public void close() throws IOException {
        if (transferStarted) {
            transferEnded();
            transferStarted = false;
        }
        if (bufferedInput != null) bufferedInput.close();
        if (response != null) response.close();
        bufferedInput = null;
        response = null;
    }
}
