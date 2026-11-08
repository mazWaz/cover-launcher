# Bagaimana Cover Launcher bekerja

Bukan launcher yang "dipilih sebagai home". Aplikasi ini **memaksa sistem** memakai cover:

1. state lipat dipaksa `CLOSED` (`cmd device_state state 0`),
2. cover home Samsung (`SubHomeActivity`) selalu ditimpa launcher,
3. setiap app dibuka di `display 1` lewat shell Shizuku, bukan `startActivity` biasa.

Karena itu tidak perlu root dan tidak perlu MultiStar. Allowlist cover milik MultiStar diisi launcher sendiri
(§4), sebagai jalur cadangan saat Shizuku mati.

## Peta subsistem

| Bagian | Kelas | Peran |
|---|---|---|
| `App` | `App.kt` | start Koin; saat binder Shizuku datang: minta izin Shizuku bila belum ada, lalu `Grants.apply()` (juga begitu izin diberikan) |
| Izin | `feature/cover/Grants.kt` | 6 perubahan sistem: state lipat (+ penjaga), overlay appop, listener notifikasi, DND, accessibility, allowlist cover MultiStar; plus pop-up Samsung, hanya saat pilihan pop-up berubah (§9) |
| Home takeover | `feature/cover/CoverService.kt` | accessibility service: timpa cover home, tarik app dari layar mati, orkestrasi autostart Shizuku |
| Rotasi & density | `feature/cover/CoverDisplay.kt` | untuk app di depan: rotasi (tile rotate/locked dari sensor orientasi, atau rotasi app sendiri) dan density (§10) |
| Banking mode | `Grants.bankingMode` + proses shell `coverlauncher-bank` | `CoverService` mati selama app bank di depan (§12) |
| Heat guard | `CoverService.onTemperature` | peringatan 42 °C, pemangkasan 45 °C (§13) |
| AOD | `feature/cover/CoverAod.kt` | overlay hitam dengan `AodScreen` di atas AOD Samsung selama cover doze (§11) |
| Pop-up | `feature/cover/CoverPopup.kt` | jendela overlay `CoverService` di display 1: notifikasi baru yang penting, 5 s (§9) |
| Autostart | `feature/cover/ShizukuAutoStart.kt` | sekali per boot: buka Shizuku dan tekan Start |
| Shell | `core/system/Shell.kt` | `Shizuku.newProcess` (reflection) → perintah sebagai uid 2000 |
| Buka target | `feature/launcher/data/CoverOpener.kt` | app / notifikasi / halaman settings ke display 1 |
| Data | `feature/launcher/data/*` | apps, ikon, notifikasi, status sistem, settings, wallpaper |
| State | `feature/launcher/presentation/LauncherViewModel.kt` | 5 flow digabung menjadi satu `LauncherState` |
| UI | `presentation/HomePage|AppDrawer|NotificationPanel`, `settings/SettingsScreen` | composable stateless |

## 1. Memaksa state lipat

`Grants.apply()` menjalankan, sebagai shell:

```sh
cmd device_state state 0                                  # CLOSED: hanya display 1 yang dipakai
appops set <pkg> SYSTEM_ALERT_WINDOW allow                # boleh start activity dari background
cmd notification allow_listener <pkg>/....NotificationListener
cmd notification allow_dnd <pkg>                          # tile sound boleh masuk Silent
settings put secure enabled_accessibility_services ...    # CoverService, komponen lain dipertahankan
settings put secure ACCESSIBILITY_ENABLED 1
settings put secure multistar_setting_repository ...      # slot 26 + semua app yang bisa dibuka (lihat §4)
```

Dipanggil dari `App.onCreate` lewat `Shizuku.addBinderReceivedListenerSticky`, jadi berjalan **setiap kali
binder Shizuku muncul** — termasuk setelah reboot. Semua perintah idempotent.

**Service macet setelah update.** Sesudah `adb install -r`, sistem pernah membiarkan `CoverService` aktif di
pengaturan tetapi macet di "Binding services" (tanpa `ServiceRecord`, tak dicoba ulang) — cover home, pull-to-cover,
dialog debugging dan keep awake ikut mati. `Grants` karena itu memeriksa 5 s kemudian: kalau `CoverService.connected`
masih false, service dikeluarkan lalu dimasukkan lagi ke `enabled_accessibility_services`, yang membuat sistem
mem-bind ulang (cara ini terbukti memulihkan di HP). Supaya `Grants` jalan sesudah update tanpa menunggu apa pun,
`UpdateReceiver` (`MY_PACKAGE_REPLACED`) membuka launcher di cover, yang ikut menyalakan proses app (terbukti
2026-11-08: launcher kembali sendiri setelah `adb install -r`).

