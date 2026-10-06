/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.unit.dp
import app.morphe.manager.ui.viewmodel.HomeViewModel

/**
 * Final output-architecture gate before quick patching starts.
 *
 * null means automatic selection. An empty ABI list means the input has no lib/<abi>
 * entries and is therefore treated as universal; continuing keeps selectedAbi null.
 */
@Composable
fun AbiSelectionDialog(
    state: HomeViewModel.AbiSelectionState,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Arquitectura de salida") },
        text = {
            Column {
                if (state.isUniversal) {
                    Text("Este APK no contiene bibliotecas nativas por ABI. Se tratará como Universal.")
                    ArchitectureOption(
                        label = "Universal",
                        selected = true,
                        onClick = { onSelect(null) }
                    )
                } else {
                    Text("Selecciona la arquitectura que se conservará para esta salida.")
                    ArchitectureOption(
                        label = "Automática (recomendada)",
                        selected = true,
                        onClick = { onSelect(null) }
                    )
                    state.availableAbis.forEach { abi ->
                        ArchitectureOption(
                            label = abi,
                            selected = false,
                            onClick = { onSelect(abi) }
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
private fun ArchitectureOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Text(
            text = label,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}
