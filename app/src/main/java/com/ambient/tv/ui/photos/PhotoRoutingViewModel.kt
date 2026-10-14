package com.ambient.tv.ui.photos

import androidx.lifecycle.ViewModel
import com.ambient.tv.data.photos.PhotoLibrary
import com.ambient.tv.data.photos.PhotoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** Lets the library section destination switch to the photo browser for photo libraries. */
@HiltViewModel
class PhotoRoutingViewModel @Inject constructor(repository: PhotoRepository) : ViewModel() {
    val photoSections: StateFlow<Map<String, PhotoLibrary>> = repository.photoSections
}
