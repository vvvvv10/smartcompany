package com.example.tms.intl.callback;

import java.time.LocalDateTime;
import java.util.List;

/**
 * IFTSTA（UN/EDIFACT 国际多式联运状态报告）的 JSON 信封 —— **本期报文形状的唯一真相**。
 *
 * <p><b>先纠正一个常见的误解：IFTSTA 不是段名，是报文类型名。</b>
 * IFTSTA 是 UN/EDIFACT 的一个<b>报文</b>（transport status report），段名分别是
 * UNH / BGM / DTM / NAD / RFF / FTX / CNI / <b>STS</b> / LOC / TDT / GID / MEA / DIM / PCI / GIN / UNT / UNZ。
 * 真正承载状态的是 {@code STS}（Status）段。所以本文件不叫「IFTSTA 段」，
 * 而是「IFTSTA 报文」。搞错这一点的后果不是命名问题——M4 写解析器时会去找一个叫
 * {@code IFTSTA} 的段然后找不到。</p>
 *
 * <p><b>为什么本期就定形状、而不是等真接了承运商再定</b>（评审原文：形状一旦定错，M4 要重写解析器）：
 * 回调端点一旦上线，承运商的对接文档就开始指向这个 JSON；改字段名 = 通知所有对接方改代码。
 * 而现在改形状的成本只是一次数据库迁移。所以本期宁可多花两天把每个字段对应到
 * 真实的段/元素号，也不留「以后再说」。</p>
 *
 * ============================ 形状对齐说明 ============================
 *
 * <p><b>A. 本期接入的段与元素（JSON 字段名 = 段名/元素名，注释里标元素号）</b></p>
 * <pre>
 * UNH  S009 MESSAGE IDENTIFIER     → messageType(0065 报文类型名) / messageVersion(0052)
 *                                        / messageRelease(0051) / controllingAgency(0054)
 * UNH  0062 报文参考号              → messageReferenceNumber（整条报文的唯一编号，回 CONTRL 时原样回）
 * BGM  C002 DOCUMENT/MESSAGE NAME   → documentMessageName.documentMessageNameCode(1225 报文名称码)
 * BGM  C002 4000 报文号             → documentMessageName.documentMessageNumber(4000)
 * BGM  1225 报文功能码              → messageFunctionCode（9=原始报文，4=更正/替换）
 *
 * ── 以下是本 JSON 的 events[] 每一条 ──
 * ── 对应 IFTSTA 的 SG4(CNI) 一圈 = 一票货 + SG5(STS) 一圈 = 一个状态事件。
 * ── 真实报文是两层嵌套，我们**拍平**了（原因见 C 段）。
 * CNI  C503 5794 交货单号          → messageReference（反查运单的第一把钥匙）
 * CNI  C503 1004 单据来源/提单号    → transportDocumentNumber（反查的第二把钥匙：BL/AWB）
 *
 * STS  C601 9015 状态类别码         → statusCategoryCode（1=运输 2=海关 …）
 * STS  C601 1131 状态类别           → statusCategory
 * STS  C555 4405 状态事件码         → eventCode    ← external_code 存的就是它
 * STS  C555 1131 状态事件描述       → eventDescription
 * STS  C556 9011 状态原因码         → statusReasonCode
 * STS  C556 1131 状态原因描述       → statusReason
 * DTM  C507 5283 时间限定符         → eventDateTimeQualifier（334=状态变更时间，本期只认它）
 * DTM  C507 2379 日期时间值         → eventDateTime（**已归一为 ISO-8601**，见 D 段说明）
 * DTM  C507 2380 格式码             → eventDateTimeFormat（102=CCYYMMDD，203=CCYYMMDDHHMM）
 * LOC  C517 3225 地点限定符         → placeOfEvent
 * LOC  C517 3239 地点标识           → placeOfEventName
 * RFF  C506 1153/1154 参考号        → references[]（本期只整体留存，不参与业务判定）
 * FTX  自由文本                     → freeText
 * TDT  C040 3127 承运商标识         → carrierCode  ← external_source 存的就是它
 * TDT  C040 1131 承运商名称         → carrierName
 * TDT  C220 1153 运输方式           → modeOfTransport（1=海运 4=空运 3=公路 2=铁路）
 * TDT  C220 5213 子运输方式         → subModeOfTransport
 * TDT  C222 8213 运输工具标识码     → transportMeansCode（船名航次/航班号，如 V.034E）
 * TDT  8051 运输阶段码              → transportStage（20=主运输段 11=过境 …）
 * </pre>
 *
 * <p><b>B. 本期刻意不接的元素，以及为什么</b></p>
 * <ul>
 *   <li><b>FTX 自由文本</b> —— 接了（字段在），但<b>不参与任何业务判定</b>。
 *       自由文本是给人看的，里面什么都可能出现。把它喂进状态机等于允许外部数据
 *       用自然语言驱动我们的状态。</li>
 *   <li><b>GID / MEA / DIM / PCI / GIN（货物项、件数、毛重、体积、箱号、SSCC）</b> —— 不接。
 *       这些是「货物明细」，理论上可以用来自动校正我们的件数/重量，但一旦这么做，
 *       一条报错的报文就会静默改写主单的申报数据——申报数据是要向海关担责的。
 *       这一块留给 M4 的「承运商级联」且必须带人工确认。</li>
 *   <li><b>NAD（收货人/发货人/承运商主体）</b> —— 不接。真实报文里承运商身份同时出现在
 *       {@code TDT.C040} 与 {@code NAD(角色码 CA)}，两处不一致的情况非常常见（代理转发）。
 *       本期只认 TDT 那处并在 56 号迁移里写明了「与 carrier_id 不同义」——
 *       一旦两处都接，就必须先定一个优先级，而现在没有依据定。</li>
 *   <li><b>DTM 的其它限定符</b> —— 只认 {@code 334}（状态变更时间）。
 *       DTM 还能表达 132/133（预计到港/开航）与 40E（下次状态时间）。
 *       把预计时间当事件时间落轨迹，会造出「还没到港却有一条到港节点」的数据——
 *       而 ETA 到点正是定时任务该干的活（{@code SCHEDULED} 来源），
 *       两个来源混成一条轨迹就再也分不清「谁说的」。</li>
 *   <li><b>UNH 0062 报文参考号</b> —— 留存但不参与幂等。幂等用
 *       {@code (shipment_no, node_code, node_time, external_code)}，因为承运商的一个
 *       报文里常有几十条事件，拿整条报文号当事件级幂等键会导致漏判。</li>
 *   <li><b>CONTRL 应答报文</b> —— 不接。评审 §9.2 明确把 EDI 放 M4；本期响应体
 *       （accepted/duplicated/rejected/results）已是 JSON 化的确认，回一个 CONTRL
 *       等于同时支持两种应答模型。</li>
 * </ul>
 *
 * <p><b>C. 与真实 EDIFACT 结构的唯一偏离：events[] 是拍平的</b></p>
 * <p>真实结构是 {@code SG4[CNI > SG5[STS + DTM + LOC + TDT]]}，即「一票货下面挂多个事件」。
 * 本 JSON 把 CNI 层的两个单据号<b>下沉到每一条事件</b>上，事件直接平铺在
 * {@code events[]}。理由三条：
 * <ol>
 *   <li>我们处理与幂等的最小单位是「(运单, 事件)」，不是「(一票货, 事件组)」。
 *       拍平之后一行事件自带全部反查钥匙，M4 的解析器不用先建分组再展开。</li>
 *   <li>JSON 没有 EDIFACT 的「重复次数」语义，拍平不丢信息：任何时候都能用
 *       {@code messageReference} 在客户端重新分组。<b>所以这不是有损变换，
 *       M4 也不需要重写解析器。</b></li>
 *   <li>承运商一次推来 200 条事件时（一个船期 50 个柜），嵌套数组会让「哪一条失败了」
 *       变得很难读——拍平后 results 与 events 一一对应，运维能按行号定位。</li>
 * </ol>
 *
 * <p><b>D. 时间一律按 UTC 解读，且已归一为 ISO-8601</b>。两个决定分开说：
 * <ul>
 *   <li><b>归一为 ISO-8601</b>（{@code 2026-10-14T01:40:00}）而不是 EDIFACT 的紧凑数字
 *       （{@code 202610140140} + 格式码 203）。JSON 不需要 EDIFACT 那种「值 + 格式码」
 *       才能解读时间的约定——ISO 自带分隔符，少一个字段就少一处「M4 忘了转换格式码」
 *       的可能。同时 {@code eventDateTimeFormat} 仍<b>原样保留 EDIFACT 的原始格式码</b>，
 *       所以信息没有丢，真要还原承运商原文时仍能还原。</li>
 *   <li><b>按 UTC 解读</b>：IFTSTA 的 C507 只有「值 + 格式码」，没有时区（格式码
 *       102/203/204 都是裸本地时间）。本系统所有 {@code *At} 列的既有约定也是 UTC
 *       （见 Shipment 记录注释），所以约定承运商按 UTC 报，并在本期文档里写死。
 *       这是个真实的歧义点：{@code "2026-10-14T01:40:00"} 到底是 UTC 还是上海时间，
 *       错了会让节点时间偏 8 小时。<b>M4 接真承运商时必须逐个确认口径</b>，
 *       确认之前一律按 UTC 落，并在原始报文里留证。</li>
 * </ul></p>
 *
 * <p><b>E. 与评审举例里的元素号不一致之处（已按标准修正）</b>：评审 M2-4 的任务描述里
 * 举了 {@code C045 / C252 / C220 / C040 / 8213 / 8281}。核对 UN/EDIFACT 与 GS1
 * EANCOM 2002 S4 后：{@code C252} 是通用「日期时间」复合段（= 5283+2379+2380），
 * 在 IFTSTA 里实际以 {@code C507} 出现；{@code C045} 是「事件码」的另一种编号
 * （= 4451+1131），IFTSTA 的 STS 段用的是 {@code C555}（= 4405+1131）；
 * {@code 8281} 是 <b>运输工具归属权码</b>（transport means ownership），
 * <b>不是提单号</b>——提单号在 TDT 里是 {@code C401/8053} 或 CNI 的
 * {@code C503/5794}。本实现以标准为准：事件码取 C555/4405、日期时间取 C507、
 * 运输阶段取 TDT 的 8051 与 C222/8213。之所以特意记下来，是因为这类错误在
 * 报文形状里是「看起来完全合理但永远对不上」的那种——只有在真接了承运商的
 * BIT 解析器、逐字段对不上的时候才会发现，而那时 M4 已经写完了。</p>
 *
 * <p><b>F. 事件码表是各承运商的私有约定</b>：UNCL 4405 是几百项的大码表，
 * 没有任何一家承运商会把 4405 全用一遍；行业实践（马士基、赫伯罗特、中远、丹格）的
 * 常用码是 {@code ORI/PRE/DEP/ARR/RCA/TRN/DLV/CUS/COL/EXC} 这一小撮。
 * 本期只映射这一小撮，<b>映射不上的一律只落轨迹</b>（见 {@link IftstaEventCodes}）。</p>
 *
 * @param messageType             报文类型名，应为 {@code IFTSTA}
 * @param messageVersion          UNH S009 0052
 * @param messageRelease          UNH S009 0051，如 D / 16B / 96A
 * @param controllingAgency       UNH S009 0054，如 UN / EAN004
 * @param messageReferenceNumber  UNH 0062，整条报文参考号
 * @param documentMessageName     BGM C002
 * @param messageFunctionCode     BGM 1225，9=原始报文 / 4=更正
 * @param events                  SG5 一条一个事件（已从 CNI 层拍平，见 C 段）
 * @param rawPayload              收到的原始 JSON 原文，落进 tms_tracking_nodes.raw_payload
 */
