package com.example.nightlibrary.adapter

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.nightlibrary.VaultListFragment

class VaultPagerAdapter(fragmentActivity: FragmentActivity) :
    FragmentStateAdapter(fragmentActivity) {

    override fun getItemCount(): Int = 2 // "In Progress" and "Completed"

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> VaultListFragment.newInstance(isCompleted = false) // In Progress
            1 -> VaultListFragment.newInstance(isCompleted = true)  // Completed
            else -> throw IllegalStateException("Invalid position $position")
        }
    }
}