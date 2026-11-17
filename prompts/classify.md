这是一个廉价模型（几乎不要钱）。你唯一的任务：判断下面这条文本是不是**关于 {{product}} 的真实使用反馈**。

{{product}} 是一个本地网关小工具，管理多个编程 Agent 走哪家模型。

注意：{{product}} 的英文原意是“喜鹊”，也是英超纽卡斯尔联队的外号，还常被用来指代喜鹊这种鸟。足球、鸟类、球迷内容一律不是反馈。

## 文本

<<<UNTRUSTED_DATA
{{item_body}}
UNTRUSTED_DATA>>>

只输出一行 JSON，不要任何其他内容：

{"relevant":true|false,"kind":"bug"|"question"|"feature"|"praise"|"spam"|"unrelated","reason":"不超过 12 个字"}
