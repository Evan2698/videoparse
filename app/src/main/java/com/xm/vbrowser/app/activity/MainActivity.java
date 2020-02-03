package com.xm.vbrowser.app.activity;

import android.Manifest;
import android.animation.Animator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.text.TextUtils;
import android.util.Log;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;

import android.webkit.MimeTypeMap;
import android.webkit.ValueCallback;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.alibaba.fastjson.JSON;
import com.daimajia.androidanimations.library.Techniques;
import com.daimajia.androidanimations.library.YoYo;
import com.xm.vbrowser.app.MainApplication;
import com.xm.vbrowser.app.R;
import com.xm.vbrowser.app.VideoSniffer;
import com.xm.vbrowser.app.entity.DetectedVideoInfo;
import com.xm.vbrowser.app.entity.DownloadTask;
import com.xm.vbrowser.app.entity.VideoFormat;
import com.xm.vbrowser.app.entity.VideoInfo;
import com.xm.vbrowser.app.event.AddNewDownloadTaskEvent;
import com.xm.vbrowser.app.event.NewVideoItemDetectedEvent;
import com.xm.vbrowser.app.event.RefreshGoBackButtonStateEvent;
import com.xm.vbrowser.app.event.ShowToastMessageEvent;
import com.xm.vbrowser.app.event.WebViewProgressUpdateEvent;
import com.xm.vbrowser.app.util.*;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringBufferInputStream;
import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;

import org.xwalk.core.*;

import pub.devrel.easypermissions.AfterPermissionGranted;
import pub.devrel.easypermissions.AppSettingsDialog;
import pub.devrel.easypermissions.EasyPermissions;
import pub.devrel.easypermissions.PermissionRequest;
import q.rorbin.badgeview.Badge;
import q.rorbin.badgeview.QBadgeView;

