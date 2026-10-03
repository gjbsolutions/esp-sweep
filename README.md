# ESP-SDR FFT Sweep (Android)
Android port of the desktop `esp_sdr_fft_sweep.py`: native USB-OTG serial bridge (usb-serial-for-android) + one local web page
(`app/src/main/assets/index.html`) that runs the on-chip FFT sweep (SPEC command), stitches windows and draws the spectrum.

Build: push to GitHub -> Actions -> "Build APK" -> download `app-debug.apk`, or run `gradle assembleDebug`.
Use: plug the ESP32 via USB-OTG, open the app, Connect, Start sweep. "Demo" runs with simulated data.