**Penjaga lipat (`keepFolded()` di `core/system/Cover.kt`).** Override `CLOSED` bertahan saat HP dibuka, tetapi
**hilang begitu HP dilipat kembali** (terukur 2026-11-08: base `0 → 2 → 3 → 2 → 0`, override lepas di `0`), jadi
pembukaan berikutnya akan menyalakan layar dalam yang mati. `Grants` karena itu juga menjalankan proses shell
`coverlauncher-fold`: `logcat -s DeviceStateManagerService:D` dan, pada setiap `onStateChanged`, `sleep 1;
cmd device_state state 0`. Permintaan kita sendiri tercatat sebagai `requestStateInternal`, bukan `onStateChanged`,
jadi penjaga tidak memicu dirinya sendiri. Setiap start membunuh penjaga lama dulu (`pkill`, termasuk `logcat`-nya),
jadi hanya ada satu. Ia jalan sebagai uid shell (Shizuku), **tidak pernah** sebagai app — kalau tidak, `pkill` dari
shell tak bisa menghentikannya (bug yang membuat OpenCover menumpuk 12 watcher).

Balik ke normal: `adb shell "pkill -f coverlauncher-fold; cmd device_state state reset"`.

## 2. Cover home selalu dilaporkan balik ke launcher

`CoverService` (accessibility service, bound service = proses dijaga sistem) bereaksi pada window change:

| Event | Aksi |
|---|---|
| `com.android.systemui.subscreen.SubHomeActivity` di display 1 | `startActivity(launcher, coverOptions())` |
| `com.android.systemui.qp.flashlight.SubroomFlashLightSettingsActivity` | `GLOBAL_ACTION_BACK` (Samsung membuka layarnya tiap torch menyala) |
| window Shizuku saat autostart berjalan | state machine `Opened → Pressing → Pressed → Starting → Idle` |
| package lain muncul di display 0 selagi display 0 `STATE_OFF` | `pullToCover()` |
| `UsbDebuggingActivity` / `WifiDebuggingActivity` (SystemUI) di display 0 | `pullToCover()` juga: dialog asli (sidik jari kunci, "Always allow") pindah ke cover dan **dijawab pengguna**, tidak pernah ditekan otomatis — dialog itulah yang mencegah komputer asing mendapat shell. Task-nya sendiri (`taskAffinity=com.android.systemui.usb`), bukan home. Belum diuji dengan dialog asli. |

`coverOptions()` = `ActivityOptions.setLaunchDisplayId(1)` +
`MODE_BACKGROUND_ACTIVITY_START_ALLOWED`; start dari background sah karena appop overlay.

**Quirk Android 17:** Shizuku hanya mengirim binder ke client yang hidup saat server start. `CoverService`
mendengar `ACTION_SCREEN_OFF`: bila `Shizuku.pingBinder()` false, proses mematikan dirinya dan sistem
menghidupkannya lagi (bound a11y service) tepat saat Shizuku mengirim binder.

## 3. Autostart Shizuku (One UI 9)

Start-on-boot milik Shizuku gagal di One UI 9 — ia menemukan wireless debugging lalu tidak pernah konek.
`ShizukuAutoStart` mengambil alih: setelah uptime 90 s (start-on-boot Shizuku selesai ~65 s), **sekali per
boot** (dibandingkan dengan `Settings.Global.BOOT_COUNT`), `LauncherActivity` membuka Shizuku di cover — 5 s
setelah `onResume` dan hanya bila launcher masih di depan, karena binder Shizuku baru sampai sesaat setelah proses
start (tanpa jeda ini, pemasangan pertama membuka Shizuku padahal server-nya hidup). Lalu `CoverService` mencari `CardView` berisi teks "Wireless debugging" dan menekan tombol "Start"
di dalamnya; kembali ke launcher saat `MainActivity` muncul lagi (server sudah hidup).

Batasannya: bergantung pada UI Inggris Shizuku ("Start via Wireless debugging" / "Start"). Shizuku 13.6 punya
terjemahan, termasuk Indonesia ("Mulai melalui Debug Nirkabel" / "Mulai"), jadi kalau bahasa HP bukan Inggris
tombolnya tidak ditemukan dan pengguna harus menekan Start sendiri.

