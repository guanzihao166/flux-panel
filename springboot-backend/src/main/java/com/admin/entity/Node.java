package com.admin.entity;

import java.io.Serializable;
import com.baomidou.mybatisplus.annotation.TableField;
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
@EqualsAndHashCode(callSuper = true)
public class Node extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String name;

    private String secret;

    private String ip;

    private String serverIp;

    /** 节点通信 IPv4 地址，可与 IPv6 地址二选一或同时配置。 */
    private String serverIp4;

    /** 节点通信 IPv6 地址，可与 IPv4 地址二选一或同时配置。 */
    private String serverIp6;

    private String version;

    private Integer portSta;

    private Integer portEnd;

    private Integer http;

    private Integer tls;

    private Integer socks;

    @TableField(exist = false)
    private Long tcpConnections;

    @TableField(exist = false)
    private Long udpConnections;

}
