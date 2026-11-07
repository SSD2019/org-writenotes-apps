// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "OrgWriteNotes",
    platforms: [
        .iOS(.v16),
        .macOS(.v13)
    ],
    products: [
        .library(
            name: "WriteEngineC",
            targets: ["WriteEngineC"]
        ),
        .library(
            name: "OrgWriteNotesKit",
            targets: ["OrgWriteNotesKit"]
        ),
        .executable(
            name: "OrgWriteNotesApp",
            targets: ["OrgWriteNotesApp"]
        )
    ],
    targets: [
        .target(
            name: "WriteEngineC",
            path: "Sources/WriteEngineC",
            publicHeadersPath: "include",
            cSettings: [
                .headerSearchPath("include"),
                .headerSearchPath("../../../android/app/src/main/cpp"),
                .headerSearchPath("../../../android/cpp/Write"),
                .headerSearchPath("../../../android/cpp/Write/syncscribble"),
                .headerSearchPath("../../../android/cpp/Write/ulib"),
                .headerSearchPath("../../../android/cpp/Write/usvg"),
                .headerSearchPath("../../../android/cpp/Write/nanovgXC/src"),
                .headerSearchPath("../../../android/cpp/Write/pugixml/src"),
                .headerSearchPath("../../../android/cpp/Write/miniz"),
                .headerSearchPath("../../../android/cpp/Write/stb"),
                .define("ORGWRITENOTES_NO_JNI", to: "1"),
                .define("PUGIXML_NO_XPATH", to: "1"),
                .define("PUGIXML_NO_EXCEPTIONS", to: "1"),
                .define("NO_PAINTER_GL", to: "1"),
                .define("NO_PAINTER_SWU", to: "1")
            ],
            cxxSettings: [
                .headerSearchPath("include"),
                .headerSearchPath("../../../android/app/src/main/cpp"),
                .headerSearchPath("../../../android/cpp/Write"),
                .headerSearchPath("../../../android/cpp/Write/syncscribble"),
                .headerSearchPath("../../../android/cpp/Write/ulib"),
                .headerSearchPath("../../../android/cpp/Write/usvg"),
                .headerSearchPath("../../../android/cpp/Write/nanovgXC/src"),
                .headerSearchPath("../../../android/cpp/Write/pugixml/src"),
                .headerSearchPath("../../../android/cpp/Write/miniz"),
                .headerSearchPath("../../../android/cpp/Write/stb"),
                .define("ORGWRITENOTES_NO_JNI", to: "1"),
                .define("PUGIXML_NO_XPATH", to: "1"),
                .define("PUGIXML_NO_EXCEPTIONS", to: "1"),
                .define("NO_PAINTER_GL", to: "1"),
                .define("NO_PAINTER_SWU", to: "1")
            ],
            linkerSettings: [
                .linkedLibrary("z"),
                .linkedLibrary("c++")
            ]
        ),
        .target(
            name: "OrgWriteNotesKit",
            dependencies: ["WriteEngineC"],
            path: "Sources/OrgWriteNotesKit"
        ),
        .executableTarget(
            name: "OrgWriteNotesApp",
            dependencies: ["OrgWriteNotesKit"],
            path: "Sources/OrgWriteNotesApp"
        ),
        .testTarget(
            name: "OrgWriteNotesTests",
            dependencies: ["OrgWriteNotesKit", "WriteEngineC"],
            path: "Tests/OrgWriteNotesTests"
        )
    ],
    cxxLanguageStandard: .cxx17
)

