# 区域血透中心患者排班透析机消毒耗材与异常上报平台

面向血透中心高频、固定又容易被突发情况打断的工作节奏，把**患者、护士、医生、工程、收费窗口**的状态串成一条完整的治疗记录：

- 固定每周透析的患者排班，受机位数量、感染分区、透析时长、交通接送、耗材库存、设备消毒时间约束
- 护士透前评估（体重/血压/通路/上次不适/抗凝/脱水量调整）→ 上机 → 透中定时记录（血压/心率/报警/补液/抽血/不适/医嘱变更）→ 下机记录（下机体重/穿刺点止血/离开方式/下次排班）
- 工程人员维护透析机消毒、滤芯更换、水处理记录
- 异常上报贯穿全程：迟到、请假、感染标记变化、机器故障、耗材不足、透析中低血压、医嘱调整
- 收费窗口核对医保、自费耗材、补收费用；管理端查看机位利用率、异常率、耗材损耗、消毒合规
- 护士长调班时可预览影响：波及哪些患者、哪些机器消毒时间被压缩、哪些耗材已预领
- **机位故障改排**：透析机自检失败/故障后，平台自动筛出当天受影响患者、传染病分区要求与可用备用机位；护士长逐人选择延后、换机或改到下午班，患者通知、耗材准备、消毒计划同步联动；患者已到院的由前台登记等候补偿与交通调整；无法接受延后的患者可与其他患者协商调换，双方确认留痕，防止口头换班争议

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | Vue 3 + Vite + Element Plus + Pinia + ECharts（Nginx 托管） |
| 后端 | Spring Boot 3 + Spring Security(JWT) + Spring Data JPA |
| 数据库 | PostgreSQL 16（本地开发可用 15） |

## 一键启动（Docker Compose）

```bash
docker compose up -d --build
```

启动后访问：

- 前端：http://localhost （80 端口）
- 后端 API：http://localhost:8080
- Swagger UI：http://localhost:8080/swagger-ui.html

首次启动后端会自动建表并写入演示数据（账号、12 台机位、18 名患者、耗材库存、今日排班与一条完整治疗流水，以及一个进行中的机位故障改排方案演示）。

## 本地开发

### 后端

```bash
# 1. 准备数据库（PostgreSQL）
createdb hd_center   # 或 psql -c "CREATE DATABASE hd_center;"

# 2. 启动后端（默认连 localhost:5432，可用环境变量覆盖）
cd backend
mvn spring-boot:run
# 或指定数据库：
# DB_URL=jdbc:postgresql://localhost:5432/hd_center DB_USER=postgres DB_PASS=postgres mvn spring-boot:run
```

### 前端

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173 ，已配置 /api 代理到 8080
```

### 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/hd_center` | 数据库连接 |
| `DB_USER` / `DB_PASS` | `postgres` / `postgres` | 数据库账号 |
| `SERVER_PORT` | `8080` | 后端端口 |
| `JWT_SECRET` | 内置开发密钥 | 生产环境务必覆盖 |
| `JWT_EXPIRE_MINUTES` | `720` | 登录态有效期 |

## 测试账号

| 账号 | 密码 | 角色 | 主要菜单 |
|---|---|---|---|
| `admin` | `admin123` | 护士长/管理 | 全部功能 |
| `nurse1` / `nurse2` | `123456` | 护士 | 工作台、排班、故障改排、患者、透析治疗、异常 |
| `doctor1` | `123456` | 医生 | 工作台、排班、患者、透析治疗、异常 |
| `eng1` | `123456` | 工程 | 机器与消毒、耗材库存、异常 |
| `fin1` | `123456` | 收费 | 收费核对、统计分析 |

## 验证方式（本任务实际执行过的验证）

### 1. 后端编译与启动

```bash
cd backend && mvn -s settings.xml -DskipTests package   # 构建通过，产出 target/hd-center-backend-1.0.0.jar
DB_URL=jdbc:postgresql://localhost:5433/hd_center java -jar target/hd-center-backend-1.0.0.jar
# 日志出现 "Started HdCenterApplication" 与 "演示数据初始化完成"
```

### 2. API 冒烟（curl 实际执行通过）

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")

# 今日工作台：18 条治疗单、机位状态分布、待处理异常、耗材预警
curl -s http://localhost:8080/api/stats/dashboard -H "Authorization: Bearer $TOKEN"

# 排班看板：感染标记自动匹配分区（HBV 患者排到乙肝区机位）
curl -s "http://localhost:8080/api/sessions/board?date=$(date +%F)&shift=AM" -H "Authorization: Bearer $TOKEN"

# 调班影响分析：受影响患者 + 消毒间隔压缩 + 已预领耗材
curl -s "http://localhost:8080/api/sessions/impact?sessionId=3&machineId=4&date=$(date +%F)&shift=AM" \
  -H "Authorization: Bearer $TOKEN"

