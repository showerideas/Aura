package com.showerideas.aura.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A contact received via AURA exchange.
 *
 * Each contact carries a stable [id] (UUID generated at exchange time),
 * the raw device endpoint that sent it, and the decrypted profile payload.
 */
@Entity(tableName = "contacts")
data class Contact(
    @PrimaryKey val id: String,
    val displayName: String = "",
    val phone: String = "",
    val email: String = "",
    val company: String = "",
    val title: String = "",
    val website: String = "",
    val bio: String = "",
    val avatarUri: String = "",
    /** Nearby Connections endpoint ID from which this contact was received */
    val sourceEndpointId: String = "",
    /** RSSI or distance hint at time of exchange (dBm) */
    val rssiAtExchange: Int = 0,
    val receivedAt: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    val notes: String = ""
) {
    companion object {
        fun fromMap(id: String, map: Map<String, String>, endpointId: String, rssi: Int = 0): Contact =
            Contact(
                id = id,
                displayName = map["displayName"].orEmpty(),
                phone = map["phone"].orEmpty(),
                email = map["email"].orEmpty(),
                company = map["company"].orEmpty(),
                title = map["title"].orEmpty(),
                website = map["website"].orEmpty(),
                bio = map["bio"].orEmpty(),
                sourceEndpointId = endpointId,
                rssiAtExchange = rssi
            )
    }
}
