package com.showerideas.aura.data

import com.showerideas.aura.data.local.BlockedEndpointDao
import com.showerideas.aura.model.BlockedEndpoint
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PR-14: thin wrapper around [BlockedEndpointDao] so the service and UI
 * layers depend on a repository contract instead of a DAO directly. Keeps
 * the door open for promoting the blocklist key from endpoint ID to
 * identity-key hash in a future PR without churning every call site.
 */
@Singleton
class BlocklistRepository @Inject constructor(
    private val dao: BlockedEndpointDao
) {
    fun observeAll(): Flow<List<BlockedEndpoint>> = dao.observeAll()

    suspend fun block(endpointId: String, note: String = "") =
        dao.block(BlockedEndpoint(endpointId = endpointId, note = note))

    suspend fun unblock(endpoint: BlockedEndpoint) = dao.unblock(endpoint)

    suspend fun isBlocked(endpointId: String): Boolean = dao.isBlocked(endpointId)
}