# 治疗全流程：透前评估 -> 上机 -> 透中记录(低血压自动上报) -> 下机 -> 收费核对 -> 治疗单完成
curl -s -X POST http://localhost:8080/api/sessions/6/pre  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"weight":68.5,"bpSys":140,"bpDia":85,"heartRate":78,"accessCondition":"NORMAL","anticoagPlan":"低分子肝素 3500IU"}'
curl -s -X POST http://localhost:8080/api/sessions/6/start -H "Authorization: Bearer $TOKEN"
curl -s -X POST http://localhost:8080/api/sessions/6/intra -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"bpSys":85,"bpDia":50,"heartRate":95,"discomfort":"出冷汗"}'
curl -s -X POST http://localhost:8080/api/sessions/6/post -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"offWeight":66.8,"hemostasisOk":true,"hemostasisMin":10,"departureMode":"SELF"}'
curl -s -X POST http://localhost:8080/api/billing/1/confirm -H "Authorization: Bearer $TOKEN"

# 管理端统计：机位利用率 / 异常率 / 耗材损耗率 / 消毒合规率
curl -s http://localhost:8080/api/stats/overview -H "Authorization: Bearer $TOKEN"
```

已验证的关键行为：

- 未携带 Token 访问受保护接口返回 403
- 透中记录收缩压 < 90 自动产生「透析中低血压」异常
- 透中记录填写医嘱变更自动产生「医嘱调整」异常
- 下机后机器自动转「消毒中」，登记合格消毒记录后自动回「空闲」
- 下机自动生成待核对账单；收费确认后治疗单转「已完成」
- 患者请假后其预领耗材自动归还入库
- 感染标记变更自动上报异常并提示未来排班的分区不匹配
- 机器标记故障自动上报异常并列出当日受影响患者
- 耗材出库低于安全库存自动上报「耗材不足」异常

### 3. 机位故障改排全流程（curl 实际执行通过）

```bash
# 故障申报：M04 自检失败，自动筛出当天受影响患者（AM+PM 各 1 名）生成改排方案
curl -s -X POST http://localhost:8080/api/reschedule/plans -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"machineId":4,"reason":"班前自检失败：血泵异响","expectedRepairAt":"2026-10-05T14:00:00"}'

# 方案详情：受影响患者 + 备用机位建议（按感染分区过滤）+ 通知/接送/消毒/耗材/补偿串联视图
curl -s http://localhost:8080/api/reschedule/plans/2 -H "Authorization: Bearer $TOKEN"

# 护士长决策：延后 60 分钟（早于预计修复时间的延后被拒）；换机/改下午班自动归还并重新预领耗材
curl -s -X POST http://localhost:8080/api/reschedule/actions/3/decide -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"actionType":"DELAY","delayMinutes":60,"note":"等修复"}'
curl -s -X POST http://localhost:8080/api/reschedule/actions/4/decide -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"actionType":"CHANGE_MACHINE","targetMachineId":6}'

# 协商调换：候选列表标注双向分区匹配与修复时间冲突；双方确认人必填并留痕
curl -s http://localhost:8080/api/reschedule/actions/3/swap-candidates -H "Authorization: Bearer $TOKEN"
# 从候选中取一个 zoneOk=true 且无修复时间冲突的治疗单 ID 代入
curl -s -X POST http://localhost:8080/api/reschedule/actions/3/swap -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"otherSessionId":<候选治疗单ID>,"confirmByA":"患者本人","confirmByB":"对方家属","confirmNote":"护士站当面确认"}'

# 患者通知（含家属接送安排）与到院患者的等候补偿、交通调整登记
curl -s -X POST http://localhost:8080/api/reschedule/actions/3/notify -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"channel":"电话","note":"已告知延至 14:30","familyPickupNote":"家属 18:30 来接"}'
curl -s -X POST http://localhost:8080/api/reschedule/actions/4/arrival -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"compensationNote":"早餐券+交通补贴 20 元","transportAdjustNote":"班车返程改 13:30"}'

