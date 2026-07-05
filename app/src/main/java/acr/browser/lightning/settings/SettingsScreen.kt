package acr.browser.lightning.settings

import acr.browser.lightning.R
import acr.browser.lightning.device.BuildInfo
import acr.browser.lightning.device.BuildType
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class SettingsNavigation {
    ROOT,
    ADBLOCK,
    GENERAL,
    BOOKMARK,
    DISPLAY,
    PRIVACY,
    ADVANCED,
    ABOUT,
    FAQ,
    DEBUG,
    CHILD_MODE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    buildInfo: BuildInfo,
    onNavigate: (SettingsNavigation) -> Unit
) {
    Scaffold(
        containerColor = Color(0xFFF2F2F7),
        topBar = {
            TopAppBar(
                modifier = Modifier.height(48.dp),
                title = {
                    Text(stringResource(R.string.settings))
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = Color(0xFFD8D8D8)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
            SettingsClickable(
                SettingsClickableState(
                    title = stringResource(R.string.settings_child_mode),
                    summary = stringResource(R.string.child_mode_whitelist_description)
                )
            ) {
                onNavigate(SettingsNavigation.CHILD_MODE)
            }
            SettingsClickable(SettingsClickableState(title = stringResource(R.string.settings_adblock))) {
                onNavigate(SettingsNavigation.ADBLOCK)
            }
            SettingsClickable(SettingsClickableState(title = stringResource(R.string.settings_general))) {
                onNavigate(SettingsNavigation.GENERAL)
            }
            SettingsClickable(SettingsClickableState(title = stringResource(R.string.bookmark_settings))) {
                onNavigate(SettingsNavigation.BOOKMARK)
            }
            SettingsClickable(SettingsClickableState(title = stringResource(R.string.settings_display))) {
                onNavigate(SettingsNavigation.DISPLAY)
            }
            SettingsClickable(SettingsClickableState(title = stringResource(R.string.settings_privacy))) {
                onNavigate(SettingsNavigation.PRIVACY)
            }
            SettingsClickable(SettingsClickableState(title = stringResource(R.string.settings_advanced))) {
                onNavigate(SettingsNavigation.ADVANCED)
            }
            SettingsClickable(
                SettingsClickableState(
                    title = stringResource(R.string.settings_about),
                    summary = stringResource(R.string.settings_about_explain)
                )
            ) {
                onNavigate(SettingsNavigation.ABOUT)
            }
            SettingsClickable(
                SettingsClickableState(
                    title = stringResource(R.string.faq),
                    summary = stringResource(R.string.faq_description)
                )
            ) {
                onNavigate(SettingsNavigation.FAQ)
            }
            if (buildInfo.buildType == BuildType.DEBUG) {
                SettingsClickable(SettingsClickableState(title = stringResource(R.string.debug_title))) {
                    onNavigate(SettingsNavigation.DEBUG)
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
fun SettingsClickable(
    state: SettingsClickableState,
    onClick: () -> Unit
) {
    Surface(
        color = Color.White,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = state.enabled) { onClick() }
                    .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        state.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (state.enabled) {
                            Color.Unspecified
                        } else {
                            ListItemDefaults.colors().disabledHeadlineColor
                        }
                    )
                    state.summary?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.enabled) {
                                Color.Unspecified
                            } else {
                                ListItemDefaults.colors().disabledHeadlineColor
                            }
                        )
                    }
                }
                Text(
                    "›",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(0xFFC0C0C0)
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                thickness = 1.dp,
                color = Color(0xFFD8D8D8)
            )
        }
    }
}

@Composable
fun SettingsToggle(
    state: SettingsToggleState,
    onToggle: (Boolean) -> Unit
) {
    var toggleState by remember { mutableStateOf(state.isChecked) }
    Surface(
        color = Color.White,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = state.enabled) {
                        toggleState = !toggleState
                        onToggle(toggleState)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .padding(start = 16.dp, top = 14.dp, bottom = 14.dp)
                        .weight(1f),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        state.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (state.enabled) {
                            Color.Unspecified
                        } else {
                            ListItemDefaults.colors().disabledHeadlineColor
                        }
                    )
                    state.summary?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.enabled) {
                                Color.Unspecified
                            } else {
                                ListItemDefaults.colors().disabledHeadlineColor
                            }
                        )
                    }
                }
                Switch(
                    modifier = Modifier.padding(end = 12.dp),
                    enabled = state.enabled,
                    checked = toggleState,
                    onCheckedChange = {
                        toggleState = it
                        onToggle(toggleState)
                    }
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                thickness = 1.dp,
                color = Color(0xFFD8D8D8)
            )
        }
    }
}

@Composable
fun SettingsLoader(innerPadding: PaddingValues) {
    Column(
        modifier = Modifier
            .padding(innerPadding)
            .fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            modifier = Modifier.width(64.dp),
            color = MaterialTheme.colorScheme.secondary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheetChooser(
    innerPadding: PaddingValues,
    state: SettingsBottomSheetChooserState,
    onDismiss: () -> Unit,
    onSelected: (Int) -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    var selectedState by remember { mutableIntStateOf(state.selected) }
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = { onDismiss() },
        modifier = Modifier.padding(innerPadding),
        sheetState = sheetState
    ) {
        Text(
            state.title,
            modifier = Modifier.padding(start = 16.dp, bottom = 16.dp),
            style = MaterialTheme.typography.titleMedium
        )
        state.values.forEachIndexed { index, value ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .requiredHeight(48.dp)
                    .clickable {
                        selectedState = index
                        scope.launch {
                            delay(500)
                            sheetState.hide()
                            onSelected(index)
                        }
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.padding(start = 16.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    RadioButton(
                        selected = index == selectedState,
                        onClick = {
                            scope.launch {
                                delay(500)
                                sheetState.hide()
                                onSelected(index)
                            }
                        }
                    )
                }
                Column(
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(value, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheetInput(
    innerPadding: PaddingValues,
    state: SettingsBottomSheetInputState,
    onDismiss: () -> Unit,
    onSelected: (CharSequence) -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = { onDismiss() },
        modifier = Modifier.padding(innerPadding),
        sheetState = sheetState
    ) {
        Text(
            state.title,
            modifier = Modifier.padding(start = 16.dp),
            style = MaterialTheme.typography.titleMedium
        )
        val textFieldState = rememberTextFieldState(state.currentValue)
        TextField(
            textFieldState,
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            placeholder = { Text(state.hint) }
        )
        Button(
            modifier = Modifier
                .padding(start = 16.dp),
            onClick = {
                scope.launch {
                    delay(500)
                    sheetState.hide()
                    onSelected(textFieldState.text)
                }
            }
        ) {
            Text(stringResource(R.string.action_ok))
        }
    }
}
