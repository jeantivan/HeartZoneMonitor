package com.example.heartzonemonitor.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.heartzonemonitor.domain.model.HeartZone
import com.example.heartzonemonitor.domain.model.ZoneCalculationMode
import com.example.heartzonemonitor.domain.model.ZoneCalculationResult

// Athletic Monolith Color Palette
private val VoidBlack = Color(0xFF0B0F19)
private val DarkSlate = Color(0xFF1E293B)
private val BorderSlate = Color(0xFF334155)
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFF94A3B8)
private val TextDisabled = Color(0xFF64748B)
private val AthleticLime = Color(0xFFBDCF73)
private val TertiaryCyan = Color(0xFF00DAF3)
private val ErrorRed = Color(0xFFEF4444)
private val RequiredOrange = Color(0xFFFD5921)

data class OnboardingProfileActions(
    val onBirthYearChange: (String) -> Unit = {},
    val onRestingHrChange: (String) -> Unit = {},
    val onCustomHrmaxToggled: (Boolean) -> Unit = {},
    val onCustomHrmaxChange: (String) -> Unit = {},
    val onContinueClicked: () -> Unit = {}
)

@Composable
fun OnboardingProfileScreen(
    viewModel: OnboardingProfileViewModel,
    onNavigateToPairing: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            onNavigateToPairing()
        }
    }

    OnboardingProfileContent(
        uiState = uiState,
        actions = OnboardingProfileActions(
            onBirthYearChange = viewModel::onBirthYearChange,
            onRestingHrChange = viewModel::onRestingHrChange,
            onCustomHrmaxToggled = viewModel::onCustomHrmaxToggled,
            onCustomHrmaxChange = viewModel::onCustomHrmaxChange,
            onContinueClicked = { viewModel.saveProfile() }
        ),
        modifier = modifier
    )
}

