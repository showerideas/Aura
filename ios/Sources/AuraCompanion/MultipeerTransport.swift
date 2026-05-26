// Sources/AuraCompanion/MultipeerTransport.swift
// Phase 7.1 — iOS transport layer using MultipeerConnectivity.
//
// Mirrors NearbyExchangeService on Android: advertises the local peer and
// browses for remote AURA devices. On connection, performs the AURA v6 hybrid
// key exchange handshake, then SAS verification before exchanging profiles.

import Foundation
import MultipeerConnectivity

public protocol TransportDelegate: AnyObject {
    func transport(_ transport: MultipeerTransport, didReceiveFrame: Data, from peer: MCPeerID)
    func transport(_ transport: MultipeerTransport, peerDidConnect peer: MCPeerID)
    func transport(_ transport: MultipeerTransport, peerDidDisconnect peer: MCPeerID)
}

/// Thin MultipeerConnectivity wrapper that the AuraExchangeCoordinator drives.
public final class MultipeerTransport: NSObject {

    public static let serviceType = "aura-exchange"   // must match Android string

    private let localPeerID   : MCPeerID
    private let session       : MCSession
    private let advertiser    : MCNearbyServiceAdvertiser
    private let browser       : MCNearbyServiceBrowser
    public  weak var delegate : TransportDelegate?

    public init(displayName: String) {
        localPeerID = MCPeerID(displayName: displayName)
        session     = MCSession(peer: localPeerID, securityIdentity: nil, encryptionPreference: .required)
        advertiser  = MCNearbyServiceAdvertiser(peer: localPeerID, discoveryInfo: nil, serviceType: Self.serviceType)
        browser     = MCNearbyServiceBrowser(peer: localPeerID, serviceType: Self.serviceType)
        super.init()
        session.delegate    = self
        advertiser.delegate = self
        browser.delegate    = self
    }

    public func startAdvertising() { advertiser.startAdvertisingPeer() }
    public func stopAdvertising()  { advertiser.stopAdvertisingPeer() }
    public func startBrowsing()    { browser.startBrowsingForPeers() }
    public func stopBrowsing()     { browser.stopBrowsingForPeers() }

    public func send(_ data: Data, to peer: MCPeerID) throws {
        try session.send(data, toPeers: [peer], with: .reliable)
    }

    public func disconnect() {
        session.disconnect()
        advertiser.stopAdvertisingPeer()
        browser.stopBrowsingForPeers()
    }
}

extension MultipeerTransport: MCSessionDelegate {
    public func session(_ session: MCSession, peer: MCPeerID, didChange state: MCSessionState) {
        switch state {
        case .connected:    delegate?.transport(self, peerDidConnect: peer)
        case .notConnected: delegate?.transport(self, peerDidDisconnect: peer)
        default: break
        }
    }
    public func session(_ session: MCSession, didReceive data: Data, fromPeer peer: MCPeerID) {
        delegate?.transport(self, didReceiveFrame: data, from: peer)
    }
    public func session(_ session: MCSession, didReceive stream: InputStream, withName: String, fromPeer: MCPeerID) {}
    public func session(_ session: MCSession, didStartReceivingResourceWithName: String, fromPeer: MCPeerID, with: Progress) {}
    public func session(_ session: MCSession, didFinishReceivingResourceWithName: String, fromPeer: MCPeerID, at: URL?, withError: Error?) {}
}

extension MultipeerTransport: MCNearbyServiceAdvertiserDelegate {
    public func advertiser(_ advertiser: MCNearbyServiceAdvertiser, didReceiveInvitationFromPeer peer: MCPeerID,
                           withContext: Data?, invitationHandler: @escaping (Bool, MCSession?) -> Void) {
        invitationHandler(true, session)  // auto-accept: key exchange provides security
    }
}

extension MultipeerTransport: MCNearbyServiceBrowserDelegate {
    public func browser(_ browser: MCNearbyServiceBrowser, foundPeer peer: MCPeerID, withDiscoveryInfo info: [String: String]?) {
        browser.invitePeer(peer, to: session, withContext: nil, timeout: 10)
    }
    public func browser(_ browser: MCNearbyServiceBrowser, lostPeer peer: MCPeerID) {}
}
