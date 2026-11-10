# CoverCam

Kamera untuk **cover screen Galaxy Z Flip5** (748×720, `display 1`), dibuat sebagai modul
di repo ini. Fokusnya: satu aplikasi kamera yang benar-benar bisa dipakai saat layar utama mati.

```sh
./gradlew :covercam:assembleRelease
adb install -r covercam/build/outputs/apk/release/covercam-release.apk
```

> [!NOTE]
> `applicationId` sengaja `com.samsung.android.ruler`, nama paket yang dipakai mod GCam Samsung karena
> `cameraserver` Samsung menyaring lensa per paket. Di HP ini (One UI 9) dengan paket itu `cameraIdList`
> berisi `0,1,2,3`: belakang `0` (main) dan `2` (ultrawide), depan `1` dan `3`. `dumpsys media.camera`
> juga memuat `56` (main fisik, kemampuan sama dengan `0`) dan `58` (ultrawide fisik, sama dengan `2`), tetapi
> keduanya tidak muncul di `cameraIdList` aplikasi. Belum diuji apa yang hilang kalau paketnya diganti.

## Penerapan

```sh
./gradlew :covercam:assembleRelease          # R8 aktif, sama seperti :app
adb install -r covercam/build/outputs/apk/release/covercam-release.apk
adb shell cmd package compile -m speed -f com.samsung.android.ruler
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
| Kamera dilepas saat keluar | tekan Home, lalu `adb shell dumpsys media.camera \| grep "Device 0"` | `Device 0 is closed, no client instance` |
| Tes unit | `./gradlew :covercam:testDebugUnitTest` | 13 tes hijau |

Mundur: `adb uninstall com.samsung.android.ruler`. Tidak ada izin sistem lain yang dipasang (tanpa Shizuku,
tanpa accessibility), jadi tidak ada yang perlu dibersihkan.

## Mode

| Mode | Isi |
|---|---|
| FOTO | JPEG resolusi penuh, tap-to-focus, zoom, timer, senter |
| VIDEO | H.264, 1080p/4K, 30 fps, stabilisasi, jeda/lanjut |
| PRO | ISO, shutter, EV, fokus manual, WB preset + **Kelvin**, histogram, focus peaking |
| NIGHT | burst 5 frame, tiap frame digeser ke frame pertama (sampai ±24 px) lalu dirata-rata per-tile, resolusi penuh |
| HDR | bracket 3 eksposur (WB dikunci), exposure fusion berbobot mid-tone |
| BOKEH | subjek oval bertepi lembut di atas latar yang di-blur (bukan depth asli — HAL tidak punya `DEPTH_OUTPUT`) |
| TLAPSE | timelapse 1/2/5 detik, frame JPEG berurutan |
| SLOWMO | constrained high-speed session (probe: 1080p@240), preview tetap hidup |
| PANO | 3 shot terpandu, overlap dicari SAD, seam di-feather |
| HDRV | HEVC Main10 + HLG lewat MediaCodec/MediaMuxer (video-only, eksperimental) |

PRO mulai serba otomatis: satu baris nilai (ISO, S, F, EV, WB) di tepi bawah gambar. Ketuk satu nilai dan
slider-nya terbuka di atas baris itu. Menggeser ISO atau shutter mematikan auto exposure (nilai awalnya diambil
dari metering terakhir); menggeser EV mengembalikannya; menggeser fokus mematikan autofocus, tap di preview
mengembalikannya. Chip **AUTO** di samping slider mengembalikan semuanya.

## Tata letak: gambar berdiri sendiri, kontrol di bidang hitam

Gambar 3:4 di cover yang hampir persegi menyisakan bidang hitam di kiri-kanannya (atau di bawahnya saat cover
diputar). Di OLED itu piksel mati, jadi kontrol ditaruh di sana dan tidak ada yang menumpuk di atas foto.

```
 cover tegak (748×720)                 cover diputar (720×748)