## 4. Membuka sesuatu di cover

| Target | Cara |
|---|---|
| App | `am start --display 1 -a MAIN -c LAUNCHER -f 0x10200000 -n pkg/cls`. Dimulai oleh shell, jadi Samsung melewati pemeriksaan "hanya app MultiStar di cover". Tanpa Shizuku: `startActivity` + `coverOptions()`, yang Samsung izinkan untuk app di daftar cover MultiStar. |
| Daftar cover MultiStar | Secure setting `multistar_setting_repository`: 30 slot dipisah `/`, slot 26 = `pkg,userId` dipisah `;` (`0` bila kosong). Tiap kali Shizuku tersambung, `Grants` menambahkan setiap app yang bisa dibuka (user 0, tanpa launcher sendiri) lewat `withCoverApps()`: hanya menambah, slot lain tak disentuh, tidak menulis bila tak ada yang kurang. Nilai sebelum perubahan pertama disalin ke `/data/local/tmp/coverlauncher-multistar.bak`. Terukur 2026-11-08: 35 → 40 entri, 29 slot lain identik. Uji A/B dengan server Shizuku dihentikan (launcher memakai `startActivity`, tanpa `shell am start` di log): Google TV **di luar** daftar → task dibuat lalu langsung ditutup, launcher tetap di depan; Google TV **di dalam** daftar → tampil di cover (display 1). |
| Notifikasi | `notification.contentIntent.send(..., coverOptions())`; kalau gagal, buka launch intent paketnya; `FLAG_AUTO_CANCEL` → notifikasi dibatalkan setelah dibuka. |
| Tombol notifikasi | Maks 3 per notifikasi: `action.actionIntent.send(..., coverOptions())`. Tombol dengan `RemoteInput` (Balas) membuka notifikasinya; notifikasi `category=call` tanpa tombol. |
| Halaman sistem | Panel sistem bila ada (`Settings.Panel.ACTION_WIFI`, `ACTION_INTERNET_CONNECTIVITY`, `ACTION_VOLUME`), selain itu intent settings biasa — dimulai lewat shell supaya diizinkan di cover. |
| App info / uninstall | intent `ACTION_APPLICATION_DETAILS_SETTINGS` / `ACTION_DELETE`. |

Semua pembukaan dibungkus `unlockThen { }` (`requestDismissKeyguard`). `LauncherActivity` memanggil
`setShowWhenLocked(!isDeviceSecure)` di `onStart`: kunci swipe → launcher langsung tampil saat cover bangun; PIN/pola/
password → launcher menunggu di belakang kunci Samsung (layar kunci cover, geser, PIN pad cover), lalu CoverService
membawanya kembali. Terukur 2026-11-08: dengan `setShowWhenLocked(true)` dan PIN, launcher (panel notifikasi, tile cepat,
Settings) tampil di atas kunci walau keyguard tetap `showing=true`; app tetap minta PIN.

Dua jalan ke PIN pad, terukur di HP: `requestDismissKeyguard` dari activity kita di belakang kunci memunculkan PIN pad
**cover** (`SubLauncher:BouncerViewController requestBouncerWithIntent … PIN`), lalu setelah PIN target dibuka;
`cmd window dismiss-keyguard` dari shell memunculkan bouncer di **layar utama** yang mati (`Bouncer` display 0), jadi
tidak dipakai. Lompat langsung ke PIN saat cover bangun tidak ada jalannya yang bersih; layar kunci Samsung dibiarkan.

## 5. Menarik app yang nyangkut di layar mati

App yang memakai task lama atau start dari broadcast muncul di display 0 (yang panelnya mati).
`pullToCover()` menjalankan loop 6 × 0,5 s: baca `am stack list`, ambil root task `visible=true` di
display 0, lalu `am display move-stack <task> 1`. Task home tidak ikut dipindah — memindahkan home antar
display membuat `system_server` crash.

## 6. Data dan state

```
AppsSource ─┐
SettingsStore ├─→ LauncherViewModel.combine → LauncherState → HomePage / AppDrawer / NotificationPanel
NotificationStore ┤                                                    │
SystemStatusSource┘                                                    ↓
Clock                                                  LauncherAction → ViewModel
```

