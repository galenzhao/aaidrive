# Screen mirror vs full navigation map size

Source: [BimmerGestalt/IDriveConnectAddons#14](https://github.com/BimmerGestalt/IDriveConnectAddons/issues/14)  
Status when recorded: closed  
Opened: 2022-10-31 by DrTermi

## Problem

Screen Mirror on the car display leaves unused bottom / side margins (black or HU default background), while AAIdrive’s navigation map can fill the screen more completely.

Reported on several phones and HUs, including:

- Xiaomi Redmi Note 10 Pro / Note 12 Pro (1080×2400-class tall phones)
- Samsung Galaxy S10
- Pixel 6 (2400×1080)
- MINI ID4++ (`hmi.display-width: 800`)
- BMW ID6L / EntryEvo ID5 (`hmi.display-height: 480`, `hmi.display-width: 800`)

## Observed sizes (examples from the thread)

| Surface | Example size |
| --- | --- |
| CDS / HMI reported | 800×480 |
| AAIdrive map virtual display (one log) | 885×540 |
| Screen Mirror virtual display (stock addon log) | 672×480 |

Hardcoded RHMI values that looked closer to full-screen on one ID5/ID6L setup:

```text
screenMirrorProvider.setSize(980, 540)
image WIDTH=980 HEIGHT=540 POSITION_X=-90 POSITION_Y=-67
```

Custom settings example that worked for galenzhao (phone 2400×1080, HU reported 800×480, effective ~980×540):

```text
rhmiWidth: 974
rhmiHeight: 610
paddingLeft: 181
paddingTop: 67
```

(`marginLeft` / `marginRight` were noted as useless in that experiment.)

## How the pipeline works (from discussion)

1. App creates a virtual display / image buffer sized to RHMI width × height.
2. Phone screenshot is letterboxed into that buffer at a fixed aspect ratio (black bars around the content).
3. Buffer is drawn on the HU with default left/top padding; padding must be tuned to shift the image up/left and cover the HU chrome / default background.
4. Phone and HU aspect ratios rarely match → true “edge-to-edge phone UI” is impossible without cropping or stretching.
5. Growing buffer height can hide HU background when shifting the image down, but the letterboxed screenshot then moves by half of the height delta.
6. Stock Screen Mirror may create a **smaller** virtual display than the map module; setting a larger RHMI image size does not always upscale a too-small capture, so black bars remain.

## Related notes from maintainers

- hufman: map module and screen mirroring share similar sizing logic; suspected causes are image-centering code and/or phone↔HU aspect mismatch.
- Suggested follow-ups: stretch modes (match phone aspect, then Canvas/Matrix resize), vs keep aspect and only tune placement so the buffer covers the whole HU.
- Fork / custom settings branch discussed: [galenzhao/IDriveConnectAddons `customsettings`](https://github.com/galenzhao/IDriveConnectAddons/tree/customsettings); release mentioned in-thread: [galenzhao/IDriveConnectAddons releases/tag/v1](https://github.com/galenzhao/IDriveConnectAddons/releases/tag/v1).
- Also referenced from AAIdrive-side discussion: map / mirroring not expanding on NBTevo ID5 (upstream issue links in the GitHub thread).

## Takeaway for this repo

Full-screen feel on the car is mostly **RHMI widget size + padding + virtual-display buffer size + phone aspect**, not a single “fullscreen” flag. Map already aims for the larger capture; Screen Mirror historically used a smaller buffer and letterboxing, which is why it looked smaller than the navigation map.
