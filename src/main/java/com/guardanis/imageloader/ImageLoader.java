package com.guardanis.imageloader;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.content.ContextCompat;
import android.view.View;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ImageLoader implements ImageDownloadRequest.DownloadEventListener {

    private static ImageLoader instance = null;

    public static synchronized ImageLoader getInstance(Context context) {
        if(instance == null || instance.context != context.getApplicationContext())
            instance = new ImageLoader(context);

        return instance;
    }

    private static final String PREFS = "ImageLoaderPrefs";

    protected Context context;
    protected FileCache fileCache;
    protected Map<View, String> views = Collections.synchronizedMap(new WeakHashMap<View, String>());

    protected Map<String, ImageDownloadRequest> downloadRequests = new HashMap<String, ImageDownloadRequest>();
    protected Map<String, List<ImageRequest>> delayedRequests = new HashMap<String, List<ImageRequest>>();
    protected ExecutorService executorService;

    private StubHolder stubHolder;
    private final Handler handler = new Handler(Looper.getMainLooper());

    protected ImageLoader(Context context) {
        this.context = context.getApplicationContext();
        this.fileCache = new FileCache(context);
        this.executorService = Executors.newFixedThreadPool(context.getResources().getInteger(R.integer.ail__thread_pool_size));
    }

    public void submit(ImageRequest request){
        if(request instanceof LocalImageRequest || isImageDownloaded(request))
            executorService.submit(request);
        else {
            if(delayedRequests.get(request.getTargetUrl()) == null)
                delayedRequests.put(request.getTargetUrl(), new ArrayList<ImageRequest>());

            delayedRequests.get(request.getTargetUrl())
                    .add(request);

            if(downloadRequests.get(request.getTargetUrl()) == null){
                ImageDownloadRequest downloadRequest = new ImageDownloadRequest(handler, request, this);
                downloadRequests.put(request.getTargetUrl(), downloadRequest);

                executorService.submit(downloadRequest);
            }
        }
    }

    @Override
    public synchronized void onDownloadCompleted(ImageDownloadRequest downloadRequest, String targetUrl) {
        context.getSharedPreferences(PREFS, 0)
                .edit()
                .putBoolean(targetUrl, true)
                .commit();

        if(delayedRequests.get(targetUrl) != null){
            for(ImageRequest request : delayedRequests.get(targetUrl))
                executorService.submit(request);

            delayedRequests.remove(targetUrl);
        }

        downloadRequests.remove(targetUrl);
    }

    @Override
    public synchronized void onDownloadFailed(ImageDownloadRequest downloadRequest, String targetUrl) {
        if(delayedRequests.get(targetUrl) != null){
            // For now, just execute pending requests, as they won't do anything but set appropriate stub
            for(ImageRequest request : delayedRequests.get(targetUrl))
                executorService.submit(request);

            delayedRequests.remove(targetUrl);
        }

        downloadRequests.remove(targetUrl);
    }

    public File download(String url, File file) {
        HttpURLConnection conn = null;
        InputStream is = null;

        try{
            conn = openImageDownloadConnection(getCorrectDownloadUrl(url));
            is = conn.getInputStream();
            ImageUtils.saveStream(file, is);

            return file;
        }
        catch(Throwable ex){ ex.printStackTrace(); }
        finally{ closeConnection(is); }

        return null;
    }

    protected URL getCorrectDownloadUrl(String url) throws Exception {
        URL imageUrl = new URL(url);

        if(url.contains("graph.facebook.com"))
            imageUrl = getAdjustedFacebookImageUrl(imageUrl);

        return imageUrl;
    }

    protected URL getAdjustedFacebookImageUrl(URL imageUrl) throws Exception {
        HttpURLConnection.setFollowRedirects(false);
        HttpURLConnection conn = (HttpURLConnection) imageUrl.openConnection();
        URL adjustedImageUrl = new URL(conn.getHeaderField("Location"));
        conn.disconnect();
        HttpURLConnection.setFollowRedirects(true);
        return adjustedImageUrl;
    }

    protected HttpURLConnection openImageDownloadConnection(URL url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        return conn;
    }

    public static void closeConnection(InputStream is) {
        try{
            if(is != null)
                is.close();
        }
        catch(Exception e){ }
    }

    public void addViewAndTargetUrl(View view, String targetUrl) {
        views.put(view, targetUrl);
    }

    public boolean isViewStillUsable(View target, String expectedTag) {
        return expectedTag.equals(views.get(target));
    }

    public boolean isViewStillUsable(ImageRequest request) {
        return isViewStillUsable(request.getTargetView(), request.getTargetUrl());
    }

    public FileCache getFileCache() {
        return fileCache;
    }

    public StubHolder getStubs() {
        if(stubHolder == null)
            return defaultStubHolder;
        else return stubHolder;
    }

    public void registerStubs(StubHolder stubHolder) {
        this.stubHolder = stubHolder;
    }

    public boolean isImageDownloaded(ImageRequest request){
        return request.getOriginalRequestFile().exists() && context.getSharedPreferences(PREFS, 0).getBoolean(request.getTargetUrl(), false);
    }

    private final StubHolder defaultStubHolder = new StubHolder(){

        @Override
        public Drawable getLoadingDrawable(Context context) {
            return ContextCompat.getDrawable(context, R.drawable.ail__image_loader_stub);
        }

        @Override
        public Drawable getErrorDrawable(Context context) {
            return ContextCompat.getDrawable(context, R.drawable.ail__image_loader_stub_error);
        }
    };
}
