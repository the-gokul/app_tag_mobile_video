package com.nordic.tagmobile.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class UserProfile(
    val id: String = UUID.randomUUID().toString(),
    val ownerName: String = "",
    val petType: String = "Dog",
    val petName: String = "",
    val gender: String = "Male",
    val age: Int = 0,
    val weight: Float = 0f,
    val breed: String = ""
) {
    val isComplete: Boolean get() = ownerName.isNotBlank() && petName.isNotBlank() && breed.isNotBlank()
    val safeFileName: String get() =
        "${ownerName.replace(Regex("[^A-Za-z0-9]"), "")}_${petName.replace(Regex("[^A-Za-z0-9]"), "")}"

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("ownerName", ownerName)
            put("petType", petType)
            put("petName", petName)
            put("gender", gender)
            put("age", age)
            put("weight", weight.toDouble())
            put("breed", breed)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): UserProfile {
            return UserProfile(
                id = json.optString("id", UUID.randomUUID().toString()),
                ownerName = json.optString("ownerName", ""),
                petType = json.optString("petType", "Dog"),
                petName = json.optString("petName", ""),
                gender = json.optString("gender", "Male"),
                age = json.optInt("age", 0),
                weight = json.optDouble("weight", 0.0).toFloat(),
                breed = json.optString("breed", "")
            )
        }
    }
}

object ProfileManager {
    private const val PREF_FILE = "tag_profiles"
    private const val KEY_PROFILES = "profiles_json"

    fun loadAll(context: Context): List<UserProfile> {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(KEY_PROFILES, "[]") ?: "[]"
        val list = mutableListOf<UserProfile>()
        try {
            val array = JSONArray(jsonString)
            for (i in 0 until array.length()) {
                list.add(UserProfile.fromJson(array.getJSONObject(i)))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun saveAll(context: Context, profiles: List<UserProfile>) {
        val array = JSONArray()
        profiles.forEach { array.put(it.toJson()) }
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILES, array.toString())
            .apply()
    }
    
    fun deleteProfile(context: Context, id: String) {
        val profiles = loadAll(context).toMutableList()
        profiles.removeAll { it.id == id }
        saveAll(context, profiles)
    }

    fun addOrUpdateProfile(context: Context, profile: UserProfile) {
        val profiles = loadAll(context).toMutableList()
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            profiles[index] = profile
        } else {
            profiles.add(profile)
        }
        saveAll(context, profiles)
    }
}
