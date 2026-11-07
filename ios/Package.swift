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
            exclude: [
                "shared_headers"
            ],
            publicHeadersPath: "include",
            cSettings: [
                .headerSearchPath("include"),
                .headerSearchPath("shared_headers/app_cpp"),
                .headerSearchPath("shared_headers/Write"),
                .headerSearchPath("shared_headers/Write/syncscribble"),
                .headerSearchPath("shared_headers/Write/ulib"),
                .headerSearchPath("shared_headers/Write/usvg"),
                .headerSearchPath("shared_headers/Write/nanovgXC/src"),
                .headerSearchPath("shared_headers/Write/pugixml/src"),
                .headerSearchPath("shared_headers/Write/miniz"),
                .headerSearchPath("shared_headers/Write/stb"),
                .define("ORGWRITENOTES_NO_JNI", to: "1"),
                .define("PUGIXML_NO_XPATH", to: "1"),
                .define("PUGIXML_NO_EXCEPTIONS", to: "1"),
                .define("NO_PAINTER_GL", to: "1"),
                .define("NO_PAINTER_SWU", to: "1")
            ],
            cxxSettings: [
                .headerSearchPath("include"),
                .headerSearchPath("shared_headers/app_cpp"),
                .headerSearchPath("shared_headers/Write"),
                .headerSearchPath("shared_headers/Write/syncscribble"),
                .headerSearchPath("shared_headers/Write/ulib"),
                .headerSearchPath("shared_headers/Write/usvg"),
                .headerSearchPath("shared_headers/Write/nanovgXC/src"),
                .headerSearchPath("shared_headers/Write/pugixml/src"),
                .headerSearchPath("shared_headers/Write/miniz"),
                .headerSearchPath("shared_headers/Write/stb"),
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

