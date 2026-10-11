package dev.yesplaymusic.probe;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Locale;

/** Official provider login pages run separately, without the app's JavaScript bridge. */
public final class LoginActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private String provider;
    private WebView web;
    private android.window.OnBackInvokedCallback systemBack;
    private LinearLayout container;
    private android.widget.FrameLayout browserContainer;
    private final java.util.Map<WebView,WebView> popupParents=new java.util.IdentityHashMap<>();
    private String lastCookieFlags="";
    private boolean active,finished,waitingForSession;
    private String pendingQQKey="";
    private long pendingQQAt;
    private final java.util.List<WebView> loginWindows=new java.util.ArrayList<>();
    private final Runnable collect=new Runnable(){@Override public void run(){if(!active||finished)return;collectSession();handler.postDelayed(this,1200);}};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);if(android.os.Build.VERSION.SDK_INT>=33){systemBack=this::handleBack;getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,systemBack);}provider=getIntent().getStringExtra("provider");if(!"qq".equals(provider))provider="netease";
        getWindow().setStatusBarColor(Color.WHITE);getWindow().setNavigationBarColor(Color.WHITE);
        if(android.os.Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
        getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE|android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        container=new LinearLayout(this);container.setOrientation(LinearLayout.VERTICAL);container.setBackgroundColor(Color.WHITE);
        container.setOnApplyWindowInsetsListener((view,insets)->{
            if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout()|android.view.WindowInsets.Type.ime());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);}
            else view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout header=new LinearLayout(this);header.setGravity(android.view.Gravity.CENTER_VERTICAL);header.setPadding(dp(8),dp(4),dp(8),dp(4));
        Button close=headerButton();close.setText("返回");close.setTextColor(0xffe84259);close.setBackgroundColor(Color.TRANSPARENT);close.setOnClickListener(v->finish());header.addView(close,new LinearLayout.LayoutParams(dp(64),dp(48)));
        TextView title=new TextView(this);title.setText("qq".equals(provider)?"登录 QQ 音乐":"登录网易云音乐");title.setGravity(android.view.Gravity.CENTER);title.setIncludeFontPadding(false);title.setTextColor(0xff222222);title.setTextSize(18);title.setTypeface(null,android.graphics.Typeface.BOLD);header.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));
        Button done=headerButton();done.setText("完成");done.setTextColor(0xffe84259);done.setBackgroundColor(Color.TRANSPARENT);done.setOnClickListener(v->{if(!collectSession())Toast.makeText(this,waitingForSession?"正在确认登录状态…":"登录尚未完成，请先在官方页面确认",Toast.LENGTH_SHORT).show();});header.addView(done,new LinearLayout.LayoutParams(dp(64),dp(48)));container.addView(header);
        browserContainer=new android.widget.FrameLayout(this);container.addView(browserContainer,new LinearLayout.LayoutParams(-1,0,1));
        web=createWeb();browserContainer.addView(web,new android.widget.FrameLayout.LayoutParams(-1,-1));setContentView(container);
        web.loadUrl("qq".equals(provider)?"https://y.qq.com/n/ryqq/profile":"https://music.163.com/#/login");
    }
    private Button headerButton(){Button button=new Button(this);button.setPadding(0,0,0,0);button.setMinWidth(0);button.setMinHeight(0);button.setMinimumWidth(0);button.setMinimumHeight(0);button.setGravity(android.view.Gravity.CENTER);button.setIncludeFontPadding(false);button.setTextSize(16);button.setAllCaps(false);return button;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private WebView createWeb(){
        WebView result=new WebView(this);loginWindows.add(result);WebSettings settings=result.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setLoadWithOverviewMode(true);settings.setUseWideViewPort(true);settings.setSupportZoom(true);settings.setBuiltInZoomControls(true);settings.setDisplayZoomControls(false);settings.setSupportMultipleWindows(true);settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
        CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(result,true);
        result.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                Uri uri=request.getUrl();if(official(uri))return false;
                trace("navigation-blocked scheme="+uri.getScheme()+" host="+uri.getHost()+" main="+request.isForMainFrame());
                if(request.isForMainFrame())openOfficialApp(uri);return true;
            }
            @Override public void onPageFinished(WebView view,String url){
                if(!official(Uri.parse(url)))return;
                trace("page-finished window="+loginWindows.indexOf(view)+" host="+Uri.parse(url).getHost());
                collectSession();
                if("qq".equals(provider)&&"y.qq.com".equals(Uri.parse(url).getHost())){
                    view.evaluateJavascript("(function(){if(!document.querySelector('#login_frame')){var a=document.querySelector('a.top_login__link');if(a)a.click();}})()",null);
                }
            }
        });
        result.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onCreateWindow(WebView source,boolean dialog,boolean userGesture,Message resultMsg){
                String sourceUrl=source.getUrl();
                trace("popup-create source="+loginWindows.indexOf(source)+" gesture="+userGesture);
                if(sourceUrl==null||!official(Uri.parse(sourceUrl))||loginWindows.size()>=4)return false;
                WebView popup=createWeb();popupParents.put(popup,source);web=popup;
                // Keep the opener attached: QQ completes OAuth with opener callbacks/window.close.
                browserContainer.addView(popup,new android.widget.FrameLayout.LayoutParams(-1,-1));
                WebView.WebViewTransport transport=(WebView.WebViewTransport)resultMsg.obj;transport.setWebView(popup);resultMsg.sendToTarget();return true;
            }
            @Override public void onCloseWindow(WebView window){trace("popup-close window="+loginWindows.indexOf(window));collectSession();closePopup(window);handler.postDelayed(()->collectSession(),300);}
        });return result;
    }
    private void trace(String event){if((getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0)android.util.Log.i("YPMAuth",provider+" "+event);}
    private void closePopup(WebView window){
        WebView parent=popupParents.get(window);if(parent==null)return;
        for(WebView child:new java.util.ArrayList<>(popupParents.keySet()))if(popupParents.get(child)==window)closePopup(child);
        popupParents.remove(window);
        browserContainer.removeView(window);if(web==window){web=parent;parent.bringToFront();}
        handler.post(()->{loginWindows.remove(window);window.destroy();});
    }
    private boolean official(Uri uri){
        if(!"https".equals(uri.getScheme()))return false;String host=uri.getHost();if(host==null)return false;host=host.toLowerCase(Locale.ROOT);
        return "qq".equals(provider)?host.equals("qq.com")||host.endsWith(".qq.com")||host.equals("open.weixin.qq.com"):host.equals("music.163.com")||host.endsWith(".163.com")||host.endsWith(".126.com");
    }
    private void openOfficialApp(Uri uri){
        try{
            Intent intent;
            if("intent".equals(uri.getScheme())){intent=Intent.parseUri(uri.toString(),Intent.URI_INTENT_SCHEME);intent.setComponent(null);intent.setSelector(null);}
            else intent=new Intent(Intent.ACTION_VIEW,uri);
            String scheme=intent.getData()==null?"":intent.getData().getScheme();String pkg;
            if(scheme!=null&&(scheme.equals("mqq")||scheme.equals("mqqapi")||scheme.equals("mqqopensdkapi")))pkg="com.tencent.mobileqq";
            else if(scheme!=null&&(scheme.equals("qqmusic")||scheme.equals("qqmusicactive")||scheme.equalsIgnoreCase("androidqqmusic")))pkg="com.tencent.qqmusic";
            else if(scheme!=null&&(scheme.equals("orpheus")||scheme.equals("orpheuswidget")))pkg="com.netease.cloudmusic";
            else return;
            intent.setPackage(pkg);intent.addCategory(Intent.CATEGORY_BROWSABLE);startActivity(intent);
        }catch(Exception error){Toast.makeText(this,"暂时无法唤起官方应用，可以继续在此完成登录",Toast.LENGTH_SHORT).show();}
    }
    private boolean collectSession(){
        if(finished)return true;waitingForSession=false;CookieManager cookies=CookieManager.getInstance();
        try{
            boolean saved=false;
            if("qq".equals(provider)){
                String value=cookies.getCookie("https://y.qq.com/");
                if(value!=null){
                    String key="";boolean refresh=false;boolean hasUin=false;
                    for(String field:value.split(";")){int at=field.indexOf('=');if(at<1)continue;String name=field.substring(0,at).trim(),content=field.substring(at+1).trim();if(("uin".equals(name)||"wxuin".equals(name))&&content.replaceFirst("^o0*", "").matches("[1-9][0-9]*"))hasUin=true;if(("qqmusic_key".equals(name)||"qm_keyst".equals(name))&&!content.isEmpty())key=content;if(("psrf_qqrefresh_token".equals(name)||"qqrefresh_token".equals(name))&&!content.isEmpty())refresh=true;}
                    String flags="uin="+hasUin+" musicKey="+!key.isEmpty()+" refresh="+refresh;if(!flags.equals(lastCookieFlags)){lastCookieFlags=flags;trace("cookie-flags "+flags);}
                    if(!key.isEmpty()){
                        long now=android.os.SystemClock.uptimeMillis();if(!key.equals(pendingQQKey)){pendingQQKey=key;pendingQQAt=now;}
                        // The official flow may set its refresh token shortly after the music key.
                        if(refresh||now-pendingQQAt>=1500)saved=QQSource.get(this).setCookie(value);
                        else waitingForSession=hasUin;
                    }
                }
            }else{
                String value=cookies.getCookie("https://music.163.com/");
                if(value!=null&&java.util.regex.Pattern.compile("(?:^|;\\s*)MUSIC_U=[^;\\s]+").matcher(value).find()){MusicApi.get(this).setCookie(value);saved=true;}
            }
            if(saved){trace("session-saved");finished=true;cookies.flush();Toast.makeText(this,"登录成功",Toast.LENGTH_SHORT).show();finish();return true;}
        }catch(Exception error){trace("session-save-error type="+error.getClass().getSimpleName());Toast.makeText(this,"登录信息保存失败，请重试",Toast.LENGTH_SHORT).show();}return false;
    }
    @Override protected void onResume(){super.onResume();trace("resume windows="+loginWindows.size());active=true;handler.post(collect);}
    @Override protected void onPause(){active=false;trace("pause");handler.removeCallbacks(collect);super.onPause();}
    @Override protected void onDestroy(){if(android.os.Build.VERSION.SDK_INT>=33&&systemBack!=null)getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(systemBack);handler.removeCallbacksAndMessages(null);for(WebView window:loginWindows){if(window.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)window.getParent()).removeView(window);window.stopLoading();window.destroy();}loginWindows.clear();popupParents.clear();web=null;pendingQQKey="";super.onDestroy();}
    @Override public void onBackPressed(){handleBack();}
    private void handleBack(){if(web!=null&&popupParents.containsKey(web))closePopup(web);else if(web!=null&&web.canGoBack())web.goBack();else finish();}
}