┌───────┬────────────────┬───────┐    ┌────────────────────────┬──┐
│ (1×)  │                │ FOTO  │    │                        │  │
│       │                │ ▪▫▫▫▫ │    │      gambar 4:3        │n │
│ (⋮⋮)  │   gambar 3:4   │       │    │                        │a │
│       │                │  (◯)  │    ├────────────────────────┤v │
│ [▣]   │                │       │    │ (1×)(⋮⋮)[▣]   FOTO (◯) │  │
└───────┴────────────────┴───────┘    └────────────────────────┴──┘
```

Saat diam hanya ada lima sasaran sentuh: zoom, opsi, galeri, mode, shutter. Sisanya dibuka dari kelimanya.
Polanya diambil dari kamera Samsung di cover (dilihat langsung di HP ini): satu deret tombol bulat kecil, laci
yang membuka di tempat, dan mode sebagai halaman yang digeser. Bedanya: Samsung tidak punya tombol shutter
(ketuk layar = jepret) dan memotong gambar agar penuh; di sini ketuk = fokus dan gambar ditampilkan utuh.

- Bidang yang dipilih mengikuti bentuk gambar dan inset sistem, bukan angka tetap: cover berputar mengikuti
  launcher (`748×720` atau `720×748`) dan navigation bar 3 tombol pindah dari bawah ke samping.
- Aktivitas tidak meminta orientasi tertentu. Cover mengabaikan permintaan itu, dan di perangkat lain
  permintaan `portrait` membuat jendela di-letterbox.
- Token desain sama dengan launcher (hitam OLED, abu-abu, merah `#D71921` hanya sebagai sinyal; Space Grotesk
  untuk isi, Space Mono untuk label). Font dan ikon disalin dari `:app` karena ini APK terpisah.

## Kontrol

- **Geser gambar kiri/kanan** — mode berikutnya/sebelumnya; nama mode muncul sebentar di tengah.
- **Nama mode** (di samping shutter) — daftar sepuluh mode dengan keterangan singkat. Kotak kecil di bawah
  nama menunjukkan posisi mode itu di antara sepuluh.
- **Tombol zoom** — tangga `0.5x · 1.0x · 2x · 4x` (dua pertama berganti lensa), menutup sendiri. Pinch tetap
  bekerja sampai batas lensa (maks. 8×).
- **Tombol opsi** — senter, timer (atau jeda timelapse), rasio, grid, ganti kamera, pengaturan.
- **Lingkaran kecil bergambar** — galeri in-app: foto dan video berurutan waktu, bagikan, putar, hapus (dua ketukan).
- **Tap gambar** — fokus dan metering di titik itu. **Tekan-lama** — kunci AE/AF.
- **Tombol besar / volume bawah** — foto di mode foto; mulai dan **berhenti** di VIDEO, SLOWMO, HDRV, TLAPSE.
  Saat merekam video, tempat nama mode diisi tombol jeda/lanjut. Saat timer menghitung, tekan lagi untuk batal.
- **Volume atas** — mode berikutnya. Mode tidak bisa diganti selama merekam.
- **Back** — menutup panel yang sedang terbuka dulu (viewer, galeri, daftar mode, pengaturan, kalibrasi).

Di atas gambar hanya muncul yang sedang berlaku: jam rekam, `AE/AF TERKUNCI`, `RAW`, `TIMER 3S`, dan pesan
singkat yang memudar sendiri.

## Settings

Suara shutter · geotag · garis level · histogram · focus peaking · RAW DNG · rotasi dari IMU · resolusi video ·
FPS video · jeda timelapse · kualitas JPEG · kalibrasi preview · kemampuan HAL · reset kalibrasi (semua
persisten di SharedPreferences, kecuali histogram dan focus peaking).

RAW DNG hanya berlaku di FOTO dan PRO: di mode lain (NIGHT, HDR, BOKEH, PANO, TLAPSE) stream JPEG dipakai,
karena penggabungan frame membutuhkannya.

## Orientasi dan kalibrasi

Layar cover punya `installOrientation = ROTATION_90`, jadi orientasi tidak bisa diturunkan
langsung dari `Display.getRotation()`. Yang dikalibrasi hanya **preview**; foto, DNG, dan video mengikuti.

1. `extra_<id>` — offset rotasi preview per lensa (default `2`→90°, `0`→270°, lainnya 90°).
2. `mirror_<id>` — balik kiri-kanan per lensa (default id `0`, `1`, `3`).

   Keduanya diubah dari Pengaturan → **Kalibrasi preview**: panel tertutup dan tombol PUTAR / CERMIN / SELESAI
   muncul di atas gambar, jadi hasil tiap ketukan langsung terlihat.
3. `imuReference` — bila rotasi dari IMU dinyalakan, posisi saat itu menjadi acuan 0°.

Aturannya: **yang tersimpan sama dengan yang terlihat di preview.**

- Rotasi berkas = rotasi preview + orientasi sensor (rumus `JPEG_ORIENTATION` Camera2; dibalik untuk lensa depan).
  Sensor id `0` dan `2` sama-sama melaporkan 90°.
