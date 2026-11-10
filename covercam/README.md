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
| Tes unit | `./gradlew :covercam:testDebugUnitTest` | 21 tes hijau |

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
| HDRV | Video 10-bit, HEVC Main10 lewat MediaCodec/MediaMuxer, tanpa suara: **HLG 30 fps** atau **log 60 fps** (lihat di bawah) |

PRO mulai serba otomatis: satu baris nilai (ISO, S, F, EV, WB) di tepi bawah gambar. Ketuk satu nilai dan
slider-nya terbuka di atas baris itu. Menggeser ISO atau shutter mematikan auto exposure (nilai awalnya diambil
dari metering terakhir); menggeser EV mengembalikannya; menggeser fokus mematikan autofocus, tap di preview
mengembalikannya. Chip **AUTO** di samping slider mengembalikan semuanya. WB membuka dua baris: lima preset, lalu
slider Kelvin selebar gambar (di gambar 9:16 satu baris tidak cukup untuk keduanya).

## Video log 10-bit 60 fps (mode HDRV)

Pengaturan **Video 10-bit (HDRV)** memilih isi mode HDRV: `HLG 30 FPS` (bawaan) atau `LOG 60 FPS`. Saat log aktif
lencana `LOG` muncul dan preview sudah memakai kurva yang sama dengan berkas: gambar datar di layar adalah gambar
yang disimpan.

| | HLG | LOG |
|---|---|---|
| Berkas | HEVC Main10, 1920×1080, 30 fps, target 40 Mb/s | HEVC Main10, 1920×1080, 60 fps, target 60 Mb/s |
| Kurva | HLG dari HAL | GoPro Protune Flat: `y = ln(112x + 1) / ln(113)` |
| Lensa | id `0` dan `2` | 60 fps hanya lensa utama (id `0`); ultrawide (id `2`) tetap 30 fps dan status menuliskannya |

Membuka klip log di pengolah video: gamma masukan **GoPro Protune Flat**, gamut **Rec.2020**. Tanpa preset itu,
rumus baliknya `x = (113^y − 1) / 112` dengan `y = (kode − 64) / 876` (10-bit, limited range). `x = 0.18` adalah
abu-abu tengah (`y ≈ 0.65`), `x = 1` sensor yang jenuh.

Terukur di HP (2026-11-10, `ffprobe` pada berkas yang ditarik):

| Rekaman | Berkas | Status di layar |
|---|---|---|
| LOG, lensa utama | `hevc Main 10 yuv420p10le`, 533 frame / 8,86 s = 60,2 fps, 59,6 Mb/s | `merekam log 60 fps` |
| LOG, ultrawide | 174 frame / 5,83 s = 29,8 fps | `merekam log 30 fps` |
| HLG, lensa utama | 166 frame / 5,56 s = 29,8 fps, 45,7 Mb/s | `merekam hdr` |

Batas log:

- **Berkas tetap bertanda HLG** (`arib-std-b67`, BT.2020): encoder mengabaikan permintaan transfer lain. Pemutar yang
  percaya tanda itu menampilkannya sebagai HDR yang salah; di pengolah video gammanya harus dipilih sendiri.
- Tanpa suara, dan tanpa "view assist": preview memang datar.
- Preview sebelum merekam berjalan 30 fps; 60 fps dimulai bersama rekaman. Laju yang dilaporkan status dibaca dari
  `SENSOR_FRAME_DURATION`, bukan dari yang diminta.
- Saat HP panas laju bisa turun: probe pada status termal 2 sekali memberi 55,9 fps.
- Gamut (BT.2020) diambil dari tanda stream, tidak diukur. Posisi 64 titik kurva di HAL (`i/63` atau `i/64`) juga
  tidak diukur: selisihnya paling banyak 0,02 stop.

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
- Saat cover diputar, kontrol punya pita tetap di tepi bawah (120 dp: deret laci di atas deret tombol), jadi shutter
  tidak berpindah saat rasio diganti. Gambar berdiri di atas pita itu; bila sisa hitamnya lebih dari yang dibutuhkan
  pita (gambar 16:9), hitam di atas gambar dibuat sama tinggi dengan pita sehingga gambar berada di tengah. Terukur:
  16:9 di y 170–538, 4:3 di y 48–538, 1:1 dikecilkan menjadi 538 px.
