package com.example.workbench.antd;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.Window;

import com.getcapacitor.BridgeActivity;

/**
 * 壳的 Activity。
 *
 * <p>两件事：注册 {@link NotifyPlugin}（不注册的话 Web 侧调不到原生方法），以及把
 * **通知点击的落点**接住——前台服务发的通知点了会回到这里（{@code FLAG_ACTIVITY_SINGLE_TOP}
 * 下走 onNewIntent），把待审批单子 id 暂存给插件，Web 起来后一次性取走。
 */
public class MainActivity extends BridgeActivity {

    /** 页面底色——底部导航条同色，比系统默认黑条干净 */
    private static final String NAV_BAR = "#F5F7FA";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(NotifyPlugin.class);
        registerPlugin(SysBarsPlugin.class);
        super.onCreate(savedInstanceState);
        tintSystemBars();
        captureDeepLink(getIntent());
    }

    /**
     * 给状态栏/导航条上色。
     *
     * <p>主题 {@code AppTheme.NoActionBarLaunch} 里已经写了一遍，这里再兜一次：MIUI 等
     * 定制机会在启动主题切换时把状态栏改回自己的灰，主题里那份不保证一路生效。</p>
     *
     * <p>亮色图标只在需要的 API 级别设（状态栏图标反白要 23+，导航条图标变黑要 27+），
     * 直接调会在低版本上忽略而已，所以也省不掉版本判断。</p>
     */
    private void tintSystemBars() {
        Window window = getWindow();
        View decor = window.getDecorView();
        // 页头渐变要一直铺进系统状态栏（挖孔那块也要有颜色，别再露条纯色），
        // WebView 得伸到状态栏之下：LAYOUT_FULLSCREEN + 状态栏透明，缺一不可。
        decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.parseColor(NAV_BAR));
        // 状态栏：白字压深蓝 → 关掉"浅色状态栏"（浅色=深字）
        decor.setSystemUiVisibility(decor.getSystemUiVisibility() & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            // 导航条：黑字压浅底 → 打开"浅色导航条"
            decor.setSystemUiVisibility(decor.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        captureDeepLink(intent);
    }

    private void captureDeepLink(Intent intent) {
        if (intent == null) return;
        if (!intent.hasExtra(NotifyCenter.EXTRA_APPROVAL_ID)) return;
        long id = intent.getLongExtra(NotifyCenter.EXTRA_APPROVAL_ID, NotifyCenter.NO_ID);
        // 消费掉 extra：用户之后手动从桌面进来不该又被定位一次
        intent.removeExtra(NotifyCenter.EXTRA_APPROVAL_ID);
        NotifyCenter.stashDeepLink(id);
    }
}
