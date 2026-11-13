package com.aurora.lsp

import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.animateFloatAsState
import com.aurora.lsp.liquid.lens
import com.aurora.lsp.liquid.rememberCombinedBackdrop
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.input.pointer.pointerInput
import top.yukonga.miuix.kmp.blur.drawBackdrop
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.aurora.audio.lsp.R
import top.yukonga.miuix.kmp.basic.Switch
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.graphics.drawable.ColorDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.WindowCompat
import android.app.Activity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import com.aurora.lsp.theme.LocalAuroraDarkTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // HyperOS 4 / Android 17 forces edge-to-edge, and setStatusBarColor /
        // setNavigationBarColor are no-ops there. Let the framework do it and handle insets
        // in Compose, so the gesture bar (the MIUI/HyperOS "提示线") sits on our own background
        // instead of on the framework window colour.
        // Fully immersive gesture bar: both bars transparent, page content runs underneath.
        val clear = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(clear, clear),
            navigationBarStyle = SystemBarStyle.auto(clear, clear)
        )
        setContent { AuroraApp() }
    }
}

// Colour roles, read from the theme instead of written as literals.
//
// These used to be four hard-coded greys, which is why screens did not share a ramp with the
// MIUIX surfaces around them. They are now aliases for the theme's own semantic roles, so a screen
// that still says `Ink` is automatically correct - both branches resolve to
// the same role, and only the theme decides the value.
internal val Ink: Color @Composable @ReadOnlyComposable get() = MiuixTheme.colorScheme.onSurface
internal val InkDark: Color @Composable @ReadOnlyComposable get() = MiuixTheme.colorScheme.onSurface
internal val Muted: Color @Composable @ReadOnlyComposable get() = MiuixTheme.colorScheme.onSurfaceVariantSummary
internal val MutedDark: Color @Composable @ReadOnlyComposable get() = MiuixTheme.colorScheme.onSurfaceVariantSummary
/** Live accent: follows whichever palette the user picked in 设置 → 主题. */
internal val Accent: Color get() = AuroraPalette.accent
internal val AccentDark: Color get() = AuroraPalette.accentDark
internal val OkColor: Color @Composable @ReadOnlyComposable get() = MiuixTheme.colorScheme.primary
internal val WarnColor: Color @Composable @ReadOnlyComposable get() = MiuixTheme.colorScheme.error

