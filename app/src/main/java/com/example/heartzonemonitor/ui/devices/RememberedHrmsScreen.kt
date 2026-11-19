package com.example.heartzonemonitor.ui.devices

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.heartzonemonitor.data.local.entity.RememberedHrmEntity

// Athletic Monolith Design Tokens
private val VoidBlack = Color(0xFF0B0F19)
private val DarkSlate = Color(0xFF1E293B)
private val BorderSlate = Color(0xFF334155)
private val AthleticLime = Color(0xFFBDCF73)
private val TertiaryCyan = Color(0xFF00DAF3)
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFF94A3B8)
private val TextDisabled = Color(0xFF64748B)
private val WarningAmber = Color(0xFFF59E0B)
private val ErrorRed = Color(0xFFEF4444)

data class RememberedHrmsActions(
    val onNavigateBack: () -> Unit = {},
    val onPromoteToActive: (macAddress: String) -> Unit = {},
    val onStartEditNickname: (RememberedHrmEntity) -> Unit = {},
    val onNicknameDraftChange: (String) -> Unit = {},
    val onConfirmEditNickname: () -> Unit = {},
    val onDismissEditNickname: () -> Unit = {},
    val onRequestForgetDevice: (RememberedHrmEntity) -> Unit = {},
    val onConfirmForgetDevice: () -> Unit = {},
    val onDismissForgetDevice: () -> Unit = {},
    val onDismissActiveDeletedPrompt: () -> Unit = {},
    val onScanForNewHrm: () -> Unit = {}
)

@Composable
fun RememberedHrmsScreen(
    viewModel: RememberedHrmsViewModel,
    onNavigateBack: () -> Unit,
    onScanForNewHrm: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    RememberedHrmsContent(
        uiState = uiState,
        actions = RememberedHrmsActions(
            onNavigateBack = onNavigateBack,
            onPromoteToActive = viewModel::promoteToActive,
            onStartEditNickname = viewModel::startEditNickname,
            onNicknameDraftChange = viewModel::onNicknameDraftChange,
            onConfirmEditNickname = viewModel::confirmEditNickname,
            onDismissEditNickname = viewModel::dismissEditNickname,
            onRequestForgetDevice = viewModel::requestForgetDevice,
            onConfirmForgetDevice = viewModel::confirmForgetDevice,
            onDismissForgetDevice = viewModel::dismissForgetDevice,
            onDismissActiveDeletedPrompt = viewModel::dismissActiveDeletedPrompt,
            onScanForNewHrm = onScanForNewHrm
        ),
        modifier = modifier
    )
}

@Composable
fun RememberedHrmsContent(
    uiState: RememberedHrmsUiState,
    actions: RememberedHrmsActions,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = VoidBlack,
        topBar = {
            DevicesTopBar(onNavigateBack = actions.onNavigateBack)
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (uiState.errorMessage != null) {
                ErrorBanner(message = uiState.errorMessage)
            }

            if (uiState.showActiveHrmDeletedPrompt) {
                ActiveHrmDeletedPromptCard(
                    hasRemainingHrms = uiState.inactiveHrms.isNotEmpty(),
                    onDismiss = actions.onDismissActiveDeletedPrompt
                )
            }

            if (uiState.activeHrm != null) {
                ActiveHrmHeroCard(
                    hrm = uiState.activeHrm,
                    isSessionActive = uiState.isSessionActive,
                    onEditNickname = { actions.onStartEditNickname(uiState.activeHrm) },
                    onForgetDevice = { actions.onRequestForgetDevice(uiState.activeHrm) }
                )
            }

            if (uiState.inactiveHrms.isNotEmpty()) {
                SectionHeader(title = "REMEMBERED HRMS (${uiState.inactiveHrms.size})")
                uiState.inactiveHrms.forEach { hrm ->
                    InactiveHrmCard(
                        hrm = hrm,
                        isSessionActive = uiState.isSessionActive,
                        onPromoteToActive = { actions.onPromoteToActive(hrm.macAddress) },
                        onEditNickname = { actions.onStartEditNickname(hrm) },
                        onForgetDevice = { actions.onRequestForgetDevice(hrm) }
                    )
                }
            }

            if (uiState.allHrms.isEmpty() && !uiState.showActiveHrmDeletedPrompt) {
                EmptyRememberedHrmsCard()
            }

            ScanForNewHrmButton(onClick = actions.onScanForNewHrm)

            if (uiState.isSessionActive) {
                SessionLockoutNotice()
            }
        }
    }

    val editingTarget = uiState.editingNicknameTarget
    if (editingTarget != null) {
        EditNicknameDialog(
            target = editingTarget,
            draft = uiState.nicknameDraft,
            onDraftChange = actions.onNicknameDraftChange,
            onConfirm = actions.onConfirmEditNickname,
            onDismiss = actions.onDismissEditNickname
        )
    }

    val forgetTarget = uiState.forgetConfirmationTarget
    if (forgetTarget != null) {
        ForgetDeviceConfirmationDialog(
            target = forgetTarget,
            onConfirm = actions.onConfirmForgetDevice,
            onDismiss = actions.onDismissForgetDevice
        )
    }
}

