# Vendored: Stylus Labs Write engine

`Write/` is a copy of the [Stylus Labs Write](https://github.com/styluslabs/Write)
C++ engine that the app's native library (`liborgwritenotes_jni.so`) builds from
(see `WRITE_ROOT` in `android/app/src/main/cpp/CMakeLists.txt`). It is committed
here as plain files, not as a git submodule.

## Provenance

| Component | Upstream | Commit |
| :--- | :--- | :--- |
| `Write/` | https://github.com/styluslabs/Write | `401b65d5fe0294cc83171b76a0273b6df3afc979` (2026-06-23, "set any config option") plus one local patch, below |
| `Write/stb` | https://github.com/nothings/stb | `f75e8d1cad7d90d72ef7a4661f1b994ef78b4e31` |
| `Write/pugixml` | https://github.com/zeux/pugixml | `101f32884f794130d16b8c663883528caefd5b7f` |
| `Write/miniz` | https://github.com/richgel999/miniz | `336fca3bd497d0c10e156b9ed08468d32bfeef94` |
| `Write/nanovgXC` | https://github.com/styluslabs/nanovgXC | `dce4a2cc5ddece35bafd5a0cd7d917113981837e` |
| `Write/ulib` | https://github.com/styluslabs/ulib | `e3d789ed09d1e04a06bf415af35c802715c70aa0` |
| `Write/usvg` | https://github.com/styluslabs/usvg | `d086784ad5b8879f8000e22d7916e425917f75ac` |

`Write/SDL` and `Write/ugui` are listed in the engine's own `.gitmodules` but were
never checked out and are not used by the Android build, so they are not included.

## Local patch on top of upstream

- `fix(syncscribble): preserve viewBox-free pixel coordinates, page background color,
  and SAVE_COPY stream state` (originally commit `253db53`)

## Updating

To pull in upstream changes, diff the upstream commit above against a fresh clone
of the repositories listed, apply the differences here, and update the table.