- Bila preview dibalik kiri-kanan, foto dan DNG memakai kode EXIF tercermin (2/4/5/7).
- Video memakai `setOrientationHint` dengan rotasi yang sama.

Terukur di HP ini (2026-11-10):

| Fakta | Bukti |
|---|---|
| HAL mengirim lensa utama (id `0`) **tercermin**, ultrawide (id `2`) tidak | foto papan tulis: tulisan terbaca terbalik dan angka jam berlawanan arah; objek yang sama di foto id `2` urutannya normal |
| Rumus lama (`rotasi preview + 270`) salah 180° untuk sensor 90° | foto id `0` build lama terbalik atas-bawah terhadap preview dan terhadap DNG |
| Video lama tanpa metadata rotasi | `ffprobe` berkas 5 Nov: tidak ada `rotation`, gambar rebah |
| Setelah diperbaiki: foto = preview | id `0` EXIF 5, id `2` EXIF 8; label "BOX FILE" terbaca dengan arah huruf yang benar |
| Video, slow-mo, HDR video membawa rotasi | `ffprobe`: `rotation=-90` pada ketiganya |

`RESET KALIBRASI` mengembalikan ketiganya ke nilai default; acuan IMU diambil ulang saat HP berikutnya diputar.

## Batasan

- **Depth asli mustahil** — dump HAL tidak memuat satu pun device dengan `DEPTH_OUTPUT`.
- **60 fps tidak tersedia**: untuk id `0` dan `2` HAL hanya mendaftarkan rentang sampai `[30, 30]` dan semua ukuran
  berdurasi frame minimum 33,3 ms (`dumpsys media.camera`). Baris FPS di pengaturan menulis `30 (maks. lensa ini)`.
- **Video lensa utama masih tercermin**: `MediaRecorder` hanya menerima rotasi, bukan pencerminan. Foto dan DNG sudah benar.
- Preview SLOWMO tidak dicerminkan dan tidak diputar oleh aplikasi (SurfaceView); ia menampilkan gambar seperti klipnya.
- HDRV tanpa audio.
- SLOWMO hanya di kamera belakang; kamera depan ditolak, dan rekaman tanpa frame dihapus otomatis.
- PANO hanya 3 frame; kualitas bergantung pada penggeseran pengguna.
- Night/HDR memproses per-tile 256 baris agar 12 MP tidak menghabiskan RAM.
- Thumbnail DNG di galeri in-app hanya bertuliskan `RAW` (thumbnailer sistem tidak menggambar DNG ini).

Belum diuji di HP: penyelarasan NIGHT saat tangan bergoyang (baru tes unit dan adegan diam: geseran terukur 0),
lensa depan (id `1`, `3`: tertutup saat HP dilipat), PANO setelah perubahan ini, rotasi dari IMU, panel izin
(izin sudah diberikan), tombol bagikan dan putar, arah kiri-kanan tap-to-focus (arah atas-bawah terbukti:
mengetuk area gelap menerangkan gambar).

## Catatan teknis

- `FLAG_KEEP_SCREEN_ON` wajib: saat layar tidur, Android menonaktifkan kamera
  (`CAMERA_DISABLED`) dan sesi mati.
- Saat aktivitas berhenti (`onStop`) rekaman diselesaikan dulu, lalu kamera dan sensor dilepas; `onResume` membukanya
  lagi. Error membuka kamera dicoba ulang 3× dengan backoff.
- Satu jalur berhenti untuk video, slow-mo, dan HDR video; take yang tidak berisi frame dihapus dan dilaporkan.
- Slow-mo dimulai dua tahap: preview SurfaceView dipasang dulu, sesi high-speed dibuat begitu surface-nya ada
  (sesi itu menuntut surface preview sejak dibuat).
- Mode manual memakai `CONTROL_MODE_AUTO` + `CONTROL_AE_MODE_OFF`: `CONTROL_MODE_OFF` mematikan semua
  `android.control.*`, termasuk preset white balance.
- Penyimpanan dan penggabungan berjalan di satu thread kerja; DNG ditulis langsung ke berkas (header di memori,
  24 MB piksel dialirkan).
- DNG ditulis dengan matriks warna dari `SENSOR_COLOR_TRANSFORM1/2`, `CALIBRATION_TRANSFORM1/2`,
  `FORWARD_MATRIX1/2`, dan `REFERENCE_ILLUMINANT1/2` — dua illuminant (D65 + Standard A).