@Composable
private fun DevicesTopBar(onNavigateBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(VoidBlack)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkSlate)
                        .border(1.dp, BorderSlate, RoundedCornerShape(10.dp))
                        .clickable(onClick = onNavigateBack)
                        .semantics {
                            role = Role.Button
                            contentDescription = "Navigate back"
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "<-",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Column {
                    Text(
                        text = "BLUETOOTH LE HRM",
                        color = TertiaryCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                    Text(
                        text = "Heart Rate Monitors",
                        color = TextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp
                    )
                }
            }

            Box(
                modifier = Modifier
                    .background(DarkSlate, RoundedCornerShape(8.dp))
                    .border(1.dp, BorderSlate, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "0x180D",
                    color = AthleticLime,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        HorizontalDivider(color = BorderSlate, thickness = 1.dp)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = TextSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        fontFamily = FontFamily.Monospace
    )
}

@Composable
private fun ActiveHrmHeroCard(
    hrm: RememberedHrmEntity,
    isSessionActive: Boolean,
    onEditNickname: () -> Unit,
    onForgetDevice: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DarkSlate)
            .border(2.dp, TertiaryCyan, RoundedCornerShape(14.dp))
            .testTag("active_hrm_card_${hrm.macAddress}")
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(TertiaryCyan)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .background(VoidBlack, RoundedCornerShape(6.dp))
                        .border(1.dp, AthleticLime, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(AthleticLime, CircleShape)
                    )
                    Text(
                        text = "ACTIVE HRM",
                        color = AthleticLime,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Box(
                    modifier = Modifier
                        .background(VoidBlack, RoundedCornerShape(6.dp))
                        .border(1.dp, BorderSlate, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "0x180D GATT",
                        color = TertiaryCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            HrmIdentityBlock(hrm = hrm, titleFontSizeSp = 20)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(VoidBlack, RoundedCornerShape(10.dp))
                    .border(1.dp, BorderSlate, RoundedCornerShape(10.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "MAC ADDRESS",
                        color = TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = hrm.macAddress,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "BATTERY",
                        color = TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = formatBatteryText(hrm.batteryLevel),
                        color = if (hrm.batteryLevel != null) AthleticLime else TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Text(
                text = "Prioritized for automatic BLE connection when starting a Session.",
                color = TextSecondary,
                fontSize = 12.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onEditNickname,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp)
                        .testTag("edit_nickname_${hrm.macAddress}"),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, BorderSlate),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = VoidBlack,
                        contentColor = TextPrimary
                    )
                ) {
                    Text(
                        text = "Edit Nickname",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                OutlinedButton(
                    onClick = onForgetDevice,
                    enabled = !isSessionActive,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp)
                        .testTag("forget_device_${hrm.macAddress}"),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, if (isSessionActive) BorderSlate else ErrorRed),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = VoidBlack,
                        contentColor = ErrorRed,
                        disabledContentColor = TextDisabled
                    )
                ) {
                    Text(
                        text = "Forget Device",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun InactiveHrmCard(
    hrm: RememberedHrmEntity,
    isSessionActive: Boolean,
    onPromoteToActive: () -> Unit,
    onEditNickname: () -> Unit,
    onForgetDevice: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSlate)
            .border(1.dp, BorderSlate, RoundedCornerShape(12.dp))
            .clickable(enabled = !isSessionActive, onClick = onPromoteToActive)
            .padding(16.dp)
            .testTag("inactive_hrm_card_${hrm.macAddress}"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        HrmIdentityBlock(hrm = hrm, titleFontSizeSp = 18)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = hrm.macAddress,
                color = TextSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = formatBatteryText(hrm.batteryLevel),
                color = if (hrm.batteryLevel != null) AthleticLime else TextDisabled,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace
            )
        }

        HorizontalDivider(color = BorderSlate.copy(alpha = 0.6f), thickness = 1.dp)

        Button(
            onClick = onPromoteToActive,
            enabled = !isSessionActive,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .testTag("set_active_${hrm.macAddress}"),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = VoidBlack,
                contentColor = AthleticLime,
                disabledContainerColor = VoidBlack.copy(alpha = 0.5f),
                disabledContentColor = TextDisabled
            ),
            border = BorderStroke(1.dp, if (isSessionActive) BorderSlate else AthleticLime)
        ) {
            Text(
                text = "Set as Active",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onEditNickname,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .testTag("edit_nickname_${hrm.macAddress}"),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, BorderSlate),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = VoidBlack,
                    contentColor = TextPrimary
                )
            ) {
                Text(
                    text = "Edit Nickname",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            OutlinedButton(
                onClick = onForgetDevice,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .testTag("forget_device_${hrm.macAddress}"),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.7f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = VoidBlack,
                    contentColor = ErrorRed
                )
            ) {
                Text(
                    text = "Forget Device",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun HrmIdentityBlock(
    hrm: RememberedHrmEntity,
    titleFontSizeSp: Int
) {
    val nickname = hrm.customNickname?.trim()?.takeIf { it.isNotEmpty() }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (nickname != null) {
            Text(
                text = nickname,
                color = TextPrimary,
                fontSize = titleFontSizeSp.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = hrm.advertisedName,
                color = TextSecondary,
                fontSize = 13.sp
            )
        } else {
            Text(
                text = hrm.advertisedName,
                color = TextPrimary,
                fontSize = titleFontSizeSp.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ActiveHrmDeletedPromptCard(
    hasRemainingHrms: Boolean,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSlate, RoundedCornerShape(12.dp))
            .border(1.5.dp, WarningAmber, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .background(VoidBlack, RoundedCornerShape(4.dp))
                .border(1.dp, WarningAmber, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                text = "NO ACTIVE HRM",
                color = WarningAmber,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Text(
            text = "Active HRM Removed",
            color = TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = if (hasRemainingHrms) {
                "Your Active HRM was disconnected and removed. Tap a Remembered HRM below to designate a new Active HRM, or scan for a new HRM."
            } else {
                "Your Active HRM was disconnected and removed. Scan for a new HRM to designate an Active HRM."
            },
            color = TextSecondary,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )

        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.heightIn(min = 56.dp),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, BorderSlate),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
        ) {
            Text(
                text = "Dismiss",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun EmptyRememberedHrmsCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSlate, RoundedCornerShape(12.dp))
            .border(1.dp, BorderSlate, RoundedCornerShape(12.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "No Remembered HRMs",
            color = TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Scan for a Bluetooth LE Heart Rate Monitor (0x180D) to pair and designate your Active HRM.",
            color = TextSecondary,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
    }
}

@Composable
private fun ScanForNewHrmButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AthleticLime,
            contentColor = VoidBlack
        )
    ) {
        Text(
            text = "Scan for New HRM",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
private fun SessionLockoutNotice() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(VoidBlack, RoundedCornerShape(12.dp))
            .border(1.dp, WarningAmber, RoundedCornerShape(12.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .background(DarkSlate, RoundedCornerShape(4.dp))
                .border(1.dp, WarningAmber, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            Text(
                text = "LOCKED",
                color = WarningAmber,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
        Text(
            text = "Active HRM switching is locked out during an active Session to maintain telemetry data integrity.",
            color = TextSecondary,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSlate, RoundedCornerShape(10.dp))
            .border(1.dp, ErrorRed, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Text(
            text = message,
            color = ErrorRed,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun EditNicknameDialog(
    target: RememberedHrmEntity,
    draft: String,
    onDraftChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSlate,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = {
            Text(
                text = "Edit HRM Nickname",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "${target.advertisedName} (${target.macAddress})",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    label = { Text("Custom Nickname") },
                    placeholder = { Text("e.g. Chest Strap", color = TextDisabled) },
                    singleLine = true,
                    supportingText = {
                        Text(
                            text = "Leave blank to use advertised name",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedContainerColor = VoidBlack,
                        unfocusedContainerColor = VoidBlack,
                        focusedBorderColor = AthleticLime,
                        unfocusedBorderColor = BorderSlate,
                        focusedLabelColor = AthleticLime,
                        unfocusedLabelColor = TextSecondary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("nickname_input"),
                    shape = RoundedCornerShape(8.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AthleticLime,
                    contentColor = VoidBlack
                )
            ) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun ForgetDeviceConfirmationDialog(
    target: RememberedHrmEntity,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSlate,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = {
            Text(
                text = "Forget Heart Rate Monitor?",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = if (target.isActive) {
                    "Removing ${target.displayName()} (${target.macAddress}) will close its Bluetooth connection, clear your Active HRM, and delete it from Remembered HRMs."
                } else {
                    "Remove ${target.displayName()} (${target.macAddress}) from Remembered HRMs?"
                },
                color = TextSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ErrorRed,
                    contentColor = TextPrimary
                )
            ) {
                Text("Forget", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

private fun formatBatteryText(batteryLevel: Int?): String =
    if (batteryLevel != null) "Battery: $batteryLevel%" else "Battery: Unknown"
