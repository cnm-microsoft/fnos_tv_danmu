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
    private static int chunkSize = 0; // 0 = 不分块
    private final OkHttpClient client;
    private Response response;
    private BufferedInputStream bufferedInput;
    private long bytesRead;
    private boolean transferStarted;

    /** 设置分块模式（字节数），用于夸克等云盘直链 */
    public static void setChunkedMode(int bytes) { chunkSize = bytes; }

    public OkHttpExoDataSource(OkHttpClient client) {
        super(true);
        this.client = client;
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(dataSpec.uri.toString());

        // 分块模式：每次只请求 chunkSize 字节（如 10MB），避免云盘风控
        long rangeEnd = -1;
        if (chunkSize > 0 && dataSpec.length > chunkSize) {
            rangeEnd = dataSpec.position + chunkSize - 1;
        }
        String range = rangeEnd > 0
                ? "bytes=" + dataSpec.position + "-" + rangeEnd
                : "bytes=" + dataSpec.position + "-";
        builder.header("Range", range);

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

        // 返回实际可读长度（分块模式下不超过 chunkSize）
        long contentLen = response.body().contentLength();
        if (chunkSize > 0 && contentLen > chunkSize) contentLen = chunkSize;
        return contentLen;
    }

    @Override
    public int read(byte[] buffer, int offset, int readLength) throws IOException {
        if (bufferedInput == null) return -1;
        // 分块模式下，读完块大小后截断
        if (chunkSize > 0 && bytesRead >= chunkSize) return -1;
        int maxRead = readLength;
        if (chunkSize > 0) maxRead = (int) Math.min(readLength, chunkSize - bytesRead);
        int n = bufferedInput.read(buffer, offset, maxRead);
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
