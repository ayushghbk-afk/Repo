# Device Compatibility

## Minimum Requirements

| Component | Minimum | Notes |
|-----------|---------|-------|
| Android Version | 10 (API 29) | Required for storage access improvements |
| RAM | 2 GB | 4 GB+ recommended for desktop environments |
| Storage | 2 GB free | Depends on installed distributions |
| Architecture | ARM64 | No 32-bit ARM or x86 support |
| Root Access | Not required | PRoot doesn't need root |
| Termux | Not required | Self-contained app |

## Recommended Configuration

| Component | Recommended | Notes |
|-----------|-------------|-------|
| Android Version | 12 (API 31)+ | Better performance and battery optimization |
| RAM | 4 GB+ | Required for desktop environments |
| Storage | 8 GB+ | For multiple distributions |
| Architecture | ARM64 | Optimized builds |

## Supported Devices

### Confirmed Working

*(To be populated after testing)*

### Likely Compatible

- Google Pixel (4a and newer)
- Samsung Galaxy S series (S20 and newer)
- Samsung Galaxy A series (A52 and newer)
- OnePlus (8 and newer)
- Xiaomi (Mi 10 and newer)
- POCO (X3 and newer)

### Known Incompatible

- Devices with less than 2 GB RAM
- Devices running Android 9 or earlier
- x86/x86_64 Android emulators (limited testing)

## Testing Procedure

1. Install the APK
2. Launch Lenix
3. Verify the main screen loads
4. (Phase 2+) Create an instance
5. (Phase 3+) Download a distribution
6. (Phase 4+) Launch the Linux environment

## Performance Notes

- **PRoot overhead**: ~50-100 MB RAM for the engine
- **Openbox desktop**: ~200-400 MB RAM
- **LXQt desktop**: ~400-600 MB RAM
- **XFCE desktop**: ~600-800 MB RAM

Start with Openbox for best performance on 4 GB devices.

## Storage Considerations

- APK size: ~20-30 MB (initial)
- Debian base: ~500 MB compressed
- Alpine base: ~100 MB compressed
- Openbox + dependencies: ~200-300 MB

Total for basic setup: ~1-2 GB

## Battery Impact

Lenix runs as a foreground service when the Linux environment is active.
Expected battery drain: moderate to high depending on workload.

Recommendations:
- Keep screen brightness low
- Close Lenix when not in use
- Use power-saving mode on the device
