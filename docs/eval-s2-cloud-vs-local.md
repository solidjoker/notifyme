# 云端 vs 端侧 S2 摘要可用性对比（ROADMAP M4 遗留项）

> 生成时间：11-13 14:40:41
> 样本：20 条人工标注合成会话（非真机采集）
> 云端端点：Qwen3.8-Flash-Next-Q4_0-ROCmFP4-STRIX_LEAN-00001-of-00003.gguf @ http://127.0.0.1:8080/v1
> 端侧端点：qwen25-0.5b-q4km.gguf @ http://127.0.0.1:8082/v1（端侧替身：真机 S2 是 MiniCPM5-2B，本机未备，用 qwen2.5-0.5b 当下限）
> 时间预算：单次上限 120s · 整轮预算 25min
> 判据：已交付 · 非占位照抄 · 摘要非空且非原文兜底 · due_time 可解析且与 S1 同桶 · 建议动作合规 · 分派条目完整

## 一、综合可用性

| 指标 | 云端 S2 | 端侧 S2 | 两侧差值 |
|---|---|---|---|
| 样本数 | 20 | 20 | - |
| 已交付（HTTP + JSON） | 20/20（100.0%） | 20/20（100.0%） | 0.0% |
| 六项全过（可用） | 14/20（70.0%） | 3/20（15.0%） | -55.0% |
| 单次调用中位时延 | 7.0s | 0.8s | - |

## 二、分项判据通过率（分母 = 已交付条数）

| 判据 | 云端 S2 | 端侧 S2 | 两侧差值 |
|---|---|---|---|
| 已交付 | 100.0% (20) | 100.0% (20) | 0.0% |
| 非占位照抄 | 100.0% (20) | 100.0% (20) | 0.0% |
| 摘要非空且非原文兜底 | 95.0% (19) | 95.0% (19) | 0.0% |
| due_time 可解析且与 S1 同桶 | 75.0% (15) | 25.0% (5) | -50.0% |
| 建议动作合规 | 100.0% (20) | 80.0% (16) | -20.0% |
| 分派条目完整 | 100.0% (20) | 55.0% (11) | -45.0% |

## 三、逐样本状态

| 会话 | 云端 | 端侧 | 云端时延 | 端侧时延 |
|---|---|---|---|---|
| 老板 | ✓✓✓✓✓✓ | ✓✓✓✗✓✗ | 8.6s | 0.8s |
| 朋友 | ✓✓✓✓✓✓ | ✓✓✓✗✓✓ | 5.4s | 1.3s |
| 产品群 | ✓✓✓✗✓✓ | ✓✓✓✗✗✗ | 7.0s | 0.9s |
| 张三 | ✓✓✓✗✓✓ | ✓✓✓✓✗✓ | 7.0s | 0.9s |
| 表情包 | ✓✓✓✓✓✓ | ✓✓✓✗✓✓ | 5.5s | 0.6s |
| 优惠通知 | ✓✓✓✓✓✓ | ✓✓✓✗✓✓ | 5.4s | 1.0s |
| 客户 | ✓✓✓✗✓✓ | ✓✓✓✗✓✗ | 8.0s | 0.6s |
| 李四 | ✓✓✓✓✓✓ | ✓✓✓✗✓✗ | 7.8s | 0.7s |
| 部门群 | ✓✓✓✓✓✓ | ✓✓✓✗✓✗ | 7.3s | 0.7s |
| 妈妈 | ✓✓✓✗✓✓ | ✓✓✓✓✓✓ | 6.6s | 1.0s |
| 快递 | ✓✓✓✗✓✓ | ✓✓✓✓✓✓ | 6.4s | 1.1s |
| 行政 | ✓✓✓✓✓✓ | ✓✓✗✗✗✗ | 6.8s | 0.5s |
| 系统通知 | ✓✓✓✓✓✓ | ✓✓✓✗✓✓ | 4.8s | 0.5s |
| 王五 | ✓✓✓✓✓✓ | ✓✓✓✗✓✓ | 5.3s | 0.6s |
| HR | ✓✓✓✓✓✓ | ✓✓✓✓✓✗ | 7.3s | 0.8s |
| 银行 | ✓✓✓✓✓✓ | ✓✓✓✓✓✓ | 9.7s | 1.2s |
| 业主群 | ✓✓✗✓✓✓ | ✓✓✓✗✓✗ | 7.8s | 1.0s |
| 老同学 | ✓✓✓✓✓✓ | ✓✓✓✗✓✓ | 5.5s | 1.0s |
| 赵六 | ✓✓✓✓✓✓ | ✓✓✓✗✓✓ | 5.6s | 0.8s |
| 项目群 | ✓✓✓✓✓✓ | ✓✓✓✗✗✗ | 7.6s | 0.6s |

> 状态串顺序：已交付 · 非占位照抄 · 摘要非空且非原文兜底 · due_time 可解析且与 S1 同桶 · 建议动作合规 · 分派条目完整（✓ 通过 / ✗ 不通过）

## 五、原始输出样例（便于复核解析是否走形）

### 云端样例

