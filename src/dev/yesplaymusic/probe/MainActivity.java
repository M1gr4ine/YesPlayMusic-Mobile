package dev.yesplaymusic.probe;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.ConsoleMessage;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Trusted packaged UI; audio and provider sessions are owned by native components. */
public class MainActivity extends Activity {
    private static final String ORIGIN="appassets.androidplatform.net";
    private static final Set<String> METHODS=new HashSet<>(Arrays.asList("home","search","playlist","album","artist","artistAlbums","songDetail","userPlaylists","loginStatus","sendCaptcha","loginCaptcha","lyrics","logout","qqStatus","qqSearch","qqLyrics","qqLogout","likeStatus","setLiked","recentHistory","ownedPlaylists","playlistAddTrack","playlistUpdate","playlistRemoveTracks","playlistReorder","matchTrack","cacheInfo","clearCache"));
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService requests=Executors.newFixedThreadPool(4);
    private WebView web;
    private android.window.OnBackInvokedCallback systemBack;
    private boolean alive;
    private int topInset,bottomInset;
    private boolean imeVisible;
    private int imeBottomDp;
    private boolean standby, chromeDark;
    private OrientationEventListener uprightWatcher;
    private boolean waitForUpright;
    private boolean batteryWatching;
    private volatile String batteryState = "{\"level\":null,\"charging\":false}";
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { updateBattery(intent); }
    };
    @Override public void onCreate(Bundle state){
        super.onCreate(state);alive=true;
        if(Build.VERSION.SDK_INT>=33){systemBack=this::handleBack;getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,systemBack);}
        getWindow().setStatusBarColor(Color.TRANSPARENT);getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if(Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        web=new WebView(this);web.setBackgroundColor(Color.rgb(250,249,247));
        WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setMediaPlaybackRequiresUserGesture(true);settings.setSupportMultipleWindows(false);settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setTextZoom(100);
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        web.addJavascriptInterface(new NativeBridge(),"Native");
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return !trusted(request.getUrl());}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                Uri uri=request.getUrl();
                if(trusted(uri)){
                    String path=uri.getPath();if(path==null||!path.startsWith("/assets/")||path.contains(".."))return empty();
                    String asset=path.substring(8);if(asset.isEmpty())asset="index.html";
                    try{
                        String mime=asset.endsWith(".html")?"text/html":asset.endsWith(".css")?"text/css":asset.endsWith(".js")?"application/javascript":asset.endsWith(".svg")?"image/svg+xml":asset.endsWith(".png")?"image/png":"application/octet-stream";
                        WebResourceResponse response=new WebResourceResponse(mime,"UTF-8",getAssets().open(asset));
                        java.util.Map<String,String> headers=new java.util.HashMap<>();headers.put("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' https: data: blob:; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'");headers.put("Cache-Control","no-cache");response.setResponseHeaders(headers);return response;
                    }catch(Exception error){return empty();}
                }
                if(request.isForMainFrame()||!"https".equals(uri.getScheme())||!imageHost(uri.getHost()))return empty();
                return null;
            }
            @Override public void onPageFinished(WebView view,String url){applyInsets();applyKeyboard();}
        });
        web.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onConsoleMessage(ConsoleMessage message){
                // Do not log JS payloads, login data, provider cookies or signed URLs.
                if(message.messageLevel()==ConsoleMessage.MessageLevel.ERROR)android.util.Log.w("YPMMobileUI","JavaScript error at "+message.lineNumber());
                return true;
            }
        });
        web.setOnApplyWindowInsetsListener((view,insets)->{
            float density=getResources().getDisplayMetrics().density;
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());topInset=Math.round(bars.top/density);bottomInset=Math.round(bars.bottom/density);}
            else{topInset=Math.round(insets.getSystemWindowInsetTop()/density);bottomInset=Math.round(insets.getSystemWindowInsetBottom()/density);}
            boolean ime;
            int imeBottomPixels;
            if(Build.VERSION.SDK_INT>=30){
                ime=insets.isVisible(WindowInsets.Type.ime());
                imeBottomPixels=ime?insets.getInsets(WindowInsets.Type.ime()).bottom:0;
            }else{
                imeBottomPixels=Math.max(0,insets.getSystemWindowInsetBottom()-insets.getStableInsetBottom());
                ime=imeBottomPixels>100*density;
                if(!ime)imeBottomPixels=0;
            }
            int nextImeBottomDp=ime?Math.max(0,Math.round(imeBottomPixels/density)):0;
            if(ime!=imeVisible||nextImeBottomDp!=imeBottomDp){imeVisible=ime;imeBottomDp=nextImeBottomDp;applyKeyboard();}
            applyInsets();return insets;
        });
        setContentView(web);web.loadUrl("https://"+ORIGIN+"/assets/index.html");
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},51);
    }
    private static boolean trusted(Uri uri){return "https".equals(uri.getScheme())&&ORIGIN.equals(uri.getHost());}
    private static boolean imageHost(String host){if(host==null)return false;host=host.toLowerCase(Locale.ROOT);return host.endsWith(".music.126.net")||host.endsWith(".music.163.com")||host.endsWith(".music.126.com")||host.endsWith(".gtimg.cn")||host.endsWith(".qlogo.cn")||host.endsWith(".qq.com")||host.endsWith(".gtimg.com");}
    private static WebResourceResponse empty(){return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));}
    private void applyInsets(){if(web!=null&&alive)web.evaluateJavascript("document.documentElement.style.setProperty('--safe-top','"+topInset+"px');document.documentElement.style.setProperty('--safe-bottom','"+bottomInset+"px');",null);}
    private void applyKeyboard(){if(web!=null&&alive)web.evaluateJavascript("window.YPM&&window.YPM.setKeyboard&&window.YPM.setKeyboard("+imeVisible+","+imeBottomDp+")",null);}
    private void response(String id,String result,String error){main.post(()->{if(alive&&web!=null)web.evaluateJavascript("window.YPM&&window.YPM.resolve("+JSONObject.quote(id)+","+(result==null?"null":JSONObject.quote(result))+","+(error==null?"null":JSONObject.quote(error))+");",null);});}
    @Override public void onBackPressed(){handleBack();}
    private void handleBack(){if(!alive)return;if(web!=null)web.evaluateJavascript("window.YPM&&window.YPM.back?window.YPM.back():false",result->{if(alive&&!"true".equals(result))moveTaskToBack(true);});else moveTaskToBack(true);}
    @Override protected void onResume(){super.onResume();watchBattery(true);if(waitForUpright)watchUpright(true);if(web!=null){web.onResume();web.evaluateJavascript("window.dispatchEvent(new Event('native-resume'))",null);}}
    @Override protected void onPause(){super.onPause();watchBattery(false);watchUpright(false);if(web!=null)web.onPause();}
    private void watchBattery(boolean watch) {
        if (watch == batteryWatching) return;
        if (watch) {
            // BATTERY_CHANGED is a protected system sticky broadcast; no polling or permission is needed.
            Intent current = registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            batteryWatching = true;
            updateBattery(current);
        } else {
            unregisterReceiver(batteryReceiver);
            batteryWatching = false;
        }
    }
    private void updateBattery(Intent intent) {
        if (intent == null) return;
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        boolean present = intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true);
        int percent = present && level >= 0 && scale > 0 ? Math.round(level * 100f / scale) : -1;
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || (status == BatteryManager.BATTERY_STATUS_FULL && intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0);
        String next = "{\"level\":" + (percent >= 0 && percent <= 100 ? Integer.toString(percent) : "null")
                + ",\"charging\":" + charging + "}";
        if (next.equals(batteryState)) return;
        batteryState = next;
        if (alive && standby && web != null)
            web.evaluateJavascript("window.dispatchEvent(new CustomEvent('native-battery',{detail:" + next + "}))", null);
    }
    private void applyChrome(){
        if(standby){
            if(Build.VERSION.SDK_INT>=30){WindowInsetsController bars=getWindow().getInsetsController();if(bars!=null){bars.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);bars.hide(WindowInsets.Type.systemBars());}}
            else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            return;
        }
        if(Build.VERSION.SDK_INT>=30){WindowInsetsController bars=getWindow().getInsetsController();if(bars!=null)bars.show(WindowInsets.Type.systemBars());}
        int flags=View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;if(!chromeDark)flags|=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;getWindow().getDecorView().setSystemUiVisibility(flags);
    }
    /** After the standby page is closed while held sideways, portrait stays pinned until the phone is upright again. */
    private void watchUpright(boolean enable){
        if(!enable){if(uprightWatcher!=null)uprightWatcher.disable();return;}
        if(uprightWatcher==null)uprightWatcher=new OrientationEventListener(this){@Override public void onOrientationChanged(int degrees){
            if(degrees==ORIENTATION_UNKNOWN||(degrees>25&&degrees<335))return;
            waitForUpright=false;disable();setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }};
        if(uprightWatcher.canDetectOrientation())uprightWatcher.enable();
        else{waitForUpright=false;setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);}
    }
    @Override protected void onDestroy(){watchBattery(false);alive=false;if(Build.VERSION.SDK_INT>=33&&systemBack!=null)getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(systemBack);requests.shutdownNow();if(web!=null){web.removeJavascriptInterface("Native");if(web.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)web.getParent()).removeView(web);web.destroy();web=null;}super.onDestroy();}
    private void clearLoginCookies(String provider, Runnable done) {
        main.post(() -> {
            android.webkit.CookieManager manager=android.webkit.CookieManager.getInstance();
            String[] hosts="qq".equals(provider) ? new String[]{"y.qq.com","qq.com","xui.ptlogin2.qq.com","ssl.ptlogin2.qq.com","graph.qq.com"} : new String[]{"music.163.com","interface.music.163.com","reg.163.com","passport.163.com"};
            java.util.List<String[]> tasks=new java.util.ArrayList<>();
            for(String host:hosts){
                String url="https://"+host+"/", cookies=manager.getCookie(url);
                if(cookies==null)continue;
                for(String cookie:cookies.split(";")){
                    int equal=cookie.indexOf('=');if(equal<1)continue;
                    String name=cookie.substring(0,equal).trim();if(!name.matches("[A-Za-z0-9_-]+"))continue;
                    String expired=name+"=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Secure";
                    tasks.add(new String[]{url,expired});
                    String domain=host;
                    while(domain.indexOf('.')>0){tasks.add(new String[]{url,expired+"; Domain=."+domain});domain=domain.substring(domain.indexOf('.')+1);}
                }
            }
            if(tasks.isEmpty()){done.run();return;}
            java.util.concurrent.atomic.AtomicInteger remaining=new java.util.concurrent.atomic.AtomicInteger(tasks.size());
            for(String[] task:tasks)manager.setCookie(task[0],task[1],ignored->{if(remaining.decrementAndGet()==0){manager.flush();done.run();}});
        });
    }
    private JSONObject cacheInfo() throws org.json.JSONException {
        return new JSONObject().put("code",200).put("bytes",directorySize(getCacheDir())+directorySize(getExternalCacheDir()));
    }
    /** Songs stream without a disk cache; this clears cover images and other cached web data. */
    private JSONObject clearCache() throws Exception {
        java.util.concurrent.CountDownLatch cleared=new java.util.concurrent.CountDownLatch(1);
        main.post(()->{try{if(web!=null)web.clearCache(true);}finally{cleared.countDown();}});
        cleared.await(5,java.util.concurrent.TimeUnit.SECONDS);
        // WebView owns its cache folder and has just emptied it; deleting it underneath would break it.
        deleteCacheChildren(getCacheDir(),true);deleteCacheChildren(getExternalCacheDir(),true);
        return cacheInfo();
    }
    private static long directorySize(java.io.File file){
        if(file==null||!file.exists()||java.nio.file.Files.isSymbolicLink(file.toPath()))return 0;
        if(file.isFile())return file.length();
        long total=0;java.io.File[] children=file.listFiles();
        if(children!=null)for(java.io.File child:children)total+=directorySize(child);
        return total;
    }
    private static void deleteCacheChildren(java.io.File directory,boolean keepWebView){
        java.io.File[] children=directory==null?null:directory.listFiles();
        if(children==null)return;
        for(java.io.File child:children){
            String name=child.getName();
            if(keepWebView&&(name.startsWith("WebView")||name.startsWith("org.chromium")))continue;
            if(child.isDirectory()&&!java.nio.file.Files.isSymbolicLink(child.toPath()))deleteCacheChildren(child,false);
            child.delete();
        }
    }
    private class NativeBridge {
        @JavascriptInterface public void request(String id,String method,String args){
            if(id==null||id.length()>100||!METHODS.contains(method)||args==null||args.length()>100000){response(id==null?"":id,null,"请求参数不正确");return;}
            requests.execute(()->{try{
                JSONObject input=new JSONObject(args);JSONObject result;if("cacheInfo".equals(method))result=cacheInfo();else if("clearCache".equals(method))result=clearCache();else if("recentHistory".equals(method))result=RecentPlaybackStore.get(MainActivity.this).execute(input);else result=method.startsWith("qq")?QQSource.get(MainActivity.this).execute(method,input):MusicApi.get(MainActivity.this).execute(method,input);
                if ("logout".equals(method) || "qqLogout".equals(method)) { clearLoginCookies("qqLogout".equals(method) ? "qq" : "netease", () -> response(id,result.toString(),null)); }
                else response(id,result.toString(),null);
            }catch(Exception error){String message=error.getMessage();if(message==null||message.length()>180)message="暂时无法连接音乐服务，请检查网络后重试";response(id,null,message);}});
        }
        @JavascriptInterface public void command(String payload){
            if(payload==null||payload.length()>700000)return;
            main.post(()->{try{new JSONObject(payload);Intent intent=new Intent(MainActivity.this,PlaybackService.class).putExtra("command","json").putExtra("payload",payload);startForegroundService(intent);}catch(Exception error){Toast.makeText(MainActivity.this,"播放操作失败，请重新打开播放器",Toast.LENGTH_SHORT).show();}});
        }
        @JavascriptInterface public String getState(){
            String live=PlaybackService.publishedState();
            if(live!=null)return live;
            String fallback="{\"track\":null,\"queue\":[],\"playing\":false,\"buffering\":false,\"position\":0,\"duration\":0,\"index\":0,\"repeat\":\"all\",\"shuffle\":false,\"volume\":1,\"source\":\"\",\"error\":\"\"}";
            String saved=getSharedPreferences("player_state",MODE_PRIVATE).getString("json",fallback);
            if(saved==null||saved.isEmpty())return fallback;
            try{
                JSONObject snapshot=new JSONObject(saved);
                snapshot.put("playing",false);
                snapshot.put("buffering",false);
                return snapshot.toString();
            }catch(Exception ignored){return fallback;}
        }
        @JavascriptInterface public String getQueue(){String live=PlaybackService.publishedQueue();return live!=null?live:"{\"version\":-1,\"queue\":[]}";}
        @JavascriptInterface public String getBattery(){return batteryState;}
        @JavascriptInterface public String getSettings(){try{SharedPreferences values=getSharedPreferences("mobile_settings",MODE_PRIVATE);return new JSONObject().put("sourcePreference",values.getString("sourcePreference","auto")).put("theme",values.getString("theme","system")).put("statusLyrics",values.getBoolean("statusLyrics",true)).put("lyricsTranslation",values.getBoolean("lyricsTranslation",true)).toString();}catch(Exception ignored){return "{}";}}
        @JavascriptInterface public void settings(String json){try{JSONObject input=new JSONObject(json);SharedPreferences.Editor edit=getSharedPreferences("mobile_settings",MODE_PRIVATE).edit();String source=input.optString("sourcePreference"),theme=input.optString("theme");if(Arrays.asList("auto","netease","qq").contains(source))edit.putString("sourcePreference",source);if(Arrays.asList("system","light","dark").contains(theme))edit.putString("theme",theme);if(input.has("statusLyrics"))edit.putBoolean("statusLyrics",input.optBoolean("statusLyrics",true));if(input.has("lyricsTranslation"))edit.putBoolean("lyricsTranslation",input.optBoolean("lyricsTranslation",true));edit.apply();}catch(Exception ignored){}}
        @JavascriptInterface public void login(String provider){if(!"qq".equals(provider)&&!"netease".equals(provider))return;main.post(()->startActivity(new Intent(MainActivity.this,LoginActivity.class).putExtra("provider",provider)));}
        @JavascriptInterface public void haptic(){main.post(()->{if(web!=null)web.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);});}
        @JavascriptInterface public void chrome(boolean dark){main.post(()->{chromeDark=dark;applyChrome();});}
        /** Landscape lyrics standby: hide the system bars, and keep the screen on only while music plays. */
        @JavascriptInterface public void standby(boolean active,boolean keepAwake){main.post(()->{
            if(keepAwake)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if(standby!=active){
                standby=active;applyChrome();
                // On high-refresh panels, cap the standby page at 60 Hz so lyric transitions cost half the frames.
                if(Build.VERSION.SDK_INT>=35&&web!=null)web.setRequestedFrameRate(active?View.REQUESTED_FRAME_RATE_CATEGORY_NORMAL:View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT);
            }
        });}
        @JavascriptInterface public void orientation(String mode){main.post(()->{
            waitForUpright="portrait".equals(mode);
            watchUpright(waitForUpright);
            setRequestedOrientation("landscape".equals(mode)?ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE:waitForUpright?ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        });}
    }
}
