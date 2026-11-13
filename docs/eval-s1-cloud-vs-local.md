# 云端 vs 端侧 S1 判定对比（ROADMAP M4 任务 6）

> 生成时间：2026-11-13 14:45:55
> 样本：20 条人工标注合成会话（非真机采集）
> 云端端点：Qwen3.8-Flash-Next-Q4_0-ROCmFP4-STRIX_LEAN-00001-of-00003.gguf @ http://127.0.0.1:8080/v1
> 端侧端点：qwen25-0.5b-q4km.gguf @ http://127.0.0.1:8082/v1
> 判定口径：need_action = prob ≥ 0.5；escalate = prob ≥ 0.5 或 importance ≥ 6 或 confidence < 0.5
> 时间预算：单次上限 120s · 整轮预算 25min

## 一、单侧准确率（相对人工标注）

| 指标 | 云端 S1 | 端侧 S1 | 两侧差值 |
|---|---|---|---|
| 样本数 | 20 | 20 | - |
| need_action 判对 | 75.0% (15) | 40.0% (8) | -35.0% |
| importance 判对（±1.5） | 65.0% (13) | 30.0% (6) | -35.0% |
| due_window 判对 | 85.0% (17) | 40.0% (8) | -45.0% |
| topic 判对 | 85.0% (17) | 30.0% (6) | -55.0% |
| 四项全对 | 65.0% (13) | 10.0% (2) | -55.0% |

## 二、两侧一致性（云端 vs 端侧，不需标注）

| 指标 | 数值 |
|---|---|
| 样本窗口数 | 20 |
| 判定完全一致 | 2 (10.0%) |
| need_action 一致 | 11 (55.0%) |
| 升级 S2 一致 | 11 (55.0%) |
| due_window 一致 | 8 (40.0%) |
| topic 一致 | 5 (25.0%) |
| 云端 need_action 命中 | 9 |
| 端侧 need_action 命中 | 0 |
| need_action_prob 平均绝对差 | 0.438 |
| importance 平均绝对差 | 3.00 |
| 端侧 confidence 均值 | 1.000 |

结论：不可替代（差异占多数）

> 生成时间：2026-11-13 14:45:55

## 三、逐样本明细

| 会话 | 标注 need_action | 云端 | 端侧 | 标注 importance | 云端 | 端侧 | 标注 due | 云端 | 端侧 | 标注 topic | 云端 | 端侧 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 老板 | 是 | 是 | 否 | 7.0 | 6.0 | 0.0 | tomorrow | tomorrow | none | work_task | work_task | notice |
| 朋友 | 否 | 否 | 否 | 1.0 | 0.0 | 0.0 | none | none | none | smalltalk | smalltalk | notice |
| 产品群 | 是 | 是 | 否 | 6.0 | 6.0 | 0.0 | today | today | none | work_task | work_task | notice |
| 张三 | 否 | 是 | 否 | 2.0 | 5.0 | 0.0 | none | tomorrow | none | work_task | work_task | notice |
| 表情包 | 否 | 否 | 否 | 0.0 | 0.0 | 0.0 | none | none | none | smalltalk | smalltalk | notice |
| 优惠通知 | 否 | 否 | 否 | 1.0 | 0.0 | 0.0 | none | none | none | notice | notice | notice |
| 客户 | 是 | 是 | 否 | 8.0 | 8.0 | 0.0 | today | today | none | risk | risk | notice |
| 李四 | 是 | 否 | 否 | 6.0 | 2.0 | 0.0 | this_week | this_week | none | schedule | notice | notice |
| 部门群 | 是 | 是 | 否 | 6.0 | 8.0 | 0.0 | today | today | none | work_task | work_task | notice |
| 妈妈 | 是 | 是 | 否 | 5.0 | 3.0 | 0.0 | today | today | none | notice | smalltalk | notice |
| 快递 | 是 | 否 | 否 | 4.0 | 1.0 | 0.0 | today | today | none | notice | notice | notice |
| 行政 | 是 | 是 | 否 | 5.0 | 6.0 | 0.0 | this_week | this_week | none | work_task | work_task | notice |
| 系统通知 | 否 | 否 | 否 | 0.0 | 0.0 | 0.0 | none | none | none | notice | notice | notice |
| 王五 | 否 | 否 | 否 | 1.0 | 0.0 | 0.0 | none | none | none | smalltalk | smalltalk | notice |
| HR | 是 | 是 | 否 | 6.0 | 6.0 | 0.0 | tomorrow | tomorrow | none | schedule | schedule | notice |
| 银行 | 是 | 否 | 否 | 7.0 | 2.0 | 0.0 | later | this_week | none | notice | notice | notice |
| 业主群 | 是 | 否 | 否 | 3.0 | 0.0 | 0.0 | today | none | none | notice | smalltalk | notice |
| 老同学 | 否 | 否 | 否 | 1.0 | 0.0 | 0.0 | none | none | none | smalltalk | smalltalk | notice |
| 赵六 | 否 | 否 | 否 | 2.0 | 1.0 | 0.0 | none | none | none | smalltalk | smalltalk | notice |
| 项目群 | 是 | 是 | 否 | 7.0 | 6.0 | 0.0 | this_week | this_week | none | work_task | work_task | notice |

## 五、原始输出（便于复核解析是否走形）

### 云端样例

```json
{"choices":[{"finish_reason":"stop","index":0,"message":{"role":"assistant","con
{"choices":[{"finish_reason":"stop","index":0,"message":{"role":"assistant","con
{"choices":[{"finish_reason":"stop","index":0,"message":{"role":"assistant","con
```

### 端侧样例

```json
{"choices":[{"finish_reason":"stop","index":0,"message":{"role":"assistant","con
{"choices":[{"finish_reason":"stop","index":0,"message":{"role":"assistant","con
{"choices":[{"finish_reason":"stop","index":0,"message":{"role":"assistant","con
```
