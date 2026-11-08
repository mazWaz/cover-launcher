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
| Izin | `feature/cover/Grants.kt` | 6 perubahan sistem: state lipat (+ penjaga), overlay appop, listener notifikasi, DND, accessibility, allowlist cover MultiStar |
| Home takeover | `feature/cover/CoverService.kt` | accessibility service: timpa cover home, tarik app dari layar mati, orkestrasi autostart Shizuku |
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
mem-bind ulang (cara ini terbukti memulihkan di HP).

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
| Daftar cover MultiStar | Secure setting `multistar_setting_repository`: 30 slot dipisah `/`, slot 26 = `pkg,userId` dipisah `;` (`0` bila kosong). Tiap kali Shizuku tersambung, `Grants` menambahkan setiap app yang bisa dibuka (user 0, tanpa launcher sendiri) lewat `withCoverApps()`: hanya menambah, slot lain tak disentuh, tidak menulis bila tak ada yang kurang. Nilai sebelum perubahan pertama disalin ke `/data/local/tmp/coverlauncher-multistar.bak`. Terukur 2026-11-08: 35 → 40 entri, 29 slot lain identik. |
| Notifikasi | `notification.contentIntent.send(..., coverOptions())`; kalau gagal, buka launch intent paketnya; `FLAG_AUTO_CANCEL` → notifikasi dibatalkan setelah dibuka. |
| Tombol notifikasi | Maks 3 per notifikasi: `action.actionIntent.send(..., coverOptions())`. Tombol dengan `RemoteInput` (Balas) membuka notifikasinya; notifikasi `category=call` tanpa tombol. |
| Halaman sistem | Panel sistem bila ada (`Settings.Panel.ACTION_WIFI`, `ACTION_INTERNET_CONNECTIVITY`, `ACTION_VOLUME`), selain itu intent settings biasa — dimulai lewat shell supaya diizinkan di cover. |
| App info / uninstall | intent `ACTION_APPLICATION_DETAILS_SETTINGS` / `ACTION_DELETE`. |

Keyguard cover hanya swipe; semua pembukaan dibungkus `unlockThen { }` (`requestDismissKeyguard`).
`LauncherActivity` sendiri memakai `setShowWhenLocked(true)` supaya langsung tampil saat cover bangun.

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
| `NotificationStore` | `NotificationListenerService` menyimpan `StatusBarNotification` mentah (untuk dibuka/dibatalkan); `FLAG_GROUP_SUMMARY` dan yang tanpa judul+teks dibuang |
| `SystemStatusSource` | battery, wifi, BT, torch, DND, airplane, data, ringer, location, power saver. Toggle lewat `Shell` (tabel di §8), data lewat `ContentObserver` pada `Settings.Global.mobile_data` |
| `MediaSource` | `MediaSessionManager.getActiveSessions(listener)` + `OnActiveSessionsChangedListener`, menumpang izin listener notifikasi; mengikuti sesi teratas saja (paket, judul, artis, playing) dan mengirim ⏮ ⏯ ⏭ lewat `transportControls`. Ketuk judul = buka app paket itu lewat jalur app biasa. Selama pemutar tampil, notifikasi ber-`EXTRA_MEDIA_SESSION` dari paket yang sama tidak masuk daftar (`listedNotifications`) |
| `SettingsStore` | DataStore `launcher`: dock, `IconStyle`, `wallpaperVersion`, `dotFilter`; gambar di `filesDir/wallpaper.jpg` |
| `IconLoader` | `resolveActivity().getIcon()` → mono (layer monochrome di atas disc, fallback greyscale) atau warna; `LruCache` 256; ikon notifikasi = smallIcon putih |
| `Clock` | tick tiap batas menit, format 12/24 jam ikut sistem |

Yang butuh Activity (keyguard, membuka app) keluar sebagai `LauncherEvent` lewat `Channel`, dijalankan di Root.
Overlay drawer/panel adalah `enum Overlay` di state internal ViewModel, bukan rute navigasi.

## 7. Tampilan

- Kanvas 748×720 px = 352×339 dp, density 320. Cutout kamera di kanan-bawah (`Rect(379,654-748,720)` px):
  semua halaman memakai `WindowInsets.displayCutout` sebagai padding bawah, dan tappable penting dijaga di atas 308 dp.
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
| Hotspot | **tidak ada**: HP ini tanpa SIM aktif (tak ada internet untuk dibagi) dan shell hanya bisa membuat hotspot sementara (`cmd wifi start-softap`), bukan hotspot milik pengguna |
| Brightness cover (slider) | baca: `settings get system sub_screen_brightness` + `…_mode` (kunci tersembunyi Samsung, jadi lewat shell, saat panel dibuka); tulis saat jari dilepas: `settings put system sub_screen_brightness_mode 0; settings put system sub_screen_brightness N` (1..255). Mode otomatis dimatikan dulu: selama otomatis, Samsung terus menulis ulang nilainya. AUTO: `…_mode 1`. |

## Batasan yang disengaja

- Tidak ada balas inline (tombol Balas membuka app), tombol untuk notifikasi panggilan, pilihan antar sesi media / seek / sampul album, rotasi/density per app, mirroring,
  atau keyboard sendiri — lihat [PARITY-OPENCOVER.md](PARITY-OPENCOVER.md).
- Tanpa Shizuku, membuka app jatuh ke `startActivity` biasa: jalan untuk app di daftar cover MultiStar (diisi saat
  Shizuku terakhir tersambung), app yang dipasang sesudahnya bisa kena "Open your phone to continue".
- Satu perangkat target: Z Flip5, cover diasumsikan `display 1`. Ponsel lain belum diuji.