@Composable
private fun AuroraApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("aurora_ui", Context.MODE_PRIVATE) }
    val ui = remember { UiPrefs(prefs) }
    // Reads the saved palette before anything draws with the accent.
    LaunchedEffect(Unit) {
        AuroraPalette.apply(prefs.getString("theme_id", DEFAULT_THEME_ID))
    }
    val systemDark = (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    val dark = when (ui.themeMode) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }

    val state = remember { AuroraState(context.applicationContext) }
    val community = remember { CommunityState(context.applicationContext, prefs) }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }

    val version = remember { installedVersion(context) }
    var pendingUpdate by remember { mutableStateOf<Update.Release?>(null) }
    var updateChecking by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf<String?>(null) }

    fun runUpdateCheck(manual: Boolean) {
        if (updateChecking) return
        updateChecking = true
        if (manual) updateMessage = null
        scope.launch {
            when (val result = Update.check(version.code, version.name)) {
                is Update.Result.Newer ->
                    if (manual || result.release.versionCode > ui.skippedVersionCode) {
                        pendingUpdate = result.release
                    }
                is Update.Result.UpToDate ->
                    if (manual) updateMessage = "已是最新版本 " + result.versionName
                is Update.Result.Failed ->
                    if (manual) updateMessage = result.reason
            }
            updateChecking = false
        }
    }

    // The module writes a profile when it probes the device at boot. "I turned it on and nothing
    // happened" has three different causes, so each gets its own notice instead of one vague page.
    val profile = state.device
    var showFeedback by remember { mutableStateOf(false) }
    var showDevice by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(false) }
    var showThemes by remember { mutableStateOf(false) }
    var adaptationResult by remember { mutableStateOf<String?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var editorCreating by remember { mutableStateOf(false) }
    // Diagnostics lives behind the status capsule and a settings row: it is the page you open
    // when something is wrong, not a place you visit every day.
    var showDiag by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var showIdentity by remember { mutableStateOf(false) }
    var showMyLikes by remember { mutableStateOf(false) }
    var detailItem by remember { mutableStateOf<Community.Item?>(null) }
    // The first launch always gets the explanation once. Afterwards it only returns when a fresh
    // boot probe says this device cannot work - that is a new fact, not a nag.
    var showOnboarding by remember {
        mutableStateOf(!prefs.getBoolean("onboarding_done", false))
    }
    var feedbackSending by remember { mutableStateOf(false) }
    var feedbackResult by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(profile.probedAt) {
        if (profile.probedAt > 0L && !profile.supported) showOnboarding = true
    }
    LaunchedEffect(profile, version.code) {
        if (profile.probed && !profile.supported) {
            Feedback.reportDevice(
                context.applicationContext, profile, version.code, version.name, prefs
            )
        }
    }

    // Silent on every launch unless there really is something newer; picking "以后再说" remembers
    // the version so the same prompt never comes back.
    LaunchedEffect(Unit) {
        if (ui.autoCheckUpdate) runUpdateCheck(manual = false)
    }

    // Adaptation is fetched the moment the module is on: by then root has been granted, so the
    // pack write cannot trigger a surprise su prompt on a fresh install.
    LaunchedEffect(state.enabled, state.device.model) {
        if (!state.enabled || state.device.model.isBlank()) return@LaunchedEffect
        when (val result = DevicePack.checkAndApply(state)) {
            is DevicePack.Result.Applied ->
                AuroraLog.append(context, "pack", "applied: " + result.name)
            is DevicePack.Result.Unchanged, DevicePack.Result.NoMatch -> Unit
            is DevicePack.Result.Failed ->
                AuroraLog.append(context, "pack", "check failed: " + result.reason)
        }
    }

    LaunchedEffect(Unit) {
        state.refresh()
        while (true) {
            delay(2000)
            state.refresh()
        }
    }

    LaunchedEffect(Unit) {
        AuroraLog.append(context, "app", "start v" + version.name + " (" + version.code + ")")
    }

    // Records every state change the engine reports, so a later report shows the whole history.
    LaunchedEffect(state.backendState, state.backendMessage) {
        if (state.backendState.isNotEmpty()) {
            AuroraLog.append(context, "engine", state.backendState + " " + state.backendMessage)
        }
    }

    CompositionLocalProvider(LocalAuroraDarkTheme provides dark) {
        MiuixTheme(colors = if (dark) {
                darkColorScheme(primary = AccentDark)
            } else {
                lightColorScheme(primary = Accent)
            }) {
            val activity = context as? Activity
            // The window background belongs to the framework theme and does not follow the in-app
            // theme, so anything Compose does not paint (the status bar strip, the empty space
            // under a short page) used to show the framework colour. Paint it here instead.
            val surface = MiuixTheme.colorScheme.surface
            val windowBackground = remember(surface) { ColorDrawable(surface.toArgb()) }
            SideEffect {
                if (activity != null) {
                    val decor = activity.window.decorView
                    val controller = WindowCompat.getInsetsController(activity.window, decor)
                    controller.isAppearanceLightStatusBars = !dark
                    controller.isAppearanceLightNavigationBars = !dark
                    // Without this the three-button bar gets a translucent scrim on top of the
                    // gesture bar's own background, which breaks the "same colour as the page"
                    // rule from the HyperOS adaptation guide.
                    activity.window.isNavigationBarContrastEnforced = false
                    decor.background = windowBackground
                }
            }
            // One layer, sampled by the dock.
            //
            // The dock is outside the pages, so it can read a layer that holds them without the
            // render tree ever recursing into itself. Nothing inside the pages samples it: glass in
            // the content layer is gone along with the custom switch, which is what HIG asks for -
            // Liquid Glass belongs to the functional layer, not to the cards.
            val dockBackdrop = rememberLayerBackdrop()
            // Cross-page jumps now sweep with the dock's droplet instead of fading through: the
            // page and the indicator read the same scroll position, so it lands as one movement.
            // Duration grows with distance, otherwise a three-page jump whips past faster than the
            // eye can follow which direction it went.
            // Two taps in a row used to cancel each other mid-fade and leave the page half
            // transparent with no page switch. One jump runs at a time; the rest queue.
            val pagerState = rememberPagerState(pageCount = { 4 })
            LaunchedEffect(pagerState.currentPage) { tab = pagerState.currentPage }

            var backProgress by remember { mutableFloatStateOf(0f) }
            PredictiveBackHandler(enabled = ui.predictiveBack && tab != 0) { progress ->
                try {
                    progress.collect { backProgress = it.progress }
                    pagerState.scrollToPage(0)
                } finally {
                    // Runs once when the gesture ends, whether it completed or was cancelled.
                    backProgress = 0f
                }
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .background(MiuixTheme.colorScheme.surface)
                    .graphicsLayer {
                        val progress = backProgress
                        val scale = 1f - 0.07f * progress
                        scaleX = scale
                        scaleY = scale
                        shape = RoundedCornerShape((26 * progress).dp)
                        clip = progress > 0.01f
                    }
            ) {
                Scaffold(
                    // Transparent so the generated background reaches the status bar; the
                    // scaffold used to paint its own surface over it.
                    containerColor = Color.Transparent
                ) { inner ->
                    Column(
                        Modifier
                            .fillMaxSize()
                            .layerBackdrop(dockBackdrop)
                            .background(MiuixTheme.colorScheme.surface)
                            // Only the top is padded: the bottom inset is left to the content so
                            // the list scrolls underneath the gesture bar instead of stopping
                            // above it.
                            .padding(top = inner.calculateTopPadding())
                    ) {
                        HorizontalPager(
                            state = pagerState,
                            // Keeps all four pages composed, so jumping from the first tab to the
                            // last one no longer composes three pages mid-animation.
                            beyondViewportPageCount = 3,
                            modifier = Modifier.fillMaxSize()
                        ) { page ->
                            when (page) {
                                0 -> TonePage(state, dark, scope, onOpenPresets = { showPresets = true },
                                    onStatusClick = { showDiag = true })
                                1 -> EqPage(
                                    state = state,
                                    dark = dark,
                                    scope = scope,
                                    onOpenEditor = { creating ->
                                        editorCreating = creating
                                        showEditor = true
                                    },
                                    onStatusClick = { showDiag = true }
                                )
                                2 -> CommunityPage(
                                    state = state,
                                    community = community,
                                    dark = dark,
                                    scope = scope,
                                    active = tab == 2,
                                    onOpenShare = { showShare = true },
                                    onOpenDetail = { detailItem = it },
                                    onOpenIdentity = { showIdentity = true },
                                    onStatusClick = { showDiag = true }
                                )
                                else -> SettingsPage(
                                    state = state,
                                    dark = dark,
                                    ui = ui,
                                    versionName = version.name,
                                    versionCode = version.code,
                                    engineVersion = profile.engineVersion,
                                    deviceSupported = profile.supported,
                                    onOpenDevice = { showDevice = true },
                                    onOpenThemes = { showThemes = true },
                                    onFeedback = { showFeedback = true },
                                    onOpenDiag = { showDiag = true },
                                    onOpenMyLikes = { showMyLikes = true },
                                    onOpenIdentity = { showIdentity = true },
                                    checking = updateChecking,
                                    message = updateMessage,
                                    onCheckUpdate = { runUpdateCheck(manual = true) },
                                    onStatusClick = { showDiag = true }
                                )
                            }
                        }
                    }
                }

                AuroraBottomBar(
                    selected = tab,
                    onSelect = { index ->
                        scope.launch {
                            // No queue here on purpose. A lock made the second tap wait for the
                            // first animation to finish, which is exactly the "slow, then it
                            // catches up by itself" behaviour. A newer tap cancels the running
                            // scroll instead, so the page always follows the latest intent.
                            //
                            // tween rather than spring: a cancelled spring carries its velocity
                            // into the next one and overshoots - the little bounce. A tween
                            // restarts from wherever it is, so rapid taps stay smooth.
                            val distance = kotlin.math.abs(index - pagerState.currentPage)
                            pagerState.animateScrollToPage(
                                index,
                                animationSpec = tween(
                                    durationMillis = (170 + 45 * distance).coerceAtMost(320),
                                    easing = FastOutSlowInEasing
                                )
                            )
                        }
                    },
                    progress = {
                        pagerState.currentPage + pagerState.currentPageOffsetFraction
                    },
                    backdrop = dockBackdrop,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            pendingUpdate?.let { release ->
                UpdateDialog(
                    release = release,
                    dark = dark,
                    onLater = {
                        ui.skipVersion(release.versionCode)
                        pendingUpdate = null
                    },
                    onDownload = {
                        openUrl(context, release.url)
                        pendingUpdate = null
                    }
                )
            }

            if (showOnboarding) {
                OnboardingPanel(
                    dark = dark,
                    profile = profile,
                    onRecheck = { scope.launch { state.refresh() } },
                    onOpenDevice = {
                        showOnboarding = false
                        prefs.edit().putBoolean("onboarding_done", true).apply()
                        showDevice = true
                    },
                    onDismiss = {
                        showOnboarding = false
                        prefs.edit().putBoolean("onboarding_done", true).apply()
                    }
                )
            }

            if (showDevice) {
                DevicePanel(dark = dark, profile = profile) { showDevice = false }
            }

            if (showPresets) {
                PresetPanel(dark = dark, state = state, scope = scope) { showPresets = false }
            }

            if (showThemes) {
                ThemePanel(dark = dark, onPick = { id ->
                    prefs.edit().putString("theme_id", id).apply()
                    AuroraPalette.apply(id)
                }) { showThemes = false }
            }

            if (showEditor) {
                EqEditorPanel(
                    dark = dark,
                    state = state,
                    scope = scope,
                    creating = editorCreating,
                    initial = state.curve
                ) { showEditor = false }
            }

            if (showFeedback) {
                FeedbackSheet(
                    dark = dark,
                    submitting = feedbackSending,
                    result = feedbackResult,
                    onDismiss = { if (!feedbackSending) showFeedback = false },
                    onSubmit = { problem, text, withLogs ->
                        feedbackSending = true
                        feedbackResult = null
                        scope.launch {
                            try {
                                val sent = Feedback.reportIssue(
                                    context.applicationContext,
                                    problem,
                                    text,
                                    withLogs,
                                    profile,
                                    version.code,
                                    version.name,
                                    prefs
                                )
                                feedbackResult = when (sent) {
                                    Feedback.SendResult.Sent -> {
                                        AuroraLog.append(context, "feedback", problem + " sent")
                                        "已提交，谢谢。"
                                    }

                                    is Feedback.SendResult.ServerError ->
                                        "服务器拒绝了这次提交（HTTP ${sent.code}）。内容已写进本地日志，可以稍后再试，或直接把问题发到官网。"

                                    is Feedback.SendResult.NetworkError ->
                                        "连不上服务器（${sent.reason}）。检查一下网络，或把问题发到官网。"
                                }
                            } catch (t: Throwable) {
                                feedbackResult = "提交时出错了（${t.javaClass.simpleName}），稍后再试。"
                            } finally {
                                feedbackSending = false
                            }
                        }
                    }
                )
            }

            if (showDiag) {
                DiagPanel(
                    state = state,
                    dark = dark,
                    scope = scope,
                    profile = profile,
                    adaptation = adaptationResult,
                    onRequestAdaptation = {
                        adaptationResult = "正在提交…"
                        scope.launch {
                            val ok = Feedback.reportDevice(
                                context.applicationContext,
                                profile,
                                version.code,
                                version.name,
                                prefs,
                                force = true
                            )
                            adaptationResult = if (ok) {
                                AuroraLog.append(context, "adaptation", "submitted")
                                "已提交。这条记录会带着参数级日志，作者能看到卡在哪一段。"
                            } else {
                                "提交失败，检查一下网络再试。"
                            }
                        }
                    },
                    onDismiss = { showDiag = false }
                )
            }

            if (showShare) {
                ShareSheet(state, community, scope, dark) { showShare = false }
            }

            if (showIdentity) {
                IdentityPanel(community, scope, dark) { showIdentity = false }
            }

            if (showMyLikes) {
                MyLikesPanel(community, scope, dark, onOpenDetail = { detailItem = it }) {
                    showMyLikes = false
                }
            }

            detailItem?.let { item ->
                CommunityDetailPanel(item, community, state, dark, scope) { detailItem = null }
            }
        }
    }
}

/**
 * The engine's own verdict, as a capsule.
 *
 * <p>It used to be a loose row of grey text floating above the pager with no anchor, which read as
 * debris. As a capsule it has a surface, it lives directly under the page title, and it is the same
 * object on all four pages.
 */
@Composable
internal fun StatusStrip(state: AuroraState, onClick: (() -> Unit)? = null) {
    val colors = MiuixTheme.colorScheme
    val label: String
    val tone: Color
    when (state.backendState) {
        "active" -> { label = "正在生效"; tone = colors.primary }
        "selftest" -> { label = "自检中"; tone = colors.primary }
        "idle" -> { label = "等待播放"; tone = colors.onSurfaceVariantSummary }
        "disabled" -> { label = "已关闭"; tone = colors.onSurfaceVariantSummary }
        "panic" -> { label = "已紧急停用"; tone = colors.error }
        else -> {
            if (state.backendState.endsWith("error")) {
                label = "写入失败"; tone = colors.error
            } else {
                label = "读取中"; tone = colors.onSurfaceVariantSummary
            }
        }
    }
    val readings = buildString {
        if (state.saving) append("写入中… · ")
        if (state.sessions.isNotEmpty()) append("会话 ").append(state.sessions).append(" · ")
        append("强度 ").append(state.strength).append("%")
    }
    Column(Modifier.fillMaxWidth()) {
        // The capsule is the state of the engine; the diagnostics panel is where that state is
        // explained. Tapping the reading to open the explanation is the shortest path there is -
        // and it is the path you are already looking at when something is wrong.
        StatusCapsule(
            label = label,
            tone = tone,
            readings = readings,
            modifier = if (onClick != null) {
                Modifier.clip(RoundedCornerShape(Radius.block + 4.dp)).clickable(onClick = onClick)
            } else Modifier
        )
        // A failed write has to be visible from wherever it happened. It used to be printed at the
        // bottom of one page, so a failed preset tap on the equaliser page looked like nothing
        // happened at all.
        state.error?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = colors.error,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun TonePage(
    state: AuroraState,
    dark: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    onOpenPresets: () -> Unit,
    onStatusClick: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    // A hand-edited curve has no preset to name. Saying "原厂平直" there was a lie the screen told
    // whenever a band had been touched, because looking up "custom" lands on index 0.
    val isCustom = state.preset == "custom"
    val currentTitle = if (isCustom) "自定义曲线" else Presets.NAMES[state.presetIndex]
    val currentNote =
        if (isCustom) "六个频段被手动改过，不对应任何预设。"
        else Presets.NOTES[state.presetIndex]
    AuroraPage(
        title = "音效",
        underTitle = { StatusStrip(state, onStatusClick) }
    ) {
        AuroraGroup {
            AuroraRow(
                title = "叠加调音",
                summary = if (state.enabled) "已开启，曲线正挂在播放中的会话上" else "已关闭，音频走原厂链路",
                trailing = {
                    Switch(
                        checked = state.enabled,
                        onCheckedChange = { value ->
                            scope.launch { state.update(newEnabled = value) }
                        }
                    )
                }
            )
        }

        AuroraGroup("当前曲线") {
            Column(Modifier.padding(Space.card)) {
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            currentTitle,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.onSurface
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            currentNote,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = colors.onSurfaceVariantSummary
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                CurvePreview(
                    state.curve,
                    colors.primary,
                    colors.dividerLine,
                    showScale = true
                )
            }
        }

        AuroraGroup("预设") {
            Presets.KEYS.forEachIndexed { index, key ->
                val selected = !isCustom && index == state.presetIndex
                if (index > 0) RowDivider()
                AuroraRow(
                    title = Presets.NAMES[index],
                    summary = Presets.NOTES[index],
                    leading = {
                        EqGlyph(
                            curve = Presets.curve(index),
                            color = if (selected) colors.primary else colors.onSurfaceVariantSummary
                        )
                    },
                    trailing = {
                        CheckMark(selected)
                    },
                    onClick = { scope.launch { state.update(newPreset = key) } }
                )
            }
            state.userPresets.forEach { preset ->
                val selected = preset.id == state.preset
                RowDivider()
                AuroraRow(
                    title = preset.name,
                    summary = bandSummary(preset.curve),
                    leading = {
                        EqGlyph(
                            curve = preset.curve,
                            color = if (selected) colors.primary else colors.onSurfaceVariantSummary
                        )
                    },
                    trailing = { CheckMark(selected) },
                    onClick = {
                        scope.launch { state.update(newCurve = preset.curve, newPreset = "custom") }
                    }
                )
            }
            RowDivider()
            AuroraRow(
                title = "管理预设",
                summary = "按应用分配，或把手上这条存下来命名",
                trailing = { ValueText("管理") },
                onClick = onOpenPresets
            )
        }

        AuroraGroup("声场与响度") {
            AdvancedSettingsPanel(state = state, scope = scope)
        }
    }
}

/** The selected mark. One size, one colour, only where something is chosen. */
@Composable
private fun CheckMark(visible: Boolean) {
    // Selecting a preset moves the check rather than making it pop in and out: the row was already
    // laid out, so the mark should arrive.
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)) + scaleIn(initialScale = 0.6f, animationSpec = tween(180)),
        exit = fadeOut(tween(90)) + scaleOut(targetScale = 0.6f, animationSpec = tween(120))
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
internal fun CurvePreview(
    curve: FloatArray,
    accent: Color,
    grid: Color,
    showScale: Boolean = false
) {
    // Animated, so switching a preset shows the curve moving to its new shape instead of the line
    // teleporting. Six bands, six springs, one gesture.
    val live = rememberAnimatedBands(curve)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (showScale) 116.dp else 84.dp)
    ) {
        val w = size.width
        val h = size.height
        val mid = h / 2f
        val inset = 3f
        val usable = h / 2f - inset
        val step = w / (live.size - 1).coerceAtLeast(1)

        // Horizontal grid at +12 / +6 / 0 / -6 / -12. The zero line is the one that matters: it is
        // what tells a cut from a boost at a glance.
        val faint = grid.copy(alpha = 0.5f)
        listOf(1f, 0.5f, 0f, -0.5f, -1f).forEach { level ->
            val y = mid - level * usable
            drawLine(
                color = if (level == 0f) grid else faint,
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = if (level == 0f) 1.2f else 0.8f
            )
        }
        // One vertical tick per band, so the curve lines up with the labels underneath.
        curve.indices.forEach { index ->
            val x = step * index
            drawLine(
                color = faint.copy(alpha = 0.35f),
                start = Offset(x, inset),
                end = Offset(x, h - inset),
                strokeWidth = 0.8f
            )
        }

        val points = live.mapIndexed { index, db ->
            Offset(step * index, mid - (db / 12f) * usable)
        }

        // Area between the curve and zero: gives the shape weight without adding a second colour.
        val area = Path().apply {
            moveTo(points.first().x, mid)
            points.forEach { lineTo(it.x, it.y) }
            lineTo(points.last().x, mid)
            close()
        }
        drawPath(area, accent.copy(alpha = 0.14f))

        val line = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(line, accent, style = Stroke(width = 2.4f, cap = StrokeCap.Round))
        points.forEach { drawCircle(accent, radius = 3.2f, center = it) }
    }
}

/** Band label shared by every screen, so 1500 Hz never prints as "1k". */
internal fun bandLabel(hz: Float): String {
    if (hz < 1000f) return hz.toInt().toString()
    val k = hz / 1000f
    return if (k == k.toInt().toFloat()) k.toInt().toString() + "k"
    else String.format(java.util.Locale.US, "%.1fk", k)
}

/**
 * One spring per band, so a curve change is a movement rather than a jump.
 *
 * <p>That is the whole point of the chart: choosing 现场低频 should show the bass coming up and the
 * top going down, in the order the DSP applies it. A line that teleports shows the destination but
 * never the shape of the change.
 */
@Composable
private fun rememberAnimatedBands(target: FloatArray): FloatArray {
    val out = FloatArray(target.size)
    for (index in target.indices) {
        val state = animateFloatAsState(
            targetValue = target[index],
            animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f),
            label = "band" + index
        )
        out[index] = state.value
    }
    return out
}