- Gambar 1:1 hampir selebar cover, jadi ia dikecilkan sampai kontrol muat di bidang hitam, di kedua posisi cover.
- Aktivitas tidak meminta orientasi tertentu. Cover mengabaikan permintaan itu, dan di perangkat lain
  permintaan `portrait` membuat jendela di-letterbox.
- Token desain sama dengan launcher (hitam OLED, abu-abu, merah `#D71921` hanya sebagai sinyal; Space Grotesk
  untuk isi, Space Mono untuk label). Font dan ikon disalin dari `:app` karena ini APK terpisah.

## Kontrol

- **Geser gambar kiri/kanan** — mode berikutnya/sebelumnya; nama mode muncul sebentar di tengah.
- **Nama mode** (di samping shutter) — daftar sepuluh mode dengan keterangan singkat. Kotak kecil di bawah
  nama menunjukkan posisi mode itu di antara sepuluh.
- **Tombol zoom** — tangga `0.5x · 1.0x · 2x · 4x` (dua pertama berganti lensa). Pinch tetap
  bekerja sampai batas lensa (maks. 8×).
- **Tombol opsi** — senter, timer (atau jeda timelapse), rasio, grid, ganti kamera, pengaturan.
- Kedua laci (zoom dan opsi) diakhiri tombol tutup, dan menutup sendiri beberapa detik setelah perubahan terakhir.
- **Lingkaran kecil bergambar** — galeri in-app: foto dan video berurutan waktu, bagikan, putar, hapus (dua ketukan).
- **Tap gambar** — fokus dan metering di titik itu. **Tekan-lama** — kunci AE/AF.
- **Tombol besar / volume bawah** — foto di mode foto; mulai dan **berhenti** di VIDEO, SLOWMO, HDRV, TLAPSE.
  Saat merekam video, tempat nama mode diisi tombol jeda/lanjut. Saat timer menghitung, tekan lagi untuk batal.
- **Volume atas** — mode berikutnya. Mode tidak bisa diganti selama merekam.
- **Back** — menutup panel yang sedang terbuka dulu (viewer, galeri, daftar mode, pengaturan, kalibrasi).

Di atas gambar hanya muncul yang sedang berlaku: jam rekam, `AE/AF TERKUNCI`, `RAW`, `TIMER 3S`, dan pesan
singkat yang memudar sendiri.

## Rasio

Preview dibuka dalam bentuk berkas yang akan disimpan, jadi gambar di layar adalah gambar yang tersimpan.

| Yang disimpan | Bentuk | Stream preview | Stream berkas |
|---|---|---|---|
| JPEG (FOTO, PRO, NIGHT, BOKEH, HDR, TLAPSE, PANO) | pilihan tombol rasio: 4:3, 16:9, 1:1 | 1920×1440, 1920×1080, 1088×1088 | 4000×3000, 4000×2252, 2992×2992 |
| DNG (RAW menyala, di FOTO dan PRO) | pilihan tombol rasio, ditulis sebagai crop | sama dengan JPEG | 4000×3000 RAW16, seluruh sensor |
| Video (VIDEO, SLOWMO, HDRV) | 16:9 | 1920×1080 | ukuran perekam |

Di mode video tombol rasio diredupkan dan menampilkan 16:9; pilihan untuk foto tetap tersimpan. DNG selalu berisi
seluruh sensor: rasio ditulis sebagai `DefaultCropOrigin` / `DefaultCropSize`, jadi pengolah RAW membukanya dalam
bentuk yang dibidik dan bagian di luar crop tetap ada di berkas. Berpindah antara mode yang bentuknya berbeda membuka ulang kamera. Ukuran di atas dari daftar stream HAL
untuk id `0` dan `2` (`dumpsys media.camera`); lensa yang tidak punya bentuk itu memakai stream terbesarnya.

Terukur di HP ini (2026-11-10, build release, `dumpsys media.camera` + tangkapan layar cover):