| Sumber | Isi |
|---|---|
| `AppsSource` | `LauncherApps.getActivityList` + callback install/uninstall/update, A–Z, tanpa app sendiri |
| `NotificationStore` | `NotificationListenerService` menyimpan `StatusBarNotification` mentah (untuk dibuka/dibatalkan); `FLAG_GROUP_SUMMARY` dan yang tanpa judul+teks dibuang; `popups` = notifikasi yang baru saja lolos `shouldPopUp` (§9) |
| `SystemStatusSource` | battery (juga suhu, `batteryTenthsC`), wifi, BT, torch, DND, airplane, data, ringer, location, power saver; font/animation scale dan pembatasan background (§14). Toggle lewat `Shell` (tabel di §8), data lewat `ContentObserver` pada `Settings.Global.mobile_data` |
| `MediaSource` | `MediaSessionManager.getActiveSessions(listener)` + `OnActiveSessionsChangedListener`, menumpang izin listener notifikasi; mengikuti sesi teratas saja (paket, judul, artis, playing) dan mengirim ⏮ ⏯ ⏭ lewat `transportControls`. Ketuk judul = buka app paket itu lewat jalur app biasa. Selama pemutar tampil, notifikasi ber-`EXTRA_MEDIA_SESSION` dari paket yang sama tidak masuk daftar (`listedNotifications`) |
| `SettingsStore` | DataStore `launcher`: dock, `IconStyle`, `wallpaperVersion`, `dotFilter`, `popups`, `aod`, `rotations` / `densities` (set `paket=nilai`; `flipped` lama dibaca sebagai 180°), `density` (cover), `restricted`, `tiles` (urutan) / `hidden_tiles`, `banking`; gambar di `filesDir/wallpaper.jpg` |
| `IconLoader` | `resolveActivity().getIcon()` → mono (layer monochrome di atas disc, fallback greyscale) atau warna; `LruCache` 256; ikon notifikasi = smallIcon putih |
| `Clock` | tick tiap batas menit, format 12/24 jam ikut sistem |

Yang butuh Activity (keyguard, membuka app) keluar sebagai `LauncherEvent` lewat `Channel`, dijalankan di Root.
Overlay drawer/panel adalah `enum Overlay` di state internal ViewModel, bukan rute navigasi.

## 7. Tampilan

- Kanvas 748×720 px = 352×339 dp, density 320. Cutout kamera di kanan-bawah (`Rect(379,654-748,720)` px):
  semua halaman memakai `WindowInsets.displayCutout` sebagai padding bawah, dan tappable penting dijaga di atas 308 dp.
  Cutout ikut berputar bersama cover (kanan di 90°, atas di 180°, kiri di 270°): `Modifier.cutoutSides()` memberi
  jarak di sisi itu pada Home, drawer, panel, Settings dan pop-up.
- Status bar sistem disembunyikan (`hide(systemBars())`), diganti status row sendiri; bar navigasi tetap milik Samsung
  dengan mode transien.
- Wallpaper: foto apa adanya, atau halftone dot-matrix di GPU (`RuntimeShader` di `Wallpaper.kt`), lalu scrim 20 % (dot)
  / 45 % (foto) supaya teks putih tetap terbaca. Tanpa wallpaper: grid titik 8 %.
- Gesture: `verticalSwipe` 40 dp (atas = drawer, bawah = panel; di panel: bawah = semua tile + brightness, atas =
  ringkas lalu tutup), `rememberPullPastEnd` (tarik di ujung list = tutup / perluas tile),
  `pressable` (skala 0,9 tanpa ripple). `Activity` menangani `configChanges` sendiri: tanpa recreate saat lipat/keyboard/density.
- Transisi layar hanya fade (150–250 ms); navigasi lewat `NavHost`. Panel: baris tile tambahan + brightness muncul
  dengan fade + reveal vertikal (220 ms, ease-out), alert fade keluar; ringkas kembali 150–180 ms.

## 8. Toggle cepat → perintah

