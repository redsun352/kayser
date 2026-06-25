# ArkeoSAR Ground Scan

Sıfırdan yazılmış, native Android (Kotlin + OpenGL ES 2.0) tabanlı bir
3D zemin tarama / manyetometre görüntüleme uygulaması. OKM Rover/Scorpion
sınıfı cihazlarla klasik Bluetooth (RFCOMM/SPP) üzerinden konuşur.

## Bu proje neyi referans aldı, neyi almadı

- **Referans alınan:** OKM cihazının donanım iletişim protokolü (SPP UUID
  `00001101-0000-1000-8000-00805F9B34FB`, `0x07` el sıkışma byte'ı, `0x0C`
  sorgu komutu, 3-byte büyük-endian sensör cevabı). Bu, cihazla konuşmak
  için *gereken* arayüz bilgisidir — herhangi bir kontrol uygulamasının
  bilmesi gereken donanım protokolü, OKM'nin yazılımının "yaratıcı ifadesi"
  değildir.
- **Referans alınan:** standart jeofizik tarama kavramları — zigzag
  (boustrophedon) tarama deseni, anomali şiddetine göre renk kodlama,
  GPS-etiketli grid noktaları. Bunlar sektör genelinde kullanılan genel
  yöntemlerdir, herhangi bir şirketin icadı değildir.
- **Referans ALINMAYAN / kullanılmayan:** OKM'nin `com.scr.scorpion`
  paketinin Java kaynak kodu, render algoritmalarının satır satır mantığı,
  ya da `.v3d` ikili dosya formatının byte-seviyesi düzeni. Bu uygulama
  kendi dosya formatını (`.asgs`, düz JSON) kullanır.

## 3D görselleştirme: ArkeoMag / Thuban Lodestar referansı

`ScanActivity`'nin 3D görünümü, kendi ArkeoMag / Thuban Lodestar
projenin "İnce Plaka" yüzey moduna referansla yeniden tasarlandı:

- **Thin Plate Spline interpolasyonu** (`ThinPlateSpline.kt`) — seyrek
  tarama noktalarından pürüzsüz, sürekli bir yüzey üretir (artık köşeli
  grid hücreleri yok). Standart, yayınlanmış bir nümerik yöntem
  (Duchon, 1977); implementasyon ArkeoSAR için sıfırdan yazıldı.
- **Per-vertex normal + ışıklandırma** — yüzey artık düzgün shading
  ile "kumaş gibi gerilmiş" görünüyor, düz renkli facet'ler değil.
- **Fonksiyon seçici** (`DisplayFunction.kt`) — d(X), d(Y), d(Z),
  d(XY), d(YZ), d(XZ), d(XYZ) arasında geçiş; dahili sensörden gelen
  ham X/Y/Z eksenleri saklanır, fonksiyon değişince yeniden taramaya
  gerek kalmadan anlık güncellenir. Not: Bluetooth probe şu an tek
  kanal okuduğu için bu probe'la taranan noktalarda fonksiyon
  değişikliği etkisiz kalır (ham eksen yok).
- **Eşik (threshold) filtresi** — sadece en yüksek anomali bandını
  göstermek için düşük değerli noktaları yüzeyden çıkarır.
- **Renk skalası (colorbar)** — sol altta, değer aralığı + her bandın
  yüzdelik dağılımını gösteren özel `ColorbarView`.
- **Araç çubukları** — sağ üst: ölçüm/görünüm modu/zoom sıfırlama/ekran
  görüntüsü; sol: ızgara/tel-kafes/yüzey/nokta-bulutu geçişi + ayarlar
  paneli aç/kapa.
- **Ekran görüntüsü** — `glReadPixels` ile GPU'dan kare okuyup
  `Pictures/ArkeoSARGroundScan/` altına PNG olarak kaydeder.

## Veri kaynağı: Bluetooth cihaz + dahili sensör fallback

`ScanActivity` artık iki veri kaynağını ortak bir arayüz
(`ScanDataSource`) üzerinden yönetiyor:

1. Önce **Bluetooth probe** (`BluetoothDataSource`, OKM Rover-class
   cihaz) denenir.
2. Eşleştirilmiş cihaz bulunamazsa, bağlantı hata verirse, veya
   6 saniye içinde `ACTIVE` durumuna geçmezse, otomatik olarak
   **telefonun dahili manyetometresine** (`InternalSensorSource`,
   `Sensor.TYPE_MAGNETIC_FIELD`) geçilir.

Durum çubuğunda her zaman hangi kaynağın aktif olduğu ("Harici Cihaz"
/ "Dahili Sensör") gösterilir. Dahili sensör modunda:

