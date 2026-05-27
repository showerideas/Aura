package com.showerideas.aura.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarText
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template

/**
 * Android Auto contact list screen.
 *
 * Displays recently-exchanged AURA contacts in the Car App Library
 * [ListTemplate]. Contacts are passed in at construction time (read from
 * ContactDao on the phone side before the screen is pushed) to avoid
 * performing database I/O on the car-app main thread.
 *
 * Car App Library constraints:
 * - [ListTemplate] supports up to 6 rows without scrolling (CATEGORY_MAP = false).
 * - Each [Row] must have concise text (system may truncate ~25 chars on small HUs).
 * - No images in TEMPLATE_TYPE_LIST on category 0 hosts.
 */
class ContactListScreen(
    carContext: CarContext,
    private val contacts: List<AutoContact>
) : Screen(carContext) {

    /**
     * A lightweight contact model safe to pass across the Auto Session boundary.
     * Only fields needed for driving-safe display are included.
     */
    data class AutoContact(
        val displayName: String,
        val company    : String?,
        val email      : String?
    )

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()

        if (contacts.isEmpty()) {
            listBuilder.setNoItemsMessage(
                carContext.getString(R.string.auto_contacts_empty)
            )
        } else {
            contacts.take(6).forEach { contact ->
                val subtitle = when {
                    !contact.company.isNullOrBlank() -> contact.company
                    !contact.email.isNullOrBlank()   -> contact.email
                    else                             -> ""
                }
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle(contact.displayName)
                        .addText(subtitle)
                        .build()
                )
            }
        }

        return ListTemplate.Builder()
            .setTitle(carContext.getString(R.string.auto_contacts_title))
            .setHeaderAction(Action.BACK)
            .setSingleList(listBuilder.build())
            .build()
    }
}
