import Foundation
import AppKit
import CoreImage

public class QRCodeWindowController: NSObject, NSWindowDelegate {
    public static let shared = QRCodeWindowController()
    
    private var window: NSWindow?
    private var qrImageView: NSImageView?
    private var statusLabel: NSTextField?
    private var pinLabel: NSTextField?
    
    private var currentPin: String = "8421"
    
    public override init() {
        super.init()
    }
    
    public func show(pairingPayload: String, pin: String) {
        self.currentPin = pin
        
        if window == nil {
            createWindow()
        }
        
        if let qrImage = generateQRCode(from: pairingPayload) {
            qrImageView?.image = qrImage
        }
        
        statusLabel?.stringValue = "Scan with ClipIt on Android"
        statusLabel?.textColor = .labelColor
        pinLabel?.stringValue = "Pairing Code: \(pin)"
        
        window?.center()
        window?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }
    
    public func notifyConnected() {
        DispatchQueue.main.async { [weak self] in
            self?.statusLabel?.stringValue = "Connected to Phone! 🟢"
            self?.statusLabel?.textColor = .systemGreen
            
            // Auto-dismiss window after 2 seconds on successful pairing
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
                self?.window?.orderOut(nil)
            }
        }
    }
    
    private func createWindow() {
        let win = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 340, height: 420),
            styleMask: [.titled, .closable],
            backing: .buffered,
            defer: false
        )
        win.title = "ClipIt — Pair with Phone"
        win.isReleasedWhenClosed = false
        win.delegate = self
        
        let contentView = NSView(frame: win.contentRect(forFrameRect: win.frame))
        contentView.wantsLayer = true
        contentView.layer?.backgroundColor = NSColor.windowBackgroundColor.cgColor
        
        // Title Label
        let titleLabel = NSTextField(labelWithString: "ClipIt Bluetooth Pairing")
        titleLabel.frame = NSRect(x: 20, y: 370, width: 300, height: 26)
        titleLabel.font = NSFont.boldSystemFont(ofSize: 18)
        titleLabel.alignment = .center
        contentView.addSubview(titleLabel)
        
        // Subtitle / Status
        let status = NSTextField(labelWithString: "Scan with ClipIt on Android")
        status.frame = NSRect(x: 20, y: 345, width: 300, height: 20)
        status.font = NSFont.systemFont(ofSize: 13)
        status.alignment = .center
        status.textColor = .secondaryLabelColor
        contentView.addSubview(status)
        self.statusLabel = status
        
        // QR Code Image View
        let qrView = NSImageView(frame: NSRect(x: 45, y: 85, width: 250, height: 250))
        qrView.imageScaling = .scaleProportionallyUpOrDown
        contentView.addSubview(qrView)
        self.qrImageView = qrView
        
        // PIN / Code Label
        let pin = NSTextField(labelWithString: "Pairing Code: \(currentPin)")
        pin.frame = NSRect(x: 20, y: 45, width: 300, height: 24)
        pin.font = NSFont.monospacedSystemFont(ofSize: 16, weight: .semibold)
        pin.alignment = .center
        contentView.addSubview(pin)
        self.pinLabel = pin
        
        // Help note
        let note = NSTextField(labelWithString: "Make sure Bluetooth is enabled on both devices.")
        note.frame = NSRect(x: 20, y: 15, width: 300, height: 18)
        note.font = NSFont.systemFont(ofSize: 11)
        note.alignment = .center
        note.textColor = .tertiaryLabelColor
        contentView.addSubview(note)
        
        win.contentView = contentView
        self.window = win
    }
    
    private func generateQRCode(from string: String) -> NSImage? {
        guard let data = string.data(using: .utf8) else { return nil }
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(data, forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        
        guard let ciImage = filter.outputImage else { return nil }
        
        let scale = 8.0
        let transformed = ciImage.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        
        let rep = NSCIImageRep(ciImage: transformed)
        let img = NSImage(size: rep.size)
        img.addRepresentation(rep)
        return img
    }
}
