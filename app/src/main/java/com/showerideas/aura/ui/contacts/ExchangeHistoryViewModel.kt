package com.showerideas.aura.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.showerideas.aura.data.ContactRepository
import com.showerideas.aura.data.ExchangeAuditRepository
import com.showerideas.aura.model.Contact
import com.showerideas.aura.model.ExchangeAuditEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Pairs each successful [ExchangeAuditEntry] with its corresponding [Contact]
 * (matched via [ExchangeAuditEntry.peerIdentityKeyHash] == [Contact.identityKeyHash]).
 *
 * Contact may be null if the user has since deleted it from their AURA contact list.
 */
data class ExchangeHistoryItem(
    val entry: ExchangeAuditEntry,
    /** Null when the contact was deleted after the exchange. */
    val contact: Contact?
)

@HiltViewModel
class ExchangeHistoryViewModel @Inject constructor(
    auditRepository: ExchangeAuditRepository,
    contactRepository: ContactRepository
) : ViewModel() {

    val historyItems: StateFlow<List<ExchangeHistoryItem>> =
        combine(
            auditRepository.allEntries,
            contactRepository.allContacts
        ) { entries, contacts ->
            val contactByHash = contacts
                .filter { !it.identityKeyHash.isNullOrBlank() }
                .associateBy { it.identityKeyHash!! }

            entries
                .filter { it.outcome == ExchangeAuditEntry.OUTCOME_SUCCESS }
                .map { entry ->
                    ExchangeHistoryItem(
                        entry   = entry,
                        contact = entry.peerIdentityKeyHash?.let { contactByHash[it] }
                    )
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
