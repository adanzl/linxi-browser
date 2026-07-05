package acr.browser.lightning.settings.screens

import acr.browser.lightning.R
import acr.browser.lightning.preference.UserPreferencesDataStore
import acr.browser.lightning.resources.ResourceProvider
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import javax.inject.Inject
import kotlinx.coroutines.launch

class ChildModeSettingsScreen @Inject constructor(
    private val resourceProvider: ResourceProvider,
    private val userPreferencesDataStore: UserPreferencesDataStore,
) {
    val key = "child_mode"

    suspend fun getWhitelistUrls(): List<String> {
        val whitelistString = userPreferencesDataStore.childModeWhitelist.get()
        return if (whitelistString.isEmpty()) {
            emptyList()
        } else {
            whitelistString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    suspend fun saveWhitelistUrls(urls: List<String>) {
        userPreferencesDataStore.childModeWhitelist.set(urls.joinToString(","))
    }

    suspend fun isChildModeEnabled(): Boolean = userPreferencesDataStore.childModeEnabled.get()

    suspend fun setChildModeEnabled(enabled: Boolean) {
        userPreferencesDataStore.childModeEnabled.set(enabled)
    }

    suspend fun getPin(): String = userPreferencesDataStore.childModePin.get()

    suspend fun setPin(pin: String) {
        userPreferencesDataStore.childModePin.set(pin)
    }
}

/**
 * Extract only the domain from a URL input.
 * Strips protocol (http://, https://), path, query params, and port numbers.
 * Example: "https://www.example.com:8080/path?q=1" -> "example.com"
 */
private fun extractDomain(input: String): String {
    var s = input.trim().lowercase()
    // Strip protocol
    s = s.removePrefix("https://").removePrefix("http://")
    // Strip path and query
    val slashIndex = s.indexOf('/')
    if (slashIndex > 0) {
        s = s.substring(0, slashIndex)
    }
    // Strip port
    val lastColonIndex = s.lastIndexOf(':')
    if (lastColonIndex > 0) {
        val afterColon = s.substring(lastColonIndex + 1)
        if (afterColon.all { it.isDigit() }) {
            s = s.substring(0, lastColonIndex)
        }
    }
    // Remove www. prefix for cleaner display
    s = s.removePrefix("www.")
    return s.trimEnd('.')
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildModeSettingsScreen(
    childModeSettingsScreen: ChildModeSettingsScreen,
    onUp: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var childModeEnabled by remember { mutableStateOf(false) }
    var whitelistUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var pinCode by remember { mutableStateOf("") }

    // Load data on first composition
    LaunchedEffect(Unit) {
        childModeEnabled = childModeSettingsScreen.isChildModeEnabled()
        whitelistUrls = childModeSettingsScreen.getWhitelistUrls()
        pinCode = childModeSettingsScreen.getPin()
    }

    BackHandler { onUp() }

    // Dialog state
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf(-1) }
    var dialogInput by remember { mutableStateOf("") }
    var isAddMode by remember { mutableStateOf(false) }

    // PIN dialog state
    var showPinVerifyDialog by remember { mutableStateOf(false) }
    var showPinSetupDialog by remember { mutableStateOf(false) }
    var pinVerifyInput by remember { mutableStateOf("") }
    var pinVerifyError by remember { mutableStateOf(false) }
    var pinNewInput by remember { mutableStateOf("") }
    var pinConfirmInput by remember { mutableStateOf("") }
    var pinSetupError by remember { mutableStateOf(false) }
    var pendingPinAction by remember { mutableStateOf<String?>(null) }
    var showWhitelistDialog by remember { mutableStateOf(false) }

    // Whitelist count summary
    val whitelistCount = if (whitelistUrls.isEmpty()) {
        stringResource(R.string.child_mode_whitelist_empty)
    } else {
        "${whitelistUrls.size} URLs"
    }

    Scaffold(
        containerColor = Color(0xFFF2F2F7),
        topBar = {
            TopAppBar(
                modifier = Modifier.height(48.dp),
                title = { Text(stringResource(R.string.settings_child_mode)) }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
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
            // Toggle: Enable child mode
            Surface(
                color = Color.White,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val newValue = !childModeEnabled
                                if (newValue && pinCode.isNotEmpty()) {
                                    pendingPinAction = "toggle"
                                    pinVerifyInput = ""
                                    pinVerifyError = false
                                    showPinVerifyDialog = true
                                } else {
                                    childModeEnabled = newValue
                                    scope.launch { childModeSettingsScreen.setChildModeEnabled(newValue) }
                                }
                            }
                            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            stringResource(R.string.child_mode_enable),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Switch(
                            checked = childModeEnabled,
                            onCheckedChange = {
                                if (it && pinCode.isNotEmpty()) {
                                    pendingPinAction = "toggle"
                                    pinVerifyInput = ""
                                    pinVerifyError = false
                                    showPinVerifyDialog = true
                                } else {
                                    childModeEnabled = it
                                    scope.launch { childModeSettingsScreen.setChildModeEnabled(it) }
                                }
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

            // Whitelist section
            Surface(
                color = Color.White,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (pinCode.isNotEmpty()) {
                                    pendingPinAction = "whitelist"
                                    pinVerifyInput = ""
                                    pinVerifyError = false
                                    showPinVerifyDialog = true
                                } else {
                                    showWhitelistDialog = true
                                }
                            }
                            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                stringResource(R.string.child_mode_whitelist),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                whitelistCount,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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

            // PIN Code section
            Surface(
                color = Color.White,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (pinCode.isNotEmpty()) {
                                    pendingPinAction = "pin"
                                    pinVerifyInput = ""
                                    pinVerifyError = false
                                    showPinVerifyDialog = true
                                } else {
                                    pinNewInput = ""
                                    pinConfirmInput = ""
                                    pinSetupError = false
                                    showPinSetupDialog = true
                                }
                            }
                            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                stringResource(R.string.child_mode_pin),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                if (pinCode.isNotEmpty()) "●●●●" else stringResource(R.string.child_mode_pin_not_set),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
            }
        }
    }
    }

    // Edit/Add Dialog
    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = {
                Text(
                    if (isAddMode) stringResource(R.string.child_mode_whitelist_add_title)
                    else stringResource(R.string.child_mode_whitelist_edit_title)
                )
            },
            text = {
                OutlinedTextField(
                    value = dialogInput,
                    onValueChange = { dialogInput = it },
                    label = { Text(stringResource(R.string.child_mode_whitelist_domain_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (dialogInput.isNotEmpty()) {
                            IconButton(onClick = { dialogInput = "" }) {
                                Text("✕")
                            }
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val domain = extractDomain(dialogInput)
                        if (domain.isNotEmpty()) {
                            val newList = whitelistUrls.toMutableList()
                            if (isAddMode) {
                                if (!newList.contains(domain)) {
                                    newList.add(domain)
                                }
                            } else {
                                if (editingIndex in newList.indices) {
                                    newList[editingIndex] = domain
                                }
                            }
                            whitelistUrls = newList
                            scope.launch { childModeSettingsScreen.saveWhitelistUrls(newList) }
                        }
                        showEditDialog = false
                    }
                ) {
                    Text(stringResource(R.string.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Delete Confirmation Dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.child_mode_whitelist_del)) },
            text = { Text(stringResource(R.string.child_mode_whitelist_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newList = whitelistUrls.toMutableList()
                        if (editingIndex in newList.indices) {
                            newList.removeAt(editingIndex)
                            whitelistUrls = newList
                            scope.launch { childModeSettingsScreen.saveWhitelistUrls(newList) }
                        }
                        showDeleteDialog = false
                    }
                ) {
                    Text(stringResource(R.string.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Whitelist Dialog
    if (showWhitelistDialog) {
        AlertDialog(
            onDismissRequest = { showWhitelistDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.child_mode_whitelist))
                    Text(
                        "${whitelistUrls.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (whitelistUrls.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(R.string.child_mode_whitelist_empty),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .let { mod ->
                                    if (whitelistUrls.size > 5) {
                                        mod.heightIn(max = 320.dp)
                                    } else mod
                                }
                        ) {
                            itemsIndexed(whitelistUrls) { index, url ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp),
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            url,
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        TextButton(
                                            onClick = {
                                                editingIndex = index
                                                dialogInput = url
                                                isAddMode = false
                                                showEditDialog = true
                                            }
                                        ) {
                                            Text(
                                                stringResource(R.string.child_mode_whitelist_edit),
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        TextButton(
                                            onClick = {
                                                editingIndex = index
                                                showDeleteDialog = true
                                            },
                                            colors = ButtonDefaults.textButtonColors(
                                                contentColor = MaterialTheme.colorScheme.error
                                            )
                                        ) {
                                            Text(
                                                stringResource(R.string.child_mode_whitelist_del),
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row {
                        TextButton(
                            onClick = {
                                editingIndex = -1
                                dialogInput = ""
                                isAddMode = true
                                showEditDialog = true
                            }
                        ) {
                            Text("+ ${stringResource(R.string.child_mode_whitelist_add)}")
                        }
                        TextButton(onClick = { showWhitelistDialog = false }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                    TextButton(onClick = { showWhitelistDialog = false }) {
                        Text(stringResource(R.string.action_ok))
                    }
                }
            }
        )
    }

    // PIN Verify Dialog
    if (showPinVerifyDialog) {
        AlertDialog(
            onDismissRequest = { showPinVerifyDialog = false },
            title = { Text(stringResource(R.string.child_mode_pin_verify_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = pinVerifyInput,
                        onValueChange = {
                            pinVerifyInput = it
                            pinVerifyError = false
                        },
                        label = { Text(stringResource(R.string.child_mode_pin_verify_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        isError = pinVerifyError,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (pinVerifyError) {
                        Text(
                            stringResource(R.string.child_mode_pin_wrong),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (pinVerifyInput == pinCode) {
                            showPinVerifyDialog = false
                            when (pendingPinAction) {
                                "toggle" -> {
                                    childModeEnabled = true
                                    scope.launch { childModeSettingsScreen.setChildModeEnabled(true) }
                                }
                                "whitelist" -> {
                                    showWhitelistDialog = true
                                }
                                "pin" -> {
                                    pinNewInput = ""
                                    pinConfirmInput = ""
                                    pinSetupError = false
                                    showPinSetupDialog = true
                                }
                            }
                            pendingPinAction = null
                        } else {
                            pinVerifyError = true
                        }
                    }
                ) {
                    Text(stringResource(R.string.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinVerifyDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // PIN Setup Dialog
    if (showPinSetupDialog) {
        AlertDialog(
            onDismissRequest = { showPinSetupDialog = false },
            title = { Text(stringResource(R.string.child_mode_pin_set_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = pinNewInput,
                        onValueChange = {
                            pinNewInput = it
                            pinSetupError = false
                        },
                        label = { Text(stringResource(R.string.child_mode_pin_new_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        isError = pinSetupError,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.padding(4.dp))
                    OutlinedTextField(
                        value = pinConfirmInput,
                        onValueChange = {
                            pinConfirmInput = it
                            pinSetupError = false
                        },
                        label = { Text(stringResource(R.string.child_mode_pin_confirm_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        isError = pinSetupError,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (pinSetupError) {
                        Text(
                            stringResource(R.string.child_mode_pin_mismatch),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(
                        onClick = {
                            showPinSetupDialog = false
                            pinCode = ""
                            scope.launch { childModeSettingsScreen.setPin("") }
                        }
                    ) {
                        Text(stringResource(R.string.child_mode_whitelist_clear))
                    }
                    TextButton(
                        onClick = {
                            if (pinNewInput.isEmpty()) {
                                pinSetupError = true
                            } else if (pinNewInput == pinConfirmInput) {
                                pinCode = pinNewInput
                                scope.launch { childModeSettingsScreen.setPin(pinNewInput) }
                                showPinSetupDialog = false
                            } else {
                                pinSetupError = true
                            }
                        }
                    ) {
                        Text(stringResource(R.string.action_ok))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinSetupDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}