| Yang diuji | Hasil |
|---|---|
| Tombol rasio, id `0` dan `2` | stream berganti 1920×1440 + 4000×3000, 1920×1080 + 4000×2252, 1088×1088 + 2992×2992; gambar di layar 3:4, 9:16, 1:1 |
| Foto 16:9 dan 1:1 (id `0`) | berkas 4000×2252 dan 2992×2992, bingkainya sama dengan preview |
| RAW menyala (id `0`) | tombol rasio tetap aktif: preview 1920×1440, 1920×1080, 1088×1088 di samping RAW16 4000×3000; DNG 16:9 dan 1:1 terbaca 4000×2250 dan 3000×3000 di macOS (`sips`), bingkainya sama dengan preview |
| Home lalu buka lagi | stream tetap 1920×1440, gambar tidak melar (sebelumnya 1088×1088) |
| VIDEO (id `2`), HDRV dan SLOWMO (id `0`) | preview 1920×1080 sebelum dan selama rekam, berkas tersimpan (perekam 1920×1080; slow-mo 1080p@240) |
| Laci zoom | tombol tutup tampil dan menutup laci |

## Settings

Suara shutter · geotag · garis level · histogram · focus peaking · RAW DNG · rotasi dari IMU · resolusi video ·
FPS video · video 10-bit (HLG/log) · jeda timelapse · kualitas JPEG · kalibrasi preview · kemampuan HAL · reset kalibrasi (semua
persisten di SharedPreferences, kecuali histogram dan focus peaking).

RAW DNG hanya berlaku di FOTO dan PRO: di mode lain (NIGHT, HDR, BOKEH, PANO, TLAPSE) stream JPEG dipakai,
karena penggabungan frame membutuhkannya.

## Orientasi dan kalibrasi

Layar cover punya `installOrientation = ROTATION_90`, jadi orientasi tidak bisa diturunkan
langsung dari `Display.getRotation()`. Yang dikalibrasi hanya **preview**; foto, DNG, dan video mengikuti.

1. `extra_<id>` — offset rotasi preview per lensa (default `2`→90°, `0`→270°, lainnya 90°).
2. `mirror_<id>` — balik kiri-kanan per lensa (default id `0`, `1`, `3`).

   Keduanya diubah dari Pengaturan → **Kalibrasi preview**: panel tertutup dan tombol PUTAR / CERMIN / SELESAI
   muncul di atas gambar, jadi hasil tiap ketukan langsung terlihat (di gambar 9:16 SELESAI turun ke baris kedua).
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
- **60 fps hanya di log HDRV, lensa utama**: untuk semua id HAL hanya mendaftarkan rentang sampai `[30, 30]`
  (`dumpsys media.camera`), dan meminta `[60, 60]` saja tetap memberi 33,3 ms per frame. Yang menjalankan sensor di
  60 adalah kunci Samsung (lihat Catatan teknis). Mode VIDEO (MediaRecorder) belum memakainya: baris FPS di
  pengaturan menulis `30 (maks. mode Video)`.
- **Video lensa utama masih tercermin**: `MediaRecorder` hanya menerima rotasi, bukan pencerminan. Foto dan DNG sudah benar.
- Preview SLOWMO tidak dicerminkan dan tidak diputar oleh aplikasi (SurfaceView); ia menampilkan gambar seperti klipnya.
- HDRV tanpa audio.
- SLOWMO hanya di kamera belakang; kamera depan ditolak, dan rekaman tanpa frame dihapus otomatis.
- PANO hanya 3 frame; kualitas bergantung pada penggeseran pengguna.
- Night/HDR memproses per-tile 256 baris agar 12 MP tidak menghabiskan RAM.
- Thumbnail DNG di galeri in-app hanya bertuliskan `RAW` (thumbnailer sistem tidak menggambar DNG ini).
- **DNG di tempat gelap tetap berbutir**: DNG adalah satu frame sensor tanpa noise reduction. Terukur pada DNG ruang
  gelap (id `2`, 2026-11-10): median tiap kanal hanya 7–20 tingkat di atas hitam dari 959, jadi aplikasi pengolah
  harus menguatkannya sekitar 5 stop. Yang dibuang aplikasi hanya hot pixel (bintik merah/hijau/biru tunggal).

Belum diuji di HP: penyelarasan NIGHT saat tangan bergoyang (baru tes unit dan adegan diam: geseran terukur 0),
lensa depan (id `1`, `3`: tertutup saat HP dilipat), PANO setelah perubahan ini, rotasi dari IMU, panel izin
(izin sudah diberikan), tombol bagikan dan putar, arah kiri-kanan tap-to-focus (arah atas-bawah terbukti:
mengetuk area gelap menerangkan gambar).

