package ru.alexeyrf.maxmusic

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree

@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.autofill(
    autofillTypes: List<AutofillType>,
    currentValue: String = "",
    onFill: ((String) -> Unit),
) = composed {
    val view = androidx.compose.ui.platform.LocalView.current
    val autofill = LocalAutofill.current
    val autofillTree = LocalAutofillTree.current
    val autofillNode = androidx.compose.runtime.remember { AutofillNode(onFill = onFill, autofillTypes = autofillTypes) }

    androidx.compose.runtime.LaunchedEffect(autofillNode) {
        autofillTree += autofillNode
    }

    androidx.compose.runtime.LaunchedEffect(currentValue) {
        if (currentValue.isNotEmpty()) {
            try {
                val autofillManager = view.context.getSystemService(android.view.autofill.AutofillManager::class.java)
                autofillManager?.notifyValueChanged(view, autofillNode.id, android.view.autofill.AutofillValue.forText(currentValue))
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    this.onGloballyPositioned {
        autofillNode.boundingBox = it.boundsInWindow()
    }.onFocusChanged { focusState ->
        autofill?.run {
            if (focusState.isFocused) {
                requestAutofillForNode(autofillNode)
            } else {
                cancelAutofillForNode(autofillNode)
            }
        }
    }
}
