# YunBanKe-AutoSign

[![Build](https://github.com/Canary233/Mosoteach-AutoSign/actions/workflows/build.yml/badge.svg)](https://github.com/Canary233/Mosoteach-AutoSign/actions/workflows/build.yml)

云班课签到状态查询与自动签到工具，使用云班课的 Web 登录和移动端签名接口。

## 已实现的功能

- 限时签到 `CLOCKIN`
- 一键签到 `SIMPLE`
- 手势签到 `POINT_9`
- 位置模拟
- 只读检查
- 自定义检查间隔
- 多账户

## 运行

需要 Java 8 或更高版本。

```powershell
java -jar autosign.jar
```

在 JAR 同目录创建 `config.toml`：

```toml
check_interval = 30
debug = false

[[accounts]]
name = "主账号"
username = "你的账号"
password = "你的密码"
# 可选：普通签到的自定义定位，「经度,纬度」，GCJ-02 坐标
# location = "121.482388,29.885685"
```

配置文件示例见 `config.example.toml`，修改配置后需要重启程序。
`check_interval` 的单位为秒，默认 30 秒，最小 10 秒，表示两轮检查开始时间的间隔。

## 参数

```text
--interval <秒>        覆盖配置文件的轮询间隔，最小 10 秒
--location <经度,纬度> 覆盖所有账户的签到定位，如 121.482388,29.885685
--once                 只检查一轮，不持续轮询
--no-auto-checkin      只查询签到状态，不自动签到
--debug                调试模式
--help                 显示帮助
--version              显示版本
```

配置优先级为：命令行参数、JAR 同目录的 `config.toml`。

只读检查示例：

```powershell
java -jar autosign.jar --once --no-auto-checkin
```

## 调试模式

在 `config.toml` 中设置 `debug = true`，或临时加 `--debug`：

```powershell
java -jar autosign.jar --debug
```

## 账户与定位

`config.toml` 账户写法：`[[accounts]]`。
单个账号就是一项，多个账号就重复添加多项。

```toml
check_interval = 20
debug = true

[[accounts]]
name = "主账号"
username = "账号1"
password = "密码1"
location = "121.482388,29.885685"

[[accounts.locations]]
course = "算法设计与分析"
location = "121.000000,31.000000"

[[accounts]]
name = "小号"
username = "账号2"
password = "密码2"
location = "121.473701,31.230416"
```

规则：

- 每个 `[[accounts]]` 必须同时有 `username` 和 `password`，缺一或为空即报错。
- `name` 只用于控制台和日志前缀，可省略，默认「账号1」「账号2」。
- 重复账号（忽略大小写和首尾空格）会被拒绝。
- 所有账户并发轮询，各自登录、各自查询和签到；单个账户登录失败或运行异常
  不影响其他账户，退出码取各账户的最大值。
- `--interval`、`--once`、`--debug` 等命令行选项对所有账户生效。

### 定位

一键签到(`SIMPLE`) 和 手势签到(`POINT_9`) 会提交 `report_pos_flag=Y` 和经纬度。
未配置坐标时经纬度为空；需要定位校验的签到会失败。
定位写一个值，顺序是 **经度,纬度**（和地图选点工具复制出来的一致）：

```toml
[[accounts]]
username = "你的账号"
password = "你的密码"
location = "121.482388,29.885685"
```

坐标需使用 GCJ-02（高德/腾讯地图）。中文逗号和空格也能识别。
也可以用命令行临时覆盖所有账户的定位：

```powershell
java -jar autosign.jar --location 121.482388,29.885685
```

启动时会显示每个账户生效的定位。`CLOCKIN` 限时签到不使用该坐标。

### 手势签到

`POINT_9` 与 `SIMPLE` 走同一个提交接口，请求体只有
`checkin_id`、`report_pos_flag`、`lat`、`lng` 四个字段，因此同样会提交上面的定位。

反编译云班课 5.4.x 客户端可见：服务端下发的 `checkin.points` 只在客户端
`LocusPassWordView` 里做本地比对，`https://checkin.mosoteach.cn:19528/checkin`
不接收图案参数，所以不需要（也无法）还原手势。调试模式下会打印收到的图案，
便于核对服务端行为。

### 不同课程使用不同定位

用 `[[accounts.locations]]` 为账户内的指定班课单独设置坐标。每块只能选一种匹配方式：

```toml
[[accounts.locations]]
course = "算法设计与分析"
location = "121.482388,29.885685"

[[accounts.locations]]
course_id = "F9CF052C-AD94-11F1-BAE9-A088C2A30E68"
location = "120.160000,30.280000"
```

匹配优先级：`course_id` > `course` > 账户 `location`。
课程名会忽略首尾空格与大小写，必须完整匹配。
同一选择器不能重复配置；未匹配到任何规则的班课使用账户定位，未配置时提交空经纬度。
课程定位只在所属账户内生效。

## 构建

```powershell
.\gradlew.bat test build
```

构建产物：

```text
build/libs/autosign.jar
```

## 说明

本项目仅用于个人学习与自动化辅助。请遵守学校及云班课平台的使用规则。