public record IftstaMessage(
        String messageType,
        String messageVersion,
        String messageRelease,
        String controllingAgency,
        String messageReferenceNumber,
        DocumentMessageName documentMessageName,
        String messageFunctionCode,
        List<Event> events,
        String rawPayload) {

    /**
     * BGM C002 DOCUMENT/MESSAGE NAME。
     *
     * @param documentMessageNameCode 1225 报文名称码
     * @param documentMessageNumber   4000 报文号
     */
    public record DocumentMessageName(
            String documentMessageNameCode,
            String documentMessageNumber) {
    }

    /**
     * 一条状态事件（IFTSTA 的 SG5 一圈）。
     *
     * <p>字段顺序按「反查 → 判定 → 落库」的业务顺序排，不按 EDIFACT 段序：
     * 调用方拿到就能顺着做下去。</p>
     */
    public record Event(
            /* --- 反查运单的两把钥匙（原本在 CNI 段，本期下沉，见类注释 C 段） --- */
            /** CNI C503 5794 交货单号／我方运单号。 */
            String messageReference,
            /** CNI C503 1004 提单号或空运单号。 */
            String transportDocumentNumber,

            /* --- 事件本身（STS 段） --- */
            /** STS C601 9015 状态类别码。 */
            String statusCategoryCode,
            /** STS C601 1131 状态类别。 */
            String statusCategory,
            /** STS C555 4405 状态事件码。external_code 存它。 */
            String eventCode,
            /** STS C555 1131 状态事件描述。 */
            String eventDescription,
            /** STS C556 9011 状态原因码（异常类事件才有）。 */
            String statusReasonCode,
            /** STS C556 1131 状态原因描述。 */
            String statusReason,

            /* --- 时间（DTM 段，C507） --- */
            /** DTM C507 5283 时间限定符，本期只认 334（状态变更时间）。 */
            String eventDateTimeQualifier,
            /** DTM C507 2379 日期时间值。 */
            LocalDateTime eventDateTime,
            /** DTM C507 2380 格式码（102=CCYYMMDD，203=CCYYMMDDHHMM）。 */
            String eventDateTimeFormat,

            /* --- 地点（LOC 段，C517） --- */
            /** LOC C517 3225 地点限定符。 */
            String placeOfEvent,
            /** LOC C517 3239 地点标识。 */
            String placeOfEventName,

            /* --- 承运商与运输工具（TDT 段） --- */
            /** TDT C040 3127 承运商标识（SCAC/IATA）。external_source 存它。 */
            String carrierCode,
            /** TDT C040 1131 承运商名称。 */
            String carrierName,
            /** TDT C220 1153 运输方式。 */
            String modeOfTransport,
            /** TDT C220 5213 子运输方式。 */
            String subModeOfTransport,
            /** TDT C222 8213 运输工具标识（船名航次 / 航班号）。 */
            String transportMeansCode,
            /** TDT 8051 运输阶段码。 */
            String transportStage,

            /* --- 其它，本期只留存 --- */
            /** SG5 内 RFF C506 参考号。 */
            List<Reference> references,
            /** FTX 自由文本。接了但绝不参与业务判定。 */
            String freeText) {
    }

    /**
     * RFF C506 REFERENCE。
     *
     * @param referenceQualifier 1153 参考号限定符（AWB/MBL/HBL/CON/POD…）
     * @param referenceNumber   1154 参考号值
     */
    public record Reference(
            String referenceQualifier,
            String referenceNumber) {
    }
}