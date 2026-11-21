import Foundation
import AppKit

class AppDelegate: NSObject, NSApplicationDelegate, ClipboardMonitorDelegate, BLEPeripheralManagerDelegate, MenuBarManagerDelegate {
    private var menuBarManager: MenuBarManager!
    private var clipboardMonitor: ClipboardMonitor!
    private var bleManager: BLEPeripheralManager!
    
    func applicationDidFinishLaunching(_ notification: Notification) {
        print("[ClipItMac] Starting ClipIt Menu Bar App...")
        
        menuBarManager = MenuBarManager()
        menuBarManager.delegate = self
        
        clipboardMonitor = ClipboardMonitor()
        clipboardMonitor.delegate = self
        clipboardMonitor.startMonitoring()
        
        bleManager = BLEPeripheralManager()
        bleManager.delegate = self
        
        print("[ClipItMac] Initialized and running in menu bar.")
    }
    
    // MARK: - ClipboardMonitorDelegate (Mac Copied Text -> Send to Android)
    func clipboardDidChange(newText: String) {
        print("[ClipItMac] Mac clipboard changed (\(newText.count) chars). Broadcasting to Android...")
        bleManager.sendText(newText)
        menuBarManager.updateLastSynced(text: newText, direction: "Sent to Phone")
    }
    
    // MARK: - BLEPeripheralManagerDelegate (Android Sent Text -> Set on Mac Clipboard)
    func bleStatusDidChange(status: String, isConnected: Bool) {
        menuBarManager.updateConnectionStatus(status: status, isConnected: isConnected)
    }
    
    func bleDidReceiveText(text: String) {
        print("[ClipItMac] Received text from Android (\(text.count) chars). Updating Mac clipboard...")
        clipboardMonitor.setRemoteClipboard(text: text)
        menuBarManager.updateLastSynced(text: text, direction: "Received from Phone")
    }
    
    // MARK: - MenuBarManagerDelegate
    func didRequestShowQRCode() {
        print("[ClipItMac] Showing pairing QR Code window...")
        bleManager.showPairingQRCode()
    }
    
    func didRequestTestSend() {
        let currentText = NSPasteboard.general.string(forType: .string) ?? ""
        let textToSend = currentText.isEmpty ? "Hello from Mac at \(Date())" : currentText
        print("[ClipItMac] Sending clipboard to phone: \(textToSend)")
        clipboardMonitor.setRemoteClipboard(text: textToSend)
        bleManager.sendText(textToSend)
        menuBarManager.updateLastSynced(text: textToSend, direction: "Sent to Phone")
    }
    
    func didRequestRestartAdvertising() {
        print("[ClipItMac] Restarting BLE advertising...")
        bleManager.startAdvertising()
    }
    
    func didRequestQuit() {
        print("[ClipItMac] Quitting ClipIt...")
        NSApplication.shared.terminate(nil)
    }
}

// Main execution
let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.accessory)
app.run()
