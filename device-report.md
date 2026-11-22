# Echo device report (2026-11-22 03:19)
Serial: 192.168.10.136:45861

## Build
```
ro.product.model             Echo Show 5
ro.product.device            checkers
ro.build.version.release     11
ro.build.version.sdk         30
ro.build.type                userdebug
ro.lineage.version           18.1-20261101-UNOFFICIAL-checkers
ro.lineage.build.version     18.1
ro.product.cpu.abi           armeabi-v7a
ro.product.cpu.abilist       armeabi-v7a,armeabi
ro.hardware                  mt8163
ro.board.platform            mt8163
ro.sf.lcd_density            195
```

## Root
```
/system/bin/sh: su: inaccessible or not found
```

## Display
```
Physical size: 960x480
Physical density: 195
	Refresh Rate Map:
GLES: ARM, Mali-T720, OpenGL ES 3.1 v1.r26p0-01rel0.526d936ea9da20486773a9aaceecd920
  refresh-rate              : 58.930002 fps
      activeBuffer=[   0x   0:   0,Unknown/None], tr=[0.00, 0.00][0.00, 0.00] queued-frames=0, mRefreshPending=0, metadata={}, cornerRadiusCrop=[0.00, 0.00, 0.00, 0.00],  shadowRadius=0.000, 
      activeBuffer=[   0x   0:   0,Unknown/None], tr=[0.00, 0.00][0.00, 0.00] queued-frames=0, mRefreshPending=0, metadata={}, cornerRadiusCrop=[0.00, 0.00, 0.00, 0.00],  shadowRadius=0.000, 
```

## CPU / RAM / storage
```
      1 Hardware	: MT8163
      1 processor	: 0
      1 processor	: 1
      1 processor	: 2
      1 processor	: 3
MemTotal:         996976 kB
MemFree:           38424 kB
MemAvailable:     335960 kB
Filesystem            Size  Used Avail Use% Mounted on
/dev/block/mmcblk0p16 3.7G  184M  3.4G   5% /data/user/0
/dev/block/mmcblk0p12 2.9G  1.0G  1.9G  35% /apex/com.android.wifi
```

## Network
```
    inet 192.168.10.136/24 brd 192.168.10.255 scope global wlan0
5555
```

## Sensors (looking for a light sensor)
```
Sensor List:
0000000000) Light Sensor              | amazon-oss      | ver: 1 | type: android.sensor.light(5) | perm: n/a | flags: 0x00000002
	on-change | maxDelay=0us | minDelay=0us | no batching | non-wakeUp | 
Fusion States:
9-axis fusion disabled (0 clients), gyro-rate= 200.00Hz, q=< 0, 0, 0, 0 > (0), b=< 0, 0, 0 >
game fusion(no mag) disabled (0 clients), gyro-rate= 200.00Hz, q=< 0, 0, 0, 0 > (0), b=< 0, 0, 0 >
geomag fusion (no gyro) disabled (0 clients), gyro-rate= 200.00Hz, q=< 0, 0, 0, 0 > (0), b=< 0, 0, 0 >
Recent Sensor events:
Active sensors:
Socket Buffer size = 39 events
WakeLock Status: not held 
Mode : NORMAL
Sensor Privacy: disabled
0 active connections
0 direct connections
Previous Registrations:
```

## Cameras
```
Number of camera devices: 1
Number of public camera devices visible to API1: 1
  API1 info:
    Facing: Front
  API2 camera characteristics:
      android.lens.facing (80005): byte[1]
```

