# YunBanKe-AutoSign

云班课签到状态查询与自动签到工具。当前版本使用云班课的 Web 登录和移动端签名
接口，不再依赖已经失效的旧网页登录流程。

## 当前状态

- 已真实验证账号登录。
- 已真实验证读取进行中的班课和当前签到状态。
- 已实现 `CLOCKIN` 限时签到。
- 已实现 `SIMPLE`、`POINT_9` 普通签到请求和签名，`SIMPLE` 已线上提交验证。
- 支持轮询、单轮检查、只读检查和自定义间隔。
- 只支持多账户配置：单个账号也写成一项 `[[accounts]]`。

`POINT_9` 的手势或定位校验是否会被服务端强制要求，仍需在真实签到出现后确认。

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

配置文件示例见 `config.example.toml`。`config.toml` 已加入 `.gitignore`，不会被提交。
修改配置后需要重启程序。启动时会显示实际读取的配置路径、账户列表和生效的检查间隔。
`check_interval` 的单位为秒，默认 30 秒，最小 10 秒，表示两轮检查开始时间的间隔。
若一整轮检查耗时已超过配置间隔，完成后立即开始下一轮，不会再额外等待。
程序不再读取或生成旧版账号文件。

账号和密码只从 `config.toml` 读取：不再支持命令行 `--account`/`--password`，
也不读取 `YBK_ACCOUNT`/`YBK_PASSWORD` 环境变量。

## 参数

```text
--interval <秒>        覆盖配置文件的轮询间隔，最小 10 秒
--location <经度,纬度> 覆盖所有账户的签到定位，如 121.482388,29.885685
--once                 只检查一轮，不持续轮询
--no-auto-checkin      只查询签到状态，不自动签到
--debug                调试模式：逐班课输出状态并保存脱敏抓包
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

调试模式会：

- 逐条输出 `[序号/总数] 班课名`，以及该班课的签到状态、错误码和耗时。
- 把 HTTP 请求/响应写入 `logs/debug/http.log`；`/ccs/joined` 只在每次启动时记录一次。
- 仅在签到已开启或响应解析失败时，保存该班课的原始响应到 `logs/debug/*.json`。
- 把每轮总耗时写入日志，便于判断是否受检查间隔限制。
- 把班课之间的固定等待从 300ms 缩短到 100ms。

多账户并发时，控制台、`logs/log.log` 和抓包文件名都带账户名前缀，便于区分。
抓包会自动隐藏密码、token、access secret、签名等凭据字段；登录请求只记录路径、
状态码和耗时，不记录请求体。`logs/` 已加入 `.gitignore`。
调试抓包可能包含课程名和班课 ID，分享前请自行确认。

## 支持范围

当前会遍历进行中的班课并查询签到状态。遇到开放签到时：

- `CLOCKIN`：调用限时签到接口。
- `SIMPLE`、`POINT_9`：调用普通签到接口。
- 其他类型：只显示状态，不提交。

签到提交需要登录响应同时包含移动端签名凭据和 `lastSecUpdateTsS`。缺失任一字段时
程序会拒绝提交，避免发送不完整请求。

## 账户与定位

`config.toml` 只有一种账户写法：`[[accounts]]`。单个账号就是一项，
多个账号就重复多块，程序对所有账户一视同仁，并发运行：

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

普通签到（`SIMPLE`、`POINT_9`）会提交 `report_pos_flag=Y` 和经纬度。未配置坐标时
经纬度为空；需要定位校验的签到会失败。定位写一个值，顺序是 **经度,纬度**
（和地图选点工具复制出来的一致）：

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

匹配优先级：`course_id` > `course`（课程名）> 账户 `location`。
课程名会忽略首尾空格与大小写，必须完整匹配。
同一选择器不能重复配置；未匹配到任何规则的班课使用账户定位，未配置时提交空经纬度。
课程定位只在所属账户内生效，不会被其他账户继承。

## 从旧配置迁移

旧版单账户写法已移除，启动时会提示改成 `[[accounts]]`：

```toml
# 不再支持
username = "你的账号"
password = "你的密码"
location = "121.482388,29.885685"

# 改为
[[accounts]]
username = "你的账号"
password = "你的密码"
location = "121.482388,29.885685"
```

`[[locations]]` 也改名为账户内的 `[[accounts.locations]]`。

## 安全说明

`config.toml` 和 `logs/` 已加入 `.gitignore`。不要把账号、密码、登录令牌或签名凭据
提交到仓库。若凭据曾经进入 Git 历史，应视为已经泄露并立即更换密码。

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
