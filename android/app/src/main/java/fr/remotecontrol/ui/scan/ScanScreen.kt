package fr.remotecontrol.ui.scan

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import fr.remotecontrol.qr.QrAnalyzer
import fr.remotecontrol.ui.common.NoticeCard
import fr.remotecontrol.ui.common.RcIcons
import fr.remotecontrol.ui.common.RcSnackbar
import fr.remotecontrol.ui.common.Texts
import fr.remotecontrol.ui.theme.Inter
import fr.remotecontrol.ui.theme.RcColors
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.awaitCancellation

/**
 * Scan des fiches imprimées (maquette « Scan QR ») : aperçu caméra voilé, cadre de visée, consigne, message
 * « QR-code non reconnu ». Demande la permission CAMERA ; sans elle, une carte propose de l'accorder.
 * Pendant [ScanUi.busy], l'analyse est coupée.
 */
@Composable
fun ScanScreen(state: ScanUi, onDecoded: (String) -> Unit, onDismissMessage: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var granted by remember { mutableStateOf(context.hasCameraPermission()) }
    // Refus définitif (« Ne plus demander ») : seule la page des réglages de l'appli peut l'accorder.
    var blocked by rememberSaveable { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    var hasTorch by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        blocked = !ok && activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false
    }
    // Demandée une fois par visite de l'écran (pas de nouvelle demande à la rotation).
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!granted && !asked) {
            asked = true
            request.launch(Manifest.permission.CAMERA)
        }
    }
    // Retour des réglages système : la permission a pu changer.
    LifecycleResumeEffect(Unit) {
        granted = context.hasCameraPermission()
        onPauseOrDispose { }
    }

    Box(Modifier.fillMaxSize().background(CameraBackground)) {
        when {
            !granted -> Column(Modifier.fillMaxSize().background(RcColors.bg)) {
                ScanHeader(torchVisible = false, torchOn = false, onTorch = {})
                NoticeCard(
                    RcIcons.Scan,
                    title = Texts.cameraDenied,
                    body = Texts.cameraPermission,
                    warn = false,
                    modifier = Modifier.padding(16.dp),
                    action = if (blocked) Texts.openAppSettings else Texts.allowCamera,
                    onAction = {
                        if (blocked) context.openAppSettings() else request.launch(Manifest.permission.CAMERA)
                    },
                )
            }
            unavailable -> Column(Modifier.fillMaxSize().background(RcColors.bg)) {
                ScanHeader(torchVisible = false, torchOn = false, onTorch = {})
                NoticeCard(
                    RcIcons.Scan,
                    title = Texts.cameraUnavailable,
                    body = Texts.cameraUnavailableHelp,
                    warn = true,
                    modifier = Modifier.padding(16.dp),
                )
            }
            else -> {
                CameraPreview(
                    analyzing = !state.busy,
                    onDecoded = onDecoded,
                    onCamera = { c ->
                        camera = c
                        hasTorch = c?.cameraInfo?.hasFlashUnit() == true
                        torchOn = false
                    },
                    onUnavailable = { unavailable = true },
                )
                Viewfinder(busy = state.busy)
                ScanHeader(
                    torchVisible = hasTorch,
                    torchOn = torchOn,
                    onTorch = {
                        torchOn = !torchOn
                        camera?.cameraControl?.enableTorch(torchOn)
                    },
                )
            }
        }
        state.message?.let { message ->
            RcSnackbar(
                text = if (message == Texts.qrUnknown) Texts.qrUnknownDetail else message,
                onAction = onDismissMessage,
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
            )
        }
    }
}

private val CameraBackground = Color(0xFF2A2723)
private val FrameSize = 260.dp

private fun Context.hasCameraPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
}

/**
 * Caméra arrière liée au cycle de vie de l'écran : Preview + ImageAnalysis (dernière image seulement, ~1280×720),
 * analyse sur un fil dédié, texte décodé renvoyé sur le fil principal. Tout est libéré en quittant l'écran.
 */
