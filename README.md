# 碳排放披露数据血缘与版本影响管理平台

面向制造集团的碳排放披露管理平台：按工厂、能源类型、产品线汇总范围一/二/三排放，
对每一项 tCO2e 指标回答"用了哪份表计数据、哪版排放因子、为什么没随最新资料改变"。

- **来源批次**：工厂上传燃料/用电/采购活动数据，审核员核验；采购批次按来源行+供应商凭证检测冲突，已核算采购量不可被后续上传覆盖
- **凭证数量裁定**：接收端保存采购发票/过磅单/环境声明的文件哈希、抽取字段、单位与订单/交货/退货关系；换算到共同单位后按订单-收货-退货-批次守恒约束枚举全部可行数量组合，给出唯一证据链与每个落选候选的排除理由；密度/包装规格缺失维持待补，供应商重发文件一律新建版本并使旧裁定失效；运行启动冻结采用量+理由+证据版本，未裁定指标待证并阻断披露
- **因子目录**：按地域、适用期维护排放因子版本，审批生效；平台只使用地域与期间匹配的批准因子，缺因子时指标列为**待核**，不用集团平均数兜底
- **核算运行**：启动时快照因子清单（运行中替换因子不影响本次运行），活动量×因子生成带血缘的排放结果
- **披露模块**：运行结果冻结为报告版本；产品线合计与工厂明细不勾稽时报告保持**待核**，差额不能归零；已发布版本内容不可改写
- **影响服务**：来源补录/修订/缺失、因子修订时自动重算并对比受影响指标与已发布报告，生成重述建议与差异说明，经审批后以新版本重述，披露历史完整保留

## 技术栈与架构

```
┌────────────┐   HTTP    ┌─────────────────────────┐      ┌──────────────┐
│ Vue3 前端   │ ───────▶ │ SpringBoot 后端 (Java)   │ ───▶ │ PostgreSQL   │
│ 血缘下钻/   │  /api/*  │ 批次校验·运行编排·版本状态 │      │ 来源/因子/运行 │
│ 影响对比/   │          │ 影响评估·披露审批          │      │ 快照/披露证据 │
│ 披露审批    │          └─────────────────────────┘      └──────────────┘
└────────────┘
```

- 前端：Vue 3 + Vite + Element Plus + Vue Router（hash 模式）
- 后端：Spring Boot 3.5（Web / Data JPA / Validation），HMAC 令牌认证，角色权限（管理员/分析员/审核员/财务披露）
- 数据库：PostgreSQL（表结构见 `backend/src/main/resources/schema.sql`）

## 一、Docker Compose 一键启动（推荐）

```bash
docker compose up --build
```

- 前端：http://localhost （nginx，反向代理 /api 到后端）
- 后端：http://localhost:8080/api/health
- 数据库：PostgreSQL 18（compose 内部 `postgres:5432`，库/用户/密码均为 `ccdev`）

首次启动时后端自动建表并写入样例数据（工厂、表计/燃料/采购批次、因子、2025-05 已发布报告）。

## 二、本地开发启动

前置：本机 PostgreSQL 可用（`127.0.0.1:5432`，库/用户/密码 `ccdev`）。

```bash
# 1. 后端（端口 8080；启动时自动建表+写入种子数据）
cd backend
mvn package -DskipTests
java -jar target/carbon-platform.jar

# 2. 前端（二选一）
#  a) 开发模式：vite 开发服务器，端口 5173，代理 /api 到 8080
cd frontend && npm install && npm run dev        # 打开 http://localhost:5173
#  b) 一体化模式：构建后由后端直接托管
cd frontend && npm install && npm run build      # 然后打开 http://localhost:8080
```

> 后端以 `file:./frontend/dist/` 与 `file:../frontend/dist/` 作为可选静态资源目录，
> 在仓库根目录或 backend/ 目录启动 jar 均可托管已构建的前端。

## 三、测试账号

