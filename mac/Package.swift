// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "ClipItMac",
    platforms: [
        .macOS(.v13)
    ],
    products: [
        .executable(
            name: "ClipItMac",
            targets: ["ClipItMac"]
        )
    ],
    dependencies: [],
    targets: [
        .executableTarget(
            name: "ClipItMac",
            dependencies: [],
            path: "Sources/ClipItMac"
        )
    ]
)
