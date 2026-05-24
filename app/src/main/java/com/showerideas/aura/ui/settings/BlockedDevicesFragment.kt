package com.showerideas.aura.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.showerideas.aura.databinding.FragmentBlockedDevicesBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * lists every device the user has blocked via  and lets them
 * unblock individual entries.
 */
@AndroidEntryPoint
class BlockedDevicesFragment : Fragment() {

    private var _binding: FragmentBlockedDevicesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: BlockedDevicesViewModel by viewModels()
    private lateinit var adapter: BlockedDevicesAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBlockedDevicesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = BlockedDevicesAdapter { endpoint ->
            viewModel.unblock(endpoint)
        }
        binding.recyclerBlocked.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.blocked.collect { list ->
                    adapter.submitList(list)
                    binding.tvBlockedEmpty.visibility =
                        if (list.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
