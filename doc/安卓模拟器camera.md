# Linux 下为 Android 模拟器预设自定义摄像头图片完整文档

## 一、目标与原理

在 Linux 系统上，让 Android 模拟器的摄像头显示一张自定义静态图片（如二维码、测试图）。

实现原理：

1. 使用 `v4l2loopback` 创建虚拟摄像头设备，例如 `/dev/video1`。
2. 使用 `FFmpeg` 或 `GStreamer` 将静态图片循环推流到该虚拟设备。
3. Android 模拟器通过 `-camera-back webcam0` 使用这个虚拟摄像头。
4. 模拟器相机应用中即可看到预设图片。

---

## 二、前置条件

- Linux 系统，本文以 Ubuntu 为例
- 已安装 Android SDK 和 Android 模拟器
- 已创建 AVD，例如 `Pixel_Tablet`
- 已安装内核头文件、编译工具
- 可选：Secure Boot 已关闭，或能够签名内核模块

安装依赖：

```bash
sudo apt update
sudo apt install -y build-essential linux-headers-$(uname -r) v4l-utils ffmpeg
```

如需使用 GStreamer 方案：

```bash
sudo apt install -y gstreamer1.0-tools gstreamer1.0-plugins-good gstreamer1.0-plugins-bad
```

---

## 三、安装 v4l2loopback

### 1. 下载并编译

```bash
wget https://github.com/umlaeute/v4l2loopback/archive/master.zip
unzip master.zip
cd v4l2loopback-master
make
sudo make install
sudo depmod -a
```

### 2. 说明与注意点

- `make` 过程中出现类似下面的提示可以忽略：
  - `grep: /etc/dkms/framework.conf.d/*.conf: No such file or directory`
  - `To sign the module, you must set KBUILD_SIGN_KEY/KBUILD_SIGN_CERT...`
- 安装时出现 `SIGN` 表示模块已签名。
- 如果出现：
  ```text
  Warning: modules_install: missing 'System.map' file. Skipping depmod.
  ```
  手动执行一次：
  ```bash
  sudo depmod -a
  ```
- 如果 Secure Boot 拦截模块，`modprobe` 可能失败，需要关闭 Secure Boot 或注册 MOK 密钥。

---

## 四、加载虚拟摄像头模块

### 1. 卸载旧模块并重新加载

```bash
sudo modprobe -r v4l2loopback
sudo modprobe v4l2loopback video_nr=1 card_label="mockCam"
```

参数说明：

- `video_nr=1`：创建 `/dev/video1`
- `card_label="mockCam"`：设备标签，便于识别

### 2. 验证设备

```bash
lsmod | grep v4l2loopback
ls -l /dev/video*
cat /sys/module/v4l2loopback/parameters/video_nr
```

正常结果示例：

```text
v4l2loopback           61440  0
videodev              368640  1 v4l2loopback
crw-rw----+ 1 root video 81, 0 ... /dev/video1
1,-1,-1,-1,-1,-1,-1,-1
```

注意：

- 如果 `v4l2-ctl --list-devices` 报：
  ```text
  Cannot open device /dev/video0, exiting.
  ```
  这通常是因为系统没有 `/dev/video0`。可以直接指定 `/dev/video1`：
  ```bash
  v4l2-ctl -d /dev/video1 --all
  ```
- `card_label` 的 sysfs 文件可能不存在，不影响使用。

---

## 五、解决权限问题

如果访问 `/dev/video1` 报：

```text
Permission denied
```

### 方法一：将用户加入 video 组（推荐）

```bash
sudo usermod -aG video $USER
```

执行后必须**注销重新登录**或重启系统。

验证：

```bash
id | grep video
groups
```

### 方法二：临时放宽权限

```bash
sudo chmod 666 /dev/video1
```

适合开发机临时使用，重启或模块重载后失效。

### 方法三：udev 规则持久化

```bash
sudo tee /etc/udev/rules.d/99-v4l2loopback.rules <<EOF
KERNEL=="video*", SUBSYSTEM=="video4linux", GROUP="video", MODE="0660"
EOF

sudo udevadm control --reload-rules
sudo udevadm trigger
```

---

## 六、推流自定义图片到虚拟摄像头

准备一张图片，例如：

```bash
wget "https://chart.googleapis.com/chart?chs=600x340&cht=qr&chl=testing" -O qr.png
```

### 方式一：FFmpeg（推荐）

```bash
ffmpeg -loop 1 -stream_loop -1 -framerate 10 -i qr.png -pix_fmt yuv420p -s 640x480 -f v4l2 /dev/video1
```

参数说明：

- `-loop 1 -stream_loop -1`：无限循环图片
- `-framerate 10`：10 帧/秒
- `-pix_fmt yuv420p`：常见像素格式，模拟器通常兼容
- `-s 640x480`：输出分辨率，建议先用 640x480 或 1280x720
- `-f v4l2 /dev/video1`：输出到虚拟摄像头

**保持这个终端窗口运行，不要关闭。**

### 方式二：GStreamer

```bash
gst-launch-1.0 filesrc location=qr.png ! pngdec ! imagefreeze ! v4l2sink device=/dev/video1
```

如果提示找不到 `v4l2sink`，安装插件：

```bash
sudo apt install gstreamer1.0-plugins-good gstreamer1.0-plugins-bad
```

---

## 七、验证推流是否正常

另开一个终端：

