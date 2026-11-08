# CoverCam

Kamera untuk **cover screen Galaxy Z Flip5** (748×720, `display 1`), dibuat sebagai modul
di repo ini. Fokusnya: satu aplikasi kamera yang benar-benar bisa dipakai saat layar utama mati.

```sh
./gradlew :covercam:assembleRelease
adb install -r covercam/build/outputs/apk/release/covercam-release.apk
```

> [!NOTE]
> `applicationId` sengaja `com.samsung.android.ruler`. Samsung memasang allowlist paket di
> `cameraserver` untuk lensa "hidden" (56 = main fisik, 58 = ultrawide); nama paket itu yang
> dipakai semua mod GCam Samsung. Tanpa itu hanya ID publik `0`/`1`/`3` yang terlihat.

## Penerapan

```sh
./gradlew :covercam:assembleRelease
adb install -r covercam/build/outputs/apk/release/covercam-release.apk
# izin runtime: dialog di cover tidak selalu muncul, jadi berikan lewat adb
for p in CAMERA RECORD_AUDIO ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION; do
  adb shell pm grant com.samsung.android.ruler android.permission.$p
done
adb shell am start --display 1 -n com.samsung.android.ruler/com.mazwas.covercam.MainActivity
```

Verifikasi:

| Cek | Perintah | Harapan |
|---|---|---|
| APK terpasang | `adb shell pm list packages \| grep ruler` | `package:com.samsung.android.ruler` |
| Izin aktif | `adb shell dumpsys package com.samsung.android.ruler \| grep -E "CAMERA\|RECORD_AUDIO"` | `granted=true` |
| Hasil tersimpan | `adb shell ls /sdcard/DCIM/CoverCam` | ada file JPEG/MP4/DNG baru |

Mundur: `adb uninstall com.samsung.android.ruler`. Tidak ada izin sistem lain yang dipasang (tanpa Shizuku,
tanpa accessibility), jadi tidak ada yang perlu dibersihkan.

## Mode

| Mode | Isi |
|---|---|
| FOTO | JPEG resolusi penuh, tap-to-focus, zoom, timer, torch |
| VIDEO | H.264, 1080p/4K, 30/60 fps, stabilisasi, pause/resume |
| PRO | ISO, shutter, EV, fokus manual, WB preset + **Kelvin**, histogram, focus peaking |
| NIGHT | burst 5 frame, dirata-rata per-tile (resolusi penuh, alignment ±6 px) |
| HDR | bracket 3 eksposur, exposure fusion berbobot mid-tone |
| BOKEH | oval mask + blur latar (bukan depth asli — HAL tidak punya `DEPTH_OUTPUT`) |
| TLAPSE | timelapse 1/2/5 detik, frame JPEG berurutan |
| SLOWMO | constrained high-speed session (probe: 1080p@240) |
| PANO | 3 shot terpandu, overlap dicari SAD, seam di-feather |
| HDRV | HEVC Main10 + HLG lewat MediaCodec/MediaMuxer (video-only, eksperimental) |

## Kontrol

- **Tap preview** — fokus di titik itu.
- **Tekan-lama preview** — kunci AE/AF.
- **Pinch** — zoom 1×–8×.
- **Volume bawah** — shutter. **Volume atas** — mode berikutnya.
- **Label COVERCAM**: tap = rotasi preview +90° untuk lensa aktif, tekan-lama = mirror.
- **Tombol GAL / thumbnail** — galeri in-app (share, hapus).

## Settings

RASIO · SHUTTER SOUND · GEOTAG · LEVEL · RAW DNG · DNG ROT · IMU ROT · VIDEO · FPS ·
TLAPSE · HAL CAPS · RESET KALIBRASI · kualitas JPEG (semua persisten di SharedPreferences).

## Kalibrasi

Layar cover punya `installOrientation = ROTATION_90`, jadi orientasi tidak bisa diturunkan
langsung dari `Display.getRotation()`. Empat mekanisme yang dipakai:

1. `extra_<id>` — offset rotasi per lensa (default `2`→90°, `0`→270°, lainnya 90°), dikoreksi lewat label COVERCAM.
2. `mirror_<id>` — mirror horizontal per lensa (default id `0`, `1`, `3`).
3. `dngRotation` — tag orientasi DNG, terpisah karena DNG ditulis sendiri, bukan oleh HAL.
4. `imuReference` — bila IMU ROT dinyalakan, posisi saat itu menjadi acuan 0°.

Semuanya tersimpan di SharedPreferences. `RESET KALIBRASI` mengembalikan keempatnya ke nilai default;
acuan IMU diambil ulang saat HP berikutnya diputar.

## Batasan

- **Depth asli mustahil** — dump HAL tidak memuat satu pun device dengan `DEPTH_OUTPUT`.
- HDRV tanpa audio, dan belum terverifikasi di perangkat.
- SLOWMO hanya di kamera belakang; kamera depan ditolak, dan rekaman tanpa frame dihapus otomatis.
- PANO hanya 3 frame; kualitas bergantung pada penggeseran pengguna.
- Night/HDR memproses per-tile 256 baris agar 12 MP tidak menghabiskan RAM.

## Catatan teknis

- `FLAG_KEEP_SCREEN_ON` wajib: saat layar tidur, Android menonaktifkan kamera
  (`CAMERA_DISABLED`) dan sesi mati.
- Sesi dibuka ulang otomatis saat `onResume` dan setelah error (retry 3× dengan backoff).
- DNG ditulis dengan matriks warna dari `SENSOR_COLOR_TRANSFORM1/2`, `CALIBRATION_TRANSFORM1/2`,
  `FORWARD_MATRIX1/2`, dan `REFERENCE_ILLUMINANT1/2` — dua illuminant (D65 + Standard A).