| Tile | Perintah |
|---|---|
| Wi-Fi | `cmd wifi set-wifi-enabled enabled\|disabled` |
| Bluetooth | `cmd bluetooth_manager enable\|disable` |
| Data | `cmd phone data enable\|disable` |
| DND | `cmd notification set_dnd on\|off` |
| Airplane | `cmd connectivity airplane-mode enable\|disable` |
| Location | `cmd location set-location-enabled true\|false` |
| Power saver | `cmd power set-mode 1\|0` |
| Torch | `CameraManager.setTorchMode` (tanpa shell) |
| Sound | `AudioManager.ringerMode`; Silent butuh izin DND |
| NFC | `cmd nfc enable-nfc` / `cmd nfc disable-nfc persist` (`svc nfc` dibunuh di HP ini, exit 137, juga dari adb); status `NfcAdapter.isEnabled` + `ACTION_ADAPTER_STATE_CHANGED` (izin normal `NFC`) |
| Dark | `cmd uimode night yes\|no`; status `Configuration.isNightModeActive` saat `ACTION_CONFIGURATION_CHANGED` |
| Keep awake | Tanpa shell: launcher menulis prefs `keep_awake`, `CoverService` memasang jendela 1×1 `TYPE_ACCESSIBILITY_OVERLAY` di display 1 dengan `FLAG_KEEP_SCREEN_ON`; tombol power tetap mematikan. Terukur (tanpa cas): dengan jendela itu cover tetap `Awake` setelah 40 s diam, tanpa jendela `Dozing` (timeout layar 30 s) |
| Rotate | Tanpa shell di tile: prefs `rotate_auto` / `rotate_lock`; `CoverDisplay` yang memutar cover (§10) |
| Hotspot | **tidak ada**: HP ini tanpa SIM aktif (tak ada internet untuk dibagi) dan shell hanya bisa membuat hotspot sementara (`cmd wifi start-softap`), bukan hotspot milik pengguna |
| Brightness cover (slider) | baca: `settings get system sub_screen_brightness` + `…_mode` (kunci tersembunyi Samsung, jadi lewat shell, saat panel dibuka); tulis saat jari dilepas: `settings put system sub_screen_brightness_mode 0; settings put system sub_screen_brightness N` (1..255). Mode otomatis dimatikan dulu: selama otomatis, Samsung terus menulis ulang nilainya. AUTO: `…_mode 1`. |

## 9. Pop-up notifikasi

Opt-in: Settings → *pop-ups: ours* (default *samsung*, launcher tidak menyentuh setelan Samsung).

**Kapan muncul** (`shouldPopUp`, aturan heads-up Android): importance `HIGH` ke atas, lolos DND
(`Ranking.matchesInterruptionFilter()`), notifikasi baru atau update yang berbunyi lagi (update dengan
`FLAG_ONLY_ALERT_ONCE` diam), bukan `FLAG_GROUP_SUMMARY` (anaknya yang muncul) dan bukan `FLAG_ONGOING_EVENT`
(unduhan/pemutar akan muncul terus; akibatnya panggilan masuk, yang ongoing, juga tanpa pop-up). Notifikasi
launcher sendiri tidak.
`NotificationListener` memakai `onNotificationPosted(sbn, rankingMap)`; "update" = key sudah ada di daftar sebelum
`publish()`.

**Tampilannya**: `CoverService` mengumpulkan `NotificationStore.popups` dan, bila `popups` hidup, `CoverPopup`
memasang `ComposeView` sebagai `TYPE_ACCESSIBILITY_OVERLAY` di display 1 (jenis jendela yang sama dengan keep awake),
lebar penuh, tinggi sesuai isi, di atas; `FLAG_NOT_FOCUSABLE`, jadi sentuhan di luar kartu jatuh ke app di bawahnya.
Isinya `NotificationRow` milik panel. Compose di luar activity butuh lifecycle + saved-state owner di view tree:
`AlwaysResumed` (jendelanya dibuat `addCoverOverlay()` di `CoverOverlay.kt`, dipakai juga oleh AOD). Fade masuk
200 ms / keluar 150 ms (ease-out), hilang sendiri setelah 5 s; notifikasi lebih baru menggantikan yang tampil dan
mengulang timer.

**Tidak di atas AOD**: hanya saat `PowerManager.isInteractive`. Cover yang doze tidak menerima geser, dan AOD sudah
menandai alert baru (ikon + jumlah) tanpa teksnya. Terukur 2026-11-08: sebelum aturan ini nama dan pesan tertahan di
cover yang doze; sesudahnya pesan saat AOD tidak membuat jendela pop-up.

