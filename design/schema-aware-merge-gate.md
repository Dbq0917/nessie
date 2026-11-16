# 提交期 Schema 语义冲突守门（Schema-Aware Merge Gate）设计

## 1. 背景与问题复述

两个团队（清洗 / 特征）各开分支，共享十几张 Iceberg 表。现有合并流程只做
**路径（ContentKey）级冲突检测**，schema 变更与数据写入在路径上互不冲突，于是合并被直接
放行，产生字段错位的数据，且读取时不报错，只能靠事后对账发现，回滚成本半天。

关键复现条件：错位只发生在 **B 分支“先读后写回”** 的场景，纯读、纯写都不复现。

## 2. 根因分析

### 2.1 Nessie 的合并语义天然到不了 schema 层

- 每张表在 Nessie 里是一个 `ContentKey` 下的内容指针，Iceberg 表对应的内容对象是
  `IcebergTable`（携带 `metadataLocation / snapshotId / schemaId / specId / sortOrderId`）。
- 合并实现（`BaseMergeTransplantSquash` / `MergeBehaviors` / `MergeBase`）只对
  ContentKey 做三路（base / source / target）比对：
  - `MergeBehavior.NORMAL`：同 key 两侧都改 → key 冲突；
  - `MergeBehavior.FORCE`：**完全不做冲突检测，无条件覆盖**（`MergeBehavior.java:21`）；
  - `MergeBehavior.DROP`：丢弃该 key。
- 合并期唯一的服务端校验钩子 `CommitValidator`
  （`VersionStore.CommitValidator`，调用点 `BaseCommitHelper#validateMergeTransplantCommit`）
  收到的 `CommitValidation` 只有 `IdentifiedContentKey + CREATE/UPDATE/DELETE`，
  **没有 schema 信息**，因此现有 SPI 无法判断 schema 新旧。
- schema 变更走独立审批流，但在 Nessie 看来就是一次普通 commit，审批状态不会出现在
  合并判定里。

结论：Nessie 现有的是“指针有没有冲突”的判断，缺的是“指针指向的 schema 是否兼容”的判断。

### 2.2 错位为什么只在“读后写回”出现，且静默

典型时序（表 T，初始 schema v1）：

1. B 分支在 `hB0` 读取 T，作业/会话缓存了 v1（字段按位置/字段 id 映射进内存）。
2. main 上 schema 变更经审批后合并：T 升级为 v2（典型：加列、调列序、rename、改类型）。
3. B 分支继续用缓存的 v1 写出数据文件，并把“基于 v1 的新 metadata 指针”提交到自己的分支。
4. B 合并回 main：若用 FORCE 或自定义只看路径的检测，指针被直接接受。
5. 后续按 v2 读这批数据文件：Parquet/Arrow 按位置或字段 id 投影，类型还兼容时
   **不抛异常，只产生错位**；这正是“没有任何报错”的来源。

纯读不产生 commit，没有东西可合并；纯写（新会话、写前重新解析 schema）会基于当前
metadata 生成文件，不存在陈旧 schema 窗口。所以问题等价于：

> 合并时，source 分支对该表的写入是否基于一个 **落后于 target、且与 target schema
> 不兼容** 的 schema。

## 3. 设计目标与非目标

目标：

1. 在**合并/提交入口**拦截陈旧 schema 的读后写回，给出明确、可操作的冲突信息，
   替代人工 review 和事后对账。
2. 正常并行提交不被拦：schema 没变、或变了但兼容、或 B 已 rebase 到新 schema，全部自动放行。
3. 只对真正高风险的 key 做深度检查，十几张共享表、每天几十次提交的量级下保持毫秒~
   几十毫秒级开销。
4. schema 审批流仍然是独立系统，Nessie 不改造审批本身，只在合并时**消费审批结论**。
5. 不改数据面（不重写文件、不改 Iceberg 规范），只在控制面加判定。

非目标：

- 不解决业务语义层面的冲突（两个团队对同一指标的口径分歧），只解决 schema/物理映射错位。
- 不替代 Nessie 已有的 key 级乐观并发控制，而是在其之前/之外补一层语义判定。

## 4. 总体架构

三层防线，合并门是核心，其余两层做纵深防御：

```
团队分支 (清洗/特征)
   |  ① 写入侧：写前重新解析 schema + 提交带 schema 指纹
   v
合并入口（CI/合并服务 / 反向代理 / 服务端 SPI，三选一落地）
   |  ② Schema-Aware Merge Gate（本设计核心）
   |     三路对比 base->target / base->source 的 IcebergTable 指针
   |     只拦：target schema 演进 ^ source 写回基于旧 schema ^ 不兼容
   v
Nessie Server（NORMAL 合并，dry-run 预检 + 正式提交）
   |  ③ Events(MergeEvent/ContentStoredEvent) -> 对账与审计旁路
   v
main
```

