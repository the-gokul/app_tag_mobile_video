package com.nordic.tagmobile.model

import android.content.Context

data class UserProfile(
    val name: String = "",
    val dogName: String = "",
) {
    val isComplete: Boolean get() = name.isNotBlank() && dogName.isNotBlank()
    val safeFileName: String get() =
        "${name.replace(Regex("[^A-Za-z0-9]"), "")}_${dogName.replace(Regex("[^A-Za-z0-9]"), "")}"

    companion object {
        private const val PREF_FILE = "tag_profile"
        private const val KEY_NAME = "profile_name"
        private const val KEY_DOG = "profile_dog"

        fun load(context: Context): UserProfile {
            val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            return UserProfile(
                name = prefs.getString(KEY_NAME, "") ?: "",
                dogName = prefs.getString(KEY_DOG, "") ?: "",
            )
        }

        fun save(context: Context, profile: UserProfile) {
            context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_NAME, profile.name)
                .putString(KEY_DOG, profile.dogName)
                .apply()
        }
    }
}