Belum dilihat di HP setelah perubahan rasio: gambar 1:1 saat cover tegak (yang terlihat: cover diputar), tap-to-focus
pada preview 16:9 dan 1:1 (baru tes unit), DNG dengan eksposur panjang di ruang yang benar-benar gelap, crop DNG di
galeri dan pengolah RAW di HP (yang dicek: macOS).

## Catatan teknis

- **60 fps**: `samsung.android.control.recordingMaxFps` dan `recordingMinFps` (int32) = 60, sebagai session parameter
  dan di setiap request, bersama `CONTROL_AE_TARGET_FPS_RANGE [60, 60]`. Terukur di id `0`: jarak frame 16,6 ms.
  Ultrawide menerima kunci itu tetapi tetap 33,3 ms.
- **Kurva log**: `TONEMAP_MODE_CONTRAST_CURVE` menggantikan kurva HLG di stream 10-bit (kode = 64 + 876·y) dan gamma
  di preview 8-bit. HAL mengabaikan posisi x tiap titik (64 keluaran dianggap berjarak rata), dan menarik ruas
  terakhir kurva ke putih penuh. Kurva yang berakhir lebih rendah karenanya rusak: dengan Apple Log (sensor jenuh =
  0,69) setiap kanal yang hampir jenuh melompat ke 1,0 (terukur: B 0,995 di samping R 0,65 dan G 0,67), terlihat
  sebagai cincin hijau dan ungu di sekitar lampu. Protune Flat berakhir tepat di 1, jadi tidak ada lompatan.

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
- Ukuran buffer preview dipasang tepat sebelum setiap sesi dibuat. TextureView mengembalikan buffer ke ukuran view
  saat layer-nya dibuat ulang (setelah Home) atau saat view berubah ukuran, dan kamera mengambil ukuran stream dari
  buffer itu. Terukur sebelum diperbaiki: sesudah Home stream preview menjadi 1088×1088 dan gambar melar.
- Hot pixel dibuang dari mosaic sebelum DNG ditulis: photosite yang jauh di atas kedelapan tetangga sewarnanya
  disamakan dengan tetangga paling terang. Terukur pada DNG ruang gelap dari HP ini: 3.244 dari 12 juta photosite,
  sebagian bernilai penuh di atas latar yang hampir hitam. Di HP dengan build ini: ruang terang id `2` 604 dibuang
  (sisa 21 titik terisolasi), frame gelap id `0` ISO 3075 1/46326 s 175 dibuang dan nilai tertinggi yang tersisa
  42 tingkat di atas hitam.
- Kelvin manual (PRO) dihitung dari kalibrasi sensor itu sendiri (`WhiteBalance.kt`): warna benda hitam pada suhu
  itu dibawa ke ruang sensor dengan `SENSOR_COLOR_TRANSFORM1/2` dan `CALIBRATION_TRANSFORM1/2` (dicampur menurut
  1/T antara kedua illuminant, seperti aturan DNG); gain = kebalikannya, dan `COLOR_CORRECTION_TRANSFORM` diisi
  `FORWARD_MATRIX` → sRGB. Rumus lama memakai warna benda hitam di layar (gain hampir 1 : 1 : 1) dan membiarkan
  matriks identitas, sehingga gambar hijau. Terukur di HP ini (id `0`, lampu kantor, plafon putih, R/G dan B/G):
  AWB 1,23 dan 0,86; 3732K 1,01 dan 0,92; 6515K 1,38 dan 0,52; 2552K 0,52 dan 1,32. JPEG yang tersimpan sama dengan
  preview (6515K: R/G 1,23 di keduanya), dan DNG 6515K membawa `AsShotNeutral` 0,3995 · 1 · 0,7447.
- DNG ditulis dengan matriks warna dari `SENSOR_COLOR_TRANSFORM1/2`, `CALIBRATION_TRANSFORM1/2`,
  `FORWARD_MATRIX1/2`, dan `REFERENCE_ILLUMINANT1/2` — dua illuminant (D65 + Standard A).