## Input devices and keys (looking for the mic/camera button)
```
add device 1: /dev/input/event2
  name:     "goodix-ts"
  events:
    ABS (0003): ABS_MT_SLOT           : value 0, min 0, max 15, fuzz 0, flat 0, resolution 0
                ABS_MT_TOUCH_MAJOR    : value 0, min 0, max 255, fuzz 0, flat 0, resolution 0
                ABS_MT_WIDTH_MAJOR    : value 0, min 0, max 255, fuzz 0, flat 0, resolution 0
                ABS_MT_POSITION_X     : value 0, min 0, max 480, fuzz 0, flat 0, resolution 0
                ABS_MT_POSITION_Y     : value 0, min 0, max 960, fuzz 0, flat 0, resolution 0
                ABS_MT_TRACKING_ID    : value 0, min 0, max 255, fuzz 0, flat 0, resolution 0
  input props:
    INPUT_PROP_DIRECT
add device 2: /dev/input/event3
  name:     "ACCDET"
  events:
  input props:
    <none>
add device 3: /dev/input/event5
  name:     "m_alsps_input"
  events:
    REL (0002): REL_Y                 REL_Z                
    ABS (0003): ABS_X                 : value 0, min -32768, max 32767, fuzz 0, flat 0, resolution 0
                ABS_WHEEL             : value 0, min 0, max 64, fuzz 0, flat 0, resolution 0
  input props:
    <none>
add device 4: /dev/input/event4
  name:     "hwmdata"
  events:
    REL (0002): REL_Y                 REL_Z                
  input props:
    <none>
add device 5: /dev/input/event1
  name:     "mtk-kpd"
  events:
  input props:
    <none>
add device 6: /dev/input/event6
  name:     "gpio-keys"
  events:
    KEY (0001): KEY_VOLUMEDOWN        KEY_VOLUMEUP         
    SW  (0005): SW_CAMERA_LENS_COVER 
  input props:
    <none>
add device 7: /dev/input/event0
  name:     "gating"
  events:
    KEY (0001): KEY_POWER            
  input props:
    <none>
```

## Audio
```
   Devices: speaker
   Devices: speaker
   Devices: speaker
   Devices: speaker
   Devices: speaker
   Devices: speaker
   Devices: speaker
   Devices: speaker
   Devices: speaker
   Devices: speaker
   Devices: speaker
   Devices: speaker
   Devices: speaker
   Devices: speaker
   Devices: speaker
Failed to write while dumping service audio: Broken pipe
```

## Current home app and third-party apps
```
com.android.launcher3/.uioverrides.QuickstepLauncher
```

## Live key events while pressing the mic/camera button
```
add device 1: /dev/input/event2
  name:     "goodix-ts"
add device 2: /dev/input/event3
  name:     "ACCDET"
add device 3: /dev/input/event5
  name:     "m_alsps_input"
add device 4: /dev/input/event4
  name:     "hwmdata"
add device 5: /dev/input/event1
  name:     "mtk-kpd"
add device 6: /dev/input/event6
  name:     "gpio-keys"
add device 7: /dev/input/event0
  name:     "gating"
/dev/input/event0: EV_KEY       KEY_POWER            DOWN                
/dev/input/event0: EV_SYN       SYN_REPORT           00000000            
/dev/input/event0: EV_KEY       KEY_POWER            UP                  
/dev/input/event0: EV_SYN       SYN_REPORT           00000000            
/dev/input/event0: EV_KEY       KEY_POWER            DOWN                
/dev/input/event0: EV_SYN       SYN_REPORT           00000000            
/dev/input/event0: EV_KEY       KEY_POWER            UP                  
/dev/input/event0: EV_SYN       SYN_REPORT           00000000            
/dev/input/event0: EV_KEY       KEY_POWER            DOWN                
/dev/input/event0: EV_SYN       SYN_REPORT           00000000            
/dev/input/event0: EV_KEY       KEY_POWER            UP                  
/dev/input/event0: EV_SYN       SYN_REPORT           00000000            
/dev/input/event0: EV_KEY       KEY_POWER            DOWN                
/dev/input/event0: EV_SYN       SYN_REPORT           00000000            
/dev/input/event0: EV_KEY       KEY_POWER            UP                  
/dev/input/event0: EV_SYN       SYN_REPORT           00000000            
/dev/input/event0: EV_KEY       KEY_POWER            DOWN                
/dev/input/event0: EV_SYN       SYN_REPORT           00000000            
/dev/input/event0: EV_KEY       KEY_POWER            UP                  
/dev/input/event0: EV_SYN       SYN_REPORT           00000000            
/dev/input/event0: EV_KEY       KEY_POWER            DOWN                
/dev/input/event0: EV_SYN       SYN_REPORT           00000000            
/dev/input/event0: EV_KEY       KEY_POWER            UP                  
/dev/input/event0: EV_SYN       SYN_REPORT           00000000            
/dev/input/event0: EV_KEY       KEY_POWER            DOWN                
/dev/input/event0: EV_SYN       SYN_REPORT           00000000            
/dev/input/event0: EV_KEY       KEY_POWER            UP                  
/dev/input/event0: EV_SYN       SYN_REPORT           00000000            
/dev/input/event0: EV_KEY       KEY_POWER            DOWN                
/dev/input/event0: EV_SYN       SYN_REPORT           00000000            
/dev/input/event0: EV_KEY       KEY_POWER            UP                  
/dev/input/event0: EV_SYN       SYN_REPORT           00000000            
```

