// android/app/src/main/java/com/eva/app/ui/eva/EvaChatRepository.kt
package com.eva.app.ui.eva

import android.util.Log
import com.eva.app.network.APIClient
import com.eva.app.network.APIClientException
import kotlinx.coroutines.CancellationException

private const val TAG = "EvaChatRepository"

sealed class EvaChatResult {
    data class Success(val reply: String, val model: String) : EvaChatResult()
    data class Failure(val message: String) : EvaChatResult()
}

class EvaChatRepository(private val apiClient: APIClient) {

    /**
     * Eva'nin Grok sohbeti. Anahtar sunucuda kalir; istemci yalnizca
     * /v1/eva/chat konusur. Gecmis istemcide tutulur, her istek stateless.
     */
    suspend fun chat(
        message: String,
        history: List<EvaChatTurnDto>,
    ): EvaChatResult {
        return try {
            val response: EvaChatResponseDto = apiClient.post(
                path = "/v1/eva/chat",
                body = EvaChatRequestDto(message = message, history = history),
                requiresAuth = true,
            )
            val reply = response.reply.trim()
            if (reply.isEmpty()) {
                EvaChatResult.Failure("Eva suskun kaldi.")
            } else {
                EvaChatResult.Success(reply, response.model)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: APIClientException.HttpStatus) {
            Log.w(TAG, "Eva sohbeti HTTP ${e.code}")
            EvaChatResult.Failure(
                if (e.code == 503) "Eva su anda ulasilamiyor. Birazdan tekrar dene."
                else "Eva cevap veremedi.",
            )
        } catch (e: APIClientException) {
            Log.w(TAG, "Eva sohbeti basarisiz: ${e.message}")
            EvaChatResult.Failure("Baglanti kopuk. Tekrar dene.")
        } catch (e: Exception) {
            Log.e(TAG, "Eva sohbeti beklenmeyen hata", e)
            EvaChatResult.Failure("Bir sey ters gitti.")
        }
    }
}
