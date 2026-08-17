# FluxStick — Perfect Large UI

This is a clean dependency-light Android virtual Xbox-style controller.

## UI
- Landscape fullscreen
- Large dual analog sticks
- Large A/B/X/Y cluster
- Large D-pad
- Large LB/RB and LT/RT
- Select / Guide / Start
- FluxStick black + electric-blue honeycomb styling
- Touch hitboxes are intentionally larger than the visible controls

## Build
Use the same JDK 17 and Gradle setup that worked for the previous FluxStick project.

Gradle wrapper: 9.6
Android Gradle Plugin: 9.3.1
compileSdk/targetSdk: 37

## PC connection
The Android app sends UDP controller state to:
192.168.0.103:26760

If your PC IP is different, edit `pcIp` in:
app/src/main/java/com/fluxstick/controller/ControllerView.java

On the PC:
```powershell
py -m pip install vgamepad
py PC/fluxstick_receiver.py
```

The existing ViGEm/vgamepad setup is required for Windows to expose the virtual Xbox controller.

## Important
This build is intentionally not a WebView. The controller is a native Android custom View, so the UI scales from the actual phone screen instead of inheriting the old browser UI's alignment problems.