## Live key events while pressing volume up/down
```
add device 1: /dev/input/event2
  name:     "goodix-ts"
add device 2: /dev/input/event3
  name:     "ACCDET"
add device 3: /dev/input/event5
  name:     "m_alsps_input"
add device 4: /dev/input/event4
  name:     "hwmdata"
add device 5: /dev/input/event1
  name:     "mtk-kpd"
add device 6: /dev/input/event6
  name:     "gpio-keys"
add device 7: /dev/input/event0
  name:     "gating"
/dev/input/event2: EV_ABS       ABS_MT_TRACKING_ID   00000000            
/dev/input/event2: EV_ABS       ABS_MT_POSITION_X    0000003b            
/dev/input/event2: EV_ABS       ABS_MT_POSITION_Y    000000a7            
/dev/input/event2: EV_ABS       ABS_MT_TOUCH_MAJOR   0000004e            
/dev/input/event2: EV_ABS       ABS_MT_WIDTH_MAJOR   0000004e            
/dev/input/event2: EV_SYN       SYN_REPORT           00000000            
/dev/input/event2: EV_ABS       ABS_MT_POSITION_X    00000040            
/dev/input/event2: EV_ABS       ABS_MT_POSITION_Y    000000a6            
/dev/input/event2: EV_SYN       SYN_REPORT           00000000            
/dev/input/event2: EV_ABS       ABS_MT_POSITION_X    0000004e            
/dev/input/event2: EV_SYN       SYN_REPORT           00000000            
/dev/input/event2: EV_ABS       ABS_MT_POSITION_X    00000078            
/dev/input/event2: EV_ABS       ABS_MT_POSITION_Y    000000a7            
/dev/input/event2: EV_SYN       SYN_REPORT           00000000            
/dev/input/event2: EV_ABS       ABS_MT_POSITION_X    0000009e            
/dev/input/event2: EV_ABS       ABS_MT_POSITION_Y    000000a9            
/dev/input/event2: EV_SYN       SYN_REPORT           00000000            
/dev/input/event2: EV_ABS       ABS_MT_POSITION_X    000000c9            
/dev/input/event2: EV_ABS       ABS_MT_POSITION_Y    000000ad            
/dev/input/event2: EV_SYN       SYN_REPORT           00000000            
/dev/input/event2: EV_ABS       ABS_MT_POSITION_X    000000f0            
/dev/input/event2: EV_ABS       ABS_MT_POSITION_Y    000000b3            
/dev/input/event2: EV_ABS       ABS_MT_TOUCH_MAJOR   00000045            
/dev/input/event2: EV_ABS       ABS_MT_WIDTH_MAJOR   00000045            
/dev/input/event2: EV_SYN       SYN_REPORT           00000000            
/dev/input/event2: EV_ABS       ABS_MT_TRACKING_ID   ffffffff            
/dev/input/event2: EV_SYN       SYN_REPORT           00000000            
/dev/input/event6: EV_KEY       KEY_VOLUMEUP         DOWN                
/dev/input/event6: EV_SYN       SYN_REPORT           00000000            
/dev/input/event6: EV_KEY       KEY_VOLUMEUP         UP                  
/dev/input/event6: EV_SYN       SYN_REPORT           00000000            
/dev/input/event6: EV_KEY       KEY_VOLUMEDOWN       DOWN                
/dev/input/event6: EV_SYN       SYN_REPORT           00000000            
/dev/input/event6: EV_KEY       KEY_VOLUMEDOWN       UP                  
/dev/input/event6: EV_SYN       SYN_REPORT           00000000            
```
