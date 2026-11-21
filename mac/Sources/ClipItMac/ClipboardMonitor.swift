import Foundation
import AppKit

public protocol ClipboardMonitorDelegate: AnyObject {
    func clipboardDidChange(newText: String)
}

public class ClipboardMonitor {
    public weak var delegate: ClipboardMonitorDelegate?
    
    private let pasteboard = NSPasteboard.general
    private var lastChangeCount: Int = 0
    private var timer: Timer?
    
    // Echo prevention hashes
    private var lastReceivedHash: String = ""
    private var lastSentHash: String = ""
    
    private let checkInterval: TimeInterval = 0.4
    
    public init() {
        self.lastChangeCount = pasteboard.changeCount
    }
    
    public func startMonitoring() {
        stopMonitoring()
        self.lastChangeCount = pasteboard.changeCount
        
        timer = Timer.scheduledTimer(withTimeInterval: checkInterval, repeats: true) { [weak self] _ in
            self?.checkClipboard()
        }
        RunLoop.main.add(timer!, forMode: .common)
    }
    
    public func stopMonitoring() {
        timer?.invalidate()
        timer = nil
    }
    
    private func checkClipboard() {
        let currentCount = pasteboard.changeCount
        guard currentCount != lastChangeCount else { return }
        lastChangeCount = currentCount
        
        guard let text = pasteboard.string(forType: .string), !text.isEmpty else { return }
        
        let textHash = BleProtocol.hash(text: text)
        
        // If this text matches what we just received from Android, do not echo back!
        if textHash == lastReceivedHash {
            return
        }
        
        // If this text matches what we just sent, do not send duplicate
        if textHash == lastSentHash {
            return
        }
        
        lastSentHash = textHash
        delegate?.clipboardDidChange(newText: text)
    }
    
    /// Called when new text is received from Android.
    /// Writes to macOS clipboard without triggering an outgoing sync loop.
    public func setRemoteClipboard(text: String) {
        let textHash = BleProtocol.hash(text: text)
        self.lastReceivedHash = textHash
        
        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)
        self.lastChangeCount = pasteboard.changeCount
    }
}
