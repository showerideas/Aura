// Tests/AuraCompanionTests/CoreDataTofuStoreTests.swift
// T34 — Cross-platform TOFU test: validates CoreDataTofuStore behaviour
// mirrors the KnownPeerDao contract on Android.

import XCTest
import CoreData
@testable import AuraCore

final class CoreDataTofuStoreTests: XCTestCase {

    var store: CoreDataTofuStore!

    override func setUp() {
        super.setUp()
        store = CoreDataTofuStore(container: makeInMemoryContainer())
    }

    // -------------------------------------------------------------------------
    // First encounter
    // -------------------------------------------------------------------------

    func testFirstEncounterRegistersRecord() throws {
        let result = try store.verify(peerDisplayId: "peer-1", presentedHash: "abc123")
        if case .firstEncounter(let rec) = result {
            XCTAssertEqual(rec.peerDisplayId, "peer-1")
            XCTAssertEqual(rec.identityKeyHash, "abc123")
            XCTAssertEqual(rec.rotationCount, 0)
        } else {
            XCTFail("Expected firstEncounter, got \(result)")
        }
    }

    func testFirstEncounterPersisted() throws {
        _ = try store.verify(peerDisplayId: "peer-1", presentedHash: "abc123")
        let record = try store.record(for: "peer-1")
        XCTAssertNotNil(record)
        XCTAssertEqual(record?.identityKeyHash, "abc123")
    }

    // -------------------------------------------------------------------------
    // Verified (same key)
    // -------------------------------------------------------------------------

    func testSameKeyReturnsVerified() throws {
        _ = try store.verify(peerDisplayId: "peer-2", presentedHash: "def456")
        let result = try store.verify(peerDisplayId: "peer-2", presentedHash: "def456")
        if case .verified = result { /* pass */ } else {
            XCTFail("Expected verified, got \(result)")
        }
    }

    // -------------------------------------------------------------------------
    // Key rotation detection
    // -------------------------------------------------------------------------

    func testDifferentKeyReturnsRotation() throws {
        _ = try store.verify(peerDisplayId: "peer-3", presentedHash: "original-hash")
        let result = try store.verify(peerDisplayId: "peer-3", presentedHash: "rotated-hash")
        if case .keyRotationDetected(let stored, let presented) = result {
            XCTAssertEqual(stored,    "original-hash")
            XCTAssertEqual(presented, "rotated-hash")
        } else {
            XCTFail("Expected keyRotationDetected, got \(result)")
        }
    }

    func testUpdateKeyHashIncreasesRotationCount() throws {
        try store.registerFirst(peerDisplayId: "peer-4", identityKeyHash: "old")
        try store.updateKeyHash(peerDisplayId: "peer-4", newHash: "new")
        let rec = try store.record(for: "peer-4")
        XCTAssertEqual(rec?.identityKeyHash, "new")
        XCTAssertEqual(rec?.rotationCount,   1)
    }

    // -------------------------------------------------------------------------
    // Delete
    // -------------------------------------------------------------------------

    func testDeleteRemovesRecord() throws {
        try store.registerFirst(peerDisplayId: "peer-5", identityKeyHash: "hash5")
        try store.delete(peerDisplayId: "peer-5")
        let rec = try store.record(for: "peer-5")
        XCTAssertNil(rec, "Record should be nil after delete")
    }

    func testDeleteNonExistentDoesNotThrow() {
        XCTAssertNoThrow(try store.delete(peerDisplayId: "ghost-peer"))
    }

    // -------------------------------------------------------------------------
    // Record for unknown peer
    // -------------------------------------------------------------------------

    func testRecordForUnknownPeerReturnsNil() throws {
        let rec = try store.record(for: "unknown-peer")
        XCTAssertNil(rec)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private func makeInMemoryContainer() -> NSPersistentContainer {
        // Minimal in-memory container for TOFU tests
        let model = NSManagedObjectModel()

        let entity = NSEntityDescription()
        entity.name              = "TofuRecord"
        entity.managedObjectClassName = NSStringFromClass(NSManagedObject.self)

        let attrs: [(String, NSAttributeType)] = [
            ("peerDisplayId",   .stringAttributeType),
            ("identityKeyHash", .stringAttributeType),
            ("firstSeenAt",     .dateAttributeType),
            ("lastSeenAt",      .dateAttributeType),
            ("rotationCount",   .integer32AttributeType)
        ]
        entity.properties = attrs.map { (name, type) in
            let attr = NSAttributeDescription()
            attr.name          = name
            attr.attributeType = type
            attr.isOptional    = false
            return attr
        }
        model.entities = [entity]

        let container = NSPersistentContainer(name: "AuraTOFU", managedObjectModel: model)
        let desc = NSPersistentStoreDescription()
        desc.type = NSInMemoryStoreType
        container.persistentStoreDescriptions = [desc]
        container.loadPersistentStores { _, error in
            if let error = error { fatalError("CoreData in-memory setup failed: \(error)") }
        }
        return container
    }
}