# 闭环：全部患者已改排且已通知后方案才可关闭
curl -s -X POST http://localhost:8080/api/reschedule/plans/2/close -H "Authorization: Bearer $TOKEN"
```

改排功能已验证的关键行为（实际执行 43 项断言全部通过）：

- 申报故障后机位自动置「故障」，当天未上机治疗单全部筛出为待决策动作
- 备用机位建议严格按患者感染标记匹配分区（普通/乙肝/丙肝/隔离）
- 延后上机时间早于预计修复时间会被拒绝；换机/改下午班自动归还原预领耗材并按新安排重新预领
- 改到下午班可不指定机位，自动分配下午班最早可用的同分区机位
- 协商调换校验双向分区匹配与对方落点晚于预计修复时间，双方确认人必填并写入双方治疗单异常记录
- 患者已到院的可由前台登记等候补偿与交通调整（决策前后均可登记）
- 方案关闭强制校验「全部已决策 + 全部已通知」，关闭后不可再操作
- 同一机位当天已有进行中方案时重复申报返回 409

### 4. 前端构建与联调

```bash
cd frontend && npm install && npm run build   # 构建通过，产出 dist/
npm run dev                                    # 开发服务器 5173，/api 代理验证通过
```

## 主要功能页面

| 页面 | 说明 |
|---|---|
| 工作台 | 今日治疗单/透析中/待处理异常/耗材预警总览，机位状态色块 |
| 排班看板 | 按日期+班次查看机位-患者安排；自动排班；调班（含影响分析预览）；迟到/请假登记 |
| 故障改排 | 机位自检失败申报→当天受影响患者筛查→延后/换机/改下午班/协商调换→通知、接送、消毒、耗材、补偿串联闭环 |
| 患者管理 | 档案、感染标记、风险等级、固定透析日、接送时间窗、熟悉护士 |
| 透析治疗 | 治疗单总览（异常时间线+本机消毒记录）、透前评估、透中记录、透后记录、耗材与费用 |
| 机器与消毒 | 机位状态变更（故障自动上报）、消毒/滤芯/水处理记录登记 |
| 耗材库存 | 库存与安全库存预警、出入库（使用/损耗/归还/入库）、流水查询 |
| 异常上报 | 全类型异常的查询、上报、处理闭环 |
| 收费核对 | 医保/自费耗材/补收费用核对，确认后治疗单完成 |
| 统计分析 | 机位利用率、异常类型分布、耗材损耗、消毒合规图表 |

## 项目结构

```
├── docker-compose.yml          # 一键启动：db + backend + frontend
├── backend/                    # Spring Boot 后端
│   ├── Dockerfile
│   ├── settings.xml            # Maven 阿里云镜像（加速依赖下载）
│   └── src/main/java/com/hd/center/
│       ├── config/             # 安全(JWT)、全局异常、数据种子
│       ├── entity/             # 14 个实体 + 枚举
│       ├── repository/         # Spring Data JPA
│       ├── service/            # 排班算法/故障改排/耗材/计费/统计/异常
│       └── controller/         # REST API
└── frontend/                   # Vue 3 前端
    ├── Dockerfile
    ├── nginx.conf              # SPA 路由回退 + /api 反代
    └── src/
        ├── api/                # axios 封装
        ├── layout/             # 主布局（按角色过滤菜单）
        ├── views/              # 10 个业务页面
        └── utils/labels.js     # 枚举中文标签
```

## 核心设计说明

**自动排班**（`POST /api/sessions/generate`）：筛选当日透析规律匹配的患者 → 按「风险等级 → 班车接送 → 病历号」排序 → 按感染标记匹配机位分区（普通/乙肝/丙肝/隔离）→ 逐机位计算下一可用上机时刻（上一台结束 + 消毒间隔）→ 生成治疗单并自动预领标准耗材套件。

**调班影响分析**（`GET /api/sessions/impact`）：执行调班前返回——目标机位当日受影响患者、插入后相邻治疗间隔小于消毒缓冲的压缩项、涉及治疗单已预领的耗材、分区不匹配等警告；确认后 `POST /api/sessions/{id}/move` 执行。

**治疗单详情**（`GET /api/sessions/{id}/detail`）：一个接口聚合患者档案、透前评估、透中记录、透后记录、异常事件、耗材流水、账单、本机位当日消毒记录，即"一条治疗记录"的全貌。

**机位故障改排**（`/api/reschedule/**`）：

1. **申报筛查**（`POST /api/reschedule/plans`）：机位自检失败/故障申报后，机位置为故障，平台筛出当天该机位全部未上机治疗单生成改排方案（每患者一条待决策动作），同时下达"修复后须热消毒合格"的工程任务；
2. **方案决策**（`POST /api/reschedule/actions/{id}/decide`）：护士长对每名患者选择——延后（留原机位等修复，上机时间不得早于预计修复时间）、换机（备用机位建议按感染分区过滤并给出最早可上机时刻）、改到下午班（可自动分配最早可用机位）；换机/改班自动归还原预领耗材并按新安排重新预领；
3. **协商调换**（`POST /api/reschedule/actions/{id}/swap`）：患者不接受延后时，可与当天其他未上机患者互换机位/时段；校验双向分区匹配与对方落点晚于预计修复时间，双方确认人必填并写入双方治疗单的异常记录，杜绝口头换班争议；
4. **串联闭环**（`GET /api/reschedule/plans/{id}`）：方案详情把每条动作的**患者通知（渠道/家属接送）、机位消毒（当前机位状态+当日消毒记录）、耗材领用流水、到院患者的等候补偿与交通调整（前台登记）**重新串成一屏；全部患者已决策且已通知后方案方可闭环（`POST /api/reschedule/plans/{id}/close`）。
