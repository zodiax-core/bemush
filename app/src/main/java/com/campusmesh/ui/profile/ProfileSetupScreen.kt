package com.campusmesh.ui.profile

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmesh.ui.theme.AppTheme
import com.campusmesh.ui.theme.PixelCyan
import com.campusmesh.ui.theme.PixelMagenta
import com.campusmesh.ui.theme.PixelYellow
import com.campusmesh.ui.theme.ThemeLoadingOverlay
import java.io.File

@Composable
fun ProfileSetupRoute(
    onBackClick: () -> Unit,
    viewModel: ProfileSetupViewModel = hiltViewModel(),
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val currentTheme by viewModel.currentTheme.collectAsStateWithLifecycle()

    ProfileSetupScreen(
        currentName = profile.displayName,
        avatarPath = profile.avatarPath,
        activeTheme = currentTheme,
        onSaveName = { newName ->
            viewModel.updateName(newName)
            onBackClick()
        },
        onImagePicked = { uri ->
            viewModel.updateProfileImage(uri)
        },
        onThemeSelected = { selectedTheme ->
            viewModel.setTheme(selectedTheme)
        },
        onBackClick = onBackClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSetupScreen(
    currentName: String,
    avatarPath: String?,
    activeTheme: AppTheme,
    onSaveName: (String) -> Unit,
    onImagePicked: (Uri) -> Unit,
    onThemeSelected: (AppTheme) -> Unit,
    onBackClick: () -> Unit,
) {
    var nameInput by remember(currentName) { mutableStateOf(currentName) }
    var pendingThemeSwitch by remember { mutableStateOf<AppTheme?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            if (uri != null) {
                onImagePicked(uri)
            }
        },
    )

    val avatarBitmap = remember(avatarPath) {
        avatarPath?.let { path ->
            val file = File(path)
            if (file.exists()) BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() else null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "Your Profile",
                            fontWeight = FontWeight.Bold,
                            fontFamily = if (activeTheme == AppTheme.PIXEL_8BIT) FontFamily.Monospace else FontFamily.Default,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Profile Avatar Container with Floating Edit Icon Overlay
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clickable {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // Avatar Circle
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .clip(CircleShape)
                            .border(
                                width = if (activeTheme == AppTheme.PIXEL_8BIT) 3.dp else 0.dp,
                                color = if (activeTheme == AppTheme.PIXEL_8BIT) PixelCyan else Color.Transparent,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (avatarBitmap != null) {
                            Image(
                                bitmap = avatarBitmap,
                                contentDescription = "Profile Avatar",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = null,
                                    modifier = Modifier.size(60.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }

                    // Elevated Edit Pencil Badge (Z-Index on top, unclipped)
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 4.dp, bottom = 4.dp)
                            .size(36.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        tonalElevation = 6.dp,
                        shadowElevation = 4.dp,
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Change photo",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                ) {
                    Text("Change Photo", style = MaterialTheme.typography.labelLarge)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Set Your Display Name",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    fontFamily = if (activeTheme == AppTheme.PIXEL_8BIT) FontFamily.Monospace else FontFamily.Default,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Your display name and profile image are stored in private app storage and shared securely over BLE.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(20.dp))

                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Display Name") },
                    placeholder = { Text("e.g. Alex (CS '25)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                )

                Spacer(modifier = Modifier.height(28.dp))

                // ──────────────────────────────────────────────────────────
                // Theme Switcher Section
                // ──────────────────────────────────────────────────────────
                Divider()
                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "App Theme & UI Style",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    fontFamily = if (activeTheme == AppTheme.PIXEL_8BIT) FontFamily.Monospace else FontFamily.Default,
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ThemeOptionCard(
                        theme = AppTheme.DEFAULT,
                        isSelected = (activeTheme == AppTheme.DEFAULT),
                        modifier = Modifier.weight(1f),
                        onSelect = {
                            if (activeTheme != AppTheme.DEFAULT) {
                                pendingThemeSwitch = AppTheme.DEFAULT
                            }
                        },
                    )

                    ThemeOptionCard(
                        theme = AppTheme.PIXEL_8BIT,
                        isSelected = (activeTheme == AppTheme.PIXEL_8BIT),
                        modifier = Modifier.weight(1f),
                        onSelect = {
                            if (activeTheme != AppTheme.PIXEL_8BIT) {
                                pendingThemeSwitch = AppTheme.PIXEL_8BIT
                            }
                        },
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = {
                        if (nameInput.isNotBlank()) {
                            onSaveName(nameInput.trim())
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        "Save Profile",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = if (activeTheme == AppTheme.PIXEL_8BIT) FontFamily.Monospace else FontFamily.Default,
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        // Theme Switch Loading Animation Overlay
        pendingThemeSwitch?.let { target ->
            ThemeLoadingOverlay(
                targetTheme = target,
                onLoadingComplete = {
                    onThemeSelected(target)
                    pendingThemeSwitch = null
                },
            )
        }
    }
}

@Composable
private fun ThemeOptionCard(
    theme: AppTheme,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
) {
    val isPixel = (theme == AppTheme.PIXEL_8BIT)
    val cardBg = if (isPixel) Color(0xFF161626) else MaterialTheme.colorScheme.surfaceVariant
    val borderColor = if (isSelected) {
        if (isPixel) PixelYellow else MaterialTheme.colorScheme.primary
    } else Color.Transparent

    Card(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable { onSelect() },
        colors = CardDefaults.cardColors(containerColor = cardBg),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isPixel) PixelYellow else MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isPixel) Icons.Default.Star else Icons.Default.Check,
                    contentDescription = null,
                    tint = if (isPixel) Color.Black else MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp),
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = theme.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                fontFamily = if (isPixel) FontFamily.Monospace else FontFamily.Default,
                color = if (isPixel) PixelYellow else MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (isPixel) "Retro 8-Bit" else "Cyber Dark",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
