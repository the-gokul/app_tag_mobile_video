package com.nordic.tagmobile

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nordic.tagmobile.databinding.ActivityLoginBinding
import com.nordic.tagmobile.model.AppUser
import com.nordic.tagmobile.model.UserProfile

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private var isEditMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isEditMode = intent.getBooleanExtra(EXTRA_EDIT, false)
        val existing = AppUser.load(this)

        if (isEditMode) {
            binding.backBtn.visibility = View.VISIBLE
            binding.backBtn.setOnClickListener { finish() }
            binding.toolbarTitle.setText(R.string.edit_user)
            binding.continueBtn.setText(R.string.save_profile)
            binding.nameInput.setText(existing.name)
            binding.phoneInput.setText(existing.phone)
        } else {
            binding.backBtn.visibility = View.GONE
            // Prefill name from legacy pet owner field if upgrading.
            if (!existing.isComplete) {
                val legacyName = UserProfile.loadAll(this).firstOrNull()?.name.orEmpty()
                if (legacyName.isNotBlank()) binding.nameInput.setText(legacyName)
            } else {
                binding.nameInput.setText(existing.name)
                binding.phoneInput.setText(existing.phone)
            }
        }

        binding.continueBtn.setOnClickListener { saveAndContinue(existing.id) }
    }

    private fun saveAndContinue(existingId: String) {
        val name = binding.nameInput.text.toString().trim()
        val phone = binding.phoneInput.text.toString().trim()
        var ok = true
        if (name.isBlank()) {
            binding.nameInput.error = getString(R.string.required)
            ok = false
        }
        if (phone.isBlank()) {
            binding.phoneInput.error = getString(R.string.required)
            ok = false
        } else if (phone.filter { it.isDigit() }.length < 8) {
            binding.phoneInput.error = getString(R.string.phone_too_short)
            ok = false
        }
        if (!ok) return

        val user = AppUser(
            id = existingId.ifBlank { java.util.UUID.randomUUID().toString() },
            name = name,
            phone = phone,
        )
        AppUser.save(this, user)
        TagSession.appUser = user

        if (isEditMode) {
            Toast.makeText(this, R.string.user_saved, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // First install: login → pet profiles → main
        val pets = UserProfile.loadAll(this)
        if (pets.isEmpty()) {
            startActivity(
                Intent(this, ProfileActivity::class.java).apply {
                    putExtra(ProfileActivity.EXTRA_FIRST_RUN, true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                },
            )
        } else {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                },
            )
        }
        finish()
    }

    companion object {
        const val EXTRA_EDIT = "edit_user"
    }
}