| 账号 | 密码 | 角色 | 主要权限 |
| --- | --- | --- | --- |
| `admin` | `admin123` | 管理员 | 全部操作 |
| `analyst` | `analyst123` | 分析员 | 上传/修订批次、新建因子版本、启动运行、创建报告 |
| `auditor` | `auditor123` | 审核员 | 核验/驳回/作废批次、批准因子版本 |
| `finance` | `finance123` | 财务披露 | 审批/发布报告、接受/驳回重述建议 |

越权操作会被拒绝（403），例如分析员不能核验批次。

## 四、验证方式

### 4.1 自动化验收回放（已实际执行，S0~S5 60 项 + S6~S9 44 项全部通过）

```bash
# 前置：后端已启动且数据库为初始种子状态
python3 scripts/verify.py            # S0~S5 基线回放，默认 http://127.0.0.1:8080
python3 scripts/verify_evidence.py   # S6~S9 凭证数量裁定专项回放
# 如需重置到种子状态后重跑：
bash scripts/reset-db.sh             # 清空数据库
# 重启后端（自动重建表+种子数据，含采购三证与裁定），再依次执行两个回放
```

回放场景（对应验收要求）：

| 场景 | 内容 | 关键断言 |
| --- | --- | --- |
| S0 | 种子基线 | 2025-05 已发布报告总量 2503.18 tCO2e（范围一/二/三 = 70.92/917.26/1515）；运行绑定 6 个因子快照；血缘可下钻到批次行+因子版本 |
| S1 | 来源补录 | 补录柴油 1500L 核验后自动生成影响评估（苏州/精密结构件/SCOPE1 +4.02）与重述建议；接受→冻结 v2→审批→发布；v1 标记"已重述"，历史保留 |
| S2 | 因子修订 | 运行启动后批准新版电力因子（0.5566→0.5420）：本次运行仍用 v1（绑定快照），新运行用 v2；自动生成对 2025-05 已发布报告的影响评估（苏州 SCOPE2 合计 -16.06）与重述 |
| S3 | 并发采购更正 | 修订试图把已核算凭证 V-1001 采购量 500→520：核验被拒（409）并标记冲突，原数量未被覆盖；合规修订（不动已消耗行+新增凭证行）通过；两工厂同时修订同一批次，第二份被拒（409） |
| S4 | 勾稽不平 | 含未分摊公共用电（54.2 tCO2e）与缺因子待核指标的报告保持待核（RECONCILE_FAILED），审批/发布均被拒（409）；修复分摊+批准因子后重算、冻结、审批、发布 |
| S5 | 已发布报告发现来源缺失 | 作废已消耗的 2025-05 燃料批次：自动生成影响评估（苏州 SCOPE1 -70.92）与重述建议；已发布 v3 内容不被覆盖；接受重述→v4 发布，v1~v3 均为"已重述"，披露历史完整 |
| S6 | 三证冲突 | 发票 100t vs 过磅单 96t 同交货号互斥：枚举可行集合 {96,100} 维持待裁定，不挑"最完整"单据；可行集合外的 99t（平均数）被拒；待裁定运行 PENDING_EVIDENCE、报告审批/发布 409；人工裁定 96t 后发票带互斥组与落选理由、过磅单采用、哈希冻结 |
| S7 | 部分交货与退货守恒 | 订单 100t：首批 60t 唯一解自动裁定；第二批 40t 累计 100t 自动重裁且旧裁定 INVALID；挂钩交货的退货 10t 产生 {100,90} 组合，无对应收货的退货被守恒约束排除；人工裁定净量 90t |
| S8 | 单位无法换算 | m3 缺密度、PCS 缺包装规格 → 维持待补（不用金额/平均数）；补录规则库默认密度 7.85 后 30m3 自动裁定 235.5t；带包装规格重发文件 200 件×25kg → 5t，旧版本 SUPERSEDED 且哈希变化 |
| S9 | 重发环境声明 | 重发文件新建 v2（哈希变化、旧版 SUPERSEDED），引用旧版的裁定 INVALID 并生成待裁定；旧运行快照仍冻结 v1 哈希与 50t；重裁前新运行待证且发布被拒；人工裁定 48t 后新运行采用 48t 并可发布 |