public class MainActivity extends Activity implements EasyPermissions.PermissionCallbacks{
    private static final String HOME_URL = "https://chinese-elements.com/static/vv/video.html";
    private static final String IPHONE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 9_1 like Mac OS X) AppleWebKit/601.1.46 (KHTML, like Gecko) Version/9.0 Mobile/13B143 Safari/601.1";
    private static final String VPARSE = "https://timerd.me/static/cv.html?zwx=";
    private XWalkView mainWebView;

     private View bottomGoBackButton;

    private View bottomRefreshButton;
    private View bottomHomeButton;

    private View webViewProgressVIew;


    private Thread refreshGoBackButtonStateThread;

    private static final String TAG = "VIDEOXXXXX";


    private String currentTitle = "";
    private String currentUrl = "";

    private boolean pageAnimationLock = false;


     enum RotateDeviceStatus {
         Rotate_Unknown,
         Rotate_Portrait,
         Rotate_Landscap,
     }

     private static RotateDeviceStatus RotateState = RotateDeviceStatus.Rotate_Unknown;



    /**
     * 为权限赋予一个唯一的标示码
     */
    public static final int WRITE_EXTERNAL_STORAGE = 1001;

    private boolean initReady = false;

    private OrientationEventListener mOrientationListener;

    private View parseView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initView();


        mOrientationListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int orientation) {
                Log.d(TAG, "onOrientationChanged: " + orientation);
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) {
                    return; // 手机平放时，检测不到有效的角度
                }
                // 只检测是否有四个角度的改变
                if (orientation > 350 || orientation < 10) {
                    // 0度：手机默认竖屏状态（home键在正下方）
                    Log.d(TAG, "下");
                } else if (orientation > 80 && orientation < 100) {
                    // 90度：手机顺时针旋转90度横屏（home建在左侧）
                    Log.d(TAG, "左");
                } else if (orientation > 170 && orientation < 190) {
                    // 180度：手机顺时针旋转180度竖屏（home键在上方）
                    initView();
                    Log.d(TAG, "上");
                } else if (orientation > 260 && orientation < 280) {
                    // 270度：手机顺时针旋转270度横屏，（home键在右侧）
                    initView();
                    Log.d(TAG, "右");
                }
            }
        };
        mOrientationListener.enable();

        showPlayerContent();
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        mOrientationListener.disable();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // Forward results to EasyPermissions
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }

    @AfterPermissionGranted(WRITE_EXTERNAL_STORAGE)
    private void requireAllPermissionForInit() {
        //可以只获取写或者读权限，同一个权限Group下只要有一个权限申请通过了就都可以用了
        String[] perms = {Manifest.permission.WRITE_EXTERNAL_STORAGE};
        if (EasyPermissions.hasPermissions(this, perms)) {
            // Already have permission, do the thing
            if(!initReady){
                mainInit();
                initReady = true;
            }

            if (mainWebView != null) {
                mainWebView.resumeTimers();
                mainWebView.onShow();
            }
            startRefreshGoBackButtonStateThread();
        } else {
            // Do not have permissions, request them now
            EasyPermissions.requestPermissions(this, "下载需要读写外部存储权限",
                    WRITE_EXTERNAL_STORAGE, perms);
        }
    }



    @Override
    public void onPermissionsGranted(int requestCode, @NonNull List<String> perms) {
        //如果不使用AfterPermissionGranted注解，就在这里调用
    }

    @Override
    public void onPermissionsDenied(int requestCode, @NonNull List<String> perms) {
        if (EasyPermissions.somePermissionPermanentlyDenied(this, perms)) {
            ViewUtil.openConfirmDialog(this,
                    "必需权限",
                    "没有该权限，此应用程序可能无法正常工作。打开应用设置屏幕以修改应用权限",
                    "去设置", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            startActivity(
                                    new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                            .setData(Uri.fromParts("package", getPackageName(), null)));
                        }
                    },
                    "退出",  new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            finish();
                        }
                    }
            );
            return;
        }
        ViewUtil.openConfirmDialog(this,
                "必需权限",
                "没有该权限，此应用程序可能无法正常工作。",
                "再试一次", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        requireAllPermissionForInit();
                    }
                },
                "退出",  new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                }
        );
    }

    private void initView() {

        mainWebView = (XWalkView)findViewById(R.id.mainWebView);

        bottomGoBackButton = findViewById(R.id.bottomGoBackButton);

        bottomRefreshButton = findViewById(R.id.bottomRefreshButton);
        bottomHomeButton = findViewById(R.id.bottomHomeButton);
              webViewProgressVIew = findViewById(R.id.webViewProgressVIew);
    }

    private void mainInit() {
        initWebView();


        bottomGoBackButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(!mainWebView.getNavigationHistory().canGoBack()){
                    refreshGoBackButtonStatus();
                    return;
                }else{
                    mainWebView.getNavigationHistory().navigate(XWalkNavigationHistory.Direction.BACKWARD, 1);
                    refreshGoBackButtonStatus();
                }
            }
        });

        bottomRefreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mainWebView.reload(XWalkView.RELOAD_IGNORE_CACHE);
            }
        });

        bottomHomeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                loadOrSearch(HOME_URL);
                mainWebView.getNavigationHistory().clear();
            }
        });


    }

    private void initWebView() {
        //开启调式,支持谷歌浏览器调式
        XWalkPreferences.setValue(XWalkPreferences.REMOTE_DEBUGGING, true);

        XWalkSettings webSettings = mainWebView.getSettings();
        webSettings.setAllowFileAccessFromFileURLs(true);
        webSettings.setAllowUniversalAccessFromFileURLs(true);
        webSettings.setSupportZoom(true);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setJavaScriptEnabled(true);
        webSettings.setUserAgentString(IPHONE_UA);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(true);

        mainWebView.requestFocus();

        mainWebView.setResourceClient(new MainXWalkResourceClient(mainWebView));
        mainWebView.setUIClient(new MainXWalkUIClient(mainWebView));
        XWalkCookieManager xm = new XWalkCookieManager();
        xm.setAcceptCookie(true);

        if (this.RotateState == RotateDeviceStatus.Rotate_Unknown){
            loadOrSearch(HOME_URL);
        }
    }

    private void showPlayerContent() {
        this.parseView = LayoutInflater.from(this).inflate(R.layout.layout, null);
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-2, -1, 1.0f);
        layoutParams.gravity = 16;
        addContentView(this.parseView, layoutParams);
        View findViewById = this.parseView.findViewById(R.id.parseVideoBtn);
        if (findViewById != null) {
            findViewById.setOnClickListener(new View.OnClickListener() {
                public void onClick(View view) {
                    String url = MainActivity.this.mainWebView.getUrl();
                    XWalkView access$000 = MainActivity.this.mainWebView;
                    StringBuilder sb = new StringBuilder();
                    sb.append(MainActivity.VPARSE);
                    sb.append(url);
                    access$000.loadUrl(sb.toString());
                }
            });
        }
        hideParseButton();
    }

    public boolean evalParseButtonVisible(String str) {
        boolean z = false;
        if (TextUtils.isEmpty(str)) {
            return false;
        }
        String[] strArr = {"//v.youku.com/", "//m.youku.com/", "iqiyi.com/", "le.com/ptv/vplay/", "le.com", "v.qq.com/", ".tudou.com/", "mgtv.com/b/", "film.sohu.com/", "tv.sohu.com/", ".bilibili.com/", ".pptv.com/show/", ".baofeng.com/play/", "baofeng.com", "wasu.cn/Play/show", ".1905.com/play"};
        if (!str.startsWith("https://timerd.me/")) {
            int length = strArr.length;
            int i = 0;
            while (true) {
                if (i >= length) {
                    break;
                } else if (str.contains(strArr[i])) {
                    z = true;
                    break;
                } else {
                    i++;
                }
            }
        }
        return z;
    }

    public void changeParseButtonVisible(boolean z) {
        if (z) {
            showParseButton();
        } else {
            hideParseButton();
        }
    }

    private void showParseButton() {
        View view = this.parseView;
        if (view != null && !view.isShown()) {
            this.parseView.setVisibility(view.VISIBLE);
        }
    }

    private void hideParseButton() {
        View view = this.parseView;
        if (view != null && view.isShown()) {
            this.parseView.setVisibility(View.INVISIBLE);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // Checks the orientation of the screen
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            //Toast.makeText(this, "landscape", Toast.LENGTH_SHORT).show();
            this.RotateState = RotateDeviceStatus.Rotate_Landscap;
        } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT){
            //Toast.makeText(this, "portrait", Toast.LENGTH_SHORT).show();
            this.RotateState = RotateDeviceStatus.Rotate_Portrait;
        }
    }

    private void loadOrSearch(String content){
        if(TextUtils.isEmpty(content)){
            return;
        }

        if(content.startsWith("http")){
            mainWebView.loadUrl(content);
            return;
        }

        String encodedContent = "";
        try {
            encodedContent = URLEncoder.encode(content, "utf-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        mainWebView.loadUrl("https://m.baidu.com/s?word="+encodedContent);
    }

    private void startRefreshGoBackButtonStateThread(){
        stopRefreshGoBackButtonStateThread();
        refreshGoBackButtonStateThread = new Thread(new Runnable() {
            @Override
            public void run() {
                Log.d("MainActivity", "RefreshGoBackButtonStateThread thread (" + Thread.currentThread().getId() +") :start");
                while(!Thread.currentThread().isInterrupted()){
                    try {
                        EventBus.getDefault().post(new RefreshGoBackButtonStateEvent());
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        Log.d("MainActivity", "RefreshGoBackButtonStateThread thread (" + Thread.currentThread().getId() +") :Interrupted");
                        return;
                    }
                }
                Log.d("MainActivity", "RefreshGoBackButtonStateThread thread (" + Thread.currentThread().getId() +") :exit");
            }
        });
        refreshGoBackButtonStateThread.start();
    }

    private void stopRefreshGoBackButtonStateThread(){
        try {
            refreshGoBackButtonStateThread.interrupt();
        }catch (Exception e){
            Log.d("MainActivity", "RefreshGoBackButtonStateThread线程已中止, Pass");
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
        requireAllPermissionForInit();
    }

    @Override
    protected void onStop() {
        stopRefreshGoBackButtonStateThread();
        if (mainWebView != null) {
            mainWebView.pauseTimers();
            mainWebView.onHide();
        }

        EventBus.getDefault().unregister(this);
        super.onStop();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onNewVideoItemDetectedEvent(NewVideoItemDetectedEvent newVideoItemDetectedEvent){


    }
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshGoBackButtonStateEvent(RefreshGoBackButtonStateEvent refreshGoBackButtonStateEvent){
        refreshGoBackButtonStatus();
    }
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onWebViewProgressUpdateEvent(WebViewProgressUpdateEvent webViewProgressUpdateEvent){
        int percent = webViewProgressUpdateEvent.getProgress();
        if(percent==100){
            webViewProgressVIew.setVisibility(View.INVISIBLE);
        }else{
            webViewProgressVIew.setVisibility(View.VISIBLE);
        }
        float weight = (100-percent)>0?((float)percent/(100-percent)):999999;
        webViewProgressVIew.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight));


    }
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onAddNewDownloadTaskEvent(AddNewDownloadTaskEvent addNewDownloadTaskEvent){
        VideoInfo videoInfo = addNewDownloadTaskEvent.getVideoInfo();
        DownloadTask downloadTask = new DownloadTask(
                UUIDUtil.genUUID(),videoInfo.getFileName(),
                ("m3u8".equals(videoInfo.getVideoFormat().getName())?"m3u8":"normal"),
                videoInfo.getVideoFormat().getName(),
                videoInfo.getUrl(),
                videoInfo.getSourcePageUrl(),
                videoInfo.getSourcePageTitle(),
                videoInfo.getSize());
        MainApplication.downloadManager.addTask(downloadTask);
    }


    private void refreshGoBackButtonStatus() {
        boolean canGoBack = mainWebView.getNavigationHistory().canGoBack();
        if(canGoBack){
            updateBottomButtonStatus(bottomGoBackButton, false);
        }else{
            updateBottomButtonStatus(bottomGoBackButton, true);
        }
    }



    private void updateBottomButtonStatus(View buttonView, boolean isDisabled){
        if (!(buttonView instanceof ViewGroup)) {
            return;
        }
        ViewGroup viewGroup = (ViewGroup) buttonView;
        if(viewGroup.getChildCount()<2){
            return;
        }
        if(isDisabled){
            viewGroup.getChildAt(0).setVisibility(View.INVISIBLE);
            viewGroup.getChildAt(1).setVisibility(View.VISIBLE);
        }else{
            viewGroup.getChildAt(0).setVisibility(View.VISIBLE);
            viewGroup.getChildAt(1).setVisibility(View.INVISIBLE);
        }
    }




    class MainXWalkResourceClient extends XWalkResourceClient{

        public MainXWalkResourceClient(XWalkView view) {
            super(view);
        }

        @Override
        public void onDocumentLoadedInFrame(XWalkView view, long frameId) {
            super.onDocumentLoadedInFrame(view, frameId);
        }

        @Override
        public void onLoadStarted(XWalkView view, String url) {
            super.onLoadStarted(view, url);
            Log.d("MainActivity", "onLoadStarted url:" + url);


            Log.d("MainActivity", "shouldInterceptLoadRequest hint url:" + url);


        }

        @Override
        public void onLoadFinished(XWalkView view, String url) {
            super.onLoadFinished(view, url);
            boolean access$600 = MainActivity.this.evalParseButtonVisible(url);
            if (access$600) {
                MainActivity.this.changeParseButtonVisible(access$600);
            } else {
                MainActivity.this.changeParseButtonVisible(false);
            }
        }

        @Override
        public void onProgressChanged(XWalkView view, int progressInPercent) {
            super.onProgressChanged(view, progressInPercent);
            Log.d("MainActivity", "onProgressChanged progressInPercent=" + progressInPercent);
            EventBus.getDefault().post(new WebViewProgressUpdateEvent(progressInPercent));
        }

        @Override
        public XWalkWebResourceResponse shouldInterceptLoadRequest(XWalkView view, XWalkWebResourceRequest request) {
            XWalkWebResourceResponse xWalkWebResourceResponse = super.shouldInterceptLoadRequest(view, request);
            String url = request.getUrl().toString();
            Log.d("MainActivity", "shouldInterceptLoadRequest url:" + url);

            return xWalkWebResourceResponse;
        }

        @Override
        public boolean shouldOverrideUrlLoading(XWalkView view, String url) {
            if (!(url.startsWith("http") || url.startsWith("https"))) {
                //非http https协议 不动作
                return true;
            }

            //http https协议 在本webView中加载

            String extension = MimeTypeMap.getFileExtensionFromUrl(url);
            if(VideoFormatUtil.containsVideoExtension(extension)){

                Log.d("MainActivity", "shouldOverrideUrlLoading detectTaskUrlList.add(url):" + url);
                return true;
            }

            Log.d("MainActivity", "shouldOverrideUrlLoading url="+url);
            view.loadUrl(url);
            return true;
        }

        @Override
        public void onReceivedSslError(XWalkView view, ValueCallback<Boolean> callback, SslError error) {
            callback.onReceiveValue(true);
        }
    }

    class MainXWalkUIClient extends XWalkUIClient{

        public MainXWalkUIClient(XWalkView view) {
            super(view);
        }

        @Override
        public void onReceivedTitle(XWalkView view, String title) {
            super.onReceivedTitle(view, title);
            Log.d("MainActivity", "onReceivedTitle title=" + title);
            currentTitle = title;
        }

        @Override
        public void onPageLoadStarted(XWalkView view, String url) {
            super.onPageLoadStarted(view, url);
            Log.d("MainActivity", "onPageLoadStarted url=" + url);
            currentUrl = url;
            MainActivity.this.currentUrl = url;
            boolean access$600 = MainActivity.this.evalParseButtonVisible(url);
            if (!access$600) {
                MainActivity.this.changeParseButtonVisible(access$600);
            }
        }

        @Override
        public void onPageLoadStopped(XWalkView view, String url, LoadStatus status) {
            super.onPageLoadStopped(view, url, status);
            Log.d("MainActivity", "onPageLoadStopped url=" + url);
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            super.onShowCustomView(view, callback);
        }

        @Override
        public void onHideCustomView() {
            super.onHideCustomView();
        }


    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch(keyCode){
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS://耳机三个按键是的上键，注意并不是耳机上的三个按键的物理位置的上下。
                Log.d("MainActivity", "onKeyDown KEYCODE_MEDIA_PREVIOUS");
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE://耳机单按键的按键或三按键耳机的中间按键。
                Log.d("MainActivity", "onKeyDown KEYCODE_MEDIA_PLAY_PAUSE");
            case KeyEvent.KEYCODE_HEADSETHOOK://耳机单按键的按键或三按键耳机的中间按键。与上面的按键可能是相同的，具体得看驱动定义。
                Log.d("MainActivity", "onKeyDown KEYCODE_HEADSETHOOK");
            case KeyEvent.KEYCODE_MEDIA_NEXT://耳机三个按键是的下键。
                Log.d("MainActivity", "onKeyDown KEYCODE_MEDIA_NEXT");
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0) {

            if(mainWebView.getNavigationHistory().canGoBack()){
                mainWebView.getNavigationHistory().navigate(XWalkNavigationHistory.Direction.BACKWARD, 1);
                refreshGoBackButtonStatus();
                return true;
            }
            // 创建退出对话框
            AlertDialog exitAlertDialog = new AlertDialog.Builder(this).create();
            // 设置对话框标题
            exitAlertDialog.setTitle("系统提示");
            // 设置对话框消息
            exitAlertDialog.setMessage("确定要退出吗?");
            // 添加选择按钮并注册监听
            exitAlertDialog.setButton("确定", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                    MainApplication.mainApplication.stopDownloadForegroundService();
                    if (mainWebView != null) {
                        mainWebView.onDestroy();
                    }
                    android.os.Process.killProcess(android.os.Process.myPid());
                }
            });
            exitAlertDialog.setButton2("取消", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                    dialogInterface.dismiss();
                }
            });
            // 显示对话框
            exitAlertDialog.show();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }


    private static class ViewHolder{
        TextView itemNewItemTitle;
        TextView itemNewItemVideoType;
        TextView itemNewItemFileInfo;
        View itemNewItemDownloadImageView;
        View itemNewItemDoneImageView;
        View itemNewItemDownloadButton;
    }


    public class NewItemAdapter extends BaseAdapter {

        private LayoutInflater mInflater;
        private SortedMap<String, VideoInfo> foundVideoInfoMap;
        private String[] foundVideoInfoMapKeyArray;

        public NewItemAdapter(Context context, SortedMap<String, VideoInfo> foundVideoInfoMap){
            this.mInflater = LayoutInflater.from(context);
            this.foundVideoInfoMap = foundVideoInfoMap;
            prepareData();
        }

        @Override
        public void notifyDataSetChanged() {
            prepareData();
            super.notifyDataSetChanged();
        }

        @Override
        public void notifyDataSetInvalidated() {
            prepareData();
            super.notifyDataSetInvalidated();
        }

        private void prepareData(){
            Set<String> strings = this.foundVideoInfoMap.keySet();
            this.foundVideoInfoMapKeyArray = strings.toArray(new String[strings.size()]);
        }

        @Override
        public int getCount() {
            return foundVideoInfoMapKeyArray.length;
        }

        @Override
        public Object getItem(int arg0) {
            return foundVideoInfoMapKeyArray[arg0];
        }

        @Override
        public long getItemId(int arg0) {
            return foundVideoInfoMapKeyArray[arg0].hashCode();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {

            ViewHolder holder = null;
            if (convertView == null) {

                holder=new ViewHolder();

                convertView = mInflater.inflate(R.layout.item_new_item, null);
                holder.itemNewItemTitle = (TextView) convertView.findViewById(R.id.itemNewItemTitle);
                holder.itemNewItemFileInfo = (TextView) convertView.findViewById(R.id.itemNewItemFileInfo);
                holder.itemNewItemDownloadImageView = convertView.findViewById(R.id.itemNewItemDownloadImageView);
                holder.itemNewItemDoneImageView = convertView.findViewById(R.id.itemNewItemDoneImageView);
                holder.itemNewItemDownloadButton = convertView.findViewById(R.id.downloadingItemDeleteButton);
                holder.itemNewItemVideoType = (TextView)convertView.findViewById(R.id.itemNewItemVideoType);
                convertView.setTag(holder);
            }else {
                holder = (ViewHolder)convertView.getTag();
            }

            VideoInfo videoInfo = foundVideoInfoMap.get(foundVideoInfoMapKeyArray[position]);
            VideoFormat videoFormat = videoInfo.getVideoFormat();
            holder.itemNewItemTitle.setText(TextUtils.isEmpty(videoInfo.getSourcePageTitle())?videoInfo.getFileName()+"."+videoFormat.getName():videoInfo.getSourcePageTitle()+"."+videoFormat.getName());
            holder.itemNewItemVideoType.setText(videoFormat.getName().toUpperCase());
            if("m3u8".equals(videoFormat.getName())){
                holder.itemNewItemFileInfo.setText(TimeUtil.formatTime((int)videoInfo.getDuration()));
            }else{
                holder.itemNewItemFileInfo.setText(FileUtil.getFormatedFileSize(videoInfo.getSize()));
            }

            holder.itemNewItemDownloadButton.setTag(videoInfo);
            holder.itemNewItemDownloadButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    VideoInfo videoInfo = (VideoInfo) v.getTag();
                    EventBus.getDefault().post(new AddNewDownloadTaskEvent(videoInfo));
                    EventBus.getDefault().post(new ShowToastMessageEvent("下载任务添加成功"));
                }
            });

            return convertView;
        }

    }


}
