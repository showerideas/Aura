// Sources/AuraCompanion/AuraExchangeCoordinator.swift
// Phase E1 — High-level exchange state machine for the iOS companion.
//
// Orchestrates: MultipeerTransport → AuraCrypto ECDH → SasVerifier → profile
// encrypt/decrypt. Drives the SwiftUI view layer via published @Published state.

import Foundation
import CryptoKit
import MultipeerConnectivity
import AuraCore

// ─────────────────────────────────────────────────────────────────────────────
// MARK: — Exchange state
// ─────────────────────────────────────────────────────────────────────────────

/// Observable exchange state machine for SwiftUI binding.
@MainActor
public final class AuraExchangeCoordinator: ObservableObject {

    // ── Published state ───────────────────────────────────────────────────────

    @Published public private(set) var phase: ExchangePhase = .idle
    @Published public private(set) var sasCode: String = ""
    @Published public private(set) var receivedProfile: ContactProfile?
    @Published public private(set) var errorMessage: String?

    // ── Private state ─────────────────────────────────────────────────────────

    private let transport     : MultipeerTransport
    private let localProfile  : ContactProfile
    private var localKeyPair  : P256.KeyAgreement.PrivateKey?
    private var sessionKey    : SymmetricKey?
    private var remotePeerID  : MCPeerID?

    // ── Init ──────────────────────────────────────────────────────────────────