## 5. 合并门判定规则（核心）

### 5.1 输入：一次 merge 的三方视图

对 merge 请求（fromRef -> toBranch），通过标准客户端 API 构造：

- `base`：merge-base（Nessie 合并语义中的共同祖先；服务端 `MergeBase.identifyMergeBase`，
  事件侧 `MergeEvent#getCommonAncestorHash`）。
- `target`：目标分支 HEAD 的内容。
- `source`：源分支 HEAD 的内容。

用 `getDiff().fromRef(base).toRef(...)` 分别取 base->source、base->target 的
`DiffResponse.DiffEntry`，只关注受守门清单管辖的共享表 key（可配，十几张）。

### 5.2 判定矩阵

对每个共享表 key，取三方的 `IcebergTable`（无 schemaId 的内容类型跳过本规则）：

| target 相对 base | source 相对 base | source 写入基于的 schema | 判定 |
|---|---|---|---|
| 未变 | 任意 | - | 放行（无 schema 漂移） |
| 有演进 | 未变（纯读/无写） | - | 放行（无写回，天然安全） |
| 有演进 | 有写入 | `schemaId == target.schemaId`（已 rebase/新会话写） | 放行 |
| 有演进 | 有写入 | `schemaId` 落后，且 schema 变更兼容（见 5.3） | 放行，记审计事件 |
| 有演进 | 有写入 | `schemaId` 落后，且不兼容 | **拦截**，返回结构化冲突 |

“source 有写入”的判定：base->source diff 中该 key 存在 `Put`（指针变化），且
`snapshotId` 相对 base 发生变化。schema 升级本身也会改指针，用 snapshotId +
commit 操作类型区分“数据写回”与“仅 schema 变更”。

### 5.3 兼容性判定（只需要 metadata，不碰数据文件）

从 `IcebergTable.metadataLocation` 取 Iceberg table metadata，比较
`base.schemaId / source.schemaId / target.schemaId` 三个 schema：

- 兼容（放行）：target 相对 source 基线仅为**加列（含嵌套加 optional 字段）、
  列 id 稳定的 rename、加宽类型（int->long、float->double、decimal 扩精度）**，
  且 source 写出的文件 schema 中每个字段 id 都能在 target 找到同 id 且类型兼容。
- 不兼容（拦截）：删列/字段 id 复用、列序调整伴随按位置写入、收窄类型、
  类型族变更（string<->int 等）、partition spec 变更导致写入口径变化。

字段 id 稳定时 Iceberg 读取本身能处理加列；真正错位来自 id 被复用/重排后按位置写入。
判定以 **field-id 集合 + 类型兼容矩阵** 为准，不依赖列名顺序。

### 5.4 拦截返回与自动修复提示

拦截时返回 `NessieConflictException`（HTTP 409），冲突体复用现有
`Conflict(ConflictType.KEY_CONFLICT, key, message)` 模型，message 含：

- 表 key、三方 hash、三方 `schemaId`；
- 不兼容的具体字段（id、类型、动作）；
- 修复动作：`rebase 分支到 target HEAD -> 用新 schema 重放/重写受影响快照 -> 重新合并`。

合并服务可进一步提供一键 rebase 流水线（merge main into branch，引擎按新 schema
重写受影响分区），把“半天回滚”变成一次自动化作业。

### 5.5 审批流怎么接

- schema 审批系统在审批通过、提交 schema 变更时，在 `CommitMeta.properties` 写入
  约定标记，如 `schema-change.approved=<ticket-id>`（`CommitMeta` 原生支持任意 properties）。
- 守门只信任一个事实：**target 上的当前 schema 本身**。审批流负责保证进 main 的
  schema 变更合法；守门负责保证任何分支不会用旧 schema 的写回去覆盖它。
- 可选硬规则：守门发现 target 的 schema 演进 commit 缺少审批标记时，按配置
  `audit-only` 或 `block`（默认 audit-only，避免与既有审批系统的故障耦合）。

## 6. 落地形态（三种，按改造成本排序）

### 6.1 合并服务 / CI 守门（推荐先落地，零服务端改动）

独立服务或 CI job，所有合并请求先调它。全部使用公开客户端 API
（`NessieApiV2`：`getDiff`、`getContent`、`getCommitLog`、
`mergeRefIntoBranch().dryRun(true)`）：

