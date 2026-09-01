package com.campusmesh.ui.profile

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campusmesh.profile.LocalProfileState
import com.campusmesh.profile.ProfileManager
import com.campusmesh.ui.theme.AppTheme
import com.campusmesh.ui.theme.ThemeManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ProfileSetupViewModel @Inject constructor(
    private val profileManager: ProfileManager,
    private val themeManager: ThemeManager,
) : ViewModel() {

    val profile: StateFlow<LocalProfileState> = profileManager.localProfile
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = profileManager.localProfile.value,
        )

    val currentTheme: StateFlow<AppTheme> = themeManager.currentTheme
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = themeManager.currentTheme.value,
        )

    fun updateName(displayName: String) {
        profileManager.updateProfile(displayName = displayName)
    }

    fun updateProfileImage(uri: Uri) {
        profileManager.updateProfileImage(uri)
    }

    fun setTheme(theme: AppTheme) {
        themeManager.setTheme(theme)
    }
}
