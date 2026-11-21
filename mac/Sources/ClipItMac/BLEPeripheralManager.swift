import Foundation
import CoreBluetooth

public protocol BLEPeripheralManagerDelegate: AnyObject {
    func bleStatusDidChange(status: String, isConnected: Bool)
    func bleDidReceiveText(text: String)
}

public class BLEPeripheralManager: NSObject, CBPeripheralManagerDelegate {
    public weak var delegate: BLEPeripheralManagerDelegate?
    
    private var peripheralManager: CBPeripheralManager!
    private var clipboardCharacteristic: CBMutableCharacteristic?
    private var subscribedCentrals: Set<CBCentral> = []
    
    private let reassembler = BleProtocol.Reassembler()
    private var currentMessageId: UInt8 = 0
    private var pendingOutgoingPackets: [Data] = []
    
    public private(set) var pairingPin: String = String(format: "%04d", Int.random(in: 1000...9999))
    
    public var pairingPayload: String {
        return "clipit://pair?name=\(BleProtocol.advertisedName)&uuid=\(BleProtocol.serviceUUID.uuidString)&pin=\(pairingPin)"
    }
    
    public override init() {
        super.init()
        peripheralManager = CBPeripheralManager(delegate: self, queue: nil)
    }
    
    public func startAdvertising() {
        guard peripheralManager.state == .poweredOn else {
            print("[BLE] Bluetooth is not powered on yet.")
            return
        }
        
        setupService()
    }
    
    private func setupService() {
        peripheralManager.removeAllServices()
        
        let characteristic = CBMutableCharacteristic(
            type: BleProtocol.clipboardCharUUID,
            properties: [.read, .write, .writeWithoutResponse, .notify],
            value: nil,
            permissions: [.readable, .writeable]
        )
        
        let service = CBMutableService(type: BleProtocol.serviceUUID, primary: true)
        service.characteristics = [characteristic]
        
        self.clipboardCharacteristic = characteristic
        // Once this service is added, peripheralManager(_:didAdd:error:) will be invoked!
        peripheralManager.add(service)
    }
    
    private func startAdvertisingAfterServiceAdded() {
        let advertisementData: [String: Any] = [
            CBAdvertisementDataServiceUUIDsKey: [BleProtocol.serviceUUID],
            CBAdvertisementDataLocalNameKey: BleProtocol.advertisedName
        ]
        
        peripheralManager.stopAdvertising()
        peripheralManager.startAdvertising(advertisementData)
        print("[BLE] Started advertising service \(BleProtocol.serviceUUID) with name '\(BleProtocol.advertisedName)'")
        delegate?.bleStatusDidChange(status: "Waiting for Android device...", isConnected: false)
    }
    
    public func showPairingQRCode() {
        QRCodeWindowController.shared.show(pairingPayload: self.pairingPayload, pin: self.pairingPin)
    }
    
    public func sendText(_ text: String) {
        guard clipboardCharacteristic != nil, !subscribedCentrals.isEmpty else {
            print("[BLE] Cannot send: No subscribed Android device.")
            return
        }
        
        currentMessageId = currentMessageId &+ 1
        
        let packets = BleProtocol.chunk(text: text, messageId: currentMessageId, maxPacketSize: 180)
        pendingOutgoingPackets.append(contentsOf: packets)
        
        flushOutgoingPackets()
    }
    
    private func flushOutgoingPackets() {
        guard let characteristic = clipboardCharacteristic else { return }
        
        while !pendingOutgoingPackets.isEmpty {
            let nextPacket = pendingOutgoingPackets[0]
            let success = peripheralManager.updateValue(nextPacket, for: characteristic, onSubscribedCentrals: nil)
            
            if success {
                pendingOutgoingPackets.removeFirst()
            } else {
                print("[BLE] Send buffer full. Waiting for ready signal...")
                break
            }
        }
    }
    
    // MARK: - CBPeripheralManagerDelegate
    
