package com.nordic.tagmobile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nordic.tagmobile.databinding.ActivityProfileBinding
import com.nordic.tagmobile.model.UserProfile

class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    private var isFirstRun = false
    private val profiles = mutableListOf<UserProfile>()
    private lateinit var adapter: ProfileAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isFirstRun = intent.getBooleanExtra(EXTRA_FIRST_RUN, false)

        supportActionBar?.setDisplayHomeAsUpEnabled(!isFirstRun)
        supportActionBar?.title = if (isFirstRun) "Set up your profile" else "Profiles"

        profiles.addAll(UserProfile.loadAll(this))

        adapter = ProfileAdapter(profiles) { profile ->
            profiles.remove(profile)
            adapter.notifyDataSetChanged()
            UserProfile.saveAll(this, profiles)
            if (TagSession.userProfile.id == profile.id) {
                TagSession.userProfile = UserProfile() // clear active if deleted
            }
        }
        binding.profilesList.layoutManager = LinearLayoutManager(this)
        binding.profilesList.adapter = adapter

        binding.addProfileBtn.setOnClickListener {
            if (validateAndAddProfile()) {
                clearForm()
                adapter.notifyDataSetChanged()
                UserProfile.saveAll(this, profiles)
                Toast.makeText(this, "Profile added!", Toast.LENGTH_SHORT).show()
            }
        }

        binding.saveProfileBtn.setOnClickListener {
            // If the user filled the form but didn't click '+', save it anyway if complete
            if (binding.nameInput.text.toString().isNotBlank() || binding.dogNameInput.text.toString().isNotBlank()) {
                if (!validateAndAddProfile()) return@setOnClickListener
                UserProfile.saveAll(this, profiles)
            }
            
            if (profiles.isEmpty()) {
                Toast.makeText(this, "Please add at least one profile", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Set the first profile as default if none selected
            if (!TagSession.userProfile.isComplete) {
                TagSession.userProfile = profiles.first()
            }

            if (isFirstRun) {
                startActivity(Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
            }
            finish()
        }
    }

    private fun validateAndAddProfile(): Boolean {
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

        if (!isValid) return false

        val gender = if (isMale) "Male" else "Female"
        val newProfile = UserProfile(
            name = name,
            dogName = dogName,
            breed = breed,
            age = age,
            weight = weight,
            gender = gender
        )
        profiles.add(newProfile)
        return true
    }

    private fun clearForm() {
        binding.nameInput.text.clear()
        binding.dogNameInput.text.clear()
        binding.breedInput.text.clear()
        binding.ageInput.text.clear()
        binding.weightInput.text.clear()
        binding.genderGroup.clearCheck()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val EXTRA_FIRST_RUN = "first_run"
    }
}

class ProfileAdapter(
    private val items: List<UserProfile>,
    private val onDelete: (UserProfile) -> Unit
) : RecyclerView.Adapter<ProfileAdapter.Holder>() {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_profile, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.details.text = "Dog: ${item.dogName} | ${item.breed} | ${item.gender} | ${item.age}y | ${item.weight}kg"
        holder.deleteBtn.setOnClickListener { onDelete(item) }
    }

    override fun getItemCount() = items.size

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.profileName)
        val details: TextView = view.findViewById(R.id.profileDetails)
        val deleteBtn: ImageButton = view.findViewById(R.id.deleteProfileBtn)
    }
}
