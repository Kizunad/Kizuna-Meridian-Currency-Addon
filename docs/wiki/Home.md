# 铜钱银两

创造栏的「铜钱银两」页收录全部面额。快捷操作中的「铜钱银两图鉴」列出名称与说明，背包中的实物可手持、丢出或放进容器。

| 物品 | 面额 | 每栈上限 |
| --- | --- | --- |
| 铜钱 | 一文 | 64 |
| 一贯钱 | 一千文 | 16 |
| 碎银 | 一钱银，折一百文 | 64 |
| 银锭 | 一两银，折一千文 | 16 |

## 调用服务

在服务端主线程经 `CurrencyService.get(server)` 取得本世界服务。`player(player)` 使用本人原版随身物品槽，含快捷栏。生物商人用 `merchant(mob)` 从背囊收付。独立钱匣首次调用 `openTill(id, slots)` 时由内容宿主提供库存，空栈也占一格，以后用 `openTill(id)` 重开。

1. 内容宿主核对事件权限、距离、收付方向及可售商品。用 `operationId(actor, event, branch, delivery)` 生成稳定操作号，重进同一步时先查 `receipt(id)`。
2. `quote(payer, payee, price, goods)` 给出本次扣款和找零各面额的件数。货物来自收款方，组件随实物转交；纯收付用三参数重载。剧情价格例为 `{"currency_id":"meridian_currency:copper","units":120}`，可用 `Price.CODEC` 读取。银的最小单位为钱。`catalog.format(price)` 按客户端语言显示文、钱或两。
3. 货郎通过 `exchange(customer, till, input, output, lots)` 指定两种面额。调用方和钱匣必须实际持有所交实物及空间。该方法和付款返回同一种报价。
4. `settle(id, quote)` 再核双方版本及格内物品，返回含操作号、双方、价单和实付实找的回执。只有已确认的回执才能推进剧情。收到 `TradeRejected` 时让玩家重选或重新报价。同一操作号绑定同一业务意图。

世界的 `meridian_currency/` 保存不可变交易流水，`tills/` 保存钱匣首次库存。服务先刷盘再应用各方的变更槽；玩家的已应用序号与库存同档保存。重登按序补齐，钱匣按初存与流水还原。备份和还原应以整个世界为单位。

## 商人货单和会话

内容包在 `data/<namespace>/merchant_offers/<name>.json` 定义货单，宿主以
`<namespace>:<name>` 引用。根对象的 `offers` 是有序数组，两页分别保留本方向的行序。
每行的 `id` 在同一货单内唯一，`direction` 从玩家角度取 `buy` 或 `sell`。
`goods` 使用原版物品栈（含组件，`count` 是每份件数），`price` 是每份的价格。
单件货的余量优先使用物品翻译键 `trade.<命名空间>.<物品路径>.count`。货行可以在 `count_format` 指定备用翻译键，
如 `trade.meridian_currency.stock.scrolls`，中文为「%s 卷」，英文为「%s」。省略时中文用「个」；多件一份按「份」计。
收购行另填正整数 `target`，按钱匣里同物品、同组件的实物数扣减可收数量。
整批货单通过校验才替换旧目录，已有钱匣继续使用存档。[示例数据包](../../src/gametest/resources/data/meridian_currency_gametest/merchant_offers/counter.json)

1. 在服务端主线程取 `CurrencyService.get(server).merchants()`。宿主以持久摊位 ID、货单 ID、
   当前演员和即时授权条件构造 `Stall`。用 `open(player, stall)` 建立会话。演员的真实背囊同时容纳钱货，收货与找零使用其中的空格。
2. `view` 给出两页货行、按件成份的数量、货单版本、钱匣水位、各实物面额及按结算组折算的总值。
   `availableLots` 反映现货与收购限额，能否付钱、放下货和找零由选货报价决定。
3. `select` 只接受会话、所见货单版本、页、从零开始的行号、份数及递增请求序号。
   服务端返回合价和付款方视角的实付、实找。一个会话只保留最新选货。
4. 用 `confirm(player, ticket)` 确认；重传沿用同一个凭据。已成交返回原回执。未成交时复核
   玩家、演员、距离、宿主授权、货单与双方实物版本，过期须再次选货。其他观察者可用
   `view` 刷新余量并比较钱匣水位。关窗调用 `close(player, sessionId)`。
5. 世界目录 `meridian_currency/merchant-tickets/` 为每位玩家原子保存最近发出的凭据。
   重连时由 `lastTicket(player)` 取回；已成交可取原回执，重启前只报价的订单须重新选货。

宿主在玩家与商人交互时调用 `TradeCounter.open(player, stall, greeting)`。此入口在服务端开匣并将当前货单发给玩家。窗口使用玩家选择的工作台外框，关闭、离开工作台或商人离场均释放会话。钱货明细来自服务端报价，货单或钱包变动后须重新看价，再确认成交。[窗口入口](../../src/main/java/dev/kizuna/meridian/currency/TradeCounter.java)

## 生物随身钱财

[钱数表](../../src/main/resources/data/meridian_currency/mob_purses.json)可由同路径数据包覆盖。
每行 `types` 列出实体 ID，`mortal`、`wandering_qi`、`true_qi` 下的键是面额物品，值是件数闭区间。
各面额独立抽数。无身体或游炁未开脉按凡人档，真炁及以上取最高档。巡逻队长和带
`meridian_currency.purse_leader` 实体标签的头目再抽一份 `leader_bonus`。猪灵蛮兵有独立档位。
新增面额时，同时登记货币目录与拾取物品标签 `meridian_mob_inventory:pocket_money`。

出生配装完成后，生物首次 tick、开包查看、开担交易或结算掉落时，按当时已有的身体快照发一次钱。旧实体按同样规则补领。
没有身体时按凡人档，后来建身或晋境不补差额。满囊的溢出实物落在脚边。取走、交易或死亡都改变背囊里的实物。
演员的成交水位和背囊随实体一起存档，旧货郎首次开包时从旧账取回成交后所余的钱货。

## 扩充目录

模组元数据的 `custom.meridian_currency:catalog` 指向一个随模组分发的 JSON 文件。启动时先合并所有币种，再登记面额，最后核对两向拆兑。模板见[正式目录](../../src/main/resources/data/meridian_currency/currency.json)。

- `currencies` 声明 ID、结算组和每一最小单位的组内整数价值。新币可使用独立结算组。可选的 `units_per_major` 设置大单位的最小单位数。
- `denominations` 声明物品 ID、币种、面额和堆叠数，顺序也是创造页顺序。为物品配齐模型、贴图、语言键及图鉴条目。
- `exchanges` 明列允许兑换的两端件数。两种物品须同属一组且总值相同。一条规则同时允许反向拆兑。
