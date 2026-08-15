package com.admin.common.dto;

import lombok.Data;

/**
 * Agent 上报的活跃连接统计。
 */
@Data
public class ConnectionStatDto {

    private String connectionId;

    private Long startTime;

    private String clientAddr;

    private String targetAddr;

    private String realTarget;

    private String localAddr;

    private String type;

    private String entryServer;

    private String forwarder;

    private Long upload;

    private Long download;

    private String serviceName;

    private Long nodeId;
}
