package com.openfield.survey

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.openfield.core.config.Feature
import com.openfield.survey.ui.AppearanceScreen
import com.openfield.survey.ui.CalibrationScreen
import com.openfield.survey.ui.CogoScreen
import com.openfield.survey.ui.CoordLibraryScreen
import com.openfield.survey.ui.LocalUnitLabel
import com.openfield.survey.ui.MapScreen
import com.openfield.survey.ui.NewJobScreen
import com.openfield.survey.ui.ContinuousScreen
import com.openfield.survey.ui.CoordEditScreen
import com.openfield.survey.ui.CoordinateSystemsScreen
import com.openfield.survey.ui.DeviceEditScreen
import com.openfield.survey.ui.EquipmentScreen
import com.openfield.survey.ui.FeatureCodesScreen
import com.openfield.survey.ui.GnssStatusScreen
import com.openfield.survey.ui.HomeScreen
import com.openfield.survey.ui.ImportExportScreen
import com.openfield.survey.ui.JobsScreen
import com.openfield.survey.ui.LabelsScreen
import com.openfield.survey.ui.MeasureScreen
import com.openfield.survey.ui.OpenFieldTheme
import com.openfield.survey.ui.RealtimeScreen
import com.openfield.survey.ui.ReviewScreen
import com.openfield.survey.ui.ScriptEditScreen
import com.openfield.survey.ui.StakeLinesScreen
import com.openfield.survey.ui.StakePointsScreen
import com.openfield.survey.ui.StyleEditScreen
import com.openfield.survey.ui.StylesScreen
import com.openfield.survey.ui.ThemeEditScreen
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 12+ needs a runtime permission before the app can talk to a paired Bluetooth receiver.
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        }
        setContent { Root(vm) }
    }
}

@Composable
fun Root(vm: AppViewModel) {
    val cfg = vm.config
    OpenFieldTheme(cfg.theme, cfg.labels, cfg.layout.leftHanded) {
        val view = LocalView.current
        SideEffect { view.keepScreenOn = cfg.keepScreenOn }
        BackHandler(enabled = vm.screenStack.size > 1) { vm.back() }
        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalUnitLabel provides vm.unit.abbr()) { Router(vm) }
            val msg = vm.message
            if (msg != null) {
                LaunchedEffect(msg) { delay(4500); if (vm.message == msg) vm.message = null }
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
                    color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shape = MaterialTheme.shapes.medium, shadowElevation = 6.dp
                ) { Text(msg, Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
            }
        }
    }
}

@Composable
fun Router(vm: AppViewModel) {
    val r = vm.route
    key(r) {   // fresh state for every screen, even two of the same kind with different ids
        when {
            r == "home" -> HomeScreen(vm)
            r == Feature.JOBS -> JobsScreen(vm)
            r == Feature.MAP -> MapScreen(vm)
            r == "job_new" -> NewJobScreen(vm)
            r.startsWith("coord_library:") -> CoordLibraryScreen(vm, r.removePrefix("coord_library:"))
            r == Feature.MEASURE_TOPO || r == Feature.MEASURE_CODES || r == Feature.OBSERVED_CONTROL -> MeasureScreen(vm, r)
            r == Feature.CONTINUOUS_TOPO -> ContinuousScreen(vm)
            r == Feature.STAKE_POINTS -> StakePointsScreen(vm)
            r == Feature.STAKE_LINES -> StakeLinesScreen(vm)
            r == Feature.COGO -> CogoScreen(vm)
            r == Feature.CALIBRATION -> CalibrationScreen(vm)
            r == Feature.REVIEW_JOB -> ReviewScreen(vm)
            r == Feature.IMPORT_EXPORT -> ImportExportScreen(vm)
            r == Feature.GNSS_STATUS -> GnssStatusScreen(vm)
            r == Feature.EQUIPMENT -> EquipmentScreen(vm)
            r == Feature.REALTIME -> RealtimeScreen(vm)
            r == Feature.SURVEY_STYLES -> StylesScreen(vm)
            r == Feature.COORDINATE_SYSTEMS -> CoordinateSystemsScreen(vm)
            r == Feature.FEATURE_CODES -> FeatureCodesScreen(vm)
            r == Feature.APPEARANCE -> AppearanceScreen(vm)
            r == "labels" -> LabelsScreen(vm)
            r.startsWith("device_edit:") -> DeviceEditScreen(vm, r.removePrefix("device_edit:"))
            r.startsWith("script_edit:") -> ScriptEditScreen(vm, r.removePrefix("script_edit:"))
            r.startsWith("style_edit:") -> StyleEditScreen(vm, r.removePrefix("style_edit:"))
            r.startsWith("coord_edit:") -> CoordEditScreen(vm, r.removePrefix("coord_edit:"))
            r.startsWith("theme_edit:") -> ThemeEditScreen(vm, r.removePrefix("theme_edit:"))
            else -> HomeScreen(vm)
        }
    }
}