### 4.2 页面手动验证

登录 http://localhost:8080 （或 5173）后：

1. **仪表盘**：最新发布披露总量、范围一/二/三、待处理影响评估数、待核报告/指标数、分工厂排放
2. **核算运行 → 详情 → 血缘**：任一排放结果可下钻到来源批次行（含供应商凭证、核验人）与因子版本（值、适用期、批准人）
3. **影响评估 → 详情**：受影响指标旧值/新值/差异对比、受影响报告链接、重述建议处置
4. **披露报告 → 详情**：版本历史（已发布/已重述/被取代）、披露条目、勾稽核对表、重述记录
5. **来源批次**：上传/核验/修订/作废；修订已消耗采购批次时可见 409 冲突提示
6. **因子目录**：新增版本→批准（替换旧版）；使用追踪展示绑定该版本的运行
7. **凭证裁定**：凭证台账（单据版本、SHA-256 哈希、签章、权威性、订单/交货/退货关系）；登记同号重发文件观察版本递增与旧版 SUPERSEDED；裁定工作台查看可行组合、互斥组、排除理由并人工裁定；单位换算规则维护（密度/包装规格缺失→待补）
8. **核算运行 → 详情**：除因子快照外可查看采购数量裁定快照（采用量+理由+冻结证据链）；血缘下钻采购行可继续打开裁定证据链

## 五、关键业务规则实现

- **因子绑定快照**：运行创建时把当时已批准且覆盖该期间的因子版本写入 `run_factor_bindings`，核算只读快照；因子在运行中被替换只影响之后的新运行（部分唯一索引保证同一期间同时只有一个进行中的正式运行）
- **待核而非平均数**：因子匹配严格按 工厂地域+能源类型+适用期间；匹配不到批准因子时结果标记 `PENDING_FACTOR`（排放量留空），报告勾稽状态为 `PENDING_FACTOR`，审批/发布被拒
- **采购量保护**：修订批次核验时，原批次中已被核算消耗（存在血缘记录）的采购行必须按凭证号保持数量不变，否则整单拒绝并标记冲突；同一批次同时只允许一个进行中的修订
- **勾稽**：冻结时按"工厂明细合计 vs 产品线合计"核对，未分摊差异记入 `reconciliations`；`MISMATCH`/`PENDING_FACTOR` 状态下审批与发布均被拒，无"差额归零"入口
- **不可改写与重述**：已发布版本内容不更新；来源缺失/因子修订触发影响评估（候选运行 vs 已发布版本逐指标对比），重述建议被接受后冻结新版本，发布时旧版本标记 `RESTATED`，版本链完整可查
- **数量裁定而非挑单据**：采购活动量只来自守恒约束（净收货=Σ交货事件取值−Σ退货，且不超过订单量）枚举的可行组合，不按金额或平均数填；发票/过磅单/声明换算到共同单位后按交货事件聚类，同事件互斥取值不可同选，双证印证事件强制纳入、单证事件允许按部分交货排除；同交货号只计一次防重复入账，无对应收货的退货被守恒约束排除；组合超 256 个按权威性→签章→时点规则剪枝并标记
- **待补与版本失效**：密度/包装规格在凭证抽取值与规则库默认值均缺失时凭证 `UNCONVERTIBLE`，裁定保持 PENDING；供应商重发文件必须新建版本（同号 version_no 递增、旧版 SUPERSEDED、部分唯一索引保证同时只有一个生效版本），引用旧版的裁定置 INVALID 并自动重裁；运行快照中的证据链 JSON 独立保留，旧运行血缘不受影响

## 六、API 概览（/api，除登录外均需 `X-Auth-Token`）

