// android/app/src/main/java/com/eva/app/ui/subscription/SubscriptionViewModel.kt
package com.eva.app.ui.subscription

import android.app.Activity
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eva.app.commerce.PurchaseUiEvent
import com.eva.app.commerce.SubscriptionRepository
import com.eva.app.commerce.SubscriptionState
import com.eva.app.commerce.toUserMessage
import com.revenuecat.purchases.Package
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class SubscriptionScreenState {
    data object Loading : SubscriptionScreenState()
    data class Loaded(
        val subscriptionState: SubscriptionState,
        val availablePackages: List<Package>,
        val isPurchaseInProgress: Boolean,
        val errorMessage: String?,
    ) : SubscriptionScreenState()
    data class Unavailable(val reason: String) : SubscriptionScreenState()
}

@HiltViewModel
class SubscriptionViewModel @Inject constructor(
    private val repository: SubscriptionRepository,
) : ViewModel() {

    private val _availablePackages = MutableStateFlow<List<Package>>(emptyList())
    private val _isPurchaseInProgress = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _isLoading = MutableStateFlow(true)

    val uiState: StateFlow<SubscriptionScreenState> = combine(
        repository.subscriptionState,
        _availablePackages,
        _isPurchaseInProgress,
        _errorMessage,
    ) { subState, packages, inProgress, error ->
        if (_isLoading.value) {
            SubscriptionScreenState.Loading
        } else {
            SubscriptionScreenState.Loaded(
                subscriptionState = subState,
                availablePackages = packages,
                isPurchaseInProgress = inProgress,
                errorMessage = error,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SubscriptionScreenState.Loading,
    )

    init {
        viewModelScope.launch {
            repository.initialize()

            val packagesResult = repository.fetchAvailablePackages()
            packagesResult.onSuccess { packages ->
                _availablePackages.value = packages
            }.onFailure { error ->
                // Teknik gerekce log'a, kullaniciya anlasilir metin.
                Log.e(TAG, "Abonelik paketleri alinamadi", error)
                _errorMessage.value = error.toUserMessage()
            }

            _isLoading.value = false
        }
    }

    fun onPurchaseClicked(activity: Activity, packageToPurchase: Package) {
        if (_isPurchaseInProgress.value) return

        _isPurchaseInProgress.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            when (val event = repository.purchase(activity, packageToPurchase)) {
                is PurchaseUiEvent.Success -> {
                    _isPurchaseInProgress.value = false
                }
                is PurchaseUiEvent.Cancelled -> {
                    _isPurchaseInProgress.value = false
                }
                is PurchaseUiEvent.Failed -> {
                    _isPurchaseInProgress.value = false
                    _errorMessage.value = event.message
                }
            }
        }
    }

    fun onRestoreClicked() {
        viewModelScope.launch {
            when (val event = repository.restorePurchases()) {
                is PurchaseUiEvent.Success -> _errorMessage.value = null
                is PurchaseUiEvent.Failed -> _errorMessage.value = event.message
                is PurchaseUiEvent.Cancelled -> Unit
            }
        }
    }

    fun dismissError() {
        _errorMessage.value = null
    }
}

private const val TAG = "SubscriptionViewModel"
