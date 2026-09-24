package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import auth.MinecraftProfile

@Composable
fun DashboardScreen(
    profile: MinecraftProfile,
    onLogout: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // User profile badge on the top-right corner - clickable for logout
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 20.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF031419).copy(alpha = 0.65f))
                .border(1.dp, Color(0xFF83B9AD).copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                .clickable(
                    role = Role.Button,
                    onClickLabel = "Sair da conta ${profile.name}"
                ) {
                    onLogout()
                }
                .semantics {
                    contentDescription = "Sair da conta ${profile.name}"
                }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.widthIn(max = 160.dp)
            ) {
                Text(
                    text = "Logado como",
                    color = Color(0xFFDCE5DF).copy(alpha = 0.65f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.2.sp
                )
                Text(
                    text = profile.name,
                    color = Color(0xFFDCE5DF),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.3.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 3D Isometric Skin Head positioned to the right of the text
            MinecraftHead3D(
                skinUrl = profile.skinUrl,
                playerName = profile.name,
                size = 44.dp
            )
        }

        // Remaining area is kept blank with the animated grain gradient background
    }
}
