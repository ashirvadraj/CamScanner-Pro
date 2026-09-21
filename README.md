# 📄 CamScanner Pro — Production Android Document Scanner

[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-blue.svg)](https://kotlinlang.org)
[![CameraX](https://img.shields.io/badge/CameraX-1.3.3-orange.svg)](https://developer.android.com/training/camerax)
[![ML Kit](https://img.shields.io/badge/ML%20Kit-Text%20Recognition-red.svg)](https://developers.google.com/ml-kit)
[![Room](https://img.shields.io/badge/Room-2.6.1-brightgreen.svg)](https://developer.android.com/training/data-storage/room)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

An enterprise-grade, offline-first Android Document Scanner application (equivalent to CamScanner) built with Kotlin, CameraX, Google ML Kit on-device Text Recognition, Room Database, and Material Design 3.

---

## 🌟 Key Features

### 📸 1. CameraX Live Document Scanner
- **Live Viewfinder**: Real-time camera stream with guide overlay.
- **Capture Modes**:
  - **Single Page Mode**: Immediate capture-and-process workflow.
  - **Batch Scanning Mode**: Rapidly scan 10+ pages in seconds before processing in bulk.
- **Controls**: Flash / Torch toggle (`Auto`, `On`, `Off`), Gallery import shortcut.

### 📐 2. Smart Boundary Detection & 8-Handle Crop
- **Auto Edge Detection**: Fast luminance-gradient edge detection algorithm that detects page boundaries in under 30ms.
- **Interactive 8-Point Crop Overlay**:
  - 4 corner handles + 4 edge midpoint handles.
  - **Magnifying Loupe**: Floating 2x zoom loupe with crosshair follows active touch so fingers never block the corner view.
  - Fallback to safe 8% inset margin or full image expansion.
- **Perspective Dewarping**: 4-point projective homography matrix flattening skewed/tilted document captures into clean rectangular pages.

### 🎨 3. Post-Processing & Image Filters
- **Magic Color**: Signature CamScanner enhancement with adaptive contrast and dynamic range boost.
- **Grayscale**: Clean desaturation with luminance balance.
- **B&W Document**: Otsu-like adaptive thresholding turning paper white and text dark.
- **Lighten**: Gamma lift removing desk lamp shadows.
- **Rotation**: 90° clockwise/counter-clockwise orientation fixes.

### 🔍 4. On-Device Optical Character Recognition (OCR)
- Powered by **Google ML Kit Text Recognition v2**.
- 100% offline on-device processing with zero latency.
- Full extracted text viewer with one-tap **Copy to Clipboard** and **Share Text**.
- Extracted text indexed in local database for instant full-text search.

### 📑 5. Multi-Page Document Management & PDF Export
- Document dashboard with live search by title or OCR content.
- Multi-page document grid view: add pages, delete pages, view OCR per page, and rename document.
- **Multi-page PDF Generation**:
  - Standard A4 page sizing (595 × 842 pt).
  - Centered aspect-fit layout with high-quality bitmap rendering.
  - Native Android Sharesheet export (`FileProvider`) for WhatsApp, Gmail, Google Drive, Print.

---

## 🏛 Architecture & Tech Stack

```
com.camscanner.pro/
├── core/
│   ├── cv/             # EdgeDetector, QuadBounds, PerspectiveTransformer, ImageFilterEngine
│   ├── ocr/            # OcrManager (Google ML Kit on-device OCR)
│   ├── pdf/            # PdfGenerator (Standard A4 multi-page PDF generation)
│   └── storage/        # FileManager (Scoped storage & sampled memory-safe bitmap decoding)
├── data/
│   ├── local/
│   │   ├── dao/        # DocumentDao, PageDao
│   │   ├── entity/     # DocumentEntity, PageEntity
│   │   └── AppDatabase # Room database with foreign keys & cascade delete
│   └── repository/     # DocumentRepository (Coroutines Flow & Dispatchers.IO)
└── ui/
    ├── custom/         # CropOverlayView (8 handles + Magnifying Loupe touch view)
    ├── camera/         # CameraActivity (CameraX live viewfinder & batch mode)
    ├── crop/           # CropActivity (Interactive boundary fine-tuning)
    ├── filter/         # FilterActivity (Magic Color & OCR extraction)
    ├── detail/         # DocumentDetailActivity (Page grid & PDF export)
    └── main/           # MainActivity (Dashboard & live search)
```

---

## 🧪 Testing Suite

CamScanner Pro includes unit tests verifying:
- **QuadBoundsTest**: Polygon point ordering (TL, TR, BR, BL), Euclidean distance calculations, edge midpoints, and boundary clamping.
- **PdfGeneratorTest**: Standard A4 page dimensions (595x842 pt), aspect ratio preservation, and margin fits.
- **ImageFilterTest**: Filter types, display titles, and color contrast translation matrices.
- **EdgeDetectorTest**: Point math, distance metrics, and synthetic boundary calculations.

Run tests:
```bash
./gradlew testReleaseUnitTest
```

---

## 📦 Building the Release APK

```bash
./gradlew assembleRelease
```
The compiled release APK will be located at:
`app/build/outputs/apk/release/CamScannerPro-v1.0.0.apk`

---

## 📄 License
Licensed under the Apache License, Version 2.0.
