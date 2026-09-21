# 📄 CamScanner Pro — Production Android Document Scanner

[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-blue.svg)](https://kotlinlang.org)
[![CameraX](https://img.shields.io/badge/CameraX-1.3.3-orange.svg)](https://developer.android.com/training/camerax)
[![ML Kit](https://img.shields.io/badge/ML%20Kit-Text%20%26%20Barcode-red.svg)](https://developers.google.com/ml-kit)
[![Room](https://img.shields.io/badge/Room-2.6.1-brightgreen.svg)](https://developer.android.com/training/data-storage/room)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

An enterprise-grade, offline-first Android Document Scanner application (equivalent to CamScanner) built with Kotlin, CameraX, Google ML Kit on-device Text & Barcode Recognition, Room Database, and Material Design 3.

---

## 🌟 Key Features

### 📸 1. CameraX Live Document Scanner & ID Card 2-in-1 Mode
- **Live Viewfinder**: Real-time camera stream with guide overlay and flash controls (`Auto`, `On`, `Off`).
- **Capture Modes**:
  - **Single Page Mode**: Immediate capture-and-process workflow.
  - **Batch Scanning Mode**: Rapidly scan 10+ pages in seconds before processing in bulk.
  - **ID Card (2-in-1) Mode**: Specialized guided mode that captures the **Front** and **Back** of an ID card/license and automatically aligns them side-by-side on a single printable A4 page.

### 📐 2. Smart Boundary Detection & 8-Handle Crop
- **Auto Select Most Important Part (Salient Document Detector)**:
  - Multi-stage Computer Vision pipeline combining border luminance differentials, high-frequency text/gradient energy grids, and Otsu convex extrema.
  - **Sub-frame Document Detection**: If a receipt, business card, ID card, check, or note is on a surface/desk, it automatically identifies and tightly fits the 4-corner quadrilateral around that document, ignoring background clutter.
  - **Full-Page Document Detection**: If the document is an A4 paper or book page that fills the viewfinder, it automatically detects that it's a full-page document and selects the entire page cleanly without cutting off margins or headers.
  - **Perspective Dewarping**: 4-point projective homography matrix flattening skewed/tilted document captures into clean rectangular pages.
- **Interactive 8-Point Crop Overlay**:
  - 4 corner handles + 4 edge midpoint handles.
  - **Magnifying Loupe**: Floating 2x zoom circular loupe with crosshairs follows active touch so fingers never obstruct corner positioning.
  - Quick options: **Auto (Smart)** (most important part / document), **Full Document** (clean margins), and **Full Image** (border-to-border).

### 🎨 3. Post-Processing & Filters
- **Magic Color**: Signature CamScanner enhancement with adaptive contrast and dynamic range boost.
- **Grayscale**: Clean desaturation with luminance balance.
- **B&W Document**: Otsu-like adaptive binarization turning paper pure white and text crisp black.
- **Lighten**: Gamma lift removing desk lamp shadows.
- **Rotation**: 90° clockwise/counter-clockwise orientation fixes.

### ✍️ 4. Electronic Signature (E-Sign) Tool
- Dedicated smooth signature drawing canvas with quadratic bezier curves.
- Multi-color ink selection (**Black**, **Blue**, **Red**) and one-tap clear/undo.
- Stamps transparent signatures directly onto any document page.

### 🔒 5. Custom Diagonal Watermarking
- Stamp custom watermarks across pages (e.g., "CONFIDENTIAL", "FOR REVIEW ONLY", "DO NOT COPY").
- Configurable text and transparency, applied non-destructively or embedded into PDF exports.

### 🔲 6. Integrated QR Code & Barcode Scanner
- Powered by **Google ML Kit Barcode Scanning**.
- Detects QR codes, Code 128, Data Matrix, EAN, UPC embedded in scanned documents or images.
- Instant action sheet with "Open URL", "Copy to Clipboard", and "Share".

### 🔍 7. On-Device Optical Character Recognition (OCR)
- Powered by **Google ML Kit Text Recognition v2** (100% offline).
- Full extracted text viewer with one-tap **Copy to Clipboard** and **Share Text**.
- Extracted text indexed in Room DB for instant dashboard search.

### 🏷️ 8. Smart Categorization & Dashboard Filters
- Organize documents into tags: **All**, **ID Cards**, **Receipts**, **Contracts**, **Personal**, **Work**.
- Reactive filter chips on the dashboard for instant document lookup.

### 📑 9. PDF Export Customizer & Multi-Page Management
- **Customizable PDF Options**:
  - Page Sizes: **A4 Standard** (595 × 842 pt), **US Letter** (612 × 792 pt), **Fit to Image**.
  - Quality Levels: **High Quality** (1920px), **Medium** (1280px), **Compact** (800px email friendly).
  - Watermark toggle & text.
- Multi-page grid view: add pages, delete pages, reorder, and preview in full-screen.
- Native Android Sharesheet export (`FileProvider`) for WhatsApp, Gmail, Drive, and Print.

### 📥 10. CamScanner Migration & Import Engine
- **Direct CamScanner PDF Import**: Render multi-page PDFs exported from the original CamScanner app directly into individual high-res pages via native `PdfRenderer`.
- **Automatic Text Re-indexing**: Background ML Kit OCR runs on all imported pages to populate searchable document text.
- **Batch Image Import**: Import entire sets of scanned photos or camera roll images into a single structured multi-page document.
- **CamScanner Folder Scanner**: Storage Access Framework folder picker that scans and imports entire directories of CamScanner scans and PDFs in one tap.

### ☁️ 11. Google Sign-In & Secure Cloud Backup / Restore
- **Google Account Authentication**: Powered by `play-services-auth` for seamless single-sign-on.
- **Full-Fidelity Backup Archive**: Packages all local Room database records, categories, OCR text, timestamps, and page images into a compressed ZIP package with standard `manifest.json`.
- **Cross-Device Restore**: Reinstalling the app or moving to a new phone? Sign in with your Google account, select your cloud backup or archive file, and instantly restore all documents and pages.
- **Offline / Cloud Export**: Share your encrypted backup archive to Google Drive, email, or local external storage.

### 🗜️ 12. Merge & Compress Studio (Exact Target Size Optimizer)
- **PDF Merger with Target File Size Limit**:
  - Select multiple PDFs and merge them into a single unified PDF.
  - Set custom target file size limits (e.g., **500 KB**, **1 MB**, **2 MB**, or any custom KB value).
  - Automatically calculates per-page byte budgets and optimizes raster streams to stay strictly within size limits without degrading document readability.
- **Image Compressor to Exact Target Size (e.g., 1 MB ➜ 50 KB)**:
  - Input any custom target size in Kilobytes (e.g., `50 KB`, `100 KB`).
  - Adaptive 2-tier optimization: pixel budget estimation + binary search on JPEG/WebP quality factor.
  - Compresses heavy multi-megabyte captures down to 50 KB with razor-sharp text clarity and zero macroblock distortion.
  - Before vs. After comparison with file savings percentage and quality breakdown.

### 🎨 13. Brand Adaptive Vector App Icon
- Premium emerald and deep teal gradient background with layered depth.
- Stylized document sheet with crisp folded corner, high-intensity glowing cyan scanner laser beam, and camera lens aperture iris emblem.
- Adaptive icon vectors supporting all Android densities and launchers.

---

## 🏛 Architecture & Tech Stack

```
com.camscanner.pro/
├── core/
│   ├── auth/           # GoogleAuthManager (Google Sign-In authentication)
│   ├── backup/         # CloudBackupManager, BackupManifest (ZIP archive & manifest serialization)
│   ├── barcode/        # BarcodeScannerManager (ML Kit QR/Barcode detection)
│   ├── compression/    # SizeTargetCompressor (Exact target KB image compressor)
│   ├── cv/             # EdgeDetector, QuadBounds, PerspectiveTransformer, ImageFilterEngine,
│   │                   # IdCardMerger, WatermarkStamper, SignatureStamper
│   ├── migration/      # CamScannerImporter (PDF page rendering, batch image import, SAF folder scanning)
│   ├── ocr/            # OcrManager (Google ML Kit on-device OCR)
│   ├── pdf/            # PdfGenerator, PdfOptions, PdfMergerEngine (Target size PDF merger)
│   └── storage/        # FileManager (Scoped storage & sampled memory-safe bitmap decoding)
├── data/
│   ├── local/
│   │   ├── dao/        # DocumentDao, PageDao
│   │   ├── entity/     # DocumentEntity (with category), PageEntity
│   │   └── AppDatabase # Room database with foreign keys & cascade delete
│   └── repository/     # DocumentRepository (Coroutines Flow & Dispatchers.IO)
└── ui/
    ├── backup/         # BackupActivity (Google profile, Backup/Restore controls, archive share)
    ├── camera/         # CameraActivity (CameraX live viewfinder, batch mode, ID card mode)
    ├── crop/           # CropActivity (Interactive boundary fine-tuning & 8-point loupe)
    ├── custom/         # CropOverlayView, SignaturePadView
    ├── detail/         # DocumentDetailActivity (Page grid & PDF export customizer)
    ├── filter/         # FilterActivity (Magic Color & OCR extraction)
    ├── main/           # MainActivity (Category filter chips, Search, shortcuts for Import, Cloud, Studio)
    ├── tools/          # MergeCompressActivity (PDF merger & target-size compressor studio)
    └── viewer/         # PagePreviewActivity (Full screen viewer with E-sign, Watermark, Barcode)
```

---

## 🧪 Testing Suite

CamScanner Pro includes 31 comprehensive unit tests verifying:
- **SizeTargetCompressorTest**: Exact target size byte budget, savings percentage calculation, quality limits.
- **PdfMergerEngineTest**: PDF merge models, page count tracking, file name sanitization.
- **BackupManifestTest**: JSON manifest serialization, deserialization, schema integrity, and version compatibility.
- **CamScannerImporterTest**: Filename sanitization, fallbacks, and multi-file import result models.
- **QuadBoundsTest**: Polygon point ordering (TL, TR, BR, BL), Euclidean distance calculations, edge midpoints, and boundary clamping.
- **PdfGeneratorTest & PdfOptionsTest**: Page layout calculations, margin fitting, aspect ratio preservation, and PDF quality dimension settings.
- **IdCardMergerTest**: ISO/IEC 7810 ID-1 card proportions and dual-card A4 canvas layout.
- **BarcodeResultTest**: Barcode format mapping and URL parsing.
- **ImageFilterTest**: Filter types, display titles, and color contrast translation matrices.
- **EdgeDetectorTest**: Point math, distance metrics, Shoelace polygon area calculation, convexity checks, full-page margins, and synthetic boundary calculations.

Run tests:
```bash
./gradlew testReleaseUnitTest
```
*Result: 31 tests, 0 failures, 100% success rate.*

---

## 📦 Building the Release APK

```bash
./gradlew assembleRelease
```
The compiled release APK will be located at:
`app/build/outputs/apk/release/CamScannerPro-v1.3.0.apk`

---

## 📄 License
Licensed under the Apache License, Version 2.0.