    public init(localProfile: ContactProfile, displayName: String) {
        self.localProfile = localProfile
        self.transport    = MultipeerTransport(displayName: displayName)
        self.transport.delegate = nil   // set below — avoid capturing self during init
        self.transport.delegate = WeakDelegate(coordinator: self)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MARK: — Public control
    // ─────────────────────────────────────────────────────────────────────────

    /// Begin advertising and browsing. Call from SwiftUI onAppear.
    public func startExchange() {
        guard phase == .idle else { return }
        localKeyPair = AuraCrypto.generateEphemeralKeyPair()
        transition(to: .advertising)
        transport.startAdvertising()
        transport.startBrowsing()
    }

    /// Abort an in-progress exchange and return to idle.
    public func cancel() {
        transport.disconnect()
        reset()
    }

    /// Called by the user after visually comparing SAS codes.
    public func confirmSasMatch() {
        guard phase == .sasVerification, let peer = remotePeerID else { return }
        sendSasConfirmation(confirmed: true, to: peer)
        transition(to: .exchangingProfile)
        sendProfile(to: peer)
    }

    /// Called by the user when SAS codes differ (possible MITM).
    public func rejectSasMismatch() {
        guard let peer = remotePeerID else { return }
        sendSasConfirmation(confirmed: false, to: peer)
        transition(to: .failed(reason: "SAS mismatch — exchange aborted"))
        transport.disconnect()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MARK: — Frame handling (called from transport delegate)
    // ─────────────────────────────────────────────────────────────────────────

    fileprivate func didReceiveFrame(_ data: Data, from peer: MCPeerID) {
        guard let frameType = FrameType(rawValue: data.first ?? 0xFF) else { return }
        let payload = data.dropFirst()

        switch frameType {
        case .keyExchange:
            handleKeyExchangeFrame(payload, from: peer)
        case .sasConfirmation:
            handleSasConfirmation(payload, from: peer)
        case .encryptedProfile:
            handleEncryptedProfile(payload, from: peer)
        default:
            break
        }
    }

    fileprivate func peerDidConnect(_ peer: MCPeerID) {
        remotePeerID = peer
        transition(to: .keyExchange)
        sendKeyExchangeFrame(to: peer)
    }

    fileprivate func peerDidDisconnect(_ peer: MCPeerID) {
        if case .complete = phase { return }   // normal disconnect after success
        if case .failed = phase   { return }
        transition(to: .failed(reason: "Peer disconnected unexpectedly"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MARK: — Key exchange
    // ─────────────────────────────────────────────────────────────────────────

    private func sendKeyExchangeFrame(to peer: MCPeerID) {
        guard let localKey = localKeyPair else { return }
        let pubKeyData = localKey.publicKey.x963Representation   // 65-byte uncompressed
        var frame = Data([FrameType.keyExchange.rawValue])
        frame.append(pubKeyData)
        try? transport.send(frame, to: peer)
    }

    private func handleKeyExchangeFrame(_ payload: Data, from peer: MCPeerID) {
        guard let localKey = localKeyPair,
              payload.count == 65,
              let remotePubKey = try? P256.KeyAgreement.PublicKey(x963Representation: payload)
        else {
            transition(to: .failed(reason: "Invalid key exchange frame"))
            return
        }

        // Derive session key: ECDH + HKDF-SHA256
        guard let sharedSecret = try? localKey.sharedSecretFromKeyAgreement(with: remotePubKey)
        else {
            transition(to: .failed(reason: "ECDH failed"))
            return
        }

        let key = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data(),
            sharedInfo: WireProtocol.info,
            outputByteCount: 32
        )
        sessionKey   = key
        sasCode      = SasVerifier.code(from: key)
        transition(to: .sasVerification)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MARK: — SAS confirmation frame
    // ─────────────────────────────────────────────────────────────────────────

    private func sendSasConfirmation(confirmed: Bool, to peer: MCPeerID) {
        var frame = Data([FrameType.sasConfirmation.rawValue, confirmed ? 0x01 : 0x00])
        try? transport.send(frame, to: peer)
    }

    private func handleSasConfirmation(_ payload: Data, from peer: MCPeerID) {
        let confirmed = payload.first == 0x01
        if !confirmed {
            transition(to: .failed(reason: "Remote rejected SAS — exchange aborted"))
            transport.disconnect()
        }
        // If confirmed by remote, wait for profile or send own profile
        // (both sides send profiles; first to arrive wins for display)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MARK: — Profile exchange
    // ─────────────────────────────────────────────────────────────────────────

    private func sendProfile(to peer: MCPeerID) {
        guard let key = sessionKey,
              let profileJSON = try? localProfile.toJSON()
        else { return }

        guard let ciphertext = try? AuraCrypto.encrypt(key: key, plaintext: profileJSON)
        else { return }

        var frame = Data([FrameType.encryptedProfile.rawValue])
        frame.append(ciphertext)
        try? transport.send(frame, to: peer)
    }

    private func handleEncryptedProfile(_ payload: Data, from peer: MCPeerID) {
        guard let key = sessionKey,
              let plaintext = try? AuraCrypto.decrypt(key: key, ciphertext: payload),
              let profile = try? ContactProfile.fromJSON(plaintext)
        else {
            transition(to: .failed(reason: "Profile decryption failed"))
            return
        }

        receivedProfile = profile
        transition(to: .complete)
        transport.disconnect()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MARK: — Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private func transition(to newPhase: ExchangePhase) {
        phase = newPhase
    }

    private func reset() {
        phase          = .idle
        sasCode        = ""
        receivedProfile = nil
        errorMessage   = nil
        localKeyPair   = nil
        sessionKey     = nil
        remotePeerID   = nil
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: — ExchangePhase
// ─────────────────────────────────────────────────────────────────────────────

public enum ExchangePhase: Equatable {
    case idle
    case advertising
    case keyExchange
    case sasVerification
    case exchangingProfile
    case complete
    case failed(reason: String)

    public static func == (lhs: ExchangePhase, rhs: ExchangePhase) -> Bool {
        switch (lhs, rhs) {
        case (.idle, .idle), (.advertising, .advertising),
             (.keyExchange, .keyExchange), (.sasVerification, .sasVerification),
             (.exchangingProfile, .exchangingProfile), (.complete, .complete):
            return true
        case (.failed(let a), .failed(let b)):
            return a == b
        default:
            return false
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: — Weak delegate adapter (breaks retain cycle)
// ─────────────────────────────────────────────────────────────────────────────

private final class WeakDelegate: TransportDelegate {
    weak var coordinator: AuraExchangeCoordinator?
    init(coordinator: AuraExchangeCoordinator) { self.coordinator = coordinator }

    func transport(_ t: MultipeerTransport, didReceiveFrame data: Data, from peer: MCPeerID) {
        Task { @MainActor in coordinator?.didReceiveFrame(data, from: peer) }
    }
    func transport(_ t: MultipeerTransport, peerDidConnect peer: MCPeerID) {
        Task { @MainActor in coordinator?.peerDidConnect(peer) }
    }
    func transport(_ t: MultipeerTransport, peerDidDisconnect peer: MCPeerID) {
        Task { @MainActor in coordinator?.peerDidDisconnect(peer) }
    }
}