```json
{"choices":[{"finish_reason":"stop","index":0,"message":{"role":"assistant","content":"{\"summary\": \"老板要求准备明天会议材料\", \"due_time\": \"2025-11-11 10:00\", \"suggested_action\": \"今晚准备会议材料\", \"tasks\": [{\"who\": \"我\", \"what\": \"准备会议材料\", \"when\": \"2025-11-11 10:00前\"}]}"}}],"created":1794551898,"model":"Qwen3.8-Flash-Next-Q4_0-ROCmFP4-STRIX_LEAN-00001-of-00003.gguf","system_fingerprint":"b271-dfeacaf","object":"chat.completion","usage":{"completion_tokens":85,"prompt_tokens":510,"total_tokens":595,"prompt_tokens_details":{"cached_tokens":0}},"id":"chatcmpl-JrEHmgDbDbldqxbbH5m06YNRU9GSFp44","timings":{"cache_n":0,"prompt_n":510,"prompt_ms":2634.99,"prompt_per_token_ms":5.166647058823529,"prompt_per_second":193.5491216285451,"predicted_n":85,"predicted_ms":4694.754,"predicted_per_token_ms":55.2324,"predicted_per_second":18.105314996270305}}
{"choices":[{"finish_reason":"stop","index":0,"message":{"role":"assistant","content":"{\"summary\": \"朋友分享搞笑视频，用户附和\", \"due_time\": \"\", \"suggested_action\": \"无需处理\", \"tasks\": []}"}}],"created":1794551904,"model":"Qwen3.8-Flash-Next-Q4_0-ROCmFP4-STRIX_LEAN-00001-of-00003.gguf","system_fingerprint":"b271-dfeacaf","object":"chat.completion","usage":{"completion_tokens":32,"prompt_tokens":507,"total_tokens":539,"prompt_tokens_details":{"cached_tokens":0}},"id":"chatcmpl-N2jHE48yTGrjN1byyXCmCG8ggdNsuiWr","timings":{"cache_n":0,"prompt_n":507,"prompt_ms":2639.13,"prompt_per_token_ms":5.205384615384616,"prompt_per_second":192.10876311511745,"predicted_n":32,"predicted_ms":1591.786,"predicted_per_token_ms":49.7433125,"predicted_per_second":20.103204827784637}}
```

### 端侧样例

```json
{"choices":[{"finish_reason":"stop","index":0,"message":{"role":"assistant","content":"{\"summary\":\"老板最近消息\",\"who\":\"老板\",\"what\":\"最近消息\",\"when\":\"2025-11-10 16:01\",\"suggested_action\":\"给对方发送开会通知\",\"tasks\":[]}"}}],"created":1794551898,"model":"qwen25-0.5b-q4km.gguf","system_fingerprint":"b271-dfeacaf","object":"chat.completion","usage":{"completion_tokens":50,"prompt_tokens":531,"total_tokens":581,"prompt_tokens_details":{"cached_tokens":530}},"id":"chatcmpl-9OtPz3nQjdiStQSpgGxTkb1WIOmYLdgQ","timings":{"cache_n":530,"prompt_n":1,"prompt_ms":20.644,"prompt_per_token_ms":20.644,"prompt_per_second":48.440224762642906,"predicted_n":50,"predicted_ms":730.509,"predicted_per_token_ms":14.61018,"predicted_per_second":68.44542640816198}}
{"choices":[{"finish_reason":"stop","index":0,"message":{"role":"assistant","content":"{\"summary\":\"最近消息\",\"due_time\":\"2025-11-10 16:02\",\"suggested_action\":\"给对方发条消息\",\"tasks\":[{\"who\":\"对方\",\"what\":\"回复\",\"when\":\"2025-11-10 16:02\"}]}"}}],"created":1794551905,"model":"qwen25-0.5b-q4km.gguf","system_fingerprint":"b271-dfeacaf","object":"chat.completion","usage":{"completion_tokens":67,"prompt_tokens":526,"total_tokens":593,"prompt_tokens_details":{"cached_tokens":525}},"id":"chatcmpl-SXXsR5tPEs2s3fPEAhDwoWsfEj72rcps","timings":{"cache_n":525,"prompt_n":1,"prompt_ms":42.409,"prompt_per_token_ms":42.409,"prompt_per_second":23.57990049281992,"predicted_n":67,"predicted_ms":1271.179,"predicted_per_token_ms":18.97282089552239,"predicted_per_second":52.70697517816137}}
```

## 六、口径与限制

1. **S1 输入用人工标注而不是模型结论**：need_action 转 prob 1.0/0.0，importance/due_window/topic 直接用标注值。这样做是为了把「S2 输出可不可用」和「S1 判得准不准」解耦——后者已由 `docs/评测-云端vs端侧S1.md` 单独量化。真实链路里 S1 误判会让 S2 跟着歪，那是 S1 准确率的后果，不是 S2 的能力问题。
2. **端侧替身是 base 补全模型**：真机 S2 用 MiniCPM5-2B（chat 模板 + 结构化约束），本机只有 qwen2.5-0.5b 且走 llama-server 的 chat 端点。0.5B 的数字只能读作**下限**，不能当成端侧真机的实测结论。
3. **「可用」是六项判据全过**，判据都在窗口内的客观事实（S1 桶、窗口末条时间）上，不看措辞好坏。措辞好不好请直接看第五节的原始样例。
4. **占位照抄不抛异常**：生产链路会 `rejectPlaceholderEcho` 回退 S1，评测里保留输出并记一条 `非占位照抄` 失败，才能统计「模型跑飞」的比例。
