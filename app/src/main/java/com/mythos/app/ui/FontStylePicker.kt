package com.mythos.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val FontPick = Color(0xFF1F5FA8)

/** Baris "Gaya font": ketuk untuk membuka dialog pilihan; berlaku seketika dan tersimpan. */
@Composable
internal fun FontStyleRow(selectedKey: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val cur = FontTheme.options.firstOrNull { it.key == selectedKey } ?: FontTheme.options[0]
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Ubah gaya font", role = Role.Button) { open = true }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Gaya font",
            style = TextStyle(fontFamily = MythosSans, fontSize = 16.sp, color = MythosColors.Ink),
            modifier = Modifier.weight(1f)
        )
        Text(cur.label, style = TextStyle(fontFamily = MythosSans, fontSize = 15.sp, color = MythosColors.Hint))
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(28.dp),
            containerColor = MythosColors.Background,
            title = { Text("Gaya font", style = TextStyle(fontFamily = MythosSans, fontWeight = FontWeight.Medium, fontSize = 20.sp, color = MythosColors.Ink)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    FontTheme.options.forEach { o ->
                        val on = o.key == cur.key
                        val tint = if (on) FontPick else MythosColors.Ink
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(onClickLabel = "Pilih ${o.label}", role = Role.RadioButton) { open = false; onSelect(o.key) }
                                .padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Pratinjau: "Aa" dan nama pilihan ditulis dengan font pilihan itu sendiri.
                            Box(Modifier.width(40.dp)) {
                                Text("Aa", style = TextStyle(fontFamily = o.family, fontWeight = FontWeight.Medium, fontSize = 20.sp, color = tint))
                            }
                            Text(
                                o.label,
                                style = TextStyle(fontFamily = o.family, fontSize = 18.sp, color = tint),
                                modifier = Modifier.weight(1f)
                            )
                            if (on) Icon(Icons.Outlined.Done, contentDescription = "Dipilih", tint = FontPick)
                            else Spacer(Modifier.width(24.dp))
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }
}