@Composable
private fun CameraPreview(
    analyzing: Boolean,
    onDecoded: (String) -> Unit,
    onCamera: (Camera?) -> Unit,
    onUnavailable: () -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val decoded by rememberUpdatedState(onDecoded)
    val cameraChanged by rememberUpdatedState(onCamera)
    val failed by rememberUpdatedState(onUnavailable)
    val previewView = remember {
        PreviewView(context).apply {
            // TextureView : se compose correctement avec les vues Compose superposées et les transitions.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val analyzer = remember {
        val main = ContextCompat.getMainExecutor(context)
        QrAnalyzer { text -> main.execute { decoded(text) } }
    }
    val analysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                    )
                    .build(),
            )
            .build()
    }

    LaunchedEffect(owner) {
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val provider = try {
            ProcessCameraProvider.awaitInstance(context)
        } catch (e: CancellationException) {
            throw e // l'écran quitte la composition : ce n'est pas une panne de caméra
        } catch (_: Exception) {
            failed()
            return@LaunchedEffect
        }
        try {
            cameraChanged(provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis))
        } catch (_: Exception) {
            // Pas de caméra arrière, ou caméra refusée par le système.
            failed()
            return@LaunchedEffect
        }
        try {
            awaitCancellation()
        } finally {
            provider.unbind(preview, analysis)
            cameraChanged(null)
        }
    }
    LaunchedEffect(analyzing) {
        if (analyzing) analysis.setAnalyzer(executor, analyzer) else analysis.clearAnalyzer()
    }
    DisposableEffect(Unit) {
        onDispose {
            analysis.clearAnalyzer()
            executor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize().semantics { contentDescription = Texts.cameraView },
    )
}

/** Voile sur l'aperçu, percé à l'endroit du cadre de visée ; coins laiton ; consigne sous le cadre. */
@Composable
private fun Viewfinder(busy: Boolean) {
    var frame by remember { mutableStateOf<Rect?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = minOf(FrameSize, maxWidth - 64.dp)
        Canvas(Modifier.fillMaxSize()) {
            val hole = frame
            val veil = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                if (hole != null) addRoundRect(RoundRect(hole, CornerRadius(16.dp.toPx())))
            }
            drawPath(veil, RcColors.scrim(0.55f))
        }
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(64.dp)) // en-tête
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(side)
                    .onGloballyPositioned { c -> frame = Rect(c.positionInParent(), c.size.toSize()) },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) { aimCorners() }
                if (busy) CircularProgressIndicator(color = RcColors.accentStrong)
            }
            Text(
                Texts.scanHint,
                style = TextStyle(fontFamily = Inter, fontSize = 15.sp, lineHeight = 22.sp),
                color = RcColors.text,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 26.dp),
            )
            Spacer(Modifier.weight(2f))
        }
    }
}

/** Quatre coins de 44 dp, trait 4 dp, rayon 16 dp (maquette). */
private fun DrawScope.aimCorners() {
    val stroke = 4.dp.toPx()
    val half = stroke / 2
    val arm = 44.dp.toPx()
    val radius = 16.dp.toPx() - half
    val corner = Path().apply {
        moveTo(half, arm)
        lineTo(half, half + radius)
        arcTo(Rect(half, half, half + 2 * radius, half + 2 * radius), 180f, 90f, false)
        lineTo(arm, half)
    }
    for (angle in listOf(0f, 90f, 180f, 270f)) {
        rotate(angle) { drawPath(corner, RcColors.accentStrong, style = Stroke(stroke, cap = StrokeCap.Butt)) }
    }
}

@Composable
private fun ScanHeader(torchVisible: Boolean, torchOn: Boolean, onTorch: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            Texts.scan,
            style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 20.sp),
            color = RcColors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp).semantics { heading() },
        )
        if (torchVisible) {
            IconButton(onClick = onTorch) {
                Icon(RcIcons.Torch, contentDescription = Texts.torch, tint = if (torchOn) RcColors.accentStrong else RcColors.text)
            }
        }
    }
}
