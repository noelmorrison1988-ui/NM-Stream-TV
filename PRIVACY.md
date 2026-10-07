# NM Stream TV — privacy notes

NM Stream TV is designed as a local Android TV client.

The app may store the following on the Android device:

- installed add-on manifest URLs;
- TMDB API Read Access Token supplied by the user;
- Trakt Client ID and OAuth access data supplied/authorized by the user;
- Real-Debrid OAuth access data authorized by the user;
- local Continue Watching playback positions.

Service credentials handled through `SecretStore` are encrypted using an AES-GCM key held by Android Keystore. Continue Watching history is stored in app-private preferences.

NM Stream TV communicates directly with services the user configures, including installed add-on servers, TMDB, Trakt and Real-Debrid. This repository does not contain an NM-operated analytics or telemetry backend.

For a public commercial release, publish a formal privacy policy describing the final production infrastructure, retention rules, support contact and account/data deletion process.