```bash
v4l2-ctl -d /dev/video1 --all
```

或抓一帧保存：

```bash
ffmpeg -f v4l2 -i /dev/video1 -frames:v 1 test_out.png
```

打开 `test_out.png`，如果显示的是你推流的图片，说明流正常。

---

## 八、启动 Android 模拟器并使用虚拟摄像头

### 1. 进入 emulator 目录

```bash
cd $ANDROID_HOME/emulator
```

如果 `ANDROID_HOME` 未设置，进入 Android SDK 的 `emulator` 目录。

### 2. 列出可用摄像头

```bash
./emulator -avd Pixel_Tablet -webcam-list
```

正常输出示例：

```text
List of web cameras connected to the computer:
 Camera 'webcam0' is connected to device 'mockCam' on channel 0 using pixel format 'YU12'
```

**关键点：**

- 设备标签是 `mockCam`
- 模拟器识别到的编号是 `webcam0`
- 启动时应使用 `webcam0`，不是 `webcam1`

### 3. 启动模拟器

仅使用后置摄像头：

```bash
./emulator -avd Pixel_Tablet -camera-back webcam0 -camera-front none
```

前后摄都使用该虚拟设备：

```bash
./emulator -avd Pixel_Tablet -camera-back webcam0 -camera-front webcam0
```

### 4. 在模拟器中验证

1. 打开模拟器里的 **Camera** 应用。
2. 如果默认是前置，点击翻转按钮切换到后置。
3. 画面应显示你推流的静态图片。

---

## 九、开机自动加载配置（可选）

避免每次重启后手动加载模块。

```bash
sudo tee /etc/modprobe.d/v4l2loopback.conf <<EOF
options v4l2loopback video_nr=1 card_label="mockCam"
EOF

sudo tee /etc/modules-load.d/v4l2loopback.conf <<EOF
v4l2loopback
EOF
```

验证：

```bash
sudo modprobe -r v4l2loopback
sudo modprobe v4l2loopback
ls -l /dev/video*
```

---

## 十、完整流程速查

```bash
# 1. 安装依赖
sudo apt update
sudo apt install -y build-essential linux-headers-$(uname -r) v4l-utils ffmpeg

# 2. 编译安装 v4l2loopback
wget https://github.com/umlaeute/v4l2loopback/archive/master.zip
unzip master.zip
cd v4l2loopback-master
make
sudo make install
sudo depmod -a

# 3. 加载模块
sudo modprobe -r v4l2loopback
sudo modprobe v4l2loopback video_nr=1 card_label="mockCam"

# 4. 权限
sudo usermod -aG video $USER
# 注销重登，或临时：
sudo chmod 666 /dev/video1

# 5. 推流图片（终端 1，保持不关）
ffmpeg -loop 1 -stream_loop -1 -framerate 10 -i qr.png -pix_fmt yuv420p -s 640x480 -f v4l2 /dev/video1

# 6. 验证
v4l2-ctl -d /dev/video1 --all

# 7. 启动模拟器（终端 2）
cd $ANDROID_HOME/emulator
./emulator -avd Pixel_Tablet -webcam-list
./emulator -avd Pixel_Tablet -camera-back webcam0 -camera-front none
```

---

## 十一、常见问题与注意点

| 问题 | 原因 | 解决 |
|------|------|------|
| `Permission denied` 访问 `/dev/video1` | 用户不在 `video` 组 | `sudo usermod -aG video $USER` 后注销重登；或 `sudo chmod 666 /dev/video1` |
| `v4l2-ctl --list-devices` 报 `Cannot open /dev/video0` | 系统没有 video0 | 忽略，直接用 `-d /dev/video1` |
| 没有 `/dev/video1` | 加载模块时未指定 `video_nr` | 卸载后重新加载：`sudo modprobe v4l2loopback video_nr=1 card_label="mockCam"` |
| `modprobe` 失败，提示 key rejected | Secure Boot 拦截 | 关闭 Secure Boot 或注册 MOK 密钥 |
| `missing System.map` | 自动 depmod 被跳过 | 手动执行 `sudo depmod -a` |
| 模拟器 `-webcam-list` 显示 `webcam0` | 设备编号与 `/dev/video1` 不同 | 启动时用 `-camera-back webcam0` |
| 模拟器相机黑屏 | 推流终端已关闭，或分辨率/格式不匹配 | 保持推流终端运行；使用 `640x480`、`yuv420p`；模拟器期望 `YU12`，通常兼容 |
| 相机应用默认前置 | 前置未配置 | 加 `-camera-front webcam0` 或手动翻转摄像头 |
| 重启后虚拟摄像头消失 | 模块未自动加载 | 配置 `/etc/modprobe.d` 和 `/etc/modules-load.d` |

---

## 十二、关键要点总结

1. `v4l2loopback` 负责创建虚拟摄像头设备。
2. 加载模块时必须指定 `video_nr=1`，否则可能不生成 `/dev/video1`。
3. 普通用户需要加入 `video` 组，或调整 `/dev/video1` 权限。
4. 推流终端必须保持运行，否则模拟器画面会黑屏。
5. 模拟器识别到的摄像头编号是 `webcam0`，不是 `webcam1`。
6. 启动模拟器时使用：
   ```bash
   ./emulator -avd Pixel_Tablet -camera-back webcam0 -camera-front none
   ```
7. Secure Boot、内核签名、depmod 警告是常见干扰项，按文档处理即可。