@Composable
private fun BandScale(color: Color, hz: FloatArray = AudioEq.BAND_HZ) {
    Row(Modifier.fillMaxWidth()) {
        hz.forEach { value ->
            Text(
                bandLabel(value),
                fontSize = 9.sp,
                color = color,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun EqPage(
    state: AuroraState,
    dark: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    onOpenEditor: (Boolean) -> Unit,
    onStatusClick: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    var strengthDraft by remember(state.strength) { mutableStateOf(state.strength.toFloat()) }
    var confirmRestore by remember { mutableStateOf(false) }
    // A local copy of the curve while a finger is down. Writing straight through to Settings on
    // every pixel meant a su shell round trip per frame, and the thumb only moved once the write
    // came back - which is exactly what "拖不动" felt like.
    var draftCurve by remember(state.curve) { mutableStateOf(state.curve) }
    // What "default" means for a band: the value the chosen preset gives it. "custom" has no
    // reference to return to, so in that case there is no affordance at all rather than a jump to 0.
    val bandDefaults: FloatArray? =
        if (state.preset == "custom") null else Presets.curve(state.presetIndex).copyOf()

    AuroraPage(
        title = "均衡器",
        underTitle = { StatusStrip(state, onStatusClick) }
    ) {
        AuroraGroup("曲线") {
            Column(Modifier.padding(Space.card)) {
                CurvePreview(draftCurve, colors.primary, colors.dividerLine, showScale = true)
                Spacer(Modifier.height(6.dp))
                BandScale(colors.onSurfaceVariantSummary, state.engineBandHz ?: AudioEq.BAND_HZ)
            }
        }

        AuroraGroup("六个频段") {
            val activeHz = state.engineBandHz ?: AudioEq.BAND_HZ
            activeHz.forEachIndexed { index, hz ->
                if (index > 0) RowDivider()
                BandRow(
                    label = bandLabel(hz),
                    value = draftCurve[index],
                    default = bandDefaults?.get(index),
                    onValueChange = { value ->
                        draftCurve = draftCurve.copyOf().also { it[index] = value }
                    },
                    onCommit = {
                        scope.launch { state.update(newCurve = draftCurve) }
                    }
                )
            }
        }

        AuroraGroup("强度") {
            Column(Modifier.padding(horizontal = Space.card, vertical = Space.rowV)) {
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("叠加强度", fontSize = 15.sp, color = colors.onSurface)
                        Text(
                            "整条曲线一起按比例缩放，100% 就是预设本来的样子。",
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = colors.onSurfaceVariantSummary
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    ResetAffordance(
                        visible = kotlin.math.abs(strengthDraft - 100f) > 0.5f,
                        description = "恢复默认 100%"
                    ) {
                        strengthDraft = 100f
                        scope.launch { state.update(newStrength = 100) }
                    }
                    ValueText(strengthDraft.toInt().toString() + "%", accent = true)
                }
                Slider(
                    value = strengthDraft,
                    onValueChange = { strengthDraft = it },
                    valueRange = 0f..100f,
                    steps = 19,
                    onValueChangeFinished = {
                        scope.launch { state.update(newStrength = strengthDraft.toInt()) }
                    },
                    height = 24.dp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        AuroraGroup {
            if (bandDefaults != null) {
                AuroraRow(
                    title = "恢复预设原值",
                    summary = "六个频段一起放回「" + Presets.NAMES[state.presetIndex] + "」的值",
                    trailing = { ValueText("恢复", accent = true) },
                    onClick = {
                        draftCurve = bandDefaults.copyOf()
                        // Re-select the preset instead of writing its values as a new custom curve:
                        // same sound, and the page keeps saying which preset it is rather than
                        // turning into 自定义曲线 the moment you ask for the original back.
                        scope.launch { state.update(newPreset = state.preset) }
                    }
                )
                RowDivider()
            }
            if (state.savedCurve != null) {
                AuroraRow(
                    title = "恢复自存曲线",
                    summary = "回到上次保存下来的那条",
                    trailing = { ValueText("恢复", accent = true) },
                    // Replacing the live curve throws away whatever was not saved, so it asks first.
                    onClick = { confirmRestore = true }
                )
                RowDivider()
            }
            AuroraRow(
                title = "存为新预设",
                summary = "给现在这条曲线起个名字，留在「我的预设」里",
                trailing = { ValueText("保存", accent = true) },
                onClick = { onOpenEditor(true) }
            )
        }

        if (confirmRestore) {
            ConfirmDialog(
                title = "恢复自存曲线",
                body = "现在这条曲线会被换掉，还没保存的调整就没了。",
                confirmLabel = "恢复",
                onConfirm = {
                    confirmRestore = false
                    scope.launch { state.restoreSaved() }
                },
                onDismiss = { confirmRestore = false }
            )
        }
    }
}

/** A saved curve in the preset grid, below the divider. */

/** One tappable line that leads somewhere: an entry point, not a setting. */
@Composable
private fun EntryRow(
    title: String,
    detail: String,
    action: String,
    dark: Boolean,
    onClick: () -> Unit,
    // Navigating somewhere is not the same as changing something. Only the second kind carries the
    // accent; a page full of coloured action words is what made this read as a generated screen.
    accentAction: Boolean = false
) {
    AuroraRow(
        title = title,
        summary = detail,
        trailing = { ValueText(action, accent = accentAction) },
        onClick = onClick
    )
}

internal fun bandSummary(curve: FloatArray): String =
    curve.joinToString(" ") {
        val v = Math.round(it * 10) / 10f
        if (v > 0) "+$v" else v.toString()
    }

@Composable
internal fun BandRow(
    label: String,
    value: Float,
    /** What this band should be for the current preset, or null when there is nothing to return to. */
    default: Float?,
    onValueChange: (Float) -> Unit,
    onCommit: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.card, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                fontSize = 15.sp,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.width(56.dp)
            )
            Spacer(Modifier.weight(1f))
            if (default != null) {
                ResetAffordance(
                    visible = kotlin.math.abs(value - default) > 0.05f,
                    description = "恢复默认 " + String.format(java.util.Locale.US, "%+.1f dB", default)
                ) {
                    onValueChange(default)
                    onCommit()
                }
            }
            Text(
                String.format(java.util.Locale.US, "%+.1f dB", value),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (kotlin.math.abs(value) < 0.05f) {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                } else {
                    MiuixTheme.colorScheme.primary
                }
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onCommit,
            valueRange = -12f..12f,
            steps = 23,
            // Six of these stack in one card. At the default height the bars were slabs; the thumb
            // still gets a full-size touch target, only the drawing gets thinner.
            height = 22.dp
        )
    }
}

@Composable
private fun DiagPanel(
    state: AuroraState,
    dark: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    profile: DeviceProfile,
    adaptation: String?,
    onRequestAdaptation: () -> Unit,
    onDismiss: () -> Unit
) {
    val ink = Ink
    val muted = Muted
    val accent = Accent
    val context = LocalContext.current
    val player = remember { TonePlayer() }
    var playing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { player.onFinished = { playing = false } }
    LaunchedEffect(playing) {
        if (playing) player.start(context) { scope.launch { state.selfTest() } } else player.stop()
    }

    // 适配结论：先回答"这台机器到底能不能用"，再谈其它。
    val rejected = state.backendState == "error" && state.backendMessage.contains("band")
    val verdict = when {
        !profile.probed -> "模块没有运行"
        rejected -> "这台设备拒绝了均衡参数"
        !profile.supported -> "自动检测停在「" + profile.stageLabel() + "」"
        else -> "已适配"
    }
    val verdictDetail = when {
        !profile.probed ->
            "检测不到音频引擎。多半是 LSPosed 里没启用，或者启用后没重启设备。"
        rejected ->
            "系统拒绝了写入的某一频段。下面是它报的原始信息，申请适配会一起发回。"
        !profile.supported ->
            "音频链路和已验证的机型不同，现在开启不会有声音变化。"
        else ->
            "六段参数都能写入。如果愿意，可以把这台机器的链路档案发回，作者做适配时会拿来对照。"
    }

    PanelScaffold(
        dark = dark,
        title = "诊断",
        subtitle = "出问题时能自证：先说结论，再摊开回执、会话与链路。",
        onBack = onDismiss,
        actions = {
            PanelButton("关闭", dark, true, Modifier.weight(1f), onDismiss)
        }
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.group)) {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(9.dp).clip(RoundedCornerShape(5.dp)).background(
                            when {
                                !profile.probed || !profile.supported || rejected -> WarnColor
                                else -> OkColor
                            }
                        )
                    )
                    Spacer(Modifier.size(9.dp))
                    Text(
                        verdict,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ink
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(verdictDetail, fontSize = 13.sp, lineHeight = 20.sp, color = muted)
                if (rejected && state.backendMessage.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(state.backendMessage, fontSize = 11.sp, lineHeight = 17.sp, color = muted)
                }
                val adaptNote = buildString {
                    state.packName?.let { append("设备适配包：$it") }
                    if (state.adapt.contains("fallback")) {
                        if (isNotEmpty()) append("，")
                        append("频点已回退到 250 Hz–6.3 kHz")
                    }
                    if (isEmpty() && state.engineBandHz != null) append("按兼容频点运行")
                }
                if (adaptNote.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(adaptNote, fontSize = 11.sp, lineHeight = 17.sp, color = muted)
                }
                var packChecking by remember { mutableStateOf(false) }
                var packNote by remember { mutableStateOf<String?>(null) }
                run {
                    // Manual entry point for the same check the app runs when the module turns on:
                    // a pack can appear on the server after the user already enabled AURORA.
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (packChecking) "正在检查适配包…" else "检查适配包",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f))
                            .clickable {
                                if (!packChecking) {
                                    packChecking = true
                                    packNote = null
                                    scope.launch {
                                        packNote = when (val r = DevicePack.checkAndApply(state)) {
                                            is DevicePack.Result.Applied -> "已应用适配包：" + r.name
                                            is DevicePack.Result.Unchanged -> "适配包没有变化。"
                                            DevicePack.Result.NoMatch ->
                                                "服务器上还没有这台机器的适配包。"
                                            is DevicePack.Result.Failed -> "检查失败：" + r.reason
                                        }
                                        packChecking = false
                                    }
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                    packNote?.let { note ->
                        Spacer(Modifier.height(6.dp))
                        Text(note, fontSize = 11.sp, lineHeight = 17.sp, color = muted)
                    }
                }
                run {
                    // Always offer the upload, even on a device that already works. Adaptation is
                    // only useful if the author can compare chains across models, and a device
                    // that never fails is exactly the reference point other reports get compared
                    // against. Hiding the button behind "unsupported" hid most of that data.
                    Spacer(Modifier.height(12.dp))
                    Text(
                        when {
                            adaptation == null && rejected -> "申请适配"
                            adaptation == null -> "提交设备档案"
                            else -> "重新提交"
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f))
                            .clickable { onRequestAdaptation() }
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                    adaptation?.let { message ->
                        Spacer(Modifier.height(8.dp))
                        Text(message, fontSize = 12.sp, lineHeight = 18.sp, color = muted)
                    }
                }
            }

            SectionCard {
                Text("试听自检", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = ink)
                Spacer(Modifier.height(4.dp))
                Text(
                    "点一下会播放测试音，并把效果压掉 6 秒。听到一强一弱说明工作正常。",
                    fontSize = 13.sp, color = muted
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    if (playing) "停止测试音" else "播放测试音",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f))
                        .clickable { playing = !playing }
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }

            AuroraGroup("实际写入") {
                PaddedBlock {
                    val bands = state.actualBands
                    if (bands != null) {
                        CurvePreview(bands, accent, muted, showScale = true)
                        Spacer(Modifier.height(4.dp))
                        BandScale(muted, state.engineBandHz ?: AudioEq.BAND_HZ)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "引擎当前下发到系统的增益，单位 dB，精度 0.1。",
                            fontSize = 11.sp,
                            color = muted
                        )
                    } else {
                        Text(
                            "还没有写入记录。播一段音频，回到这里就能看到引擎实际下发的曲线。",
                            fontSize = 12.sp,
                            lineHeight = 19.sp,
                            color = muted
                        )
                    }
                }
            }

            AuroraGroup("系统回执") {
                PaddedBlock {
                    Row {
                        ReceiptCell("后端状态", state.backendState.ifEmpty { "—" }, dark, Modifier.weight(1f))
                        ReceiptCell("生效会话", state.applied.ifEmpty { "0" }, dark, Modifier.weight(1f))
                    }
                    Row {
                        ReceiptCell("挂载会话", state.sessions.ifEmpty { "无" }, dark, Modifier.weight(1f))
                        ReceiptCell("叠加强度", state.strength.toString() + "%", dark, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("说明", fontSize = 11.sp, color = muted)
                    Text(
                        state.backendMessage.ifEmpty { "—" },
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = ink
                    )
                }
            }

            AuroraGroup("运行日志") {
                PaddedBlock {
                    if (state.engineLog.isEmpty()) {
                        Text(
                            "还没有日志。播一段音频、切一次预设，这里就会出现最近 50 条状态变化。",
                            fontSize = 12.sp,
                            lineHeight = 19.sp,
                            color = muted
                        )
                    } else {
                        Text(
                            "最近 " + state.engineLog.size + " 条，最新的在最下面。长按可以选中复制。",
                            fontSize = 11.sp,
                            color = muted
                        )
                        Spacer(Modifier.height(8.dp))
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                state.engineLog.forEach { line ->
                                    Text(line, fontSize = 11.sp, lineHeight = 16.sp, color = ink)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReceiptCell(
    key: String,
    value: String,
    dark: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier.padding(vertical = 5.dp)) {
        Text(key, fontSize = 11.sp, color = Muted)
        Text(value, fontSize = 14.sp, color = Ink)
    }
}

@Composable
private fun SettingsPage(
    state: AuroraState,
    dark: Boolean,
    ui: UiPrefs,
    versionName: String,
    versionCode: Int,
    engineVersion: Int,
    deviceSupported: Boolean,
    onOpenDevice: () -> Unit,
    onOpenThemes: () -> Unit,
    onFeedback: () -> Unit,
    onOpenDiag: () -> Unit,
    onOpenMyLikes: () -> Unit,
    onOpenIdentity: () -> Unit,
    checking: Boolean,
    message: String?,
    onCheckUpdate: () -> Unit,
    onStatusClick: () -> Unit
) {
    val ink = Ink
    val muted = Muted
    val accent = Accent

    AuroraPage(
        title = "设置",
        underTitle = { StatusStrip(state, onStatusClick) }
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.group)) {
            AuroraGroup("外观") {
                listOf(
                    "system" to "跟随系统",
                    "light" to "浅色",
                    "dark" to "深色"
                ).forEachIndexed { index, (key, label) ->
                    if (index > 0) RowDivider()
                    SelectRow(label, ui.themeMode == key, dark) { ui.setTheme(key) }
                }
            }

            AuroraGroup("主题") {
                EntryRow(
                    title = "管理主题",
                    detail = "当前：" + AuroraPalette.nameOf(AuroraPalette.currentId),
                    action = "选择",
                    dark = dark,
                    onClick = onOpenThemes
                )
            }

            AuroraGroup("交互") {
                ToggleRow(
                    title = "预测性返回",
                    detail = "侧滑时页面跟随手指收缩",
                    checked = ui.predictiveBack,
                    dark = dark,
                    onChange = { ui.changePredictiveBack(it) }
                )
                RowDivider()
                ToggleRow(
                    title = "启动时检查更新",
                    detail = "只有发现新版本才会提示",
                    checked = ui.autoCheckUpdate,
                    dark = dark,
                    onChange = { ui.changeAutoCheckUpdate(it) }
                )
            }

            AuroraGroup("社区") {
                EntryRow(
                    title = "我的喜欢",
                    detail = "你点过喜欢的曲线，换设备后凭找回码也还在",
                    action = "查看",
                    dark = dark,
                    onClick = onOpenMyLikes
                )
                RowDivider()
                EntryRow(
                    title = "社区身份",
                    detail = "昵称、头像与找回码。匿名发布，不需要账号",
                    action = "管理",
                    dark = dark,
                    onClick = onOpenIdentity
                )
            }

            AuroraGroup("设备") {
                EntryRow(
                    title = "设备适配",
                    detail = if (deviceSupported) "检测通过，这台机器可以正常使用"
                    else "查看自动检测停在哪一步",
                    action = "查看",
                    dark = dark,
                    onClick = onOpenDevice
                )
                RowDivider()
                EntryRow(
                    title = "诊断与链路",
                    detail = "引擎回执、播放会话、实际写入与试听自检",
                    action = "打开",
                    dark = dark,
                    onClick = onOpenDiag
                )
            }

            AuroraGroup("更新") {
                EntryRow(
                    title = "当前版本",
                    detail = versionName,
                    action = if (checking) "检查中" else "检查更新",
                    dark = dark,
                    onClick = { if (!checking) onCheckUpdate() },
                    accentAction = true
                )
                if (message != null) {
                    RowDivider()
                    NoteText(message)
                }
                if (engineVersion > 0 && engineVersion != versionCode) {
                    RowDivider()
                    NoteText(
                        "引擎版本不一致：模块里跑的是 " + engineVersion + "，当前安装的是 " +
                            versionCode + "。重启设备后生效。",
                        error = true
                    )
                }
            }

            AuroraGroup("反馈") {
                EntryRow(
                    title = "反馈问题",
                    detail = "用不了、听不出区别、界面不对，都可以在这里说",
                    action = "去反馈",
                    dark = dark,
                    onClick = onFeedback,
                    accentAction = true
                )
            }

            AuroraGroup("创作者") {
                PaddedBlock { CreatorCard(dark) }
            }

            AuroraGroup("关于") {
                PaddedBlock { ProgramCard(dark, versionName) }
            }

            SectionCard {
                Text("开源声明", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = ink)
                Spacer(Modifier.height(6.dp))
                Text(
                    "界面组件参考 MIUIX 与 KernelSU（GPL-3.0），液态玻璃折射效果来自 " +
                        "AndroidLiquidGlass（Apache-2.0）。",
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    color = muted
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    dark: Boolean,
    onChange: (Boolean) -> Unit
) {
    AuroraRow(
        title = title,
        summary = detail,
        trailing = { Switch(checked = checked, onCheckedChange = onChange) }
    )
}


@Composable
private fun UpdateDialog(
    release: Update.Release,
    dark: Boolean,
    onLater: () -> Unit,
    onDownload: () -> Unit
) {
    val ink = Ink
    val muted = Muted
    val accent = Accent

    Dialog(onDismissRequest = onLater) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(if (dark) Color(0xFF1C1F24) else Color.White)
                .padding(20.dp)
        ) {
            Text("发现新版本", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = ink)
            Spacer(Modifier.height(6.dp))
            Text(release.versionName, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = accent)
            if (release.notes.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    release.notes.take(240),
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = muted
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DialogAction("以后再说", false, dark, Modifier.weight(1f), onLater)
                DialogAction("下载", true, dark, Modifier.weight(1f), onDownload)
            }
        }
    }
}

/**
 * Asks before something that throws work away.
 *
 * <p>Two taps, in the same shell as the update prompt: cancel on the left, the destructive choice
 * on the right, neither of them coloured as if it were the happy path.
 */
@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surfaceContainer)
                .padding(20.dp)
        ) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(body, fontSize = 13.sp, lineHeight = 20.sp, color = colors.onSurfaceVariantSummary)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceContainerHigh)
                        .clickable(onClick = onDismiss)
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("取消", fontSize = 14.sp, color = colors.onSurface)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.error.copy(alpha = 0.16f))
                        .clickable(onClick = onConfirm)
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(confirmLabel, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.error)
                }
            }
        }
    }
}

