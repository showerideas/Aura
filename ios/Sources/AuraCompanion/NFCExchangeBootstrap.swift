// Sources/AuraCompanion/NFCExchangeBootstrap.swift
// T34 — iOS companion: NFC bootstrap for AURA key exchange.
//
// Uses CoreNFC NFCTagReaderSession (ISO 7816 APDU) to read the ephemeral
// EC public key broadcast by the Android peer's HCE service (AuraHceService).
//
// ## Protocol (mirrors NfcExchangeHelper on Android)
// 1. The Android device opens HCE and responds to SELECT + READ APDUs with
//    its ephemeral X25519 public key (32 bytes) in the response data field.
// 2. This class reads that key and hands it to AuraExchangeCoordinator for
//    ECDH / HybridKEM key agreement, after which the session continues over
//    MultipeerConnectivity (Nearby equivalent on iOS).
//
// ## AID
// AURA uses AID A0 00 00 06 17 00 01 — registered as a private/test AID.
// Must match AID_AURA_EXCHANGE in AuraHceService.kt.

import Foundation
import CoreNFC

@available(iOS 13.0, *)
public protocol NFCExchangeBootstrapDelegate: AnyObject {
    /// Called when the remote X25519 public key is successfully read from the NFC tag.
    func nfcBootstrap(_ bootstrap: NFCExchangeBootstrap, didReadPeerPublicKey key: Data)
    /// Called when NFC reading fails or is cancelled.
    func nfcBootstrap(_ bootstrap: NFCExchangeBootstrap, didFailWithError error: Error)
}

/// Manages a single NFC read session to bootstrap an AURA key exchange.
///
/// Usage:
/// ```swift
/// let bootstrap = NFCExchangeBootstrap()
/// bootstrap.delegate = coordinator
/// bootstrap.beginReading()
/// ```
@available(iOS 13.0, *)
public final class NFCExchangeBootstrap: NSObject {

    // AURA proprietary AID: A0 00 00 06 17 00 01
    private static let AURA_AID: [UInt8] = [0xA0, 0x00, 0x00, 0x06, 0x17, 0x00, 0x01]

    public weak var delegate: NFCExchangeBootstrapDelegate?

    private var readerSession: NFCTagReaderSession?

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /// Start an NFC reader session. The user will see the system NFC sheet.
    /// Results arrive asynchronously via [NFCExchangeBootstrapDelegate].
    public func beginReading() {
        guard NFCTagReaderSession.readingAvailable else {
            let err = NSError(domain: "AuraNFC", code: -1,
                userInfo: [NSLocalizedDescriptionKey: "NFC not available on this device"])
            delegate?.nfcBootstrap(self, didFailWithError: err)
            return
        }
        readerSession = NFCTagReaderSession(pollingOption: .iso14443, delegate: self, queue: .main)
        readerSession?.alertMessage = NSLocalizedString("nfc_hold_near_device", comment: "")
        readerSession?.begin()
    }

    /// Cancel the reader session programmatically (e.g. on view dismiss).
    public func cancelReading() {
        readerSession?.invalidate()
        readerSession = nil
    }

    // -------------------------------------------------------------------------
    // APDU helpers
    // -------------------------------------------------------------------------

    /// SELECT AID APDU — selects the AURA HCE application on the remote device.
    private func selectAidApdu() -> NFCISO7816APDU {
        var data = [UInt8]([0x00, 0xA4, 0x04, 0x00])  // CLA=00, INS=A4 (SELECT), P1=04 (by name), P2=00
        data.append(UInt8(Self.AURA_AID.count))
        data.append(contentsOf: Self.AURA_AID)
        data.append(0x00)  // Le = 0 (any length response)
        return NFCISO7816APDU(data: Data(data))!
    }

    /// READ PUBLIC KEY APDU — custom INS 0xB0 reads the peer's 32-byte X25519 public key.
    private func readPublicKeyApdu() -> NFCISO7816APDU {
        // INS=0xB0, P1=0x00, P2=0x00, Le=0x20 (32 bytes expected)
        let bytes: [UInt8] = [0x00, 0xB0, 0x00, 0x00, 0x20]
        return NFCISO7816APDU(data: Data(bytes))!
    }
}

// MARK: — NFCTagReaderSessionDelegate

@available(iOS 13.0, *)
extension NFCExchangeBootstrap: NFCTagReaderSessionDelegate {

    public func tagReaderSessionDidBecomeActive(_ session: NFCTagReaderSession) {
        // Session is live — nothing to do here; wait for tag detection.
    }

    public func tagReaderSession(_ session: NFCTagReaderSession, didInvalidateWithError error: Error) {
        guard (error as? NFCReaderError)?.code != .readerSessionInvalidationErrorUserCanceled else {
            return  // user cancel is not an error
        }
        delegate?.nfcBootstrap(self, didFailWithError: error)
    }

    public func tagReaderSession(_ session: NFCTagReaderSession, didDetect tags: [NFCTag]) {
        guard let tag = tags.first else { return }

        // Only handle ISO 7816 tags
        guard case .iso7816(let iso7816Tag) = tag else {
            session.invalidate(errorMessage: NSLocalizedString("nfc_unsupported_tag", comment: ""))
            return
        }

        session.connect(to: tag) { [weak self] error in
            guard let self = self else { return }
            if let error = error {
                session.invalidate(errorMessage: error.localizedDescription)
                self.delegate?.nfcBootstrap(self, didFailWithError: error)
                return
            }
            self.performAidSelect(session: session, tag: iso7816Tag)
        }
    }

    private func performAidSelect(session: NFCTagReaderSession, tag: NFCISO7816Tag) {
        tag.sendCommand(apdu: selectAidApdu()) { [weak self] responseData, sw1, sw2, error in
            guard let self = self else { return }
            if let error = error {
                session.invalidate(errorMessage: error.localizedDescription)
                self.delegate?.nfcBootstrap(self, didFailWithError: error)
                return
            }
            guard sw1 == 0x90 && sw2 == 0x00 else {
                let err = NSError(domain: "AuraNFC", code: Int(sw1) << 8 | Int(sw2),
                    userInfo: [NSLocalizedDescriptionKey: "AID SELECT failed SW \(sw1) \(sw2)"])
                session.invalidate(errorMessage: err.localizedDescription)
                self.delegate?.nfcBootstrap(self, didFailWithError: err)
                return
            }
            // AID selected — read the public key
            self.performReadPublicKey(session: session, tag: tag)
        }
    }

    private func performReadPublicKey(session: NFCTagReaderSession, tag: NFCISO7816Tag) {
        tag.sendCommand(apdu: readPublicKeyApdu()) { [weak self] responseData, sw1, sw2, error in
            guard let self = self else { return }
            if let error = error {
                session.invalidate(errorMessage: error.localizedDescription)
                self.delegate?.nfcBootstrap(self, didFailWithError: error)
                return
            }
            guard sw1 == 0x90 && sw2 == 0x00, responseData.count == 32 else {
                let msg = "Unexpected response: SW \(sw1) \(sw2) len \(responseData.count)"
                let err = NSError(domain: "AuraNFC", code: -2,
                    userInfo: [NSLocalizedDescriptionKey: msg])
                session.invalidate(errorMessage: err.localizedDescription)
                self.delegate?.nfcBootstrap(self, didFailWithError: err)
                return
            }
            session.alertMessage = NSLocalizedString("nfc_key_read_success", comment: "")
            session.invalidate()
            self.delegate?.nfcBootstrap(self, didReadPeerPublicKey: responseData)
        }
    }
}