@Composable
fun OnboardingProfileContent(
    uiState: OnboardingProfileUiState,
    actions: OnboardingProfileActions,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = VoidBlack,
        bottomBar = {
            Surface(
                color = VoidBlack,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderSlate)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (uiState.errorMessage != null) {
                        Text(
                            text = uiState.errorMessage,
                            color = ErrorRed,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    Button(
                        onClick = actions.onContinueClicked,
                        enabled = uiState.isSaveEnabled && !uiState.isSaving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AthleticLime,
                            contentColor = VoidBlack,
                            disabledContainerColor = DarkSlate,
                            disabledContentColor = TextDisabled
                        )
                    ) {
                        Text(
                            text = if (uiState.isSaving) "Saving Profile..." else "Continue to Sensor Pairing",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Step 2: Connect Bluetooth HRM (Chest Strap or Optical Armband)",
                        color = TextDisabled,
                        fontSize = 11.sp
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // 1. Progress Indicator & Offline Badge
            StepProgressHeader()

            Spacer(modifier = Modifier.height(18.dp))

            // 2. Screen Header (Title Large - 24sp, Bold, -0.5sp)
            Text(
                text = "Calibrate Your Heart Zones",
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Accurate zones using the peer-reviewed Tanaka HRmax formula. Your data stays 100% encrypted on this device.",
                color = TextSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(20.dp))

            // 3. Form Input Card
            FormCard(
                uiState = uiState,
                actions = actions
            )

            Spacer(modifier = Modifier.height(20.dp))

            // 4. Live Dynamic Preview Card
            LiveZonePreviewCard(
                calculationMode = uiState.calculationMode,
                previewResult = uiState.previewResult,
                isCustomHrmaxEnabled = uiState.isCustomHrmaxEnabled,
                customHrmaxInput = uiState.customHrmaxInput
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StepProgressHeader() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "SETUP",
                    color = TertiaryCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = " / ",
                    color = TextDisabled,
                    fontSize = 11.sp
                )
                Text(
                    text = "Step 1 of 2: Physiological Profile",
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }

            // Offline & Encrypted Badge
            Row(
                modifier = Modifier
                    .background(DarkSlate, RoundedCornerShape(4.dp))
                    .border(1.dp, BorderSlate, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "OFFLINE & ENCRYPTED",
                    color = AthleticLime,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 2-Segment Progress Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(AthleticLime)
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(BorderSlate)
            )
        }
    }
}

@Composable
private fun FormCard(
    uiState: OnboardingProfileUiState,
    actions: OnboardingProfileActions
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSlate, RoundedCornerShape(12.dp))
            .border(1.dp, BorderSlate, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        // Field 1: Birth Year
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Birth Year",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .background(RequiredOrange, RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "REQ",
                        color = TextPrimary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                text = "Ages 10–110",
                color = TextSecondary,
                fontSize = 11.sp
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        OutlinedTextField(
            value = uiState.birthYearInput,
            onValueChange = actions.onBirthYearChange,
            placeholder = { Text("1988", color = TextDisabled) },
            singleLine = true,
            isError = uiState.birthYearError != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = inputColors(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
        )

        if (uiState.birthYearError != null) {
            Text(
                text = uiState.birthYearError,
                color = ErrorRed,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Field 2: Resting Heart Rate
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Resting Heart Rate",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "(optional)",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
            Text(
                text = "BPM",
                color = TertiaryCyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        OutlinedTextField(
            value = uiState.restingHrInput,
            onValueChange = actions.onRestingHrChange,
            placeholder = { Text("60", color = TextDisabled) },
            singleLine = true,
            isError = uiState.restingHrError != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = inputColors(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
        )

        if (uiState.restingHrError != null) {
            Text(
                text = uiState.restingHrError,
                color = ErrorRed,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        } else {
            Text(
                text = "Leave blank for %HRmax, or enter resting heart rate for Karvonen HRR",
                color = TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(color = BorderSlate, thickness = 1.dp)
        Spacer(modifier = Modifier.height(12.dp))

        // Custom HRmax Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Custom Measured HRmax Override",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Specify lab-tested Maximum Heart Rate (HRmax)",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
            Switch(
                checked = uiState.isCustomHrmaxEnabled,
                onCheckedChange = actions.onCustomHrmaxToggled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AthleticLime,
                    checkedTrackColor = BorderSlate,
                    uncheckedThumbColor = TextSecondary,
                    uncheckedTrackColor = VoidBlack
                )
            )
        }

        // Custom HRmax Input if enabled
        if (uiState.isCustomHrmaxEnabled) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Custom HRmax",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "BPM",
                    color = TertiaryCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = uiState.customHrmaxInput,
                onValueChange = actions.onCustomHrmaxChange,
                placeholder = { Text("195", color = TextDisabled) },
                singleLine = true,
                isError = uiState.customHrmaxError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = inputColors(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            )
            if (uiState.customHrmaxError != null) {
                Text(
                    text = uiState.customHrmaxError,
                    color = ErrorRed,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                Text(
                    text = "Must be 100–240 BPM and > resting HR + 20",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun LiveZonePreviewCard(
    calculationMode: ZoneCalculationMode,
    previewResult: ZoneCalculationResult?,
    isCustomHrmaxEnabled: Boolean,
    customHrmaxInput: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSlate, RoundedCornerShape(12.dp))
            .border(1.dp, BorderSlate, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        // Ceiling header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column {
                Text(
                    text = "CALCULATED CEILING",
                    color = TextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Estimated HRmax: ",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = previewResult?.let { "${it.hrMax}" } ?: "--",
                        color = TertiaryCyan,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "BPM",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    text = if (isCustomHrmaxEnabled && customHrmaxInput.isNotEmpty()) {
                        "(Tanaka Baseline & Lab Override Active)"
                    } else {
                        "(Tanaka Formula: 208 - 0.7 × Age)"
                    },
                    color = TextDisabled,
                    fontSize = 11.sp
                )
            }

            // Calculation Mode Badge
            Box(
                modifier = Modifier
                    .background(VoidBlack, RoundedCornerShape(4.dp))
                    .border(1.dp, TertiaryCyan, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (calculationMode == ZoneCalculationMode.KARVONEN) "KARVONEN HRR" else "%HRMAX",
                    color = TertiaryCyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 5-Zone breakdown rows
        val zoneEntries = HeartZone.entries
        zoneEntries.forEach { zone ->
            val range = previewResult?.zones?.firstOrNull { it.zone == zone }
            ZoneRow(
                zone = zone,
                minBpm = range?.minBpm,
                maxBpm = range?.maxBpm
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 5-Segment Continuous Spectrum Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(VoidBlack)
                .padding(1.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            zoneEntries.forEach { zone ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .background(Color(zone.colorArgb))
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Z1: RECOVERY", color = TextDisabled, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text("Z3: TEMPO", color = TextDisabled, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text("Z5: ANAEROBIC", color = TextDisabled, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ZoneRow(
    zone: HeartZone,
    minBpm: Int?,
    maxBpm: Int?
) {
    val zoneColor = Color(zone.colorArgb)
    val percentRange = "${(zone.minPercent * 100).toInt()}–${(zone.maxPercent * 100).toInt()}%"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(VoidBlack, RoundedCornerShape(6.dp))
            .border(1.dp, BorderSlate.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(zoneColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Zone ${zone.zoneIndex}",
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "(${zone.zoneName})",
                color = TextSecondary,
                fontSize = 11.sp
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = percentRange,
                color = TextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = if (minBpm != null && maxBpm != null) "$minBpm - $maxBpm" else "-- - --",
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "BPM",
                color = TextDisabled,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun inputColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedContainerColor = VoidBlack,
    unfocusedContainerColor = VoidBlack,
    focusedBorderColor = AthleticLime,
    unfocusedBorderColor = BorderSlate,
    errorBorderColor = ErrorRed,
    errorContainerColor = VoidBlack
)
