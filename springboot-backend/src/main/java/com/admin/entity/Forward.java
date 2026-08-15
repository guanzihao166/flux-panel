package com.admin.entity;

import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 *
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class Forward extends BaseEntity{

    private static final long serialVersionUID = 1L;

    private Integer userId;

    private String userName;

    private String name;

    private Integer tunnelId;

    private Integer inPort;

    private Integer outPort;

    private String remoteAddr;

    private String interfaceName;

    private String strategy;

    private Long inFlow;

    private Long outFlow;

    private Integer inx;

    private Double latencyMs;

    private Integer probeStatus;

    private Long probeTime;

    private String probeMessage;

    /** 与 remoteAddr 逗号分隔目标对齐的正整数权重。 */
    private String targetWeights;

    /** direct/single/chain。 */
    private String mode;

    /** smart/fixed_first/fixed_last。 */
    private String chainStrategy;

    /** 固定前N跳或后N跳的跳数。 */
    private Integer chainHops;

    /** 逗号分隔的转发端点隧道ID，用于单跳或链式转发。 */
    private String tunnelIds;

    /** none/separate/combined。 */
    private String bandwidthMode;

    /** 独立上行限速（MB/s），0 表示不限。 */
    private Long bandwidthUp;

    /** 独立下行限速（MB/s），0 表示不限。 */
    private Long bandwidthDown;

    /** 上下行合计限速（MB/s），0 表示不限。 */
    private Long bandwidthCombined;

    /** 最大来源IP数，0 表示不限。 */
    private Integer maxSourceIps;

    /** 每IP最大连接数，0 表示不限。 */
    private Integer maxConnPerIp;

    /** 端口到期时间（毫秒时间戳），0 表示永不过期。 */
    private Long expireAt;

}
