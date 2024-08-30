// Copyright 2024 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.android.libraries.places.compose.demo.presentation.addresscompletion

import android.annotation.SuppressLint
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.compose.autocomplete.domain.mappers.toAddress
import com.google.android.libraries.places.compose.autocomplete.models.Address
import com.google.android.libraries.places.compose.autocomplete.models.NearbyObject
import com.google.android.libraries.places.compose.demo.data.repositories.CompositeLocation
import com.google.android.libraries.places.compose.demo.data.repositories.MergedLocationRepository
import com.google.android.libraries.places.compose.demo.data.repositories.GeocoderRepository
import com.google.android.libraries.places.compose.demo.data.repositories.PlaceRepository
import com.google.android.libraries.places.compose.demo.mappers.toNearbyObjects
import com.google.android.libraries.places.compose.demo.presentation.ViewModelEvent
import com.google.android.libraries.places.compose.demo.presentation.common.ButtonState
import com.google.android.libraries.places.compose.demo.presentation.common.ButtonStates
import com.google.android.libraries.places.compose.demo.presentation.landmark.addresshandlers.DisplayAddress
import com.google.android.libraries.places.compose.demo.presentation.landmark.addresshandlers.toDisplayAddress
import com.google.android.libraries.places.compose.demo.presentation.landmark.addresshandlers.us.UsDisplayAddress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

enum class UiState {
    AUTOCOMPLETE, ADDRESS_ENTRY
}

sealed class AddressCompletionViewState() {
    data class Autocomplete(
        // TODO: replace with AutocompleteViewState
        val searchText: String = "",
        val predictions: List<AutocompletePrediction> = emptyList(),
    ) : AddressCompletionViewState()

    data class AddressEntry(
        val displayAddress: DisplayAddress = UsDisplayAddress(),
        val nearbyObjects: List<NearbyObject> = emptyList(),
    ) : AddressCompletionViewState()
}

@HiltViewModel
class AddressCompletionViewModel
@Inject constructor(
    private val geocoderRepository: GeocoderRepository,
    private val placesRepository: PlaceRepository,
    private val mergedLocationRepository: MergedLocationRepository
) : ViewModel() {
    private val _address = MutableStateFlow<Address?>(null)
    val address = _address.asStateFlow()

    private val _viewModelEventChannel = MutableSharedFlow<ViewModelEvent>()
    val viewModelEventChannel: SharedFlow<ViewModelEvent> = _viewModelEventChannel.asSharedFlow()

    // Warning: do not use the continuous location flow here or there will be many calls to the
    // geocoder API.
    @OptIn(ExperimentalCoroutinesApi::class)
    val location = mergedLocationRepository.location.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5.seconds.inWholeMilliseconds),
        initialValue = CompositeLocation()
    )

    private val _displayAddress = MutableStateFlow<DisplayAddress?>(null)
    private val displayAddress = _displayAddress.asStateFlow()

    private val _showMap = MutableStateFlow(false)
    private val showMap = _showMap.asStateFlow()

    private val geocoderResult = location.mapNotNull { location ->
        // TODO: require a certain amount of change from the last location before geocoding.
        geocoderRepository.reverseGeocode(location.latLng, includeAddressDescriptors = true)
    }

    private val _currentAddress = geocoderResult.mapNotNull { result ->
        result.addresses.firstOrNull()
    }

    // Determine the country code based on the location not the address.
    val countryCode = _currentAddress.filterNotNull().mapNotNull { address ->
        address.getCountryCode()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5.seconds.inWholeMilliseconds),
        initialValue = "US"
    )

    private val _nearbyObjects = geocoderResult.filterNotNull().mapNotNull { result ->
        result.addressDescriptor?.toNearbyObjects()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5.seconds.inWholeMilliseconds),
        initialValue = emptyList()
    )

    private val _uiState = MutableStateFlow(UiState.AUTOCOMPLETE)

    private var buttonStates = combine(
        location,
        showMap
    ) { location, showMap ->
        ButtonStates(
            currentLocation = if (location.isMockLocation) ButtonState.NORMAL else ButtonState.SELECTED,
            mockLocation = if (location.isMockLocation) ButtonState.SELECTED else ButtonState.NORMAL,
            map = if (showMap) ButtonState.SELECTED else ButtonState.NORMAL
        )
    }

    private val _autocompleteViewState = MutableStateFlow(AddressCompletionViewState.Autocomplete())

    private val _addressEntryViewState = combine(
        displayAddress,
        _nearbyObjects
    ) { displayAddress, nearbyObjects ->
        AddressCompletionViewState.AddressEntry(
            displayAddress = displayAddress ?: UsDisplayAddress(),
            nearbyObjects = nearbyObjects
        )
    }

    val addressCompletionViewState = combine(
        _autocompleteViewState,
        _addressEntryViewState,
        _uiState
    ) { autocompleteViewState, addressEntryViewState, uiState ->
        when (uiState) {
            UiState.ADDRESS_ENTRY -> addressEntryViewState
            UiState.AUTOCOMPLETE -> autocompleteViewState
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5.seconds.inWholeMilliseconds),
        AddressCompletionViewState.Autocomplete()
    )

    init {
        // TODO: nuke this!!!
        viewModelScope.launch {
            // We have to update the _displayAddress using a collect because it can also come from the UI.
            _currentAddress.filterNotNull().collect { address ->
                _displayAddress.value = address.toAddress(address.getCountryCode() ?: "US")
                    .toDisplayAddress()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @SuppressLint("MissingPermission")
    fun onEvent(event: AddressCompletionEvent) {
        when (event) {
            is AddressCompletionEvent.OnAddressSelected -> {
                viewModelScope.launch {
                    val place = placesRepository.getPlaceAddress(event.autocompletePlace.placeId)

                    place.addressComponents?.asList()?.toAddress()?.toDisplayAddress()?.let {
                        _displayAddress.value = it
                    }

                    _uiState.value = UiState.ADDRESS_ENTRY
                }
            }

            AddressCompletionEvent.OnNextMockLocation -> {
                mergedLocationRepository.nextMockLocation()
            }

            AddressCompletionEvent.OnToggleMap -> {
                _showMap.value = !_showMap.value
            }

            AddressCompletionEvent.OnUseSystemLocation -> {
                mergedLocationRepository.useSystemLocation()
            }

            is AddressCompletionEvent.OnAddressChanged -> {
                _displayAddress.value = event.displayAddress
            }

            is AddressCompletionEvent.OnMapClicked -> {
                mergedLocationRepository.setMockLocation(event.latLng)
            }

            AddressCompletionEvent.OnMapCloseClicked -> {
                _showMap.value = false
            }

            AddressCompletionEvent.OnNavigateUp -> reset()
        }
    }

    private fun reset() {
        _displayAddress.value = null
        _showMap.value = false
    }
}