**Di atas kunci PIN/pola/password** (`keyguard.isKeyguardLocked && isDeviceSecure`) pop-up mengikuti aturan layar kunci
Android, `lockedView()`: `lock_screen_show_notifications` 0 atau visibilitas *secret* → tidak muncul;
`lock_screen_allow_private_notifications` 0 dan *private* → versi publik notifikasi (`publicVersion`, mis. WhatsApp
"1 new message", atau "contents hidden") tanpa tombol; selain itu lengkap. Visibilitas = `lockscreenVisibility` channel
bila diset, selain itu milik notifikasi. Kedua setelan dibaca langsung (`Settings.Secure`, terbukti terbaca di HP);
kalau tak terbaca: tetap muncul tapi tanpa isi pribadi. Terbukti dengan setelan "sembunyikan isi" sementara: pop-up di
layar kunci hanya "WhatsApp · 1 new message".

| Gesture | Aksi |
|---|---|
| Ketuk / tombol | cover terkunci: `LauncherActivity.openNotification()` → launcher di belakang kunci `unlockThen { CoverOpener.open() }` → PIN pad cover → setelah PIN notifikasinya dibuka (terbukti: chat WhatsApp tampil 0,3 s setelah PIN); tidak terkunci: `CoverOpener.open()` langsung, di atas app yang sedang tampil |
| Geser ke samping | `NotificationStore.dismiss()` (sama dengan panel) |
| Geser ke atas | sembunyikan pop-up saja |

**Pop-up Samsung dimatikan** lewat `Grants.samsungPopups(false)` saat pilihan *ours*, dinyalakan lagi saat *samsung*:

```sh
settings put system edge_lighting 0                  # pil "brief" (proses com.android.systemui:edgelighting)
settings put secure cover_screen_show_notification 0 # kartu "detailed" SubscreenNotification 748×200
```

Terbukti 2026-11-08 dengan pesan WhatsApp sungguhan (log + tangkapan layar cover tiap ±0,35 s):

| `edge_lighting` | `cover_screen_show_notification` | `heads_up_notifications_enabled` | Di cover |
|---|---|---|---|
| 1 | 1 | 0 | pil *brief* tetap muncul |
| 0 | 1 | 0 | kartu *detailed* muncul |
| 1 | 0 | 1 | pil *brief* tetap muncul |
| 0 | 0 | 1 | tidak ada; log SystemUI `MAKE DETAIL : show notification is disabled. popup not showing` |

Jadi `heads_up_notifications_enabled` tidak berpengaruh di cover. Dengan pilihan *ours*: pop-up launcher tampil,
pop-up Samsung tidak, notifikasi tetap masuk panel, dan ketukan pada kartu membuka chat di cover.
Notifikasi tes dari adb (`cmd notification post`, channel `shell_cmd` importance 3) tidak pernah memunculkan pop-up;
uji butuh pesan sungguhan. Belum diuji: geser, tombol, dan apakah cover menyala sendiri untuk notifikasi saat kedua
setelan Samsung `0`.

Uninstall saat pilihan *ours* membiarkan kedua setelan `0`; kembalikan dengan nilai `1` (lihat [SETUP](SETUP.md) §7).

## 10. Rotasi dan density cover

Semua rotasi dari launcher berupa kunci tetap lewat Shizuku:

```sh
cmd window fixed-to-user-rotation -d 1 enabled   # permintaan orientasi app diabaikan (app di-letterbox)
cmd window user-rotation -d 1 lock N             # N = Surface.ROTATION_*: 0, 1 (90°), 2 (180°), 3 (270°)
```

`coverRotation()` memilih N untuk app yang sedang di depan: app dengan rotasi sendiri (drawer → *rotate*
0°/90°/180°/270°) → nilai itu; tile *rotate* → nilai
sensor; tile *locked* → rotasi saat tile dikunci (`rotate_lock`, dibaca dari `Display.rotation` cover). "App di depan" =
paket dari event `TYPE_WINDOW_STATE_CHANGED` di display 1 yang class-nya sebuah activity (`getActivityInfo`), jadi
keyboard, panel Samsung dan pop-up tidak mengubah rotasi. Setelah restart/update, sebelum ada event seperti itu,
dipakai paket `rootInActiveWindow`. Perintah hanya dikirim kalau N berubah.

**Density.** Untuk app yang sama `CoverDisplay` menjalankan `wm density N -d 1`: density app itu (drawer →
*density* 240/280/360/400), kalau tidak ada density cover (Settings → *density* 280/320/360; 320 = bawaan,
`wm density reset -d 1`). Perubahan density adalah configuration change untuk semua app di cover. Terukur 2026-11-08:
Chrome ikut berubah, tetapi banyak app cover (WhatsApp, Mobile Legends) berjalan di *compat sandbox* Samsung
(jendela 1282×1234 @480 dpi, `mCompatSandboxScale` 0,583), yang tidak dijangkau `wm density -d 1`. Font scale
menjangkau keduanya. `am compat enable NEVER_SANDBOX_DISPLAY_APIS` tidak melepas sandbox Samsung (dicoba, lalu
di-reset).

