package com.polinalinen.madre.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.polinalinen.madre.ui.theme.*

@Composable
fun CompletedScreen(
    onHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = BackgroundDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "🎉",
                style = MaterialTheme.typography.displayLarge
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Готово!",
                style = MaterialTheme.typography.displayLarge,
                color = AccentGold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Ваша выпечка готова.\nПриятного аппетита! 🍞",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(48.dp))

            Button(
                onClick = onHome,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentGold,
                    contentColor = BackgroundDark
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    text = "На главную",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}