@Composable
private fun DialogAction(
    label: String,
    primary: Boolean,
    dark: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val accent = Accent
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    primary -> accent.copy(alpha = if (dark) 0.22f else 0.12f)
                    dark -> Color(0xFF262A31)
                    else -> Color(0xFFF1F3F7)
                }
            )
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (primary) accent else (Ink)
        )
    }
}

/** Pure UI preferences; these stay in SharedPreferences and never touch Settings.Global. */
internal class UiPrefs(private val prefs: SharedPreferences) {

    var themeMode by mutableStateOf(prefs.getString("theme", "system") ?: "system")
        private set
    var predictiveBack by mutableStateOf(prefs.getBoolean("predictive_back", true))
        private set
    var autoCheckUpdate by mutableStateOf(prefs.getBoolean("auto_update", true))
        private set
    var skippedVersionCode by mutableIntStateOf(prefs.getInt("skipped_version", 0))
        private set

    fun setTheme(value: String) {
        themeMode = value
        prefs.edit().putString("theme", value).apply()
    }

    fun changePredictiveBack(value: Boolean) {
        predictiveBack = value
        prefs.edit().putBoolean("predictive_back", value).apply()
    }

    fun changeAutoCheckUpdate(value: Boolean) {
        autoCheckUpdate = value
        prefs.edit().putBoolean("auto_update", value).apply()
    }

