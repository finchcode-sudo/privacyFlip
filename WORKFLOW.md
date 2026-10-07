# Privacy Flip workflow

Privacy Flip requires Shizuku to control system-level privacy features.

## Shizuku not installed or not running
- The app shows a red alert card: Shizuku is required.
- Action button: install / open Shizuku, then start it (wireless debugging or ADB).
- No privacy actions are executed until Shizuku permission is granted.

## Shizuku running, permission not granted
- The app shows a "Grant Shizuku Permission" button.
- Tapping it opens the Shizuku authorization dialog.
- Privacy actions stay disabled until the permission is granted.

## Shizuku running and permission granted
- The system requirements card is hidden (once battery optimization is also disabled).
- On screen lock the enabled features are turned off; on unlock they are restored.
- Note: after a device reboot, Shizuku must be started again.
