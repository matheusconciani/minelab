package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import auth.MinecraftProfile

@Composable
fun DashboardScreen(
    profile: MinecraftProfile
) {
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // User profile badge on the top-right corner
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF031419).copy(alpha = 0.6f))
                .border(1.dp, Color(0xFF83B9AD).copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = profile.name,
                    color = Color(0xFFDCE5DF),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (profile.id == profile.name) "Conta Local" else "Microsoft",
                    color = Color(0xFF83B9AD).copy(alpha = 0.8f),
                    fontSize = 11.sp
                )
            }

            // 3D Isometric Skin Head
            MinecraftHead3D(
                skinUrl = profile.skinUrl,
                playerName = profile.name,
                size = 42.dp
            )
        }

        // Remaining area is kept blank with the animated grain gradient background
    }
}
