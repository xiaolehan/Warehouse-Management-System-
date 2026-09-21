package org.example.back.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 生产任务单（D42 齐套预警）：一张单 = 一个成品 × 生产数量。
 * 状态: 1-待生产, 2-生产中, 3-待质检, 4-已完成, 5-已作废, 6-已报废, 7-已终止。
 * 齐套状态: ok-齐套, partial-部分缺料, block-严重缺料（阻断开工）。
 */
@Data
@TableName("biz_production_order")
public class BizProductionOrder {

    public static final int STATUS_PENDING = 1;
    public static final int STATUS_IN_PROGRESS = 2;
    public static final int STATUS_AWAIT_QC = 3;
    public static final int STATUS_DONE = 4;
    public static final int STATUS_VOIDED = 5;
    public static final int STATUS_SCRAPPED = 6;
    /** D73：已终止（销售取消等外部原因中途停单，终态不可逆；区别于作废=单据不该存在） */
    public static final int STATUS_TERMINATED = 7;

    /** 未完结状态集（D66 删 BOM 软保护等口径共用，新增生命周期状态时只改这里） */
    public static final List<Integer> UNFINISHED_STATUSES =
            List.of(STATUS_PENDING, STATUS_IN_PROGRESS, STATUS_AWAIT_QC);

    /** D114：作废/终止原因在 remark 的留痕标记（写入方 patchRemark/appendRemark 与时间线解析共用，勿各自硬编码） */
    public static final String REMARK_VOID_REASON_MARKER = "作废原因:";
    public static final String REMARK_TERMINATE_REASON_MARKER = "终止原因:";

    public static final String KIT_OK = "ok";
    public static final String KIT_PARTIAL = "partial";
    public static final String KIT_BLOCK = "block";
    /** D105：领料单已全额出库——本单物料已发放到生产现场，不再按仓库库存重算齐套 */
    public static final String KIT_ISSUED = "issued";

    public static final String SOURCE_MANUAL = "MANUAL";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String orderNo;

    /** 成品 goods_id(type=product) */
    private Long goodsId;

    private String goodsName;

    private String unit;

    /** 生产数量 */
    private Integer quantity;

    /** 状态: 1-待生产, 2-生产中, 3-待质检, 4-已完成, 5-已作废, 6-已报废, 7-已终止 */
    private Integer status;

    /** 齐套状态: ok-齐套, partial-部分缺料, block-严重缺料 */
    private String kitStatus;

    /** 来源: MANUAL-手动创建 */
    private String source;

    /** D70：关联销售单 id（可空，一张生产单最多关联一张销售单）；通用备货单留空 */
    private Long salesOrderId;

    /** D110：关联的销售明细行 id（建单时由(销售单,成品)唯一解析写入的行级锚点，ADR-0013；
     * 当前行级读取走 (sales_order_id, goods_id) 解析，本列留给行级直连场景（如一单同成品多行的未来扩展）） */
    private Long salesDetailId;

    /** D71：生产手工修正的预计完工时间（可空），优先于系统推算 */
    private LocalDateTime expectedCompletionTime;

    /** 工序清单快照(8道装配工序静态 SOP，打印用) */
    private String processSnapshot;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}