# Accelerate to Velocity (A→V)

Metro train velocity estimation using phone IMU (accelerometer + gyroscope).  
Designed for underground metro where GPS is unavailable.

## How It Works

```
IMU Sensors (200Hz)
    ↓
Madgwick Filter → Orientation quaternion
    ↓
Rotate accel to nav frame, remove gravity
    ↓
Trapezoidal integration → raw velocity
    ↓
ZUPT Detector (station stop = v → 0)
    ↓
Extended Kalman Filter (16-state)
    ├─ ZUPT correction (every 1-3 min at stations)
    ├─ GPS correction (when above ground)
    └─ Route profile matching (optional)
    ↓
Real-time velocity display
```

## Key Concepts

### Why Metro Is Ideal for This
- **Station stops every 1-3 minutes** → Zero-Velocity Updates (ZUPT) reset drift
- **Fixed routes** → speed profile matching constrains position
- **Predictable dynamics** → accel/brake/stop pattern repeats

### The Turn Problem
Centripetal acceleration on curves confuses the accelerometer.  
Solved by: gyroscope detects turns independently → EKF reduces accel trust during turns.

### Accuracy
| Method | Error (between stations) |
|---|---|
| Accel + gyro + ZUPT | 0.5–2 m/s |
| + speed profile matching | 0.1–0.5 m/s |

For a metro at 70 km/h: **2.5–10% error** with basic EKF+ZUPT.

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.0 |
| UI | Jetpack Compose + Material 3 |
| DI | Hilt |
| Database | Room |
| Async | Coroutines + Flow |
| GPS | FusedLocationProvider |
| Math | Pure Kotlin (Quaternion, Matrix3x3, EKF) |

## Project Structure

```
app/src/main/java/com/av/
├── MainActivity.kt           # Entry point, permission handling
├── AvApplication.kt          # Hilt application
├── sensor/
│   └── SensorCollector.kt    # IMU + GPS streaming via Flow
├── pipeline/
│   ├── OrientationEstimator.kt   # Madgwick AHRS filter
│   ├── AccelerationTransformer.kt # body→nav frame, gravity removal
│   ├── VelocityIntegrator.kt     # trapezoidal integration
│   ├── ZUPTDetector.kt          # variance-based zero-velocity detection
│   └── StationDetector.kt       # metro station stop detection
├── ekf/
│   └── ExtendedKalmanFilter.kt   # 16-state EKF (error-state formulation)
├── route/
│   ├── RouteProfile.kt           # JSON route data model
│   └── RouteMatcher.kt           # particle-filter speed matching
├── data/
│   └── Models.kt                 # ImuSample, NavState, GpsFix, etc.
├── service/
│   └── TrackingService.kt        # foreground service + pipeline wiring
├── ui/
│   ├── MainScreen.kt             # main Compose screen
│   ├── VelocityGauge.kt          # circular speedometer
│   ├── SpeedChart.kt             # real-time speed chart
│   └── theme/AvTheme.kt          # Material 3 theme
└── util/
    ├── Quaternion.kt              # quaternion math
    ├── Matrix3x3.kt               # 3×3 matrix operations
    └── LowPassFilter.kt           # IIR low-pass filter
```

## Building

1. Open in Android Studio (Ladybug or later)
2. Sync Gradle
3. Run on device (not emulator — needs real sensors)

## Usage

1. Launch the app
2. Grant location permission (for GPS ground-truth)
3. Tap **Start Tracking**
4. Hold phone still for ~2 seconds (calibration)
5. Velocity gauge and chart will appear
6. Ride the metro — watch velocity go to 0 at each station

## Adding Your Metro Line

Create a JSON file in `routes/` following the format in `beijing_line1.json`:
```json
{
  "lineName": "...",
  "lineNameZh": "...",
  "totalLengthMeters": 30400,
  "avgSpeedMps": 18.0,
  "maxSpeedMps": 25.0,
  "stations": [
    {"name": "...", "nameZh": "...", "distanceFromStartMeters": 0, "isUnderground": true}
  ]
}
```

## References

- [Madgwick, S.O.H. (2010)](https://x-io.co.uk/res/doc/madgwick_internal_report.pdf) — AHRS filter
- [RoNIN (ICRA 2020)](https://arxiv.org/abs/1905.12853) — Neural inertial navigation
- [TLIO (IEEE RA-L 2020)](https://github.com/CDC-Huawei/TLIO) — Learned inertial odometry + EKF