| 模块 | 端点 |
| --- | --- |
| 认证 | `POST /auth/login`、`GET /auth/me`、`GET /health` |
| 元数据 | `GET /meta/plants`、`GET /meta/product-lines`、`GET /meta/energy-types` |
| 批次 | `GET/POST /batches`、`GET /batches/{id}`、`POST /batches/{id}/verify|reject|revise|void` |
| 凭证裁定 | `GET/POST /evidence/documents`、`POST /evidence/documents/{id}/void`、`GET/POST /evidence/conversions`、`GET /evidence/adjudications`、`GET /evidence/adjudications/{id}`、`POST /evidence/adjudications/{id}/decide`、`POST /evidence/adjudications/re-run`、`GET /evidence/runs/{runId}/bindings`、`GET /evidence/pending` |
| 因子 | `GET/POST /factors`、`POST /factors/{id}/versions`、`POST /factors/versions/{vid}/approve|retire`、`GET /factors/versions/{vid}/usage` |
| 运行 | `GET/POST /runs`、`GET /runs/{id}`、`GET /results/{rid}/lineage` |
| 披露 | `GET/POST /reports`、`GET /reports/{id}`、`POST /reports/{id}/freeze|approve|publish`、`GET /reports/versions/{vid}` |
| 影响 | `GET /impacts`、`GET /impacts/{id}`、`POST /impacts/restatements/{id}/accept|reject` |
| 仪表盘 | `GET /dashboard/summary` |

凭证裁定接口权限：凭证登记与换算规则维护为分析员/管理员，凭证作废、人工裁定与手动重裁为审核员/管理员。

## 七、目录结构

```
├── backend/                 # SpringBoot 后端
│   ├── Dockerfile
│   ├── src/main/resources/schema.sql       # 表结构（幂等，含凭证/裁定/运行快照表与迁移块）
│   └── src/main/java/com/group/carbon/     # config/security/model/repo/service/controller
│       └── service/EvidenceAdjudicationEngine.java  # 换算+守恒约束+可行组合枚举（纯逻辑）
├── frontend/                # Vue3 前端
│   ├── Dockerfile  nginx.conf
│   └── src/views/           # 仪表盘/来源/凭证裁定/因子/运行/影响/披露页面
├── scripts/
│   ├── verify.py            # 验收回放（S0~S5，60 项断言）
│   ├── verify_evidence.py   # 凭证裁定专项回放（S6~S9，44 项断言）
│   ├── reset-db.sh          # 重置本地数据库
│   └── restart-local.sh     # 本地重建+重置+启动后端辅助脚本
└── docker-compose.yml       # 一键启动（postgres + backend + frontend）
```

## 八、验证记录（实际执行过的验证）

以下验证均在本开发容器内实际执行通过：

- `mvn package -DskipTests`：后端编译打包通过（Spring Boot 3.5.7，Java 25 运行时，编译目标 21）
- `npm install && npm run build`：前端构建通过（vite 6，产物 dist/）
- 后端启动后自动建表+种子数据：`/api/health` → UP；种子报告总量 2503.18 tCO2e 与手工核算一致；4 个采购凭证号三证裁定均为唯一解自动裁定
- `python3 scripts/verify.py`：**60 项断言全部通过**（S0~S5 全场景回放）
- `python3 scripts/verify_evidence.py`：**44 项断言全部通过**（S6 三证冲突 / S7 部分交货与退货守恒 / S8 单位无法换算 / S9 重发声明）
- 后端托管前端：`GET /` 与 `GET /assets/*.js` 均 200
- 两个回放均为一次性脚本（依赖初始种子状态），重复执行前需 `bash scripts/reset-db.sh` 并重启后端
- Docker Compose 配置：本容器无 docker CLI，`docker compose up` 未实际执行；Dockerfile/compose 已经静态核验（镜像标签、构建上下文、健康检查、依赖顺序、nginx 代理路径），请在有 Docker 的环境执行 `docker compose up --build` 验证