    fun skipVersion(code: Int) {
        skippedVersionCode = code
        prefs.edit().putInt("skipped_version", code).apply()
    }
}

private class VersionInfo(val code: Int, val name: String)

private fun installedVersion(context: Context): VersionInfo = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    VersionInfo(PackageInfoCompat.getLongVersionCode(info).toInt(), info.versionName ?: "1.0.0")
}.getOrDefault(VersionInfo(0, "1.0.0"))

/**
 * Opens a link from latest.json. Restricted to https on our own host: a hijacked manifest must
 * not be able to hand the system an `intent:` or `market:` URI.
 */
private fun openUrl(context: Context, url: String) {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
    if (uri.scheme != "https") return
    val host = uri.host ?: return
    if (host != "lingluandf.online" && !host.endsWith(".lingluandf.online")) return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}

@Composable
private fun SelectRow(label: String, selected: Boolean, dark: Boolean, onClick: () -> Unit) {
    AuroraRow(
        title = label,
        trailing = { CheckMark(selected) },
        onClick = onClick
    )
}

@Composable
private fun SectionCard(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val modifier = if (onClick != null) {
        Modifier.fillMaxWidth().clickable { onClick() }
    } else {
        Modifier.fillMaxWidth()
    }
    Card(
        modifier = modifier,
        cornerRadius = Radius.card,
        insideMargin = PaddingValues(Space.card),
        colors = CardDefaults.defaultColors(color = colors.surfaceContainer)
    ) {
        // fillMaxWidth, not decoration: without it a weight() row inside collapses to zero width
        // and its text is clipped mid-word.
        Column(Modifier.fillMaxWidth()) { content() }
    }
}

