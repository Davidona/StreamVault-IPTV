package com.streamvault.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.*
import com.streamvault.app.R
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.core.ui.interaction.TvIconButton

@Composable
fun TvKeyboard(
    onKeyPress: (String) -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    initialFocusRequester: FocusRequester? = null
) {
    val rows = listOf(
        listOf("A", "B", "C", "D", "E", "F", "G"),
        listOf("H", "I", "J", "K", "L", "M", "N"),
        listOf("O", "P", "Q", "R", "S", "T", "U"),
        listOf("V", "W", "X", "Y", "Z", "-", "_"),
        listOf("0", "1", "2", "3", "4", "5", "6"),
        listOf("7", "8", "9", ".", ",", "!", "?")
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        rows.forEachIndexed { rowIndex, rowKeys ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                rowKeys.forEachIndexed { keyIndex, key ->
                    KeyboardButton(
                        text = key,
                        onClick = { onKeyPress(key) },
                        modifier = if (rowIndex == 0 && keyIndex == 0 && initialFocusRequester != null) {
                            Modifier
                                .width(48.dp)
                                .focusRequester(initialFocusRequester)
                        } else {
                            Modifier.width(48.dp)
                        }
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            KeyboardButton(
                text = stringResource(R.string.action_space),
                onClick = { onKeyPress(" ") },
                modifier = Modifier.width(96.dp)
            )
            KeyboardButton(
                text = stringResource(R.string.action_delete),
                onClick = onDelete,
                modifier = Modifier.width(96.dp)
            )
            KeyboardButton(
                text = stringResource(R.string.action_clear),
                onClick = onClear,
                modifier = Modifier.width(96.dp)
            )
            KeyboardButton(
                text = stringResource(R.string.action_done),
                onClick = onDone,
                modifier = Modifier.width(96.dp),
                backgroundColor = Primary
            )
        }
    }
}

@Composable
private fun KeyboardButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.White.copy(alpha = 0.1f)
) {
    TvClickableSurface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = backgroundColor,
            focusedContainerColor = Primary,
            pressedContainerColor = Primary.copy(alpha = 0.8f)
        ),
        modifier = modifier.height(48.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}
