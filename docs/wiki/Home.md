# 铜钱银两

创造栏的「铜钱银两」页收录全部面额。快捷操作中的「铜钱银两图鉴」列出名称与说明，背包中的实物可手持、丢出或放进容器。

| 物品 | 面额 | 每栈上限 |
| --- | --- | --- |
| 铜钱 | 一文 | 64 |
| 一贯钱 | 一千文 | 16 |
| 碎银 | 一钱银，折一百文 | 64 |
| 银锭 | 一两银，折一千文 | 16 |

## 调用服务

在服务端主线程经 `CurrencyService.get(server)` 取得本世界服务。`player(player)` 使用本人原版随身物品槽，含快捷栏。首次 `openTill(id, slots)` 由内容宿主提供库存，空栈也占一格，以后用 `openTill(id)` 重新打开同一钱匣。买卖双方可以是玩家或独立钱匣。

1. 内容宿主核对事件权限、距离、收付方向及可售商品。用 `operationId(actor, event, branch, delivery)` 生成稳定操作号，重进同一步时先查 `receipt(id)`。
2. `quote(payer, payee, price, goods)` 给出本次扣款和找零各面额的件数。货物来自收款方，组件随实物转交；纯收付用三参数重载。剧情价格例为 `{"currency_id":"meridian_currency:copper","units":120}`，可用 `Price.CODEC` 读取。银的最小单位为钱。`catalog.format(price)` 按客户端语言显示文、钱或两。
3. 货郎通过 `exchange(customer, till, input, output, lots)` 指定两种面额。调用方和钱匣必须实际持有所交实物及空间。该方法和付款返回同一种报价。
4. `settle(id, quote)` 再核双方版本及格内物品，返回含操作号、双方、价单和实付实找的回执。只有已确认的回执才能推进剧情。收到 `TradeRejected` 时让玩家重选或重新报价。同一操作号绑定同一业务意图。

世界的 `meridian_currency/` 保存不可变交易流水，`tills/` 保存钱匣首次库存。服务先刷盘再应用各方的变更槽；玩家的已应用序号与库存同档保存。重登按序补齐，钱匣按初存与流水还原。备份和还原应以整个世界为单位。

## 扩充目录

模组元数据的 `custom.meridian_currency:catalog` 指向一个随模组分发的 JSON 文件。启动时先合并所有币种，再登记面额，最后核对两向拆兑。模板见[正式目录](../../src/main/resources/data/meridian_currency/currency.json)。

- `currencies` 声明 ID、结算组和每一最小单位的组内整数价值。新币可使用独立结算组。可选的 `units_per_major` 设置大单位的最小单位数。
- `denominations` 声明物品 ID、币种、面额和堆叠数，顺序也是创造页顺序。为物品配齐模型、贴图、语言键及图鉴条目。
- `exchanges` 明列允许兑换的两端件数。两种物品须同属一组且总值相同。一条规则同时允许反向拆兑。