**Kenapa bukan auto-rotation Android.** Terukur 2026-11-08: dengan `user-rotation -d 1 free`, sensor melaporkan 0/1/2
ke system_server saat HP diputar, tapi cover tidak berputar. Listener orientasi cover (`WindowOrientationListener`
display 1) hanya dinilai ulang saat layar menyala (setelah menyala dalam keadaan terkunci ia tetap mati walau mode
kembali `free`; `user-rotation`, `fixed-to-user-rotation` dan `accelerometer_rotation` tidak menyalakannya), dan
180° selalu ditolak (`mAllowAllRotations=false`). Karena itu `CoverDisplay` membaca sendiri sensor orientasi Samsung
("auto_rotation Screen Orientation Sensor", type 27 = `TYPE_DEVICE_ORIENTATION`, on-change, nilai 0..3) selama tile
*rotate*; nilai sensor dipakai langsung sebagai N (0 = dipegang normal, 2 = terbalik; arah 90° kiri/kanan teruji
benar di HP). Saat mulai mendengar, titik awalnya rotasi cover sekarang, supaya tidak berkedip ke 0.

**App terbalik (rotate 180°).** Untuk Mobile Legends: game menggambar sampai area kamera (`layoutInDisplayCutoutMode=always`) dan
tidak bisa diubah ukurannya (`RESIZE_MODE_UNRESIZEABLE`), jadi tombol skill di kanan bawah jatuh di bawah kamera.
Diputar 180°, kamera ada di kiri atas (menutup separuh atas minimap dan ikon ⚙). Yang dicoba dan tidak menolong:
`useLetterbox` di `multistar_setting_json_repository` (jendela tetap 748×720), override compat Android untuk cutout.

## 11. AOD sendiri

Opt-in: Settings → home → *aod: ours*. Saat `ACTION_SCREEN_OFF`, `CoverAod` memasang overlay hitam penuh
(`TYPE_ACCESSIBILITY_OVERLAY`, `PixelFormat.OPAQUE`, cutout `ALWAYS`, `FLAG_NOT_TOUCHABLE`) berisi `AodScreen`: baris
status, jam dan baris tanggal milik Home (tanpa dock), dari `Clock.ticks`, `SystemStatusSource.status` dan
`NotificationStore.notifications`. Dilepas saat `ACTION_SCREEN_ON`.

Bergantung pada AOD Samsung: cover hanya *doze* (bukan mati) kalau AOD Samsung hidup dan selalu tampil
(`system aod_mode 1`, `aod_tap_to_show_mode 0`); overlay kita menutupinya. Terukur 2026-11-08: cover `Dozing`, display 1
`DOZE_SUSPEND`, overlay tampil, dan jam berganti tiap menit (dilihat langsung di HP; DozeService Samsung tetap
menyegarkan layar tiap menit). Konten 50 % alpha (putih penuh terlalu terang di HP) dan digeser ±6 dp tiap menit
melawan burn-in. Tidak bisa disentuh: ketuk ganda untuk bangun dan perlindungan saku (`screen_off_pocket`,
`PocketModeEvent` saat bangun) tetap milik Samsung. Belum diuji: AOD saat sensor jarak tertutup / di saku, dan di 180°.

Kalau HP tidak pernah masuk AOD saat dicas: opsi pengembang *Stay awake* (`stay_on_while_plugged_in` 15) menahan
layar selama dicas, juga lewat USB; di HP ini dimatikan (0) 2026-11-08.

## 12. Banking mode

Banyak app bank menolak jalan selama ada accessibility service aktif. Drawer → long-press → *banking mode: on*
menyimpan paketnya (`banking`). Membukanya dari launcher (`OpenTarget.App(banking = true)`) menjalankan
`Grants.bankingMode(pkg)` **sebelum** app di-start, di thread sendiri:

1. prefs `banking` = true (lihat langkah 4);
2. `CoverService` dikeluarkan dari `enabled_accessibility_services`; kalau daftarnya jadi kosong, juga
   `accessibility_enabled 0`;
