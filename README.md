# SeizureDetector

A robust Android and Wear OS application designed to detect seizure-like movements using device sensors and automatically notify emergency contacts with the user's location.

## Features

- **Real-time Monitoring**: Uses accelerometer data to detect high-intensity, repetitive movements characteristic of seizures.
- **Adaptive Icon**: Custom brand identity with a high-visibility icon on a solid black background.
- **Wear OS Integration**: Seamlessly syncs monitoring state and alert status with a companion Wear OS app.
- **Automatic SMS Alerts**: Sends emergency messages to a predefined list of contacts.
- **Location Sharing**: Includes Google Maps links in alerts to help responders find the user quickly.
- **Countdown Buffer**: 20-second countdown before sending alerts to prevent false alarms.
- **Modern UI**: Built with Jetpack Compose using a "Glassmorphism" design aesthetic.

## Installation

1. Clone the repository.
2. Open in Android Studio.
3. Build and deploy the `:app` module to your smartphone and the `:wear` module to your Wear OS device.

## Usage

1. **Add Contacts**: Enter the phone numbers of your emergency contacts in the main app.
2. **Start Monitoring**: Tap the "START MONITORING" button. The app will request SMS and Location permissions.
3. **Emergency**: If a seizure is detected, the phone and watch will vibrate intensely, and a 20-second countdown will begin.
4. **Cancel**: If it's a false alarm, tap "CANCEL ALERT" on either the phone or the watch.

## Permissions Required

- `SEND_SMS`: To notify contacts during an emergency.
- `ACCESS_FINE_LOCATION`: To include precise location data in alerts.
- `VIBRATE`: To provide haptic feedback during detection.

## Contributing

Contributions are welcome! Please see [CONTRIBUTORS.md](CONTRIBUTORS.md) for more details.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.