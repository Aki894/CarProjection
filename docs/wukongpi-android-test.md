# WukongPi Android CarLife test build

This is the next board bring-up step, not a finished autonomous CarPlay appliance.
Build `:app:assembleWukongpi` on branch `wukongpi-headless`. CI uploads
`CarProjection-wukongpi-apk`. The APK retains `com.projection.car` and the existing
public test signing key. It can replace the paired debug APK if signatures match.
Ordinary debug/release variants do not include BoardControlActivity.

## Configure and start without a launcher

In Windows platform-tools, replace the APK path with the downloaded artifact:

```powershell
.\adb.exe install -r "C:\path\app-wukongpi.apk"
.\adb.exe shell am force-stop com.projection.car
.\adb.exe shell am start -n com.projection.car/.BoardControlActivity --es command start
.\adb.exe logcat -d -s CarProjectionBoard:I '*:S'
```

`start` saves direct video, direct audio and direct input, then starts the existing
MainActivity so it can discover/authorize/open a CarLife accessory. It avoids
screen-capture/audio-capture setup for the selected direct mode. It does not
change device-specific audio tuning or grant USB permissions. The control
activity requires android.permission.DUMP, intended for adb shell/root. Do not
remove that restriction to work around a permission error.

`profile` saves the same settings without starting MainActivity. `status` reads
settings, session/descriptor presence and current accessories/USB permission:

```powershell
.\adb.exe shell am start -n com.projection.car/.BoardControlActivity --es command status
.\adb.exe logcat -d -s CarProjectionBoard:I '*:S'
```

Run force-stop only while preparing an idle test; it closes an active session.
Changing settings while an old Activity is running does not update that existing
session. Begin a new test with force-stop then start. Reconnects use the existing
CarLife state machine; no autonomous startup or process-death recovery is added.

## Test the car side first

ADB over Ethernet must be available before moving the OTG cable from PC to car.
The OTG port is the CarLife device side; connecting a phone to USB-A is not the
CarLife test. Check the USB accessory manufacturer/model are Baidu/CarLife,
permission is granted and the CarLife handshake/heartbeats persist. The initial
USB authorization dialog can still require remote UI interaction. An idle direct
video session without a DiPlay producer need not show a picture on the car.
Collect logcat and dumpsys usb around a connection attempt.

RTL8822CS 5 GHz and Bluetooth integration, DiPlay board adaptation, microphone
uplink, complete media/input tests and automatic appliance startup remain later
steps. Neither APK compilation nor Android boot completion proves any of these.
