package com.nekonf.nekostatus.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nekonf.nekostatus.core.designsystem.NekoPanel
import com.nekonf.nekostatus.core.designsystem.PrimaryAction
import com.nekonf.nekostatus.core.model.ServerConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList")
fun AuthScreen(
    onOpenScanner: () -> Unit,
    allowLocalServer: Boolean,
    pairToken: String? = null,
    onPairTokenConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val serverConfig by viewModel.serverConfig.collectAsState()
    var showServerConfig by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(pairToken) {
        pairToken?.let {
            onPairTokenConsumed()
            viewModel.pair(it)
        }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Spacer(Modifier.height(34.dp))
            Icon(
                Icons.Rounded.Pets,
                contentDescription = null,
                modifier = Modifier.size(54.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.auth_title),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.auth_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showServerConfig) {
            item {
                AuthServerPanel(
                    config = serverConfig,
                    allowLocalServer = allowLocalServer,
                    onSave = {
                        viewModel.saveServer(it)
                        showServerConfig = false
                    },
                )
            }
            item {
                OutlinedButton(
                    onClick = { showServerConfig = false },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                ) {
                    Text(stringResource(R.string.auth_server_cancel))
                }
            }
        } else {
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(AuthMode.LOGIN to R.string.auth_login, AuthMode.REGISTER to R.string.auth_register).forEachIndexed {
                            index,
                            (mode, label),
                        ->
                        SegmentedButton(
                            selected = state.mode == mode,
                            onClick = { viewModel.setMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                        ) { Text(stringResource(label)) }
                    }
                }
            }
            if (state.mode == AuthMode.DEVICE_KEY) {
                item {
                    NekoPanel(Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = state.deviceKey,
                            onValueChange = viewModel::setDeviceKey,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.auth_device_key)) },
                        )
                        Spacer(Modifier.height(12.dp))
                        state.errorMessage?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(12.dp))
                        }
                        PrimaryAction(
                            text = stringResource(R.string.auth_validate_key),
                            onClick = viewModel::submit,
                            enabled = !state.isLoading && state.deviceKey.isNotBlank(),
                        )
                        if (state.isLoading) {
                            Spacer(Modifier.height(12.dp))
                            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).size(22.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            } else {
                item {
                    NekoPanel(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = state.username,
                                onValueChange = viewModel::setUsername,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(stringResource(R.string.auth_username)) },
                            )
                            OutlinedTextField(
                                value = state.password,
                                onValueChange = viewModel::setPassword,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                label = { Text(stringResource(R.string.auth_password)) },
                            )
                            state.errorMessage?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            }
                            PrimaryAction(
                                text =
                                    stringResource(
                                        if (state.mode == AuthMode.REGISTER) {
                                            R.string.auth_create_account
                                        } else {
                                            R.string.auth_continue
                                        },
                                    ),
                                onClick = viewModel::submit,
                                enabled = !state.isLoading && state.username.isNotBlank() && state.password.length >= 6,
                            )
                            if (state.isLoading) {
                                CircularProgressIndicator(
                                    Modifier.align(Alignment.CenterHorizontally).size(22.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(onClick = onOpenScanner, modifier = Modifier.weight(1f).height(50.dp)) {
                        Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.auth_scan))
                    }
                    OutlinedButton(
                        onClick = { viewModel.setMode(AuthMode.DEVICE_KEY) },
                        modifier = Modifier.weight(1f).height(50.dp),
                    ) {
                        Icon(Icons.Rounded.Key, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.auth_manual))
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = { showServerConfig = true },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                ) {
                    Icon(Icons.Rounded.Public, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.auth_server))
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Security, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(R.string.auth_security_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AuthServerPanel(
    config: ServerConfig,
    allowLocalServer: Boolean,
    onSave: (ServerConfig) -> Unit,
) {
    var productionUrl by remember(config.productionUrl) { mutableStateOf(config.productionUrl) }
    var localUrl by remember(config.localUrl) { mutableStateOf(config.localUrl) }
    var useLocal by remember(config.useLocalServer) { mutableStateOf(config.useLocalServer && allowLocalServer) }
    val productionValid = productionUrl.startsWith("https://")
    val localValid = !useLocal || localUrl.startsWith("http://") || localUrl.startsWith("https://")
    NekoPanel(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.auth_server_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = productionUrl,
            onValueChange = { productionUrl = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.auth_production_url)) },
            isError = !productionValid,
        )
        if (allowLocalServer) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.auth_local_server), modifier = Modifier.weight(1f))
                Switch(checked = useLocal, onCheckedChange = { useLocal = it })
            }
            if (useLocal) {
                OutlinedTextField(
                    value = localUrl,
                    onValueChange = { localUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.auth_local_url)) },
                    isError = !localValid,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        PrimaryAction(
            text = stringResource(R.string.auth_server_save),
            onClick = {
                onSave(
                    ServerConfig(
                        productionUrl = productionUrl.trimEnd('/'),
                        localUrl = localUrl.trimEnd('/'),
                        useLocalServer = useLocal && allowLocalServer,
                    ),
                )
            },
            enabled = productionValid && localValid,
        )
    }
}