1. 拉三方 diff，按第 5 节判定；
2. 通过后先 `dryRun(true)` 让 Nessie 自己做 key 级冲突检测；
3. 两者都过，再执行正式 merge（`defaultMergeBehavior=NORMAL`，禁用 FORCE）。

优点：不改 Nessie 服务端、可独立发布、可先 shadow 模式跑准确率；
缺点：需要保证所有合并入口都经过它（配合 6.2 收口）。

### 6.2 反向代理 / 网关强制收口

在 Nessie REST 前的网关拦截 `POST .../references/branch/{branch}/merge`，
未携带守门签名头的请求直接 403。解决“有人绕过 CI 直连服务端 FORCE 合并”的问题。
该层只做入口收口，语义判定仍在 6.1 的服务里。

### 6.3 服务端 SPI 化（长期方案）

现有 `CommitValidator` 拿不到内容值，无法直接承载本逻辑。长期可在
`BaseMergeTransplantSquash#squash` 的 `validateMergeTransplantCommit` 旁新增一个
可插拔的 `MergeSemanticValidator` 接口，入参包含三方 `Content`（IcebergTable），
以 Quarkus CDI 注入实现，与 `events` 模块同级。此方案需要改 `versioned-spi`，
建议在 6.1 验证规则准确率后再做。

## 7. 为什么不会卡住正常并行提交

- **快速路径**：target 相对 base 没动 schema -> 只做一次 diff 比较即放行，
  无 metadata 文件 IO，无额外 RPC 写。
- **范围收敛**：只有守门清单内的 key 参与判定，其他表零成本；
  source 纯读（无 Put/无 snapshot 变化）直接放行。
- **兼容即放行**：加列等安全演进不拦截，只记事件；真正被拦的必然是
  “旧 schema 写回 + 不兼容”，这类合并即使放过也会产出脏数据，拦截即止损。
- **修复自动化**：被拦分支 rebase 后自动重入合并，不需要人工 review 队列。
- 合并本身仍走 Nessie 的乐观并发与重试（`CommitRetry`），守门逻辑无状态、可水平扩展。

## 8. 写入侧与审计侧的配套

写入侧（作业/会话规范，防患于未然）：

- 作业写前重新解析表 metadata，禁止复用长生命周期缓存的 schema；
- 优先使用按 field-id 解析的 Iceberg writer，避免按位置拼装行；
- 提交时在 `CommitMeta.properties` 记录写入基线 `write-base-schema-id=<id>`，
  守门可直接读取，省一次 metadata 拉取。

审计侧（替代事后翻历史）：

- 订阅 `MergeEvent`（含 sourceHash / commonAncestorHash / 前后 hash）与
  `ContentStoredEvent`，落库每张共享表的 schema 血缘（branch, commit, key, schemaId）。
- 守门每次判定（放行/拦截/兼容类型）发结构化事件，冲突复现从“翻提交历史”变成
  按 key + 时间窗查血缘。
- 周期性对账只需要核对 main 上每个表的当前 schemaId 与近期合并事件中的
  write-base-schema-id 是否存在未豁免的落后项。

## 9. 分阶段落地计划

1. **Shadow（1~2 周）**：守门以只读模式挂在合并入口，只记录判定不拦截，
   与事后对账结果比对，校准兼容矩阵（5.3）的误报/漏报。
2. **强制拦截 + 自动 rebase**：对确认不兼容的场景启用 409，合并服务提供
   rebase 重放入口；FORCE 行为从合并流水线移除。
3. **入口收口**：上线 6.2 网关规则，堵直连绕过。
4. **审批联动**：接入 `schema-change.approved` 标记的 audit/block 策略。
5. **SPI 内化（可选）**：规则稳定后按 6.3 下沉服务端。

## 10. 安全影响说明

本设计是合并期的数据完整性控制，不改变认证、授权、匿名路径、凭证发放、
TLS/部署默认值等安全契约：

- 守门服务复用调用者身份访问 Nessie，不新增常驻数据凭据；
- 6.2 网关只做入口收敛，权限模型不变；
- 不修改持久化后端假设与 GC/管理工具行为。

因此无需更新 `SECURITY_THREAT_MODEL.md` 的既有安全边界；若后续采用 6.3
服务端 SPI 方案并引入新的服务端扩展信任域，需在该变更中补充威胁模型条目。

## 11. 已知局限

- 守门只识别 Nessie 内容对象带 schema 指纹的表类型（Iceberg 表/视图）；
  自定义内容类型需提供适配器。
- 兼容性判定基于 Iceberg metadata；若引擎本身违反 Iceberg 规范写出
  field-id 错误的文件，守门能发现 schema 落后但无法替代文件级校验。
- 跨表的业务语义一致性不在本设计范围内。
