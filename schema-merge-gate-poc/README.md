# Nessie Schema-Aware Merge Gate (POC)

合并期 schema 语义冲突守门的最小可运行原型，配合
[`../design/schema-aware-merge-gate.md`](../design/schema-aware-merge-gate.md) 阅读。

## 模块结构

| 类 | 职责 |
|---|---|
| `SemanticMergeGate` | 纯判定引擎：三方指纹 + 判定矩阵，无 Nessie 依赖 |
| `SchemaCompatibilityChecker` / `RuleBasedCompatibilityChecker` | 基于 Iceberg field-id 的兼容性规则（加列/加宽兼容，删列/id 复用/收窄不兼容） |
| `SchemaLoader` | 从 metadata location 加载历史 schema 的适配器接口 |
| `nessie/NessieMergeGate` | Nessie 客户端入口：dry-run merge 取 merge-base 与 key 级冲突 -> diff 取三方指针 -> 语义判定 -> NORMAL 正式合并 |
| `nessie/SchemaConflictException` | 语义冲突异常，服务层映射为 HTTP 409 |
| `GateConfig` | 守门清单、SHADOW/ENFORCING 模式、审批属性名等配置 |

## 运行测试

```bash
# Maven（本仓库验证过的方式）
JAVA_HOME=/path/to/jdk-17 mvn -s settings-central.xml test

# Gradle（目标环境有 Gradle 时）
gradle test -PnessieVersion=0.110.0 -PicebergVersion=1.12.0
```

## 与设计文档的对应关系

- 判定矩阵（设计 5.2）：`SemanticMergeGate#evaluate`
- 兼容规则（设计 5.3）：`RuleBasedCompatibilityChecker`
- 落地形态 6.1（零服务端改动的合并服务）：`NessieMergeGate`
- SHADOW 先行（设计第 9 节阶段 1）：`GateConfig.mode()`

## 尚未包含的部分（按设计文档推进）

- `SchemaLoader` 的真实 Iceberg `FileIO` 实现（POC 中以接口 + 测试替身代替）
- 网关签名头收口（设计 6.2）与事件订阅（设计第 8 节）
- 一键 rebase 重放流水线（设计 5.4）
