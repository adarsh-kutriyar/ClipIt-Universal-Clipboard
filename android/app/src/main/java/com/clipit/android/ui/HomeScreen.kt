package com.clipit.android.ui

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.clipit.android.R
import com.clipit.android.ui.theme.ClipItTheme
import com.clipit.android.ui.theme.clipIt

/**
 * ClipIt home screen, matching the "Layered Cards" icon and modern UI design.
 * Pure UI: state comes in, events go out, wired to MainActivity state.
 */
@Composable
fun HomeScreen(
    pairedMac: String?,            // e.g. "a0:9a:8e:19:0e:65"; null = nothing paired yet
    isConnected: Boolean,
    zeroTouchEnabled: Boolean,
    onSendClipboard: () -> Unit,
    onScanQr: () -> Unit,
    onAutoSearch: () -> Unit,
    onZeroTouchChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Header(onOpenSettings)
            ConnectionCard(pairedMac, isConnected, onScanQr, onAutoSearch)
            SendSection(enabled = isConnected && pairedMac != null, onClick = onSendClipboard)
            QuickWays()
            ZeroTouchRow(zeroTouchEnabled, onZeroTouchChange)
            Spacer(Modifier.navigationBarsPadding().height(16.dp))
        }
    }
}

@Composable
private fun Header(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_clipit_mark),
            contentDescription = null,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "ClipIt",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "Universal clipboard",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                Icons.Outlined.Tune,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

@Composable
private fun ConnectionCard(
    pairedMac: String?,
    isConnected: Boolean,
    onScanQr: () -> Unit,
    onAutoSearch: () -> Unit,
) {
    ClipItCard {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                IconTile(Icons.Outlined.LaptopMac, size = 48.dp, corner = 16.dp, iconSize = 24.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (pairedMac != null) "Paired Mac" else "No Mac paired",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (pairedMac != null) {
                        Text(
                            text = pairedMac,
                            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                if (pairedMac != null) StatusPill(isConnected)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Scan QR", Icons.Outlined.QrCodeScanner, onScanQr, Modifier.weight(1f))
                SecondaryButton("Auto-search", Icons.Outlined.Search, onAutoSearch, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatusPill(connected: Boolean) {
    val extras = MaterialTheme.clipIt
    val container = if (connected) extras.successContainer else MaterialTheme.colorScheme.surfaceVariant
    val content = if (connected) extras.onSuccessContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val dot = if (connected) extras.success else MaterialTheme.colorScheme.outline
    Surface(shape = CircleShape, color = container) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(7.dp).background(dot, CircleShape))
            Text(
                text = if (connected) "Connected" else "Offline",
                style = MaterialTheme.typography.labelMedium,
                color = content,
            )
        }
    }
}

@Composable
private fun SendSection(enabled: Boolean, onClick: () -> Unit) {
    Column {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Send clipboard to Mac", style = MaterialTheme.typography.titleMedium)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.SyncAlt,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Mac to phone sync runs automatically",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QuickWays() {
    Column {
        Text(
            text = "Quick ways to sync".uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
        )
        ClipItCard {
            WayRow(Icons.Outlined.Notifications, "Notification", "Tap Send Clipboard in the shade")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            WayRow(Icons.Outlined.GridView, "Quick Settings tile", "Add the ClipIt Sync tile")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            WayRow(Icons.Outlined.Share, "Share sheet", "Share any text or link to ClipIt")
        }
    }
}

@Composable
private fun WayRow(icon: ImageVector, title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconTile(icon, size = 40.dp, corner = 12.dp, iconSize = 20.dp)
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ZeroTouchRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    ClipItCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .toggleable(value = enabled, role = Role.Switch, onValueChange = onChange)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconTile(Icons.Outlined.Bolt, size = 40.dp, corner = 12.dp, iconSize = 20.dp)
            Column(Modifier.weight(1f)) {
                Text("Zero-touch sync", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = if (enabled) "On. Syncs your clipboard in the background"
                    else "Optional. Off is safe for GPay and banking apps",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = null, // the whole row handles the toggle
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = MaterialTheme.colorScheme.outline,
                    uncheckedBorderColor = Color.Transparent,
                ),
            )
        }
    }
}

// ---- small building blocks -------------------------------------------------

@Composable
private fun ClipItCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(content = content)
    }
}

@Composable
private fun IconTile(icon: ImageVector, size: Dp, corner: Dp, iconSize: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(corner)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun SecondaryButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = null,
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

// ---- previews ----------------------------------------------------------------

@Preview(name = "Light", showBackground = true, heightDp = 844, widthDp = 390)
@Composable
private fun HomeScreenLightPreview() {
    ClipItTheme(darkTheme = false) {
        HomeScreen("a0:9a:8e:19:0e:65", true, false, {}, {}, {}, {}, {})
    }
}

@Preview(name = "Dark", showBackground = true, heightDp = 844, widthDp = 390, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomeScreenDarkPreview() {
    ClipItTheme(darkTheme = true) {
        HomeScreen("a0:9a:8e:19:0e:65", true, true, {}, {}, {}, {}, {})
    }
}
