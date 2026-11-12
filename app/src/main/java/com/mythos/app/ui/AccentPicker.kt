package com.mythos.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** PATCH7: baris "Warna aksen" dengan menu pilihan warna; berlaku seketika dan tersimpan. */
@Composable
internal fun AccentColorRow(selectedKey: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val cur = AccentTheme.options.firstOrNull { it.key == selectedKey } ?: AccentTheme.options[0]
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "Ubah warna aksen", role = Role.Button) { open = true }
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Warna aksen",
                style = TextStyle(fontFamily = MythosSans, fontSize = 16.sp, color = MythosColors.Ink),
                modifier = Modifier.weight(1f)
            )
            Box(Modifier.size(18.dp).clip(CircleShape).background(cur.color))
            Spacer(Modifier.width(8.dp))
            Text(cur.label, style = TextStyle(fontFamily = MythosSans, fontSize = 15.sp, color = MythosColors.Hint))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AccentTheme.options.forEach { o ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(18.dp).clip(CircleShape).background(o.color))
                            Spacer(Modifier.width(12.dp))
                            Text(o.label)
                        }
                    },
                    trailingIcon = { if (o.key == cur.key) Icon(Icons.Outlined.Done, contentDescription = "Dipilih") },
                    onClick = { open = false; onSelect(o.key) }
                )
            }
        }
    }
}
