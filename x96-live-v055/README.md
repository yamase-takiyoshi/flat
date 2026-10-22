# X96 v0.5.5 DRM-free app/Chrome audio translation

- Android 11 / min API 29
- Capture AudioPlaybackCapture API. Only media apps that allow capture (no DRM bypass).
- Speech recognition: Vosk English small 0.15, first download ~40MB. License Apache 2.0.
- Translation: existing X96 ML Kit application 8788 (v0.5.4).
- Poll translated events on X96 localhost 8789 (health/events) via Termux 8777 /api/live* endpoint in revised v0.5.5 server.

Installation
1. Install existing X96 ML Kit translator app. Open it and get status '準備完了'.
2. Install this debug APK, open, press ① English model download. Check internet.
3. Press ② to authorize Android screen/audio capture, then switch to video in Chrome or supported app.
4. On iPad open http://X96_IP:8777 and enable 'Live: apps/Chrome' + Japanese voice.

AudioPlaybackCaptureConfiguration cannot capture audio from apps that restrict capture. X96 firmware support is unverified, test on real unit. Standard Vosk recognition uses endpointed speech and may lag several seconds. The current iPad page speaks translated phrases, not sample-accurate dubbing. No OpenAI/Gemini subscription/API required.
