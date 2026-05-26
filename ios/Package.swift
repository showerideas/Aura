// swift-tools-version: 5.9
// Phase 7.1 — AURA iOS Companion App scaffold.
//
// Build system: Swift Package Manager (avoids Xcode project file complexity).
// Target: iOS 17+ (async/await, SwiftUI, MultipeerConnectivity, CoreBluetooth).
//
// Run unit tests:  swift test
// Open in Xcode:   open Package.swift

import PackageDescription

let package = Package(
    name: "AuraCompanion",
    platforms: [
        .iOS(.v17),
        .macOS(.v14),   // for running SPM tests on macOS CI
    ],
    products: [
        .library(
            name: "AuraCompanion",
            targets: ["AuraCompanion"]
        ),
    ],
    dependencies: [
        // CryptoKit is a system framework on iOS 13+ — no SPM dep needed.
        // MultipeerConnectivity is also system — included below via target linking.
    ],
    targets: [
        .target(
            name: "AuraCompanion",
            path: "Sources/AuraCompanion",
            linkerSettings: [
                // MultipeerConnectivity for Nearby-style peer discovery
                .linkedFramework("MultipeerConnectivity"),
                .linkedFramework("CoreBluetooth"),
                .linkedFramework("CryptoKit"),
            ]
        ),
        .testTarget(
            name: "AuraCompanionTests",
            dependencies: ["AuraCompanion"],
            path: "Tests/AuraCompanionTests"
        ),
    ]
)
