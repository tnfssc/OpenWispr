// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "OpenWispr",
    platforms: [
        .macOS(.v14),
    ],
    products: [
        .library(name: "OpenWisprCore", targets: ["OpenWisprCore"]),
        .executable(name: "OpenWispr", targets: ["OpenWispr"]),
        .executable(name: "OpenWisprSelfTest", targets: ["OpenWisprSelfTest"]),
    ],
    targets: [
        .testTarget(
            name: "OpenWisprCoreTests",
            dependencies: ["OpenWisprCore", "OpenWispr"],
            path: "Tests/OpenWisprCoreTests"
        ),
        .target(
            name: "OpenWisprCore",
            path: "Sources/OpenWisprCore"
        ),
        .executableTarget(
            name: "OpenWispr",
            dependencies: ["OpenWisprCore"],
            path: "Sources/OpenWisprMac"
        ),
        .executableTarget(
            name: "OpenWisprSelfTest",
            dependencies: ["OpenWisprCore"],
            path: "SelfTests"
        ),
    ]
)
