package ru.alexeyrf.maxmusic

import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics

fun Modifier.autofill(
    autofillTypes: List<ContentType>,
    currentValue: String = "",
    onFill: ((String) -> Unit),
) = this.semantics {
    if (autofillTypes.isNotEmpty()) {
        contentType = autofillTypes.first()
    }
}