3. proses shell `coverlauncher-bank` (`nohup`, uid 2000, watcher lama di-`pkill` dulu di exec terpisah) memberi app
   10 s untuk muncul di `topResumedActivity`, lalu mengecek tiap 0,5 s (`dumpsys activity activities`, ±0,07 s).
   Begitu app lain, home, atau layar kunci di depan: service dimasukkan lagi dan `accessibility_enabled 1`; kalau yang
   di depan `SubHomeActivity` Samsung, watcher membuka launcher (`am start --display 1`);
4. `CoverService` yang tersambung lagi melihat prefs `banking` dan **tidak** memanggil `showLauncher()`, supaya tidak
   menimpa app tempat pengguna pindah.

Dibuka dengan cara lain (notifikasi, recents), `CoverService` memicu hal yang sama dari event jendela app itu di cover:
terlambat, app bank yang mengecek saat dibuka mungkin sudah menolak. Selama mode ini semua kerja `CoverService` berhenti
(takeover cover home, pop-up, AOD, rotasi/density per app, keep awake).

Terbukti 2026-11-08 dengan Kalkulator sebagai pengganti app bank (log tiap detik): aksesibilitas `off` +
`accessibility_enabled 0` dalam 1 s setelah dibuka; tombol home → `SubHomeActivity` → launcher dibuka watcher
(`START … from uid 2000`) ±1 s kemudian (saat itu polling 1 s, sekarang 0,5 s), aksesibilitas `on` lagi.
Belum diuji: app bank sungguhan, pindah langsung dari app bank ke app lain lewat recents, layar mati di dalam app bank
lalu kembali ke app itu setelah unlock (watcher sudah menyalakan service lagi; app bisa mengecek ulang).

## 13. Heat guard

`CoverService` mendengarkan `ACTION_BATTERY_CHANGED` (`batteryTenthsC`); `heat()` memberi tiga tingkat dengan histeresis
1 °C. Naik ke **warm** (≥ 42 °C): kartu "battery getting warm" lewat jendela pop-up, dan suhu merah di baris status
Home. Naik ke **hot** (≥ 45 °C): keep awake dimatikan, torch dimatikan, brightness cover 40/255 (manual). Kartunya tidak
butuh channel notifikasi; tampil juga di atas lock dan AOD kita. Terbukti 2026-11-08 dengan
`dumpsys battery set temp 435` lalu `455` (kartu tampil, `sub_screen_brightness` 40 mode 0, torch off), dikembalikan
dengan `dumpsys battery reset`. Hotspot/mirror (yang dimatikan OpenCover) tidak ada di launcher ini.

## 14. Setelan tampilan, tile, sistem

- **Display** (Settings): density cover (§10), font (`settings put system font_scale 0.9|1|1.15`), animasi (tiga
  `*_animation_scale` global sekaligus: 0 / 0,5 / 1). Semua lewat Shizuku. Dicek di HP oleh pengguna 2026-11-08.
- **Tiles**: urutan (↑ ↓) dan tampil/sembunyi per tile (`tiles`, `hidden_tiles`); lima pertama tampil saat panel
  ringkas; *setup* selalu terakhir.
- **System**: `Grants.checks()` (off main thread) mendaftar setiap kebutuhan dengan *ok/fix*: Shizuku, listener
  notifikasi, cover service, overlay, akses DND, fold keeper (`pgrep`), *stay awake* saat cas mati, dan, bila pilihan
  kita aktif, pop-up Samsung mati / AOD Samsung "Always". **Fix** = `Grants.fix()`: `apply()` plus setelan Samsung itu.
- **Background** per app (drawer): `cmd appops set <pkg> RUN_ANY_IN_BACKGROUND ignore|allow` +
  `am set-standby-bucket <pkg> restricted|active`. Belum diuji di HP (belum ada app yang dibatasi).

## Batasan yang disengaja

- Tidak ada balas inline (tombol Balas membuka app), tombol untuk notifikasi panggilan, pilihan antar sesi media / seek / sampul album, mirroring,
  atau keyboard sendiri — lihat [PARITY-OPENCOVER.md](PARITY-OPENCOVER.md).
- Tanpa Shizuku, membuka app jatuh ke `startActivity` biasa: jalan untuk app di daftar cover MultiStar (diisi saat
  Shizuku terakhir tersambung), app yang dipasang sesudahnya bisa kena "Open your phone to continue".
- Satu perangkat target: Z Flip5, cover diasumsikan `display 1`. Ponsel lain belum diuji.
