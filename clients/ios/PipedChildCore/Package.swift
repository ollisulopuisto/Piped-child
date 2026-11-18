// swift-tools-version: 5.9
// M1-A: pure model/selection layer for the iPhone client. No AVPlayer here;
// playback lands in M4. Tested via XCTest against the shared fixtures in
// clients/contracts/fixtures (the same fixtures the Android tests use).
import PackageDescription

let package = Package(
    name: "PipedChildCore",
    platforms: [.iOS(16)],
    products: [
        .library(name: "PipedChildCore", targets: ["PipedChildCore"])
    ],
    targets: [
        .target(name: "PipedChildCore", path: "Sources/PipedChildCore"),
        .testTarget(
            name: "PipedChildCoreTests",
            dependencies: ["PipedChildCore"],
            path: "Tests/PipedChildCoreTests"
        )
    ]
)
