package com.admin.common.dto;

import lombok.Data;

import java.util.List;

/** 用户可见的隧道节点状态，不包含节点密钥或完整服务器地址。 */
@Data
public class UserNodeStatusDto {

    private Long tunnelId;
    private String tunnelName;
    private Integer tunnelType;
    private List<NodeStatusDto> nodes;

    @Data
    public static class NodeStatusDto {
        private Long nodeId;
        private String nodeName;
        private String role;
        private String server;
        private Integer status;
        private String version;
        private Long tcpConnections;
        private Long udpConnections;
        private Long bytesReceived;
        private Long bytesTransmitted;
        private Double uploadSpeed;
        private Double downloadSpeed;
        private Double cpuUsage;
        private Double memoryUsage;
        private Long lastSeen;
    }
}
