// swift-tools-version: 5.9
// Phase 7.1 — AURA iOS Companion App scaffold
// SwiftUI + MultipeerConnectivity transport + P-256 ECDH + HKDF-SHA256 + AES-256-GCM

import PackageDescription

let package = Package(
    name: "AuraiOS",
    platforms: [.iOS(.v16)],
    products: [
        .library(name: "AuraCore", targets: ["AuraCore"]),
    ],
    targets: [
        .target(
            name: "AuraCore",
            path: "Sources/AuraCore"
        ),
        .testTarget(
            name: "AuraCoreTests",
            dependencies: ["AuraCore"],
            path: "Tests/AuraCoreTests"
        ),
    ]
)
