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

## Görünüm modu anahtarı: 2D / 3D Yüzey / 3D Hacimsel

`ScanActivity`'nin üst kısmına üç sekmeli bir görünüm anahtarı eklendi
(TeknolojiEkibi'nin "3D-4D Yeraltı Görüntüleme" tanıtım videosuna
referansla):

- **2D**: Aynı yüzey mesh'i, kamera doğrudan yukarıdan bakacak şekilde
  kilitleniyor - ayrı bir 2D geometri gerekmiyor, sadece kamera açısı
  değişiyor.
- **3D Yüzey**: Önceden var olan TPS-interpolasyonlu, ışıklandırılmış
  yüzey (değişiklik yok).
- **3D Hacimsel** ("4D" pazarlama adıyla anılan görünüm): `VolumetricMesh`
  ile çizilen, yarı saydam katmanlar halinde dizilmiş bir blok.

### Şematik derinlik modeli - önemli bilimsel sınır

3D Hacimsel görünüm, **gerçek bir derinlik ölçümüne dayanmıyor** -
tek bir yüzey manyetometre taraması, gömülü bir kaynağın derinliğini
kesin olarak belirleyemez (aynı yüzey deseni, farklı derinlik+şiddet
kombinasyonlarından üretilebilir; bu "ill-posed" bir ters problemdir).

`SchematicDepthModel.kt`, gerçek bir fizik prensibine (manyetik
dipolün alanı kaynaktan mesafenin küpüyle ters orantılı azalır,
B ∝ 1/r³) dayanarak, **varsayılan bir kaynak derinliği** (şu an sabit
`DEFAULT_ASSUMED_DEPTH`) etrafında yüzey okumalarını aşağı doğru
projekte ediyor. Bu, "kaynak tipik bir dipol gibi davranıyorsa derinlikte
böyle görünür" şeklinde bir **model varsayımı**, ölçülmüş ya da
ters-çözümlenmiş bir derinlik değeri değil.

Bu yüzden 3D Hacimsel modda ekranda her zaman görünür bir uyarı bandı
var: "⚠ Şematik derinlik modeli — gerçek ölçüm değildir, tahminidir".
Bu uyarı kasıtlı olarak kalıcı/göz ardı edilemez tutuldu (sadece açılışta
bir kerelik bilgi notu değil) çünkü kullanıcı bu görünümü sahada gerçek
bir derinlik ölçümüyle karıştırabilir.

## 2D ızgara dedektör ekranı + otomatik 3D geçiş

`GridScanActivity`, ArkeoMag / Thuban Lodestar'ın hücre-hücre tarama
ekranına referansla eklendi. Ana menüdeki "3 Boyutlu Zemin Tarama"
butonu artık önce bu ekranı açıyor:

- Sütun-bazlı zigzag tarama (`ScanAxis.COLUMN_MAJOR`) - bir sütun
  yukarıdan aşağıya, sonraki sütun aşağıdan yukarıya dolduruluyor.
- Her hücre dolarken canlı interpolasyonlu (TPS) renk alıyor; henüz
  ölçülmemiş hücreler de soluk bir önizleme rengi gösteriyor.
- Aktif hücre sarı ok ile vurgulanıyor, ok yönü zigzag'ın hangi tarafta
  olduğuna göre değişiyor.
- Izgara tamamlanınca "Lütfen Bekleyiniz…" mesajıyla otomatik olarak
  `ScanActivity`'e (3D yüzey) geçiyor; toplanan veri ara bir dosyaya
  kaydedilip oradan yükleniyor.

## Renk skalaları, CSV dışa aktarma, vektörel analiz

- **10 renk skalası** (`ColorPalette.kt` içinde `AnomalyColorScale`'e
  eklendi): Termal (varsayılan), Jet, Chlorophyll, Gri Tonlama, Viridis,
  Plasma, Toprak, Okyanus, Bakır, Amber. `ScanActivity`'deki
  Enterpolasyon Ayarları panelinde "Renk Skalası" seçicisinden
  değiştirilebilir; seçim anlık olarak 3D yüzeye ve colorbar'a yansır.
  Bunlar standart, yaygın yayınlanmış bilimsel renk haritaları (Jet,
  Viridis, Plasma matplotlib/ParaView gibi araçlarda da bulunan genel
  renk-durağı tarifleridir, kimsenin tescilli varlığı değildir) +
  ArkeoSAR'ın kendi basit gradyanları.
- **CSV dışa aktarma** (`CsvExporter.kt`): "CSV Olarak Dışa Aktar"
  butonuyla taramanın tamamı (row, col, value, GPS, ham X/Y/Z) düz CSV
  olarak `Documents/ArkeoSARGroundScan/` altına kaydedilir - Excel,
  pandas, R gibi araçlarda açılabilir.
- **3 eksenli vektörel analiz**: Ayrı bir ekran eklenmedi - zaten var
  olan "Fonksiyon" seçici (d(X), d(Y), d(Z), d(XY), d(YZ), d(XZ),
  d(XYZ)) bu işlevi görüyor; dahili sensörden gelen ham X/Y/Z eksenleri
  zaten saklanıyor (bkz. `ScanGrid.recomputeWithFunction`).

## Proje yapısı

```
app/src/main/java/com/arkeosar/groundscan/
  ui/          MainActivity, ScanActivity, GridScanActivity, SettingsActivity,
               FileExplorerActivity, ColorbarView, GridScanView
  data/        ScanGrid (ScanAxis: row/column-major), ArkeoSarFile, CsvExporter,
               SettingsData, ScanDataSource (ortak arayüz)
  bluetooth/   BluetoothScanService (ham RFCOMM), BluetoothDataSource (adaptör)
  sensors/     InternalSensorSource (telefonun dahili manyetometresi)
  render/      HeightmapRenderer, HeightmapMesh, VolumetricMesh, ThinPlateSpline,
               SchematicDepthModel, AnomalyColorScale, ColorPalette, DisplayFunction,
               RenderMode, ViewMode (OpenGL ES 2.0)
app/src/main/assets/shaders/   heightmap.vert/.frag (lit surface), volumetric.vert/.frag (unlit, alpha-blended)
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

- **Yapıldı:** Şematik derinlik modeli + 3D hacimsel görünüm (bkz.
  yukarıdaki bölüm) - ama varsayılan derinlik şu an sabit bir sabit
  (`SchematicDepthModel.DEFAULT_ASSUMED_DEPTH`), kullanıcı arayüzünden
  henüz ayarlanamıyor.
- **Henüz eklenmedi (istek listesinde, sırayla planlanıyor):**
  9 farklı enterpolasyon yöntemi (şu an sadece İnce Plaka/TPS var -
  TeknolojiEkibi videosunda NearestNeighbor, Linear, Cubic2, Cubic4,
  BSpline, Sinc5, Gaussian2, Gaussian4, Cosine listelendi), tarama
  modu seçimi (Manuel / Izgara / Paralel + boyut ayarları), artırılmış
  gerçeklik (AR) destekli görselleştirme, gelişmiş manyetometre
  kalibrasyonu (XY/YZ/XZ elips fit ekranı), varsayılan derinlik
  parametresinin arayüzden ayarlanabilmesi.

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
