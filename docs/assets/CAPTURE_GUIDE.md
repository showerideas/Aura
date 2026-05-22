# Capture guide

How to produce the screenshots and demo GIF referenced from [`docs/SHOWCASE.md`](../SHOWCASE.md).

## 1. Prepare the device

Use a real device or an API-33+ Pixel emulator. Enable **Demo mode** for clean status bars:

```bash
# Allow demo-mode
adb shell settings put global sysui_demo_allowed 1

# 12:00 clock, full battery, full signal, no notifications
adb shell am broadcast -a com.android.systemui.demo -e command exit
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1200
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false
```

Reset everything when done:

```bash
adb shell am broadcast -a com.android.systemui.demo -e command exit
```

## 2. Install AURA

```bash
adb install -r aura-v1.0.0-unsigned.apk
```

## 3. Capture stills

```bash
# Single screenshot
adb exec-out screencap -p > 01-onboarding.png
```

Rename per the slot table in [`SHOWCASE.md`](../SHOWCASE.md), drop into `docs/assets/`.

## 4. Capture the demo GIF

```bash
# 1. Record ~8s with the screenrecord daemon at 540p (smaller file)
adb shell screenrecord --size 540x1170 --bit-rate 4000000 --time-limit 8 /sdcard/aura-demo.mp4
adb pull /sdcard/aura-demo.mp4 .

# 2. Convert to a 6-second loop, 320 px wide, 12 fps (tiny + smooth)
ffmpeg -i aura-demo.mp4 -t 6 -vf "fps=12,scale=320:-1:flags=lanczos,split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse" -loop 0 aura-demo.gif

# 3. Optimise with gifsicle
gifsicle -O3 --lossy=80 -o aura-demo-opt.gif aura-demo.gif
mv aura-demo-opt.gif aura-demo.gif
```

Target file size **< 4 MB**.

## 5. Privacy checklist before pushing

- [ ] No real names, phone numbers, emails, or addresses on screen
- [ ] No Bluetooth MAC addresses or device serials visible
- [ ] Status bar in demo mode (12:00, full battery, full signal)
- [ ] No notifications visible
- [ ] Wallpaper / launcher chrome cropped out (raw screenshot only)

## 6. Commit

```bash
git add docs/assets/*.png docs/assets/aura-demo.gif
git commit -m "docs(showcase): capture v1.0.0 screenshots + demo GIF"
```
