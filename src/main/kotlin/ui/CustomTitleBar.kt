package ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowScope

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WindowScope.CustomTitleBar(
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    title: String = "Minelab"
) {
    WindowDraggableArea(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Title: exatamente "Minelab", sem ícones ou elementos decorativos adicionais,
            // em cor escura de alto contraste sobre o gradiente claro.
            Text(
                text = title,
                color = Color(0xFF031419).copy(alpha = 0.95f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp
            )

            // Window controls: Minimize & Close (No maximize as specified)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Minimize
                Box(
                    modifier = Modifier
                        .size(32.dp, 28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onMinimize() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Minimize,
                        contentDescription = "Minimizar",
                        tint = Color(0xFF031419).copy(alpha = 0.85f),
                        modifier = Modifier.size(15.dp).padding(bottom = 6.dp)
                    )
                }

                // Close
                Box(
                    modifier = Modifier
                        .size(32.dp, 28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Fechar",
                        tint = Color(0xFF031419).copy(alpha = 0.85f),
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}