    public func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        switch peripheral.state {
        case .poweredOn:
            print("[BLE] Bluetooth powered on.")
            setupService()
        case .poweredOff:
            print("[BLE] Bluetooth powered off.")
            delegate?.bleStatusDidChange(status: "Bluetooth Powered Off", isConnected: false)
        case .unauthorized:
            print("[BLE] Bluetooth unauthorized. Please check System Settings > Privacy > Bluetooth.")
            delegate?.bleStatusDidChange(status: "Bluetooth Unauthorized", isConnected: false)
        case .unsupported:
            print("[BLE] Bluetooth unsupported on this machine.")
            delegate?.bleStatusDidChange(status: "Bluetooth Unsupported", isConnected: false)
        case .resetting:
            print("[BLE] Bluetooth resetting...")
            delegate?.bleStatusDidChange(status: "Bluetooth Resetting...", isConnected: false)
        case .unknown:
            delegate?.bleStatusDidChange(status: "Bluetooth Unknown", isConnected: false)
        @unknown default:
            break
        }
    }
    
    public func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error = error {
            print("[BLE] Error adding service: \(error.localizedDescription)")
            delegate?.bleStatusDidChange(status: "Service Error: \(error.localizedDescription)", isConnected: false)
        } else {
            print("[BLE] Service added successfully to GATT database: \(service.uuid)")
            // Now that service is safely registered in GATT database, begin advertising!
            startAdvertisingAfterServiceAdded()
        }
    }
    
    public func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error = error {
            print("[BLE] Error starting advertising: \(error.localizedDescription)")
            delegate?.bleStatusDidChange(status: "Adv Error: \(error.localizedDescription)", isConnected: false)
        } else {
            print("[BLE] Advertising active as \(BleProtocol.advertisedName)")
            delegate?.bleStatusDidChange(status: "Waiting for Android device...", isConnected: false)
        }
    }
    
    public func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        print("[BLE] Android central subscribed: \(central.identifier.uuidString) (MTU: \(central.maximumUpdateValueLength))")
        subscribedCentrals.insert(central)
        delegate?.bleStatusDidChange(status: "Connected to Android (\(subscribedCentrals.count))", isConnected: true)
        
        // Notify QR Code window if it is open
        QRCodeWindowController.shared.notifyConnected()
    }
    
    public func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        print("[BLE] Android central unsubscribed: \(central.identifier.uuidString)")
        subscribedCentrals.remove(central)
        let isStillConnected = !subscribedCentrals.isEmpty
        let status = isStillConnected ? "Connected to Android (\(subscribedCentrals.count))" : "Waiting for Android device..."
        delegate?.bleStatusDidChange(status: status, isConnected: isStillConnected)
        
        // Immediately refresh BLE advertisement so the phone can find the Mac as soon as it's back in range
        if !isStillConnected && peripheral.state == .poweredOn {
            print("[BLE] Restarting active advertising for returning phone...")
            startAdvertisingAfterServiceAdded()
        }
    }
    
    public func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        print("[BLE] Ready to resume outgoing packet transmission.")
        flushOutgoingPackets()
    }
    
    public func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for request in requests {
            if request.characteristic.uuid == BleProtocol.clipboardCharUUID {
                if let data = request.value {
                    if let completedText = reassembler.feed(packetData: data) {
                        print("[BLE] Reassembled complete clipboard text (\(completedText.count) chars)")
                        delegate?.bleDidReceiveText(text: completedText)
                    }
                }
                peripheral.respond(to: request, withResult: .success)
            } else {
                peripheral.respond(to: request, withResult: .attributeNotFound)
            }
        }
    }
    
    public func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        if request.characteristic.uuid == BleProtocol.clipboardCharUUID {
            request.value = "ClipIt".data(using: .utf8)
            peripheral.respond(to: request, withResult: .success)
        } else {
            peripheral.respond(to: request, withResult: .attributeNotFound)
        }
    }
}
