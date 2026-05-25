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
import androidx.navigation.fragment.findNavController
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

        // "Export all" toolbar action.
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
                    R.id.action_exchange_history -> {
                        findNavController().navigate(R.id.action_contacts_to_audit)
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
