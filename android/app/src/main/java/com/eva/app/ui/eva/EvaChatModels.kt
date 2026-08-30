// android/app/src/main/java/com/eva/app/ui/eva/EvaChatModels.kt
package com.eva.app.ui.eva

import kotlinx.serialization.Serializable

@Serializable
data class EvaChatTurnDto(
    val role: String,
    val content: String,
)

@Serializable
data class EvaChatRequestDto(
    val message: String,
    val history: List<EvaChatTurnDto> = emptyList(),
)

@Serializable
data class EvaChatResponseDto(
    val reply: String,
    val model: String = "",
)

data class EvaChatMessage(
    val id: Long,
    val role: String,
    val text: String,
    val isLocal: Boolean = false,
)
