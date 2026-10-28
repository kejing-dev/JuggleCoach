package io.embodit.jugglecoach.ui.jc

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.jugglecoach.platform.EyePermissionActivity
import io.embodit.jugglecoach.platform.JcLog
import io.embodit.jugglecoach.ui.jc.components.JcStage
import io.embodit.jugglecoach.ui.jc.components.LocalJcResources
import io.embodit.jugglecoach.ui.jc.components.localizedResources
import io.embodit.jugglecoach.ui.jc.debug.JcProbe
import io.embodit.jugglecoach.ui.jc.scene.JcFrameSystem
import io.embodit.jugglecoach.ui.jc.scene.JcScene

/**
 * The one screen of the app, living in a Mixed Stage. It wires the three parts together: the ViewModel holds the
 * state, the [JcScene] turns state into entities and reports the lesson clock's milestones, drift and dwell, and
 * [JcStage] declares the panels.
 */
@Composable
fun JcScreen() {
    val context = LocalContext.current
    val viewModel: JcViewModel = viewModel(factory = JcViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current

    remember(context) {
        JcProbe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        true
    }
    val scene = remember(viewModel) { JcScene(viewModel::onEvent) }

    DisposableEffect(scene) {
        registerSystem<JcFrameSystem>()
        JcProbe.host =
            object : JcProbe.Host {
                override fun dispatch(event: JcEvent) = viewModel.onEvent(event)

                override fun uiState(): JcUiState = viewModel.state.value

                override fun dumpJson(): String = viewModel.dumpJson()

                override fun scene(): JcProbe.SceneProbe = scene

                override fun pressControl(name: String): Boolean = JcProbe.click(name)
            }
        EyePermissionActivity.onResult = { granted, permanent ->
            scene.restartEyes()
            viewModel.onEvent(JcEvent.EyePermission(granted, permanent))
        }
        JcLog.i("STAGE enter")
        onDispose {
            EyePermissionActivity.onResult = null
            JcProbe.host = null
            unregisterSystem<JcFrameSystem>()
            scene.detach()
            JcLog.i("STAGE disposed")
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer =
            object : LifecycleEventObserver {
                override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                    when (event) {
                        Lifecycle.Event.ON_STOP -> {
                            viewModel.onEvent(JcEvent.WentBackground)
                            scene.onBackground()
                        }
                        Lifecycle.Event.ON_START -> {
                            scene.onForeground()
                            viewModel.onEvent(JcEvent.CameForeground)
                        }
                        else -> Unit
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(scene, state) { scene.render(state) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect: JcEffect ->
            when (effect) {
                JcEffect.ExitApp -> {
                    JcLog.i("STAGE close requested")
                    runCatching { navigator.closeStage() }.onFailure { JcLog.e("STAGE close failed: $it", it) }
                }
                JcEffect.RequestEyePermission -> {
                    if (!EyePermissionActivity.open(context.applicationContext)) viewModel.onEvent(JcEvent.EyePermission(granted = false, permanent = false))
                }
                JcEffect.OpenAppSettings -> EyePermissionActivity.openSettings(context.applicationContext)
            }
        }
    }

    val resources = remember(state.settings.language, context) { localizedResources(context, state.settings.language) }
    CompositionLocalProvider(LocalJcResources provides resources) {
        JcStage(state = state, scene = scene, onEvent = viewModel::onEvent)
    }
}
