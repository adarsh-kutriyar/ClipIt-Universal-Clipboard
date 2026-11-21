import Foundation
import AppKit
import ServiceManagement

// MARK: - Login Item (Launch on Startup) Helper

public class LoginItemHelper {
    public static let agentId = "com.clipit.mac"
    public static var launchAgentURL: URL {
        FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Library/LaunchAgents")
            .appendingPathComponent("\(agentId).plist")
    }
    
    public static var isLaunchAgentEnabled: Bool {
        FileManager.default.fileExists(atPath: launchAgentURL.path)
    }
    
    public static var isEnabled: Bool {
        if #available(macOS 13.0, *) {
            if SMAppService.mainApp.status == .enabled { return true }
        }
        return isLaunchAgentEnabled
    }
    
    public static func setEnabled(_ enable: Bool) {
        if #available(macOS 13.0, *) {
            do {
                if enable {
                    try SMAppService.mainApp.register()
                    return
                } else {
                    try SMAppService.mainApp.unregister()
                }
            } catch {
                print("[ClipItMac] SMAppService note: \(error.localizedDescription)")
            }
        }
        
        // Universal LaunchAgent fallback (works from /Applications or any folder)
        let appPath = Bundle.main.bundlePath
        let plistURL = launchAgentURL
        if enable {
            let plistContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>Label</key>
                <string>\(agentId)</string>
                <key>ProgramArguments</key>
                <array>
                    <string>/usr/bin/open</string>
                    <string>\(appPath)</string>
                </array>
                <key>RunAtLoad</key>
                <true/>
                <key>ProcessType</key>
                <string>Interactive</string>
            </dict>
            </plist>
            """
            try? FileManager.default.createDirectory(at: plistURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? plistContent.write(to: plistURL, atomically: true, encoding: .utf8)
        } else {
            try? FileManager.default.removeItem(at: plistURL)
        }
    }
}

public protocol MenuBarManagerDelegate: AnyObject {
    func didRequestShowQRCode()
    func didRequestTestSend()
    func didRequestRestartAdvertising()
    func didRequestQuit()
}

// MARK: - Native Apple-Grade Header View

public class ClipItHeaderView: NSView {
    private let iconImageView = NSImageView()
    private let titleLabel = NSTextField(labelWithString: "ClipIt")
    private let statusLabel = NSTextField(labelWithString: "Waiting for Android...")
    private let dotView = NSView()
    
    private let emeraldColor = NSColor(red: 0.14, green: 0.76, blue: 0.45, alpha: 1.0)
    private let amberColor = NSColor(red: 0.96, green: 0.62, blue: 0.04, alpha: 1.0)
    
    public override init(frame frameRect: NSRect) {
        super.init(frame: NSRect(x: 0, y: 0, width: 250, height: 50))
        setupUI()
    }
    
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
    
    private func setupUI() {
        // Icon container squircle
        let iconContainer = NSView(frame: NSRect(x: 14, y: 9, width: 32, height: 32))
        iconContainer.wantsLayer = true
        iconContainer.layer?.cornerRadius = 8
        iconContainer.layer?.backgroundColor = NSColor.labelColor.withAlphaComponent(0.06).cgColor
        
        iconImageView.frame = NSRect(x: 6, y: 6, width: 20, height: 20)
        let config = NSImage.SymbolConfiguration(pointSize: 13, weight: .medium)
        if let sym = NSImage(systemSymbolName: "laptopcomputer.and.iphone", accessibilityDescription: nil)?.withSymbolConfiguration(config) {
            sym.isTemplate = true
            iconImageView.image = sym
            iconImageView.contentTintColor = NSColor.labelColor.withAlphaComponent(0.8)
        }
        iconContainer.addSubview(iconImageView)
        addSubview(iconContainer)
        
        // App title
        titleLabel.frame = NSRect(x: 54, y: 25, width: 185, height: 17)
        titleLabel.font = NSFont.systemFont(ofSize: 13, weight: .semibold)
        titleLabel.textColor = .labelColor
        addSubview(titleLabel)
        
        // Live status dot
        dotView.frame = NSRect(x: 55, y: 13, width: 6, height: 6)
        dotView.wantsLayer = true
        dotView.layer?.cornerRadius = 3
        dotView.layer?.backgroundColor = amberColor.cgColor
        addSubview(dotView)
        
        // Status text
        statusLabel.frame = NSRect(x: 66, y: 9, width: 175, height: 15)
        statusLabel.font = NSFont.systemFont(ofSize: 11, weight: .regular)
        statusLabel.textColor = .secondaryLabelColor
        addSubview(statusLabel)
    }
    
    public func updateStatus(isConnected: Bool, status: String) {
        dotView.layer?.backgroundColor = isConnected ? emeraldColor.cgColor : amberColor.cgColor
        statusLabel.stringValue = isConnected ? "Connected to Android" : status
    }
}

// MARK: - Menu Bar Manager

public class MenuBarManager: NSObject {
    public weak var delegate: MenuBarManagerDelegate?
    
    private var statusItem: NSStatusItem!
    private var menu: NSMenu!
    
    private var headerView: ClipItHeaderView!
    private var previewMenuItem: NSMenuItem!
    private var iconSubmenu: NSMenu!
    private var launchAtLoginItem: NSMenuItem!
    
    private var lastSyncedText: String = ""
    private var lastSyncedTime: Date?
    
    public override init() {
        super.init()
        setupMenu()
    }
    
    // MARK: - SF Symbols Helper
    
    private func makeMenuSymbol(name: String, pointSize: CGFloat = 13, weight: NSFont.Weight = .regular) -> NSImage? {
        let config = NSImage.SymbolConfiguration(pointSize: pointSize, weight: weight)
        guard let img = NSImage(systemSymbolName: name, accessibilityDescription: nil)?.withSymbolConfiguration(config) else {
            return nil
        }
        img.isTemplate = true
        return img
    }
    
    // MARK: - Status Bar Icon Generators
    
    /// Bespoke minimal dual-sheet vector icon matching user's Layered Cards design
    public static func createDualCardsIcon() -> NSImage {
        let size = NSSize(width: 18, height: 18)
        let image = NSImage(size: size, flipped: false) { rect in
            guard let ctx = NSGraphicsContext.current?.cgContext else { return false }
            
            ctx.saveGState()
            // Center 100x100 SVG geometry [x: 22..80, y: 20..82] into 18x18 frame
            ctx.translateBy(x: -2.48, y: 20.5)
            ctx.scaleBy(x: 0.225, y: -0.225)
            
            ctx.setLineCap(.round)
            ctx.setLineJoin(.round)
            ctx.setStrokeColor(NSColor.black.cgColor)
            
            // 1. Back card outline:
            let backPath = CGMutablePath()
            backPath.move(to: CGPoint(x: 64, y: 31))
            backPath.addLine(to: CGPoint(x: 64, y: 28))
            backPath.addArc(tangent1End: CGPoint(x: 64, y: 20), tangent2End: CGPoint(x: 56, y: 20), radius: 8)
            backPath.addLine(to: CGPoint(x: 30, y: 20))
            backPath.addArc(tangent1End: CGPoint(x: 22, y: 20), tangent2End: CGPoint(x: 22, y: 28), radius: 8)
            backPath.addLine(to: CGPoint(x: 22, y: 62))
            backPath.addArc(tangent1End: CGPoint(x: 22, y: 70), tangent2End: CGPoint(x: 30, y: 70), radius: 8)
            backPath.addLine(to: CGPoint(x: 34, y: 70))
            
            ctx.setLineWidth(5.5)
            ctx.addPath(backPath)
            ctx.strokePath()
            
            // 2. Punch cutout under front card with padding
            ctx.saveGState()
            ctx.setBlendMode(.clear)
            let punchRect = CGRect(x: 33, y: 31, width: 52, height: 56)
            let punchPath = CGPath(roundedRect: punchRect, cornerWidth: 10, cornerHeight: 10, transform: nil)
            ctx.addPath(punchPath)
            ctx.fillPath()
            ctx.restoreGState()
            
            // 3. Front card:
            let frontRect = CGRect(x: 38, y: 36, width: 42, height: 46)
            let frontPath = CGPath(roundedRect: frontRect, cornerWidth: 8, cornerHeight: 8, transform: nil)
            ctx.setLineWidth(5.5)
            ctx.addPath(frontPath)
            ctx.strokePath()
            
            // 4. Text lines on front card:
            ctx.setLineWidth(4.8)
            ctx.move(to: CGPoint(x: 47, y: 53))
            ctx.addLine(to: CGPoint(x: 71, y: 53))
            ctx.strokePath()
            
            ctx.move(to: CGPoint(x: 47, y: 65))
            ctx.addLine(to: CGPoint(x: 62, y: 65))
            ctx.strokePath()
            
            ctx.restoreGState()
            return true
        }
        image.isTemplate = true
        return image
    }
    
    /// Minimal Apple SF Symbol paperclip
    public static func createPaperclipIcon() -> NSImage {
        if let img = NSImage(systemSymbolName: "paperclip", accessibilityDescription: "ClipIt") {
            let config = NSImage.SymbolConfiguration(pointSize: 13, weight: .medium)
            if let configured = img.withSymbolConfiguration(config) {
                configured.isTemplate = true
                return configured
            }
        }
        return createDualCardsIcon()
    }
    
    /// Minimal Apple SF Symbol single clipboard
    public static func createClipboardIcon() -> NSImage {
        if let img = NSImage(systemSymbolName: "clipboard", accessibilityDescription: "ClipIt") {
            let config = NSImage.SymbolConfiguration(pointSize: 13, weight: .regular)
            if let configured = img.withSymbolConfiguration(config) {
                configured.isTemplate = true
                return configured
            }
        }
        return createDualCardsIcon()
    }
    
    private func applyCurrentIcon() {
        guard let button = statusItem?.button else { return }
        let style = UserDefaults.standard.string(forKey: "ClipItMenuBarIconStyle") ?? "dual_cards"
        let icon: NSImage
        switch style {
        case "paperclip":
            icon = MenuBarManager.createPaperclipIcon()
        case "clipboard":
            icon = MenuBarManager.createClipboardIcon()
        default:
            icon = MenuBarManager.createDualCardsIcon()
        }
        button.image = icon
    }
    
    private func updateIconMenuCheckmarks() {
        guard let submenu = iconSubmenu else { return }
        let currentStyle = UserDefaults.standard.string(forKey: "ClipItMenuBarIconStyle") ?? "dual_cards"
        for item in submenu.items {
            if let style = item.representedObject as? String {
                item.state = (style == currentStyle) ? .on : .off
            }
        }
    }
    
    // MARK: - Menu Setup (Minimal, Apple-Grade)
    
    private func setupMenu() {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        
        applyCurrentIcon()
        
        menu = NSMenu()
        menu.autoenablesItems = false
        
        // 1. Header Card (Device info & live status indicator)
        headerView = ClipItHeaderView(frame: .zero)
        let headerItem = NSMenuItem()
        headerItem.view = headerView
        menu.addItem(headerItem)
        
        menu.addItem(NSMenuItem.separator())
        
        // 2. Primary Actions
        let sendItem = NSMenuItem(title: "Send Clipboard to Phone", action: #selector(handleSendTest), keyEquivalent: "s")
        sendItem.image = makeMenuSymbol(name: "paperplane")
        sendItem.target = self
        menu.addItem(sendItem)
        
        let qrItem = NSMenuItem(title: "Show Pairing QR Code…", action: #selector(handleShowQR), keyEquivalent: "p")
        qrItem.image = makeMenuSymbol(name: "qrcode")
        qrItem.target = self
        menu.addItem(qrItem)
        
        menu.addItem(NSMenuItem.separator())
        
        // 3. Last Synced Section
        let lastSyncedHeader = NSMenuItem(title: "LAST SYNCED", action: nil, keyEquivalent: "")
        lastSyncedHeader.isEnabled = false
        let headerFont = NSFont.systemFont(ofSize: 10, weight: .bold)
        lastSyncedHeader.attributedTitle = NSAttributedString(
            string: "LAST SYNCED",
            attributes: [.font: headerFont, .foregroundColor: NSColor.secondaryLabelColor]
        )
        menu.addItem(lastSyncedHeader)
        
        previewMenuItem = NSMenuItem(title: "No clipboard history yet", action: #selector(copyLastSnippet), keyEquivalent: "")
        previewMenuItem.image = makeMenuSymbol(name: "doc.text")
        previewMenuItem.target = self
        previewMenuItem.isEnabled = false
        menu.addItem(previewMenuItem)
        
        menu.addItem(NSMenuItem.separator())
        
        // 4. Connection & Preferences
        let restartItem = NSMenuItem(title: "Restart BLE Connection", action: #selector(handleRestartAdv), keyEquivalent: "r")
        restartItem.image = makeMenuSymbol(name: "arrow.triangle.2.circlepath")
        restartItem.target = self
        menu.addItem(restartItem)
        
        launchAtLoginItem = NSMenuItem(title: "Launch at Login", action: #selector(handleToggleLaunchAtLogin), keyEquivalent: "")
        launchAtLoginItem.target = self
        launchAtLoginItem.state = LoginItemHelper.isEnabled ? .on : .off
        menu.addItem(launchAtLoginItem)
        
        // Menu Bar Icon Style Submenu
        iconSubmenu = NSMenu(title: "Menu Bar Icon Style")
        
        let dualCardItem = NSMenuItem(title: "Dual Sync Cards (Modern)", action: #selector(handleSelectIconStyle(_:)), keyEquivalent: "")
        dualCardItem.target = self
        dualCardItem.representedObject = "dual_cards"
        iconSubmenu.addItem(dualCardItem)
        
        let paperclipItem = NSMenuItem(title: "Paperclip (Minimal)", action: #selector(handleSelectIconStyle(_:)), keyEquivalent: "")
        paperclipItem.target = self
        paperclipItem.representedObject = "paperclip"
        iconSubmenu.addItem(paperclipItem)
        
        let singleClipboardItem = NSMenuItem(title: "Single Clipboard", action: #selector(handleSelectIconStyle(_:)), keyEquivalent: "")
        singleClipboardItem.target = self
        singleClipboardItem.representedObject = "clipboard"
        iconSubmenu.addItem(singleClipboardItem)
        
        let iconSubmenuItem = NSMenuItem(title: "Menu Bar Icon Style", action: nil, keyEquivalent: "")
        iconSubmenuItem.image = makeMenuSymbol(name: "slider.horizontal.3")
        iconSubmenuItem.submenu = iconSubmenu
        menu.addItem(iconSubmenuItem)
        
        menu.addItem(NSMenuItem.separator())
        
        // 5. Quit
        let quitItem = NSMenuItem(title: "Quit ClipIt", action: #selector(handleQuit), keyEquivalent: "q")
        quitItem.target = self
        menu.addItem(quitItem)
        
        statusItem.menu = menu
        updateIconMenuCheckmarks()
    }
    
    public func updateConnectionStatus(status: String, isConnected: Bool) {
        headerView?.updateStatus(isConnected: isConnected, status: status)
        
        if let button = statusItem?.button {
            button.toolTip = isConnected ? "ClipIt: Connected to Android" : "ClipIt: \(status)"
        }
    }
    
    public func updateLastSynced(text: String, direction: String) {
        lastSyncedText = text
        lastSyncedTime = Date()
        
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        let timeStr = formatter.string(from: lastSyncedTime!)
        
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let preview = trimmed.count > 30 ? String(trimmed.prefix(30)) + "…" : trimmed
        let cleanPreview = preview.replacingOccurrences(of: "\n", with: " ")
        
        previewMenuItem.title = "“\(cleanPreview)”"
        previewMenuItem.toolTip = "\(direction) at \(timeStr)\n“\(text)”\n\nClick to copy to clipboard"
        previewMenuItem.isEnabled = true
        
        flashIcon()
    }
    
    private func flashIcon() {
        guard let button = statusItem.button else { return }
        
        let flashImage: NSImage
        if let check = NSImage(systemSymbolName: "checkmark", accessibilityDescription: "Synced") {
            let config = NSImage.SymbolConfiguration(pointSize: 12, weight: .bold)
            flashImage = check.withSymbolConfiguration(config) ?? check
            flashImage.isTemplate = true
        } else {
            flashImage = button.image ?? NSImage()
        }
        
        button.image = flashImage
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
            self?.applyCurrentIcon()
        }
    }
    
    @objc private func handleSelectIconStyle(_ sender: NSMenuItem) {
        guard let style = sender.representedObject as? String else { return }
        UserDefaults.standard.set(style, forKey: "ClipItMenuBarIconStyle")
        applyCurrentIcon()
        updateIconMenuCheckmarks()
    }
    
    @objc private func handleShowQR() {
        delegate?.didRequestShowQRCode()
    }
    
    @objc private func copyLastSnippet() {
        guard !lastSyncedText.isEmpty else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(lastSyncedText, forType: .string)
    }
    
    @objc private func handleSendTest() {
        delegate?.didRequestTestSend()
    }
    
    @objc private func handleRestartAdv() {
        delegate?.didRequestRestartAdvertising()
    }
    
    @objc private func handleToggleLaunchAtLogin() {
        let enable = !LoginItemHelper.isEnabled
        LoginItemHelper.setEnabled(enable)
        launchAtLoginItem.state = LoginItemHelper.isEnabled ? .on : .off
    }
    
    @objc private func handleQuit() {
        delegate?.didRequestQuit()
    }
}
