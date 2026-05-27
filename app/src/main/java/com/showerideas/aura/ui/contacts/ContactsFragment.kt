package com.showerideas.aura.ui.contacts

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.MenuProvider
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.tabs.TabLayout
import com.showerideas.aura.R
import com.showerideas.aura.databinding.FragmentContactsBinding
import com.showerideas.aura.utils.shareVCard
import com.showerideas.aura.utils.toVCardBundle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ContactsFragment : Fragment() {

    private var _binding: FragmentContactsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ContactsViewModel by viewModels()
    private lateinit var adapter: ContactsAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentContactsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // show pre-filled sheet when launched from a deeplink.
        @Suppress("UNCHECKED_CAST")
        val deeplinkFields = arguments
            ?.getSerializable(DeeplinkContactSheet.KEY_DEEPLINK_FIELDS) as? HashMap<String, String>
        if (!deeplinkFields.isNullOrEmpty() && savedInstanceState == null) {
            DeeplinkContactSheet.newInstance(deeplinkFields)
                .show(childFragmentManager, DeeplinkContactSheet.TAG)
            // Clear args so the sheet isn't shown again on config change.
            arguments?.remove(DeeplinkContactSheet.KEY_DEEPLINK_FIELDS)
        }

        // Tab strip — My Contacts (0) / History (1)
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_my_contacts))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_history))
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> showContactsTab()
                    1 -> showHistoryTab()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
            override fun onTabReselected(tab: TabLayout.Tab?) = Unit
        })

        // "Export all" toolbar action (History menu item removed — it's now a tab).
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.contacts_menu, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_export_all -> {
                        val contacts = viewModel.contacts.value
                        if (contacts.isEmpty()) {
                            Toast.makeText(
                                requireContext(),
                                R.string.export_all_empty,
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            requireContext().shareVCard(
                                contacts.toVCardBundle(),
                                "aura_contacts.vcf"
                            )
                        }
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)

        adapter = ContactsAdapter { contact ->
            ContactDetailBottomSheet.newInstance(contact.id)
                .show(childFragmentManager, "contact_detail")
        }
        binding.recyclerView.adapter = adapter

        binding.searchField.addTextChangedListener { text ->
            viewModel.setSearchQuery(text?.toString() ?: "")
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.contacts.collect { contacts ->
                    adapter.submitList(contacts)
                    binding.tvEmpty.visibility = if (contacts.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }

        // favourites filter chip.
        binding.chipFavourites.setOnClickListener { viewModel.toggleFavouritesFilter() }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.showFavouritesOnly.collect { isOn ->
                    if (binding.chipFavourites.isChecked != isOn) {
                        binding.chipFavourites.isChecked = isOn
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Tab helpers
    // ─────────────────────────────────────────────────────────────────

    private fun showContactsTab() {
        binding.contactsContent.visibility = View.VISIBLE
        binding.historyContainer.visibility = View.GONE
    }

    private fun showHistoryTab() {
        binding.contactsContent.visibility = View.GONE
        binding.historyContainer.visibility = View.VISIBLE
        // Inflate ExchangeHistoryFragment into the container lazily on first selection.
        if (childFragmentManager.findFragmentById(R.id.history_container) == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.history_container, ExchangeHistoryFragment())
                .commit()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
