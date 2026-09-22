好的，方案确认可行。下面是这个方案的完整整理，方便你以后复用。

---

# 多张图片无缝循环推流到 Android 模拟器虚拟摄像头

## 一、方案概述

**核心思路：先把多张图片合成一个 mp4 视频，再对 mp4 用 `-stream_loop -1` 无缝循环推流到 `/dev/video1`。**

相比直接用图片序列推流，这个方案解决了三个问题：

- ✅ **无缝循环**：`-stream_loop -1` 对 mp4 稳定生效，不像 image2/concat
- ✅ **精确控制每张时长**：用 concat 的 `duration` 定义，不依赖 `-framerate`
- ✅ **行为可预测**：mp4 生成一次，之后每次推流直接用

---

## 二、完整操作流程

### 前置条件

虚拟摄像头已就绪：

```bash
ls -l /dev/video1
sudo modprobe v4l2loopback video_nr=1 card_label="mockCam"
```

图片目录结构示例：

```text
~/test/img/
├── img_0001.jpg
├── img_0002.jpg
├── ...
└── img_0039.jpg
```

### 第一步：生成 mylist.txt

```bash
cd ~/test

> mylist.txt
for f in img/img_*.jpg; do
  echo "file '$f'" >> mylist.txt
  echo "duration 5" >> mylist.txt
done
# concat 要求最后一行重复第一张，否则最后一张 duration 可能不生效
echo "file 'img/img_0001.jpg'" >> mylist.txt
```

- `duration 5` 表示每张图停留 **5 秒**，按需修改
- 最后重复第一张是 concat 协议的固定要求

### 第二步：合成 mp4

```bash
ffmpeg -f concat -safe 0 -i mylist.txt \
  -vf "scale=640:480,format=yuv420p" \
  -r 10 -c:v libx264 -pix_fmt yuv420p slideshow.mp4
```

参数说明：

| 参数 | 作用 |
|------|------|
| `-f concat -safe 0` | 使用 concat 协议读取列表 |
| `-i mylist.txt` | 图片列表 |
| `-vf "scale=640:480,format=yuv420p"` | 统一分辨率、像素格式 |
| `-r 10` | 输出 10fps |
| `-c:v libx264` | H.264 编码 |
| `-pix_fmt yuv420p` | 兼容性最好的像素格式 |

验证时长：

```bash
ffprobe -v error -show_entries format=duration -of default=nw=1 slideshow.mp4
```

39 张 × 5 秒 = 195 秒左右。

### 第三步：无缝循环推流到虚拟摄像头

```bash
ffmpeg -stream_loop -1 -re -i slideshow.mp4 \
  -pix_fmt yuv420p -s 640x480 -f v4l2 /dev/video1
```

**关键参数：**

- `-stream_loop -1`：无限循环 mp4，**必须放在 `-i` 之前**
- `-re`：**按真实时间读取**，否则 FFmpeg 会全速跑完，画面一闪而过
- `-pix_fmt yuv420p -s 640x480`：与模拟器兼容的输出格式

**保持这个终端不关。**

### 第四步：启动 Android 模拟器

```bash
cd $ANDROID_HOME/emulator
./emulator -avd Pixel_Tablet -camera-back webcam0 -camera-front none
```

打开相机应用，切到后置摄像头，即可看到图片循环播放。

---

## 三、完整命令速查

```bash
cd ~/test

# 1. 生成 mylist.txt
> mylist.txt
for f in img/img_*.jpg; do
  echo "file '$f'" >> mylist.txt
  echo "duration 5" >> mylist.txt
done
echo "file 'img/img_0001.jpg'" >> mylist.txt

# 2. 合成 mp4
ffmpeg -f concat -safe 0 -i mylist.txt \
  -vf "scale=640:480,format=yuv420p" \
  -r 10 -c:v libx264 -pix_fmt yuv420p slideshow.mp4

# 3. 无缝循环推流（终端保持不关）
ffmpeg -stream_loop -1 -re -i slideshow.mp4 \
  -pix_fmt yuv420p -s 640x480 -f v4l2 /dev/video1

# 4. 另开终端启动模拟器
cd $ANDROID_HOME/emulator
./emulator -avd Pixel_Tablet -camera-back webcam0 -camera-front none
```

---

## 四、调整参数

### 每张图停留时长

改 `mylist.txt` 里的 `duration` 值，然后重新合成 mp4：

| 每张停留 | `duration` |
|---------|-----------|
| 1 秒    | `1`       |
| 2 秒    | `2`       |
| 3 秒    | `3`       |
| 5 秒    | `5`       |
| 10 秒   | `10`      |

### 不同图片不同时长

直接手写 `mylist.txt`：

```text
file 'img/img_0001.jpg'
duration 5
file 'img/img_0002.jpg'
duration 2
file 'img/img_0003.jpg'
duration 10
file 'img/img_0001.jpg'
```

### 修改分辨率

改 `-vf` 里的 `scale` 和推流命令里的 `-s`，两者保持一致：

```bash
-vf "scale=1280:720,format=yuv420p"
...
-s 1280:720
```

推荐 `640x480`、`1280x720`，避免正方形或奇怪比例。

---

## 五、方案对比

| 方案 | 无缝循环 | 控制每张时长 | 可靠性 |
|------|---------|-------------|--------|
| 单张图 + `-stream_loop -1` | ✅ | 不适用 | ✅ |
| 多张图 image2 + `-stream_loop -1` | ❌ | ✅（`1/N` 分数） | ❌ 不支持循环 |
| 多张图 concat + `-stream_loop -1` | ❌ | ❌ 被忽略 | ❌ 不可靠 |
| while 循环重启 FFmpeg | ❌ 有短暂中断 | ✅ | ✅ |
| **mp4 + `-stream_loop -1`** | ✅ | ✅ | ✅ **推荐** |

---

## 六、要点与注意点

1. **`-stream_loop -1` 必须放在 `-i` 之前**，作用于输入文件。
2. **`-re` 不能少**，否则 mp4 会全速播完，画面一闪而过。
3. **mp4 只需生成一次**，之后每次推流直接用它，不用重新合成。
4. **改图片或时长后需重新合成 mp4**。
5. **推流终端必须保持运行**，关掉模拟器画面就会黑屏。
6. **模拟器摄像头编号是 `webcam0`**，不是 `/dev/video1` 里的 `1`，用 `-webcam-list` 确认。
7. **分辨率与像素格式**：输出统一用 `yuv420p`，模拟器识别的 `YU12` 与它兼容。
8. **`deprecated pixel format` 警告可忽略**，来自 JPG 的 `yuvj420p`，不影响使用。

---

## 七、常见问题

| 现象 | 原因 | 解决 |
|------|------|------|
| 画面切换飞快 | 缺少 `-re` | 加上 `-re` |
| 播完不循环 | `-stream_loop -1` 位置不对 | 放在 `-i` 之前 |
| 模拟器黑屏 | 推流终端已关闭 | 保持终端运行 |
| 相机预览花屏 | 分辨率不匹配 | 改成 `640x480` 或 `1280x720` |
| `-webcam-list` 没设备 | 模拟器没识别 `/dev/video1` | 检查权限、确认模块已加载 |
| mp4 时长不对 | `mylist.txt` 里 duration 没生效 | 确认最后重复了第一张，重新合成 |

---

这套方案已验证可行，可以作为固定流程使用。需要换图时，只需替换 `img/` 目录里的图片、重新生成 `mylist.txt` 和 `slideshow.mp4`，然后重新推流即可。