package com.nordic.tagmobile

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nordic.tagmobile.databinding.ActivityAddProfileBinding
import com.nordic.tagmobile.model.UserProfile

class AddProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddProfileBinding
    private var isFirstRun = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isFirstRun = intent.getBooleanExtra(EXTRA_FIRST_RUN, false)
        binding.backBtn.setOnClickListener { finish() }

        binding.addProfileBtn.setOnClickListener { saveProfile() }
        binding.saveProfileBtn.setOnClickListener { saveProfile() }
    }

    private fun saveProfile() {
        val name = binding.nameInput.text.toString().trim()
        val dogName = binding.dogNameInput.text.toString().trim()
        val breed = binding.breedInput.text.toString().trim()
        val age = binding.ageInput.text.toString().trim()
        val weight = binding.weightInput.text.toString().trim()
        val isMale = binding.radioMale.isChecked
        val isFemale = binding.radioFemale.isChecked

        var isValid = true
        if (name.isBlank()) { binding.nameInput.error = "Required"; isValid = false }
        if (dogName.isBlank()) { binding.dogNameInput.error = "Required"; isValid = false }
        if (breed.isBlank()) { binding.breedInput.error = "Required"; isValid = false }
        if (age.isBlank()) { binding.ageInput.error = "Required"; isValid = false }
        if (weight.isBlank()) { binding.weightInput.error = "Required"; isValid = false }
        if (!isMale && !isFemale) {
            Toast.makeText(this, "Please select gender", Toast.LENGTH_SHORT).show()
            isValid = false
        }
        if (!isValid) return

        val profiles = UserProfile.loadAll(this).toMutableList()
        val newProfile = UserProfile(
            name = name,
            dogName = dogName,
            breed = breed,
            age = age,
            weight = weight,
            gender = if (isMale) "Male" else "Female",
        )
        profiles.add(newProfile)
        UserProfile.saveAll(this, profiles)
        if (!TagSession.userProfile.isComplete || isFirstRun) {
            TagSession.userProfile = newProfile
        }
        Toast.makeText(this, "Profile added!", Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        const val EXTRA_FIRST_RUN = "first_run"
    }
}
