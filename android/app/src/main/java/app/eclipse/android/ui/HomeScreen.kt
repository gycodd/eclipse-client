package app.eclipse.android.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.eclipse.android.data.Store
import app.eclipse.android.vpn.VpnController
import app.eclipse.android.vpn.VpnState

@Composable
fun HomeScreen(modifier: Modifier, store: Store, onToggle: () -> Unit, onOpenServers: () -> Unit) {
    val state by VpnController.state.collectAsState()
    val activeId by VpnController.activeServerId.collectAsState()
    val health by VpnController.health.collectAsState()
    val c = EC.c

    val server = store.server(activeId) ?: store.selected
    val busy = state == VpnState.CONNECTING || state == VpnState.DISCONNECTING
    val on = state == VpnState.CONNECTED

    val inf = rememberInfiniteTransition(label = "spin")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "rot")
    val pulse by inf.animateFloat(0.92f, 1.08f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "pulse")

    Column(
        modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))
        Text("Eclipse", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = c.text)
        Spacer(Modifier.weight(1f))

        Box(contentAlignment = Alignment.Center) {
            if (on) Box(Modifier.size(210.dp).scale(pulse).clip(CircleShape).background(c.accent.copy(alpha = 0.14f)))
            Box(
                Modifier.size(160.dp).clip(CircleShape)
                    .background(
                        if (on) Brush.linearGradient(listOf(Color(0xFF9B7CFF), c.accent, Color(0xFF6040E0)))
                        else Brush.linearGradient(listOf(c.card, c.card))
                    )
                    .clickable(enabled = state != VpnState.DISCONNECTING, onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PowerSettingsNew, contentDescription = "Подключить",
                    tint = if (on) Color.White else c.accentLight,
                    modifier = Modifier.size(72.dp).then(if (busy) Modifier.rotate(spin) else Modifier).alpha(if (busy) 0.7f else 1f),
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            when (state) {
                VpnState.CONNECTED -> "Защищено"
                VpnState.CONNECTING -> "Подключение…"
                VpnState.DISCONNECTING -> "Отключение…"
                VpnState.DISCONNECTED -> "Не защищено"
            },
            fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
            color = if (on) c.good else c.text,
        )
        if (on && health != null) {
            Text(
                health!!, fontSize = 12.sp, textAlign = TextAlign.Center,
                color = if (health!!.startsWith("OK")) c.muted else c.warn,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(Modifier.weight(1f))

        Card2(Modifier.clickable(onClick = onOpenServers)) {
            if (server == null) {
                Text("Серверов пока нет", color = c.text, fontWeight = FontWeight.Medium)
                Text("Откройте вкладку «Серверы» и добавьте подписку", color = c.muted, fontSize = 13.sp)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(server.name, color = c.text, fontWeight = FontWeight.Medium, maxLines = 1)
                        Text("${server.host}:${server.port}", color = c.muted, fontSize = 12.sp, maxLines = 1)
                    }
                    Chip(server.protocolLabel)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}