- Okunan değer, 3 eksenli manyetik alan vektörünün büyüklüğüdür
  (mikrotesla). Bu, herhangi bir telefon manyetometresini tek bir
  anomali-şiddeti değerine çevirmenin standart yoludur.
- Fiziksel bir tetik butonu olmadığı için bu mod her zaman "otomatik"
  gibi çalışır.
- Telefonun kendi manyetometresi, hoparlör mıknatısı gibi iç bileşenlerden
  etkilenebilir ve özel bir prob kadar hassas değildir — bu, gerçek bir
  donanım kısıtı, yazılım hatası değil.
- Konum izni verilmişse GPS koordinatları da (varsa) her ölçüme eklenir.

## Proje yapısı

```
app/src/main/java/com/arkeosar/groundscan/
  ui/          MainActivity, ScanActivity, SettingsActivity, FileExplorerActivity, ColorbarView
  data/        ScanGrid, ArkeoSarFile, SettingsData, ScanDataSource (ortak arayüz)
  bluetooth/   BluetoothScanService (ham RFCOMM), BluetoothDataSource (adaptör)
  sensors/     InternalSensorSource (telefonun dahili manyetometresi)
  render/      HeightmapRenderer, HeightmapMesh, ThinPlateSpline, AnomalyColorScale,
               DisplayFunction, RenderMode (OpenGL ES 2.0)
app/src/main/assets/shaders/   heightmap.vert / heightmap.frag (GLSL ES, lit shading)
```

## Derleme

Bu ortamda (sandbox) Android SDK ve Gradle dağıtımına ağ erişimi
olmadığı için APK burada derlenemedi. Projeyi kendi makinende veya
Android Studio'da derlemek için:

1. Bu klasörü Android Studio ile aç (`File > Open`, klasörün kökünü seç).
2. Studio otomatik olarak Gradle senkronizasyonunu yapacak ve eksik SDK
   bileşenlerini (compileSdk 34, build-tools) indirecektir.
3. `Run` (▶) tuşuna bas, ya da terminalden:
   ```
   ./gradlew assembleDebug
   ```
   Çıktı: `app/build/outputs/apk/debug/app-debug.apk`

Veya GitHub Actions ile: bu repoya push ettiğinde `.github/workflows/build.yml`
otomatik olarak debug APK'yı derler; sonucu Actions sekmesinin
Artifacts kısmından indirebilirsin.

## Bilinen sınırlamalar / sonraki adımlar

- **Ölçüm aracı (cetvel ikonu):** Şu an sadece bir bildirim gösteriyor;
  yüzey üzerinde iki nokta seçip gerçek-dünya birimleriyle mesafe
  okuma henüz uygulanmadı.
- **GRID render modu:** Şu an SURFACE ile aynı çiziliyor (ayrı bir
  "ızgara çizgileri üstte" overlay'i yok); WIREFRAME modu zaten ayrı
  bir görünüm sağlıyor.
- **TPS performansı:** Çok büyük taramalarda (yüzlerce dolu nokta)
  interpolasyon yavaşlayabilir - bkz. `ThinPlateSpline.kt` içindeki
  performans notu.
- **Fonksiyon seçici / Bluetooth probe:** Harici cihazdan gelen
  okumalar tek kanallı olduğu için (ham X/Y/Z yok), bu noktalarda
  fonksiyon değişikliği uygulanmaz - sadece dahili sensör modunda
  tam çalışır.
- **Cihaz eşleştirme:** `BluetoothScanService.findCandidateDevices()`
  şu an sadece zaten **eşleştirilmiş (bonded)** cihazları tarıyor. Yani
  Rover'ı önce Android'in Bluetooth ayarlarından eşleştirmen gerekiyor.
  Gerekirse `BluetoothAdapter.startDiscovery()` + `BroadcastReceiver`
  ile canlı keşif (discovery) akışı eklenebilir.
- **Thread senkronizasyonu:** `ScanGrid`, UI thread'inden (`onProbeReading`)
  yazılıp GL render thread'inden okunuyor. Şu anki implementasyon basit
  ve pratikte çalışır, ama tam doğruluk için `synchronized` blokları veya
  bir immutable snapshot mekanizması eklenebilir.
- **Manuel mod:** `Settings` ekranındaki "Otomatik Mod" anahtarı şu an
  `ScanActivity`'e henüz bağlanmadı — otomatik mod her zaman aktif.
  Manuel moda geçişte `onProbeReading`'i `buttonPressed` durumuna göre
  gate'lemek gerekir.
- **.v3d içe aktarma:** OKM'nin orijinal yazılımıyla kaydedilmiş eski
  taramaları açmak istersen, ayrı bir `OkmV3dImporter` sınıfı eklenebilir
  (yalnızca okuma; ArkeoSAR'ın kendi formatından bağımsız).
