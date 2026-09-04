package com.nordic.tagmobile

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nordic.tagmobile.databinding.ActivityProfileBinding
import com.nordic.tagmobile.model.UserProfile

/**
 * Profile setup screen.
 * Shown on first launch (no back button).
 * Also accessible from the home screen toolbar for editing.
 */
class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    private var isFirstRun = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isFirstRun = intent.getBooleanExtra(EXTRA_FIRST_RUN, false)

        // Hide back button on first run — user must fill profile
        supportActionBar?.setDisplayHomeAsUpEnabled(!isFirstRun)
        if (isFirstRun) {
            supportActionBar?.title = "Welcome — Set up your profile"
        } else {
            supportActionBar?.title = "Edit Profile"
        }

        val existing = UserProfile.load(this)
        binding.nameInput.setText(existing.name)
        binding.dogNameInput.setText(existing.dogName)

        binding.saveProfileBtn.setOnClickListener { saveProfile() }
    }

    private fun saveProfile() {
        val name = binding.nameInput.text.toString().trim()
        val dogName = binding.dogNameInput.text.toString().trim()

        if (name.isBlank()) {
            binding.nameInput.error = "Please enter your name"
            return
        }
        if (dogName.isBlank()) {
            binding.dogNameInput.error = "Please enter your dog's name"
            return
        }

        val profile = UserProfile(name = name, dogName = dogName)
        UserProfile.save(this, profile)
        TagSession.userProfile = profile

        Toast.makeText(this, "Profile saved!", Toast.LENGTH_SHORT).show()

        if (isFirstRun) {
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
        }
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val EXTRA_FIRST_RUN = "first_run"
    }
}
