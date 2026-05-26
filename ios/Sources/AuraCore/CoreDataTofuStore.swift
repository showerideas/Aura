// Sources/AuraCore/CoreDataTofuStore.swift
// T34 — iOS companion: CoreData-backed TOFU (Trust On First Use) store.
//
// Mirrors KnownPeerDao + KnownPeer entity on Android. Persists the first-seen
// identity public key hash for each Nearby/MultipeerConnectivity peer ID so
// AURA can detect key rotation attacks on subsequent encounters.
//
// ## TOFU protocol
// 1. First exchange: store (peerDisplayId → identityKeyHash, firstSeenAt).
// 2. Subsequent exchanges: if stored hash ≠ presented hash → KEY_ROTATION warning.
// 3. User acknowledges rotation → update hash; refuse → abort exchange.

import Foundation
import CoreData

/// Manages the CoreData persistence for the AURA TOFU identity registry.
///
/// The underlying CoreData model (`AuraTOFU.xcdatamodeld`) contains one
/// entity `TofuRecord` with attributes:
///   - `peerDisplayId` (String, unique)
///   - `identityKeyHash` (String)
///   - `firstSeenAt` (Date)
///   - `lastSeenAt` (Date)
///   - `rotationCount` (Int32)
public final class CoreDataTofuStore {

    // -------------------------------------------------------------------------
    // CoreData stack (in-memory for tests; on-disk for production)
    // -------------------------------------------------------------------------

    private let container: NSPersistentContainer

    /// Initialise with a shared `NSPersistentContainer`.
    /// Inject an in-memory container in tests.
    public init(container: NSPersistentContainer) {
        self.container = container
    }

    // -------------------------------------------------------------------------
    // TOFU record model
    // -------------------------------------------------------------------------

    public struct TofuRecord: Equatable {
        public let peerDisplayId  : String
        public var identityKeyHash: String
        public let firstSeenAt    : Date
        public var lastSeenAt     : Date
        public var rotationCount  : Int
    }

    // -------------------------------------------------------------------------
    // CRUD operations
    // -------------------------------------------------------------------------

    /// Look up a stored TOFU record for [peerDisplayId]. Returns nil if unknown.
    public func record(for peerDisplayId: String) throws -> TofuRecord? {
        let ctx = container.viewContext
        let req = NSFetchRequest<NSManagedObject>(entityName: "TofuRecord")
        req.predicate = NSPredicate(format: "peerDisplayId == %@", peerDisplayId)
        req.fetchLimit = 1
        let results = try ctx.fetch(req)
        guard let obj = results.first else { return nil }
        return tofuRecord(from: obj)
    }

    /// Register a new peer for the first time.
    /// - Returns: the created record.
    /// - Throws: if [peerDisplayId] is already registered (use [updateKeyHash] instead).
    @discardableResult
    public func registerFirst(peerDisplayId: String, identityKeyHash: String) throws -> TofuRecord {
        let ctx = container.viewContext
        let entity = NSEntityDescription.insertNewObject(forEntityName: "TofuRecord", into: ctx)
        let now = Date()
        entity.setValue(peerDisplayId,   forKey: "peerDisplayId")
        entity.setValue(identityKeyHash, forKey: "identityKeyHash")
        entity.setValue(now,             forKey: "firstSeenAt")
        entity.setValue(now,             forKey: "lastSeenAt")
        entity.setValue(Int32(0),        forKey: "rotationCount")
        try ctx.save()
        return TofuRecord(peerDisplayId: peerDisplayId, identityKeyHash: identityKeyHash,
                          firstSeenAt: now, lastSeenAt: now, rotationCount: 0)
    }

    /// Update the stored key hash on confirmed user-acknowledged key rotation.
    /// Increments [rotationCount] and updates [lastSeenAt].
    public func updateKeyHash(peerDisplayId: String, newHash: String) throws {
        let ctx = container.viewContext
        let req = NSFetchRequest<NSManagedObject>(entityName: "TofuRecord")
        req.predicate = NSPredicate(format: "peerDisplayId == %@", peerDisplayId)
        req.fetchLimit = 1
        guard let obj = try ctx.fetch(req).first else {
            throw TofuError.peerNotFound(peerDisplayId)
        }
        let existing = (obj.value(forKey: "rotationCount") as? Int32) ?? 0
        obj.setValue(newHash,          forKey: "identityKeyHash")
        obj.setValue(Date(),           forKey: "lastSeenAt")
        obj.setValue(existing + 1,     forKey: "rotationCount")
        try ctx.save()
    }

    /// Update [lastSeenAt] for a peer whose key has not changed.
    public func touch(peerDisplayId: String) throws {
        let ctx = container.viewContext
        let req = NSFetchRequest<NSManagedObject>(entityName: "TofuRecord")
        req.predicate = NSPredicate(format: "peerDisplayId == %@", peerDisplayId)
        req.fetchLimit = 1
        guard let obj = try ctx.fetch(req).first else { return }
        obj.setValue(Date(), forKey: "lastSeenAt")
        try ctx.save()
    }

    /// Delete a TOFU record (e.g. user removes a contact).
    public func delete(peerDisplayId: String) throws {
        let ctx = container.viewContext
        let req = NSFetchRequest<NSManagedObject>(entityName: "TofuRecord")
        req.predicate = NSPredicate(format: "peerDisplayId == %@", peerDisplayId)
        for obj in try ctx.fetch(req) { ctx.delete(obj) }
        try ctx.save()
    }

    // -------------------------------------------------------------------------
    // TOFU verification
    // -------------------------------------------------------------------------

    public enum VerificationResult {
        /// First time seeing this peer — record stored.
        case firstEncounter(TofuRecord)
        /// Key matches stored record — all good.
        case verified
        /// Key does not match stored record — rotation detected.
        case keyRotationDetected(stored: String, presented: String)
    }

    /// Verify [presentedHash] against the TOFU store for [peerDisplayId].
    /// Auto-registers new peers; returns [.keyRotationDetected] for mismatches.
    public func verify(peerDisplayId: String, presentedHash: String) throws -> VerificationResult {
        if let existing = try record(for: peerDisplayId) {
            if existing.identityKeyHash == presentedHash {
                try touch(peerDisplayId: peerDisplayId)
                return .verified
            } else {
                return .keyRotationDetected(stored: existing.identityKeyHash, presented: presentedHash)
            }
        } else {
            let record = try registerFirst(peerDisplayId: peerDisplayId, identityKeyHash: presentedHash)
            return .firstEncounter(record)
        }
    }

    // -------------------------------------------------------------------------
    // Error types
    // -------------------------------------------------------------------------

    public enum TofuError: Error, LocalizedError {
        case peerNotFound(String)
        public var errorDescription: String? {
            switch self {
            case .peerNotFound(let id): return "TOFU record not found for peer '\(id)'"
            }
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private func tofuRecord(from obj: NSManagedObject) -> TofuRecord? {
        guard
            let peerId  = obj.value(forKey: "peerDisplayId")   as? String,
            let hash    = obj.value(forKey: "identityKeyHash")  as? String,
            let first   = obj.value(forKey: "firstSeenAt")      as? Date,
            let last    = obj.value(forKey: "lastSeenAt")       as? Date,
            let rotations = obj.value(forKey: "rotationCount")  as? Int32
        else { return nil }
        return TofuRecord(peerDisplayId: peerId, identityKeyHash: hash,
                          firstSeenAt: first, lastSeenAt: last, rotationCount: Int(rotations))
    }
}