private class TonePlayer {
    var onFinished: (() -> Unit)? = null
    @Volatile private var running = false

    fun start(context: Context, onPulse: () -> Unit) {
        if (running) return
        running = true
        Thread {
            var track: AudioTrack? = null
            try {
                val rate = 48000
                val min = AudioTrack.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(min, 8192))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track.play()
                val chunk = ShortArray(rate / 10)
                var p1 = 0.0
                var p2 = 0.0
                val started = System.currentTimeMillis()
                var nextPulse = 2000L
                while (running && System.currentTimeMillis() - started < 20000) {
                    for (i in chunk.indices) {
                        p1 += 2 * Math.PI * 1000 / rate
                        p2 += 2 * Math.PI * 3000 / rate
                        chunk[i] = ((Math.sin(p1) + Math.sin(p2)) * 5000).toInt().toShort()
                    }
                    track.write(chunk, 0, chunk.size)
                    if (System.currentTimeMillis() - started >= nextPulse) {
                        nextPulse += 8000
                        onPulse()
                    }
                }
            } catch (_: Throwable) {
            } finally {
                try {
                    track?.stop()
                } catch (_: Throwable) {
                }
                track?.release()
                running = false
                onFinished?.invoke()
            }
        }.start()
    }

    fun stop() {
        running = false
    }
}