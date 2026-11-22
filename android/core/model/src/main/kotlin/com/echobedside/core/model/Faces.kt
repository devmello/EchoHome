package com.echobedside.core.model

import android.content.Context
import android.view.View
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** StandBy faces (NOTES §2.1). `:feature:clock` registers most; others may add more (e.g. photos). */
class FaceSpec(
    val id: String,
    val name: String,
    /** ARGB options for the edit-mode colour capsule; empty = no colour option (World). */
    val colorOptions: List<Int>,
    val supportsNight: Boolean = true,
    val supportsComplications: Boolean = false,
    val order: Int = 0,
    val factory: (Context) -> FaceView,
)

data class FaceConfig(
    val color: Int = 0xFFFFFFFF.toInt(),
    val complications: Boolean = true,
    /** Night: every colour maps to #BE180E, dimmed (NOTES §5.7). */
    val night: Boolean = false,
)

/** A face is a View that takes a config. It must cost no frames when nothing changes. */
interface FaceView : EchoScreen {
    val view: View
    fun setConfig(config: FaceConfig)
    /** Minute tick / time set / time zone change. Faces with seconds hands animate themselves. */
    fun onTimeChanged() {}
}

interface FaceRegistry {
    val faces: StateFlow<List<FaceSpec>>
    fun register(spec: FaceSpec)
    fun byId(id: String): FaceSpec? = faces.value.firstOrNull { it.id == id }
}

class DefaultFaceRegistry : FaceRegistry {
    private val _faces = MutableStateFlow<List<FaceSpec>>(emptyList())
    override val faces: StateFlow<List<FaceSpec>> = _faces.asStateFlow()
    override fun register(spec: FaceSpec) =
        _faces.update { l -> (l.filterNot { it.id == spec.id } + spec).sortedBy { it.order } }
}
