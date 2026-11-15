package app.eclipse.android.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

enum class ToastKind { SUCCESS, INFO, ERROR }

data class ToastMsg(val text: String, val kind: ToastKind)

object Toasts {
    private val _flow = MutableSharedFlow<ToastMsg>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val flow: SharedFlow<ToastMsg> = _flow

    fun show(text: String, kind: ToastKind = ToastKind.SUCCESS) {
        _flow.tryEmit(ToastMsg(text, kind))
    }
}
