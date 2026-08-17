package com.admin.service.impl;

import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.ForwardUpdateDto;
import com.admin.common.dto.ForwardWithTunnelDto;
import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.*;
import com.admin.mapper.ForwardMapper;
import com.admin.service.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.alibaba.fastjson.JSONObject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * 端口转发服务实现类
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Slf4j
@Service
public class ForwardServiceImpl extends ServiceImpl<ForwardMapper, Forward> implements ForwardService {

    // 常量定义
    private static final String GOST_SUCCESS_MSG = "OK";
    private static final String GOST_NOT_FOUND_MSG = "not found";
    private static final int ADMIN_ROLE_ID = 0;
    private static final int TUNNEL_TYPE_PORT_FORWARD = 1;
    private static final int TUNNEL_TYPE_TUNNEL_FORWARD = 2;
    private static final int TUNNEL_TYPE_FORWARD_ENDPOINT = 3;
    private static final int FORWARD_STATUS_ACTIVE = 1;
    private static final int FORWARD_STATUS_PAUSED = 0;
    private static final int FORWARD_STATUS_ERROR = -1;
    private static final int TUNNEL_STATUS_ACTIVE = 1;

    private static final long BYTES_TO_GB = 1024L * 1024L * 1024L;

    @Resource
    @Lazy
    private TunnelService tunnelService;

    @Resource
    UserTunnelService userTunnelService;

    @Resource
    UserService userService;

    @Resource
    NodeService nodeService;


    @Override
    public R createForward(ForwardDto forwardDto) {
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();

        // 2. 检查隧道是否存在和可用
        Tunnel tunnel = validateTunnel(forwardDto.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }
        if (tunnel.getStatus() != TUNNEL_STATUS_ACTIVE) {
            return R.err("隧道已禁用，无法创建转发");
        }

        R featureValidation = validateForwardFeatureFields(forwardDto.getMode(), forwardDto.getChainStrategy(),
                forwardDto.getChainHops(), forwardDto.getTunnelIds(), forwardDto.getBandwidthMode(),
                forwardDto.getBandwidthUp(), forwardDto.getBandwidthDown(), forwardDto.getBandwidthCombined(),
                forwardDto.getMaxSourceIps(), forwardDto.getMaxConnPerIp(), forwardDto.getExpireAt());
        if (featureValidation.getCode() != 0) return featureValidation;

        // 3. 普通用户权限和限制检查
        UserPermissionResult permissionResult = checkUserPermissions(currentUser, tunnel, null);
        if (permissionResult.isHasError()) {
            return R.err(permissionResult.getErrorMessage());
        }

        // 4. 分配端口
        PortAllocation portAllocation = allocatePorts(tunnel, forwardDto.getInPort());
        if (portAllocation.isHasError()) {
            return R.err(portAllocation.getErrorMessage());
        }

        // 5. 创建并保存Forward对象
        Forward forward = createForwardEntity(forwardDto, currentUser, portAllocation);
        if (!this.save(forward)) {
            return R.err("端口转发创建失败");
        }

        // 6. 获取所需的节点信息
        NodeInfo nodeInfo = getRequiredNodes(tunnel);
        if (nodeInfo.isHasError()) {
            this.removeById(forward.getId());
            return R.err(nodeInfo.getErrorMessage());
        }

        // 7. 调用Gost服务创建转发
        R gostResult = createGostServices(forward, tunnel, permissionResult.getLimiter(), nodeInfo, permissionResult.getUserTunnel());

        if (gostResult.getCode() != 0) {
            this.removeById(forward.getId());
            return gostResult;
        }

        return R.ok();
    }

    @Override
    public R getAllForwards() {
        UserInfo currentUser = getCurrentUserInfo();

        List<ForwardWithTunnelDto> forwardList;
        if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
            forwardList = baseMapper.selectForwardsWithTunnelByUserId(currentUser.getUserId());
        } else {
            forwardList = baseMapper.selectAllForwardsWithTunnel();
        }

        return R.ok(forwardList);
    }

    @Override
    public R updateForward(ForwardUpdateDto forwardUpdateDto) {
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();
        if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
            User user = userService.getById(currentUser.getUserId());
            if (user == null) return R.err("用户不存在");
            if (user.getStatus() == 0) return R.err("用户已到期或被禁用");
        }


        // 2. 检查转发是否存在
        Forward existForward = validateForwardExists(forwardUpdateDto.getId(), currentUser);
        if (existForward == null) {
            return R.err("转发不存在");
        }

        // 3. 检查隧道是否存在和可用
        Tunnel tunnel = validateTunnel(forwardUpdateDto.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }
        if (tunnel.getStatus() != TUNNEL_STATUS_ACTIVE) {
            return R.err("隧道已禁用，无法更新转发");
        }
        R featureValidation = validateForwardFeatureFields(forwardUpdateDto.getMode(), forwardUpdateDto.getChainStrategy(),
                forwardUpdateDto.getChainHops(), forwardUpdateDto.getTunnelIds(), forwardUpdateDto.getBandwidthMode(),
                forwardUpdateDto.getBandwidthUp(), forwardUpdateDto.getBandwidthDown(), forwardUpdateDto.getBandwidthCombined(),
                forwardUpdateDto.getMaxSourceIps(), forwardUpdateDto.getMaxConnPerIp(), forwardUpdateDto.getExpireAt());
        if (featureValidation.getCode() != 0) return featureValidation;
        boolean tunnelChanged = isTunnelChanged(existForward, forwardUpdateDto);
        // 4. 检查权限和限制
        UserPermissionResult permissionResult = null;
        if (tunnelChanged) {
            if (currentUser.getRoleId() == ADMIN_ROLE_ID) {
                // 管理员操作自己的转发时，不需要检查权限限制
                if (Objects.equals(currentUser.getUserId(), existForward.getUserId())) {
                    permissionResult = UserPermissionResult.success(null, null);
                } else {
                    // 管理员操作用户转发时，需要检查原用户是否有新隧道权限
                    // 获取原转发用户的信息
                    User originalUser = userService.getById(existForward.getUserId());
                    if (originalUser == null) {
                        return R.err("用户不存在");
                    }

                    // 检查原用户是否有新隧道权限
                    UserTunnel userTunnel = getUserTunnel(existForward.getUserId(), tunnel.getId().intValue());
                    if (userTunnel == null) {
                        return R.err("用户没有该隧道权限");
                    }

                    if (userTunnel.getStatus() != 1) {
                        return R.err("隧道被禁用");
                    }

                    // 检查隧道权限到期时间
                    if (userTunnel.getExpTime() != null && userTunnel.getExpTime() <= System.currentTimeMillis()) {
                        return R.err("用户的该隧道权限已到期");
                    }

                    // 检查原用户的流量和转发数量限制
                    R quotaCheckResult = checkForwardQuota(existForward.getUserId(), tunnel.getId().intValue(), userTunnel, originalUser, forwardUpdateDto.getId());
                    if (quotaCheckResult.getCode() != 0) {
                        return R.err("用户" + quotaCheckResult.getMsg());
                    }

                    permissionResult = UserPermissionResult.success(userTunnel.getSpeedId(), userTunnel);
                }
            } else {
                // 普通用户检查自己的权限
                permissionResult = checkUserPermissions(currentUser, tunnel, forwardUpdateDto.getId());
                if (permissionResult.isHasError()) {
                    return R.err(permissionResult.getErrorMessage());
                }
            }
        }

        // 5. 获取UserTunnel（即使隧道未变化也需要获取，用于构建服务名称）
        UserTunnel userTunnel = null;
        if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
            userTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
            if (userTunnel == null) {
                return R.err("你没有该隧道权限");
            }
        } else {
            // 管理员用户也需要获取UserTunnel（如果存在的话），用于构建正确的服务名称
            // 通过forward记录获取原始的用户ID
            userTunnel = getUserTunnel(existForward.getUserId(), tunnel.getId().intValue());
        }

        // 6. 更新Forward对象
        Forward updatedForward = updateForwardEntity(forwardUpdateDto, existForward, tunnel);

        // 7. 获取所需的节点信息
        NodeInfo nodeInfo = getRequiredNodes(tunnel);
        if (nodeInfo.isHasError()) {
            return R.err(nodeInfo.getErrorMessage());
        }

        // 8. 调用Gost服务更新转发
        R gostResult;
        if (tunnelChanged) {
            // 隧道变化时：先删除原配置，再创建新配置
            gostResult = updateGostServicesWithTunnelChange(existForward, updatedForward, tunnel, permissionResult != null ? permissionResult.getLimiter() : null, nodeInfo, userTunnel);
        } else {
            // 隧道未变化时：直接更新配置
            gostResult = updateGostServices(updatedForward, tunnel, permissionResult != null ? permissionResult.getLimiter() : null, nodeInfo, userTunnel);
        }

        if (gostResult.getCode() != 0) {
            return gostResult;
        }
        updatedForward.setStatus(1);
        // 9. 保存更新
        boolean result = this.updateById(updatedForward);
        return result ? R.ok("端口转发更新成功") : R.err("端口转发更新失败");
    }

    @Override
    public R deleteForward(Long id) {
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();

        // 2. 检查转发是否存在
        Forward forward = validateForwardExists(id, currentUser);
        if (forward == null) {
            return R.err("端口转发不存在");
        }

        // 3. 获取隧道信息
        Tunnel tunnel = validateTunnel(forward.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }

        // 4. 权限检查（仅普通用户需要）
        UserTunnel userTunnel = null;
        if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
            userTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
            if (userTunnel == null) {
                return R.err("你没有该隧道权限");
            }
        } else {
            // 管理员删除用户记录时，需要获取对应的UserTunnel用于构建正确的服务名称
            userTunnel = getUserTunnel(forward.getUserId(), tunnel.getId().intValue());
        }

        // 5. 获取所需的节点信息
        NodeInfo nodeInfo = getRequiredNodes(tunnel);
        if (nodeInfo.isHasError()) {
            return R.err(nodeInfo.getErrorMessage());
        }

        // 6. 调用Gost服务删除转发
        R gostResult = deleteGostServices(forward, tunnel, nodeInfo, userTunnel);
        if (gostResult.getCode() != 0) {
            return gostResult;
        }

        // 7. 删除转发记录
        boolean result = this.removeById(id);
        if (result) {
            return R.ok("端口转发删除成功");
        } else {
            return R.err("端口转发删除失败");
        }
    }

    @Override
    public R pauseForward(Long id) {
        return changeForwardStatus(id, FORWARD_STATUS_PAUSED, "暂停", "PauseService");
    }

    @Override
    public R resumeForward(Long id) {
        return changeForwardStatus(id, FORWARD_STATUS_ACTIVE, "恢复", "ResumeService");
    }

    @Override
    public R forceDeleteForward(Long id) {
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();

        // 2. 检查转发是否存在且用户有权限操作
        Forward forward = validateForwardExists(id, currentUser);
        if (forward == null) {
            return R.err("端口转发不存在");
        }

        // 3. 直接删除转发记录，跳过GOST服务删除
        boolean result = this.removeById(id);
        if (result) {
            return R.ok("端口转发强制删除成功");
        } else {
            return R.err("端口转发强制删除失败");
        }
    }

    @Override
    public R cascadeDeleteForward(Forward forward) {
        if (forward == null || forward.getId() == null) {
            return R.ok();
        }
        try {
            Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
            if (tunnel != null) {
                NodeInfo nodeInfo = getRequiredNodes(tunnel);
                if (!nodeInfo.isHasError()) {
                    UserTunnel userTunnel = getUserTunnel(forward.getUserId(), tunnel.getId().intValue());
                    R gostResult = deleteGostServices(forward, tunnel, nodeInfo, userTunnel);
                    if (gostResult.getCode() != 0) {
                        log.warn("节点级联删除转发 {} 时 GOST 清理未完成: {}", forward.getId(), gostResult.getMsg());
                    }
                } else {
                    log.warn("节点级联删除转发 {} 跳过 GOST 清理: {}", forward.getId(), nodeInfo.getErrorMessage());
                }
            }
        } catch (Exception e) {
            log.warn("节点级联删除转发 {} 时 GOST 清理失败: {}", forward.getId(), e.getMessage());
        }
        this.removeById(forward.getId());
        return R.ok();
    }

    /**
     * 改变转发状态（暂停/恢复）
     */
    private R changeForwardStatus(Long id, int targetStatus, String operation, String gostMethod) {
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();

        if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
            User user = userService.getById(currentUser.getUserId());
            if (user == null) return R.err("用户不存在");
            if (user.getStatus() == 0) return R.err("用户已到期或被禁用");
        }


        // 2. 检查转发是否存在
        Forward forward = validateForwardExists(id, currentUser);
        if (forward == null) {
            return R.err("转发不存在");
        }

        // 3. 获取隧道信息
        Tunnel tunnel = validateTunnel(forward.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }

        // 4. 恢复服务时需要额外检查
        UserTunnel userTunnel = null;
        if (targetStatus == FORWARD_STATUS_ACTIVE) {
            if (tunnel.getStatus() != TUNNEL_STATUS_ACTIVE) {
                return R.err("隧道已禁用，无法恢复服务");
            }

            // 普通用户需要检查流量和账户状态
            if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
                R flowCheckResult = checkUserFlowLimits(currentUser.getUserId(), tunnel);
                if (flowCheckResult.getCode() != 0) {
                    return flowCheckResult;
                }

                userTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
                if (userTunnel == null) {
                    return R.err("你没有该隧道权限");
                }

                if (userTunnel.getStatus() != 1) {
                    return R.err("隧道被禁用");
                }
            }
        }

        // 5. 权限检查（仅普通用户需要）
        if (currentUser.getRoleId() != ADMIN_ROLE_ID && userTunnel == null) {
            userTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
            if (userTunnel == null) {
                return R.err("你没有该隧道权限");
            }
        }

        // 6. 确保获取UserTunnel用于构建服务名称（包括管理员用户）
        if (userTunnel == null) {
            // 通过forward记录获取原始的用户ID来查找UserTunnel
            userTunnel = getUserTunnel(forward.getUserId(), tunnel.getId().intValue());
        }

        // 7. 获取所需的节点信息
        NodeInfo nodeInfo = getRequiredNodes(tunnel);
        if (nodeInfo.isHasError()) {
            return R.err(nodeInfo.getErrorMessage());
        }

        // 8. 调用Gost服务
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);
        GostDto gostResult;

        if ("PauseService".equals(gostMethod)) {
            gostResult = GostUtil.PauseService(nodeInfo.getInNode().getId(), serviceName);

            // 隧道转发需要同时暂停远端服务
            if (tunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD && nodeInfo.getOutNode() != null) {
                GostDto remoteResult = GostUtil.PauseRemoteService(nodeInfo.getOutNode().getId(), serviceName);
                if (!isGostOperationSuccess(remoteResult)) {
                    return R.err(operation + "远端服务失败：" + remoteResult.getMsg());
                }
            }
        } else {
            gostResult = GostUtil.ResumeService(nodeInfo.getInNode().getId(), serviceName);

            // 隧道转发需要同时恢复远端服务
            if (tunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD && nodeInfo.getOutNode() != null) {
                GostDto remoteResult = GostUtil.ResumeRemoteService(nodeInfo.getOutNode().getId(), serviceName);
                if (!isGostOperationSuccess(remoteResult)) {
                    return R.err(operation + "远端服务失败：" + remoteResult.getMsg());
                }
            }
        }

        if (!isGostOperationSuccess(gostResult)) {
            return R.err(operation + "服务失败：" + gostResult.getMsg());
        }

        // 9. 更新转发状态
        forward.setStatus(targetStatus);
        forward.setUpdatedTime(System.currentTimeMillis());
        boolean result = this.updateById(forward);

        return result ? R.ok("服务已" + operation) : R.err("更新状态失败");
    }

    @Override
    public R diagnoseForward(Long id) {
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();

        // 2. 检查转发是否存在且用户有权限访问
        Forward forward = validateForwardExists(id, currentUser);
        if (forward == null) {
            return R.err("转发不存在");
        }

        // 3. 获取隧道信息
        Tunnel tunnel = validateTunnel(forward.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }

        // 4. 获取入口节点信息
        Node inNode = nodeService.getNodeById(tunnel.getInNodeId());
        if (inNode == null) {
            return R.err("入口节点不存在");
        }


        List<DiagnosisResult> results = new ArrayList<>();
        String[] remoteAddresses = forward.getRemoteAddr().split(",");
        // 6. 根据隧道类型执行不同的诊断策略
        if (tunnel.getType() == TUNNEL_TYPE_PORT_FORWARD) {
            // 端口转发：入口节点直接TCP ping目标地址
            for (String remoteAddress : remoteAddresses) {
                // 提取IP和端口
                String targetIp = extractIpFromAddress(remoteAddress);
                int targetPort = extractPortFromAddress(remoteAddress);
                if (targetIp == null || targetPort == -1) {
                    return R.err("无法解析目标地址: " + remoteAddress);
                }

                DiagnosisResult result = performTcpPingDiagnosis(inNode, targetIp, targetPort, "转发->目标");
                results.add(result);
            }
        } else {
            // 隧道转发：入口TCP ping出口，出口TCP ping目标
            Node outNode = nodeService.getNodeById(tunnel.getOutNodeId());
            if (outNode == null) {
                return R.err("出口节点不存在");
            }

            // 入口TCP ping出口（使用转发的出口端口）
            DiagnosisResult inToOutResult = performTcpPingDiagnosis(inNode, outNode.getServerIp(), forward.getOutPort(), "入口->出口");
            results.add(inToOutResult);

            // 出口TCP ping目标
            for (String remoteAddress : remoteAddresses) {
                // 提取IP和端口
                String targetIp = extractIpFromAddress(remoteAddress);
                int targetPort = extractPortFromAddress(remoteAddress);
                if (targetIp == null || targetPort == -1) {
                    return R.err("无法解析目标地址: " + remoteAddress);
                }
                DiagnosisResult outToTargetResult = performTcpPingDiagnosis(outNode, targetIp, targetPort, "出口->目标");
                results.add(outToTargetResult);
            }

        }

        // 7. 构建诊断报告
        Map<String, Object> diagnosisReport = new HashMap<>();
        diagnosisReport.put("forwardId", id);
        diagnosisReport.put("forwardName", forward.getName());
        diagnosisReport.put("tunnelType", tunnel.getType() == TUNNEL_TYPE_PORT_FORWARD ? "端口转发" : "隧道转发");
        diagnosisReport.put("results", results);
        diagnosisReport.put("timestamp", System.currentTimeMillis());

        return R.ok(diagnosisReport);
    }

    @Override
    public R probeForward(Long id) {
        UserInfo currentUser = getCurrentUserInfo();
        Forward forward = validateForwardExists(id, currentUser);
        if (forward == null) return R.err("转发不存在或无权访问");
        probeOneForward(forward);
        return R.ok(getById(id));
    }

    @Override
    public R probeAllForwards() {
        if (JwtUtil.getRoleIdFromToken() != ADMIN_ROLE_ID) return R.err(403, "仅管理员可拨测全部转发");
        java.util.concurrent.CompletableFuture.runAsync(this::runScheduledProbe);
        return R.ok("拨测任务已启动");
    }

    @Scheduled(initialDelay = 60000, fixedDelay = 900000)
    public void runScheduledProbe() {
        for (Forward forward : list(new QueryWrapper<Forward>().eq("status", FORWARD_STATUS_ACTIVE))) {
            try {
                probeOneForward(forward);
            } catch (Exception e) {
                updateProbeResult(forward, -1, null, "拨测异常: " + e.getMessage());
                log.warn("转发 {} 自动拨测失败: {}", forward.getId(), e.getMessage());
            }
        }
    }

    private void probeOneForward(Forward forward) {
        Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
        if (tunnel == null) {
            updateProbeResult(forward, -1, null, "隧道不存在");
            return;
        }
        Node inNode = nodeService.getNodeById(tunnel.getInNodeId());
        if (inNode == null) {
            updateProbeResult(forward, -1, null, "入口节点不存在");
            return;
        }
        if (tunnel.getType() == TUNNEL_TYPE_PORT_FORWARD) {
            double total = 0;
            int successCount = 0;
            for (String address : forward.getRemoteAddr().split(",")) {
                String ip = extractIpFromAddress(address);
                int port = extractPortFromAddress(address);
                if (ip == null || port < 1) continue;
                DiagnosisResult target = performTcpPingDiagnosis(inNode, ip, port, "入口->目标");
                if (target.isSuccess()) { total += target.getAverageTime(); successCount++; }
            }
            if (successCount == 0) updateProbeResult(forward, -1, null, "所有目标均不可达");
            else updateProbeResult(forward, 1, total / successCount, "可用目标 " + successCount);
            return;
        }

        // Endpoint routes replace the primary tunnel's output path.  Probing the
        // primary tunnel here would therefore report an unrelated relay result.
        if (!"direct".equals(forward.getMode()) && !isBlank(forward.getTunnelIds())) {
            List<Tunnel> endpointTunnels = getEndpointTunnels(forward);
            if (endpointTunnels.isEmpty()) {
                updateProbeResult(forward, -1, null, "没有可用的转发端点");
                return;
            }
            probeEndpointForward(forward, inNode, endpointTunnels);
            return;
        }

        if (forward.getOutPort() == null) {
            updateProbeResult(forward, -1, null, "出口端口不存在");
            return;
        }
        double sharedLatency = 0;
        Node previous = inNode;
        for (Long chainId : parseCsvNodeIds(tunnel.getChainNodeIds())) {
            Node chainNode = nodeService.getNodeById(chainId);
            if (chainNode == null || chainNode.getStatus() != 1) {
                updateProbeResult(forward, -1, null, "中继节点离线: " + chainId);
                return;
            }
            DiagnosisResult hop = performTcpPingDiagnosis(previous, chainNode.getServerIp(), forward.getOutPort(), "链路中继");
            if (!hop.isSuccess()) { updateProbeResult(forward, -1, null, "中继不可达: " + chainNode.getName()); return; }
            sharedLatency += hop.getAverageTime();
            previous = chainNode;
        }

        List<Long> outputIds = parseCsvNodeIds(tunnel.getOutNodeIds());
        if (outputIds.isEmpty() && tunnel.getOutNodeId() != null) outputIds.add(tunnel.getOutNodeId());
        double totalLatency = 0;
        int availableRoutes = 0;
        for (Long outputId : outputIds) {
            Node outNode = nodeService.getNodeById(outputId);
            if (outNode == null || outNode.getStatus() != 1) continue;
            DiagnosisResult toOutput = performTcpPingDiagnosis(previous, outNode.getServerIp(), forward.getOutPort(), "末跳->出口");
            if (!toOutput.isSuccess()) continue;
            double targetTotal = 0;
            int targetCount = 0;
            for (String address : forward.getRemoteAddr().split(",")) {
                String ip = extractIpFromAddress(address);
                int port = extractPortFromAddress(address);
                if (ip == null || port < 1) continue;
                DiagnosisResult target = performTcpPingDiagnosis(outNode, ip, port, "出口->目标");
                if (target.isSuccess()) { targetTotal += target.getAverageTime(); targetCount++; }
            }
            if (targetCount > 0) {
                totalLatency += sharedLatency + toOutput.getAverageTime() + targetTotal / targetCount;
                availableRoutes++;
            }
        }
        if (availableRoutes == 0) updateProbeResult(forward, -1, null, "无完整可用路径（共 " + outputIds.size() + " 条）");
        else updateProbeResult(forward, 1, totalLatency / availableRoutes,
                "可用路径 " + availableRoutes + "/" + outputIds.size());
    }

    private void probeEndpointForward(Forward forward, Node inNode, List<Tunnel> endpointTunnels) {
        if (forward.getOutPort() == null) {
            updateProbeResult(forward, -1, null, "出口端口不存在");
            return;
        }

        List<Tunnel> routeEndpoints = "single".equals(forward.getMode())
                ? Collections.singletonList(endpointTunnels.get(0))
                : endpointTunnels;
        List<Node> previousNodes = Collections.singletonList(inNode);
        double totalLatency = 0;
        int latencySamples = 0;

        for (Tunnel endpoint : routeEndpoints) {
            for (Long chainId : applyChainSelection(endpoint, forward)) {
                Node chainNode = nodeService.getNodeById(chainId);
                if (chainNode == null || chainNode.getStatus() != TUNNEL_STATUS_ACTIVE) {
                    updateProbeResult(forward, -1, null, "端点中继节点离线: " + chainId);
                    return;
                }
                Double latency = probeHop(previousNodes, chainNode, forward.getOutPort(), "端点中继");
                if (latency == null) {
                    updateProbeResult(forward, -1, null, "端点中继不可达: " + chainNode.getName());
                    return;
                }
                totalLatency += latency;
                latencySamples++;
                previousNodes = Collections.singletonList(chainNode);
            }

            List<Long> outputIds = parseCsvNodeIds(endpoint.getOutNodeIds());
            if (outputIds.isEmpty() && endpoint.getOutNodeId() != null) outputIds.add(endpoint.getOutNodeId());
            List<Node> outputNodes = new ArrayList<>();
            for (Long outputId : outputIds) {
                Node outputNode = nodeService.getNodeById(outputId);
                if (outputNode == null || outputNode.getStatus() != TUNNEL_STATUS_ACTIVE) continue;
                Double latency = probeHop(previousNodes, outputNode, forward.getOutPort(), "端点出口");
                if (latency != null) {
                    totalLatency += latency;
                    latencySamples++;
                    outputNodes.add(outputNode);
                }
            }
            if (outputNodes.isEmpty()) {
                updateProbeResult(forward, -1, null, "端点出口不可达: " + endpoint.getName());
                return;
            }
            previousNodes = outputNodes;
        }

        int availableTargets = 0;
        for (Node outputNode : previousNodes) {
            for (String address : forward.getRemoteAddr().split(",")) {
                String ip = extractIpFromAddress(address);
                int port = extractPortFromAddress(address);
                if (ip == null || port < 1) continue;
                DiagnosisResult target = performTcpPingDiagnosis(outputNode, ip, port, "端点出口->目标");
                if (target.isSuccess()) {
                    totalLatency += target.getAverageTime();
                    latencySamples++;
                    availableTargets++;
                }
            }
        }
        if (availableTargets == 0) {
            updateProbeResult(forward, -1, null, "端点到目标不可达");
            return;
        }
        updateProbeResult(forward, 1, totalLatency / Math.max(1, latencySamples),
                "端点路径可用 " + availableTargets + "/" + previousNodes.size());
    }

    private Double probeHop(List<Node> sourceNodes, Node targetNode, Integer port, String description) {
        Double bestLatency = null;
        for (Node sourceNode : sourceNodes) {
            DiagnosisResult result = performTcpPingDiagnosis(sourceNode, targetNode.getServerIp(), port, description);
            if (!result.isSuccess()) continue;
            double latency = result.getAverageTime();
            if (bestLatency == null || latency < bestLatency) bestLatency = latency;
        }
        return bestLatency;
    }

    private void updateProbeResult(Forward forward, int status, Double latency, String message) {
        forward.setProbeStatus(status);
        forward.setLatencyMs(latency);
        forward.setProbeTime(System.currentTimeMillis());
        forward.setProbeMessage(message == null ? null : message.substring(0, Math.min(255, message.length())));
        // A successful end-to-end probe proves a previously failed service is usable again.
        // Preserve paused forwards, but let an error state rejoin scheduled probing.
        if (status == 1 && Objects.equals(forward.getStatus(), FORWARD_STATUS_ERROR)) {
            forward.setStatus(FORWARD_STATUS_ACTIVE);
        }
        updateById(forward);
    }

    @Override
    public R updateForwardOrder(Map<String, Object> params) {
        try {
            // 1. 获取当前用户信息
            UserInfo currentUser = getCurrentUserInfo();

            // 2. 验证参数
            if (!params.containsKey("forwards")) {
                return R.err("缺少forwards参数");
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> forwardsList = (List<Map<String, Object>>) params.get("forwards");
            if (forwardsList == null || forwardsList.isEmpty()) {
                return R.err("forwards参数不能为空");
            }

            // 3. 验证用户权限（只能更新自己的转发）
            if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
                // 普通用户只能更新自己的转发
                List<Long> forwardIds = forwardsList.stream()
                        .map(item -> Long.valueOf(item.get("id").toString()))
                        .collect(Collectors.toList());

                // 检查所有转发是否属于当前用户
                QueryWrapper<Forward> queryWrapper = new QueryWrapper<>();
                queryWrapper.in("id", forwardIds);
                queryWrapper.eq("user_id", currentUser.getUserId());

                long count = this.count(queryWrapper);
                if (count != forwardIds.size()) {
                    return R.err("只能更新自己的转发排序");
                }
            }

            // 4. 批量更新排序
            List<Forward> forwardsToUpdate = new ArrayList<>();
            for (Map<String, Object> forwardData : forwardsList) {
                Long id = Long.valueOf(forwardData.get("id").toString());
                Integer inx = Integer.valueOf(forwardData.get("inx").toString());

                Forward forward = new Forward();
                forward.setId(id);
                forward.setInx(inx);
                forwardsToUpdate.add(forward);
            }

            // 5. 执行批量更新
            boolean success = this.updateBatchById(forwardsToUpdate);
            if (success) {
                log.info("用户 {} 更新了 {} 个转发的排序", currentUser.getUserName(), forwardsToUpdate.size());
                return R.ok("排序更新成功");
            } else {
                return R.err("排序更新失败");
            }

        } catch (Exception e) {
            log.error("更新转发排序失败", e);
            return R.err("更新排序时发生错误: " + e.getMessage());
        }
    }

    /**
     * 从地址字符串中提取IP地址
     * 支持格式: ip:port, [ipv6]:port, domain:port
     */
    private String extractIpFromAddress(String address) {
        if (address == null || address.trim().isEmpty()) {
            return null;
        }

        address = address.trim();

        // IPv6格式: [ipv6]:port
        if (address.startsWith("[")) {
            int closeBracket = address.indexOf(']');
            if (closeBracket > 1) {
                return address.substring(1, closeBracket);
            }
        }

        // IPv4或域名格式: ip:port 或 domain:port
        int lastColon = address.lastIndexOf(':');
        if (lastColon > 0) {
            return address.substring(0, lastColon);
        }

        // 如果没有端口，直接返回地址
        return address;
    }

    /**
     * 从地址字符串中提取端口号
     * 支持格式: ip:port, [ipv6]:port, domain:port
     */
    private int extractPortFromAddress(String address) {
        if (address == null || address.trim().isEmpty()) {
            return -1;
        }

        address = address.trim();

        // IPv6格式: [ipv6]:port
        if (address.startsWith("[")) {
            int closeBracket = address.indexOf(']');
            if (closeBracket > 1 && closeBracket + 1 < address.length() && address.charAt(closeBracket + 1) == ':') {
                String portStr = address.substring(closeBracket + 2);
                try {
                    return Integer.parseInt(portStr);
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }

        // IPv4或域名格式: ip:port 或 domain:port
        int lastColon = address.lastIndexOf(':');
        if (lastColon > 0 && lastColon + 1 < address.length()) {
            String portStr = address.substring(lastColon + 1);
            try {
                return Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        // 如果没有端口，返回-1表示无法解析
        return -1;
    }

    /**
     * 执行TCP ping诊断
     *
     * @param node        执行TCP ping的节点
     * @param targetIp    目标IP地址
     * @param port        目标端口
     * @param description 诊断描述
     * @return 诊断结果
     */
    private DiagnosisResult performTcpPingDiagnosis(Node node, String targetIp, int port, String description) {
        try {
            // 构建TCP ping请求数据
            JSONObject tcpPingData = new JSONObject();
            tcpPingData.put("ip", targetIp);
            tcpPingData.put("port", port);
            tcpPingData.put("count", 2);
            tcpPingData.put("timeout", 3000); // 5秒超时

            // 发送TCP ping命令到节点
            GostDto gostResult = WebSocketServer.send_msg(node.getId(), tcpPingData, "TcpPing");

            DiagnosisResult result = new DiagnosisResult();
            result.setNodeId(node.getId());
            result.setNodeName(node.getName());
            result.setTargetIp(targetIp);
            result.setTargetPort(port);
            result.setDescription(description);
            result.setTimestamp(System.currentTimeMillis());

            if (gostResult != null && "OK".equals(gostResult.getMsg())) {
                // 尝试解析TCP ping响应数据
                try {
                    if (gostResult.getData() != null) {
                        JSONObject tcpPingResponse = (JSONObject) gostResult.getData();
                        boolean success = tcpPingResponse.getBooleanValue("success");

                        result.setSuccess(success);
                        if (success) {
                            result.setMessage("TCP连接成功");
                            result.setAverageTime(tcpPingResponse.getDoubleValue("averageTime"));
                            result.setPacketLoss(tcpPingResponse.getDoubleValue("packetLoss"));
                        } else {
                            result.setMessage(tcpPingResponse.getString("errorMessage"));
                            result.setAverageTime(-1.0);
                            result.setPacketLoss(100.0);
                        }
                    } else {
                        // 没有详细数据，使用默认值
                        result.setSuccess(true);
                        result.setMessage("TCP连接成功");
                        result.setAverageTime(0.0);
                        result.setPacketLoss(0.0);
                    }
                } catch (Exception e) {
                    // 解析响应数据失败，但TCP ping命令本身成功了
                    result.setSuccess(true);
                    result.setMessage("TCP连接成功，但无法解析详细数据");
                    result.setAverageTime(0.0);
                    result.setPacketLoss(0.0);
                }
            } else {
                result.setSuccess(false);
                result.setMessage(gostResult != null ? gostResult.getMsg() : "节点无响应");
                result.setAverageTime(-1.0);
                result.setPacketLoss(100.0);
            }

            return result;
        } catch (Exception e) {
            DiagnosisResult result = new DiagnosisResult();
            result.setNodeId(node.getId());
            result.setNodeName(node.getName());
            result.setTargetIp(targetIp);
            result.setTargetPort(port);
            result.setDescription(description);
            result.setSuccess(false);
            result.setMessage("诊断执行异常: " + e.getMessage());
            result.setTimestamp(System.currentTimeMillis());
            result.setAverageTime(-1.0);
            result.setPacketLoss(100.0);
            return result;
        }
    }

    /**
     * 获取当前用户信息
     */
    private UserInfo getCurrentUserInfo() {
        Integer userId = JwtUtil.getUserIdFromToken();
        Integer roleId = JwtUtil.getRoleIdFromToken();
        String userName = JwtUtil.getNameFromToken();
        return new UserInfo(userId, roleId, userName);
    }

    /**
     * 验证隧道是否存在
     */
    private Tunnel validateTunnel(Integer tunnelId) {
        return tunnelService.getById(tunnelId);
    }

    private R validateForwardFeatureFields(String mode, String chainStrategy, Integer chainHops,
                                           String tunnelIds, String bandwidthMode, Long bandwidthUp,
                                           Long bandwidthDown, Long bandwidthCombined, Integer maxSourceIps,
                                           Integer maxConnPerIp, Long expireAt) {
        String normalizedMode = org.apache.commons.lang3.StringUtils.defaultIfBlank(mode, "direct");
        if (!Arrays.asList("direct", "single", "chain").contains(normalizedMode)) {
            return R.err("转发模式只支持直连、单跳转发或链式转发");
        }
        if (!"direct".equals(normalizedMode)) {
            if (org.apache.commons.lang3.StringUtils.isBlank(tunnelIds)) {
                return R.err("单跳或链式转发必须至少选择一个转发端点");
            }
            if (!Arrays.asList("smart", "fixed_first", "fixed_last").contains(org.apache.commons.lang3.StringUtils.defaultIfBlank(chainStrategy, "smart"))) {
                return R.err("转发链选择只支持智能选择、固定前N跳或固定后N跳");
            }
            if (chainHops != null && (chainHops < 0 || chainHops > 100)) {
                return R.err("固定跳数必须在 0-100 之间");
            }
        }
        String normalizedBandwidthMode = org.apache.commons.lang3.StringUtils.defaultIfBlank(bandwidthMode, "none");
        if (!Arrays.asList("none", "separate", "combined").contains(normalizedBandwidthMode)) {
            return R.err("带宽限制模式不正确");
        }
        if (bandwidthUp != null && bandwidthUp < 0) return R.err("上行带宽不能为负数");
        if (bandwidthDown != null && bandwidthDown < 0) return R.err("下行带宽不能为负数");
        if (bandwidthCombined != null && bandwidthCombined < 0) return R.err("合计带宽不能为负数");
        if (maxSourceIps != null && maxSourceIps < 0) return R.err("最大来源IP数不能为负数");
        if (maxConnPerIp != null && maxConnPerIp < 0) return R.err("每IP最大连接数不能为负数");
        if (expireAt != null && expireAt < 0) return R.err("到期时间不能为负数");
        return R.ok();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * 验证转发是否存在且用户有权限访问
     */
    private Forward validateForwardExists(Long forwardId, UserInfo currentUser) {
        Forward forward = this.getById(forwardId);
        if (forward == null) {
            return null;
        }

        // 普通用户只能操作自己的转发
        if (currentUser.getRoleId() != ADMIN_ROLE_ID &&
                !Objects.equals(currentUser.getUserId(), forward.getUserId())) {
            return null;
        }

        return forward;
    }

    /**
     * 获取所需的节点信息
     */
    private NodeInfo getRequiredNodes(Tunnel tunnel) {
        Node inNode = nodeService.getNodeById(tunnel.getInNodeId());
        if (inNode == null) {
            return NodeInfo.error("入口节点不存在");
        }

        Node outNode = null;
        if (tunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD) {
            outNode = nodeService.getNodeById(tunnel.getOutNodeId());
            if (outNode == null) {
                return NodeInfo.error("出口节点不存在");
            }
        }

        return NodeInfo.success(inNode, outNode);
    }

    /**
     * 检查用户权限和限制
     */
    private UserPermissionResult checkUserPermissions(UserInfo currentUser, Tunnel tunnel, Long excludeForwardId) {
        if (currentUser.getRoleId() == ADMIN_ROLE_ID) {
            return UserPermissionResult.success(null, null);
        }

        // 获取用户信息
        User userInfo = userService.getById(currentUser.getUserId());
        if (userInfo.getExpTime() != null && userInfo.getExpTime() <= System.currentTimeMillis()) {
            return UserPermissionResult.error("当前账号已到期");
        }

        // 检查用户隧道权限
        UserTunnel userTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
        if (userTunnel == null) {
            return UserPermissionResult.error("你没有该隧道权限");
        }

        if (userTunnel.getStatus() != 1) {
            return UserPermissionResult.error("隧道被禁用");
        }

        // 检查隧道权限到期时间
        if (userTunnel.getExpTime() != null && userTunnel.getExpTime() <= System.currentTimeMillis()) {
            return UserPermissionResult.error("该隧道权限已到期");
        }

        // 流量限制检查
        if (userInfo.getFlow() <= 0) {
            return UserPermissionResult.error("用户总流量已用完");
        }
        if (userTunnel.getFlow() <= 0) {
            return UserPermissionResult.error("该隧道流量已用完");
        }

        // 转发数量限制检查
        R quotaCheckResult = checkForwardQuota(currentUser.getUserId(), tunnel.getId().intValue(), userTunnel, userInfo, excludeForwardId);
        if (quotaCheckResult.getCode() != 0) {
            return UserPermissionResult.error(quotaCheckResult.getMsg());
        }

        return UserPermissionResult.success(userTunnel.getSpeedId(), userTunnel);
    }

    /**
     * 检查用户转发数量限制
     */
    private R checkForwardQuota(Integer userId, Integer tunnelId, UserTunnel userTunnel, User userInfo, Long excludeForwardId) {
        // 检查用户总转发数量限制
        long userForwardCount = this.count(new QueryWrapper<Forward>().eq("user_id", userId));
        if (userForwardCount >= userInfo.getNum()) {
            return R.err("用户总转发数量已达上限，当前限制：" + userInfo.getNum() + "个");
        }

        // 检查用户在该隧道的转发数量限制
        QueryWrapper<Forward> tunnelQuery = new QueryWrapper<Forward>()
                .eq("user_id", userId)
                .eq("tunnel_id", tunnelId);

        if (excludeForwardId != null) {
            tunnelQuery.ne("id", excludeForwardId);
        }

        long tunnelForwardCount = this.count(tunnelQuery);
        if (tunnelForwardCount >= userTunnel.getNum()) {
            return R.err("该隧道转发数量已达上限，当前限制：" + userTunnel.getNum() + "个");
        }

        return R.ok();
    }

    /**
     * 检查用户流量限制
     */
    private R checkUserFlowLimits(Integer userId, Tunnel tunnel) {
        User userInfo = userService.getById(userId);
        if (userInfo.getExpTime() != null && userInfo.getExpTime() <= System.currentTimeMillis()) {
            return R.err("当前账号已到期");
        }

        UserTunnel userTunnel = getUserTunnel(userId, tunnel.getId().intValue());
        if (userTunnel == null) {
            return R.err("你没有该隧道权限");
        }

        // 检查隧道权限到期时间
        if (userTunnel.getExpTime() != null && userTunnel.getExpTime() <= System.currentTimeMillis()) {
            return R.err("该隧道权限已到期，无法恢复服务");
        }

        // 检查用户总流量限制
        if (userInfo.getFlow() * BYTES_TO_GB <= userInfo.getInFlow() + userInfo.getOutFlow()) {
            return R.err("用户总流量已用完，无法恢复服务");
        }

        // 检查隧道流量限制
        // 数据库中的流量已按计费类型处理，直接使用总和
        long tunnelFlow = userTunnel.getInFlow() + userTunnel.getOutFlow();

        if (userTunnel.getFlow() * BYTES_TO_GB <= tunnelFlow) {
            return R.err("该隧道流量已用完，无法恢复服务");
        }

        return R.ok();
    }

    /**
     * 分配端口
     */
    private PortAllocation allocatePorts(Tunnel tunnel, Integer specifiedInPort) {
        return allocatePorts(tunnel, specifiedInPort, null);
    }

    /**
     * 分配端口
     */
    private PortAllocation allocatePorts(Tunnel tunnel, Integer specifiedInPort, Long excludeForwardId) {
        Integer inPort;

        if (specifiedInPort != null) {
            // 用户指定了入口端口，需要检查是否可用
            if (!isInPortAvailable(tunnel, specifiedInPort, excludeForwardId)) {
                return PortAllocation.error("指定的入口端口 " + specifiedInPort + " 已被占用或不在允许范围内");
            }
            inPort = specifiedInPort;
        } else {
            // 用户未指定端口时自动分配
            inPort = allocateInPort(tunnel, excludeForwardId);
            if (inPort == null) {
                return PortAllocation.error("隧道入口端口已满，无法分配新端口");
            }
        }

        Integer outPort = null;
        if (tunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD) {
            outPort = allocateOutPort(tunnel, excludeForwardId);
            if (outPort == null) {
                return PortAllocation.error("隧道出口端口已满，无法分配新端口");
            }
        }

        return PortAllocation.success(inPort, outPort);
    }

    /**
     * 创建Forward实体对象
     */
    private Forward createForwardEntity(ForwardDto forwardDto, UserInfo currentUser, PortAllocation portAllocation) {
        Forward forward = new Forward();
        // 先复制DTO的属性，再设置其他属性，避免被覆盖
        BeanUtils.copyProperties(forwardDto, forward);
        forward.setStatus(FORWARD_STATUS_ACTIVE);
        forward.setInPort(portAllocation.getInPort());
        forward.setOutPort(portAllocation.getOutPort());
        forward.setUserId(currentUser.getUserId());
        forward.setUserName(currentUser.getUserName());
        forward.setCreatedTime(System.currentTimeMillis());
        forward.setUpdatedTime(System.currentTimeMillis());
        forward.setMode(isBlank(forward.getMode()) ? "direct" : forward.getMode());
        forward.setChainStrategy(isBlank(forward.getChainStrategy()) ? "smart" : forward.getChainStrategy());
        forward.setChainHops(forward.getChainHops() == null ? 0 : forward.getChainHops());
        forward.setBandwidthMode(isBlank(forward.getBandwidthMode()) ? "none" : forward.getBandwidthMode());
        forward.setBandwidthUp(forward.getBandwidthUp() == null ? 0L : forward.getBandwidthUp());
        forward.setBandwidthDown(forward.getBandwidthDown() == null ? 0L : forward.getBandwidthDown());
        forward.setBandwidthCombined(forward.getBandwidthCombined() == null ? 0L : forward.getBandwidthCombined());
        forward.setMaxSourceIps(forward.getMaxSourceIps() == null ? 0 : forward.getMaxSourceIps());
        forward.setMaxConnPerIp(forward.getMaxConnPerIp() == null ? 0 : forward.getMaxConnPerIp());
        forward.setExpireAt(forward.getExpireAt() == null ? 0L : forward.getExpireAt());
        return forward;
    }

    /**
     * 更新Forward实体对象
     */
    private Forward updateForwardEntity(ForwardUpdateDto forwardUpdateDto, Forward existForward, Tunnel tunnel) {
        Forward forward = new Forward();
        BeanUtils.copyProperties(forwardUpdateDto, forward);

        // 处理端口分配逻辑
        boolean tunnelChanged = !existForward.getTunnelId().equals(forwardUpdateDto.getTunnelId());
        boolean inPortChanged = forwardUpdateDto.getInPort() != null &&
                !Objects.equals(forwardUpdateDto.getInPort(), existForward.getInPort());

        if (tunnelChanged || inPortChanged) {
            // 隧道变化或入口端口变化时需要重新分配
            Integer specifiedInPort = forwardUpdateDto.getInPort();
            // 如果没有指定新端口但隧道未变化，保持原端口
            if (specifiedInPort == null && !tunnelChanged) {
                specifiedInPort = existForward.getInPort();
            }

            PortAllocation portAllocation = allocatePorts(tunnel, specifiedInPort, forwardUpdateDto.getId());
            if (portAllocation.isHasError()) {
                throw new RuntimeException(portAllocation.getErrorMessage());
            }
            forward.setInPort(portAllocation.getInPort());
            forward.setOutPort(portAllocation.getOutPort());
        } else {
            // 隧道和端口都未变化，保持原端口
            forward.setInPort(existForward.getInPort());
            forward.setOutPort(existForward.getOutPort());
        }

        forward.setUpdatedTime(System.currentTimeMillis());
        forward.setMode(isBlank(forward.getMode()) ? "direct" : forward.getMode());
        forward.setChainStrategy(isBlank(forward.getChainStrategy()) ? "smart" : forward.getChainStrategy());
        forward.setChainHops(forward.getChainHops() == null ? 0 : forward.getChainHops());
        forward.setBandwidthMode(isBlank(forward.getBandwidthMode()) ? "none" : forward.getBandwidthMode());
        forward.setBandwidthUp(forward.getBandwidthUp() == null ? 0L : forward.getBandwidthUp());
        forward.setBandwidthDown(forward.getBandwidthDown() == null ? 0L : forward.getBandwidthDown());
        forward.setBandwidthCombined(forward.getBandwidthCombined() == null ? 0L : forward.getBandwidthCombined());
        forward.setMaxSourceIps(forward.getMaxSourceIps() == null ? 0 : forward.getMaxSourceIps());
        forward.setMaxConnPerIp(forward.getMaxConnPerIp() == null ? 0 : forward.getMaxConnPerIp());
        forward.setExpireAt(forward.getExpireAt() == null ? 0L : forward.getExpireAt());
        return forward;
    }

    /**
     * 创建Gost服务
     */
    private R createGostServices(Forward forward, Tunnel tunnel, Integer limiter, NodeInfo nodeInfo, UserTunnel userTunnel) {
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);

        List<Tunnel> endpointTunnels = getEndpointTunnels(forward);
        boolean useEndpointRoute = !endpointTunnels.isEmpty();

        // 隧道转发：入口创建多跳链，中继创建 relay，所有出口创建目标服务。
        if (tunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD && !useEndpointRoute) {
            R routeResult = createRouteServices(tunnel, forward, nodeInfo.getInNode(), serviceName);
            if (routeResult.getCode() != 0) return routeResult;
        }
        if (useEndpointRoute) {
            R routeResult = createEndpointRouteServices(forward, endpointTunnels, nodeInfo.getInNode(), serviceName);
            if (routeResult.getCode() != 0) return routeResult;
        }

        String interfaceName = null;
        // 创建主服务
        if (tunnel.getType() != TUNNEL_TYPE_TUNNEL_FORWARD || useEndpointRoute) { // 不是隧道转发服务才会存在网络接口
            interfaceName = forward.getInterfaceName();
        }

        Integer serviceType = useEndpointRoute ? TUNNEL_TYPE_FORWARD_ENDPOINT : tunnel.getType();
        R serviceResult = createMainService(nodeInfo.getInNode(), serviceName, forward, limiter, serviceType, tunnel, forward.getStrategy(), interfaceName);
        if (serviceResult.getCode() != 0) {
            GostUtil.DeleteChains(nodeInfo.getInNode().getId(), serviceName);
            if (nodeInfo.getOutNode() != null) {
                GostUtil.DeleteRemoteService(nodeInfo.getOutNode().getId(), serviceName);
            }
            deleteEndpointRouteServices(forward, serviceName);
            return serviceResult;
        }
        return R.ok();
    }

    /**
     * 更新Gost服务
     */
    private R updateGostServices(Forward forward, Tunnel tunnel, Integer limiter, NodeInfo nodeInfo, UserTunnel userTunnel) {
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);
        List<Tunnel> endpointTunnels = getEndpointTunnels(forward);
        boolean useEndpointRoute = !endpointTunnels.isEmpty();

        // 路由拓扑或权重变更时使用删除后重建，避免 agent 中残留旧 hop。
        if (tunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD && !useEndpointRoute) {
            deleteRouteServices(tunnel, serviceName);
            R routeResult = createRouteServices(tunnel, forward, nodeInfo.getInNode(), serviceName);
            if (routeResult.getCode() != 0) {
                updateForwardStatusToError(forward);
                return routeResult;
            }
        }
        if (useEndpointRoute) {
            deleteEndpointRouteServices(forward, serviceName);
            R routeResult = createEndpointRouteServices(forward, endpointTunnels, nodeInfo.getInNode(), serviceName);
            if (routeResult.getCode() != 0) {
                updateForwardStatusToError(forward);
                return routeResult;
            }
        }
        String interfaceName = null;
        // 创建主服务
        if (tunnel.getType() != TUNNEL_TYPE_TUNNEL_FORWARD || useEndpointRoute) { // 不是隧道转发服务才会存在网络接口
            interfaceName = forward.getInterfaceName();
        }
        Integer serviceType = useEndpointRoute ? TUNNEL_TYPE_FORWARD_ENDPOINT : tunnel.getType();
        // 更新主服务
        R serviceResult = updateMainService(nodeInfo.getInNode(), serviceName, forward, limiter, serviceType, tunnel, forward.getStrategy(), interfaceName);
        if (serviceResult.getCode() != 0) {
            updateForwardStatusToError(forward);
            return serviceResult;
        }

        return R.ok();
    }

    /**
     * 隧道变化时更新Gost服务：先删除原配置，再创建新配置
     */
    private R updateGostServicesWithTunnelChange(Forward existForward, Forward updatedForward, Tunnel newTunnel, Integer limiter, NodeInfo nodeInfo, UserTunnel userTunnel) {
        // 1. 获取原隧道信息
        Tunnel oldTunnel = tunnelService.getById(existForward.getTunnelId());
        if (oldTunnel == null) {
            return R.err("原隧道不存在，无法删除旧配置");
        }

        // 2. 删除原有的Gost服务配置
        R deleteResult = deleteOldGostServices(existForward, oldTunnel);
        if (deleteResult.getCode() != 0) {
            // 删除失败时记录日志，但不影响后续创建（可能原配置已不存在）
            log.info("删除原隧道{}的Gost配置失败: {}", oldTunnel.getId(), deleteResult.getMsg());
        }

        // 3. 创建新的Gost服务配置
        R createResult = createGostServices(updatedForward, newTunnel, limiter, nodeInfo, userTunnel);
        if (createResult.getCode() != 0) {
            updateForwardStatusToError(updatedForward);
            return R.err("创建新隧道配置失败: " + createResult.getMsg());
        }

        return R.ok();
    }

    /**
     * 删除原有的Gost服务（隧道变化时专用）
     */
    private R deleteOldGostServices(Forward forward, Tunnel oldTunnel) {
        // 获取原隧道的用户隧道关系
        UserTunnel oldUserTunnel = getUserTunnel(forward.getUserId(), oldTunnel.getId().intValue());
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), oldUserTunnel);

        // 获取原隧道的节点信息
        NodeInfo oldNodeInfo = getRequiredNodes(oldTunnel);

        // 删除主服务（使用原隧道的入口节点）
        if (!oldNodeInfo.isHasError() && oldNodeInfo.getInNode() != null) {
            GostDto serviceResult = GostUtil.DeleteService(oldNodeInfo.getInNode().getId(), serviceName);
            if (!isGostOperationSuccess(serviceResult)) {
                log.info("删除主服务失败: {}", serviceResult.getMsg());
            }
        }

        // 如果原隧道是隧道转发类型，需要删除链和远程服务
        if (oldTunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD) {
            deleteRouteServices(oldTunnel, serviceName);
        }
        deleteEndpointRouteServices(forward, serviceName);

        return R.ok();
    }

    /**
     * 删除Gost服务
     */
    private R deleteGostServices(Forward forward, Tunnel tunnel, NodeInfo nodeInfo, UserTunnel userTunnel) {
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);

        // 删除主服务
        GostDto serviceResult = GostUtil.DeleteService(nodeInfo.getInNode().getId(), serviceName);
        if (!isGostOperationSuccess(serviceResult)) {
            return R.err(serviceResult.getMsg());
        }

        // 隧道转发需要删除链和远程服务
        if (tunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD) {
            deleteRouteServices(tunnel, serviceName);
        }
        deleteEndpointRouteServices(forward, serviceName);

        return R.ok();
    }

    private void deleteRouteServices(Tunnel tunnel, String serviceName) {
        if (tunnel.getInNodeId() != null) GostUtil.DeleteChains(tunnel.getInNodeId(), serviceName);
        for (Long nodeId : getRouteNodeIds(tunnel)) GostUtil.DeleteRemoteService(nodeId, serviceName);
    }

    private R createRouteServices(Tunnel tunnel, Forward forward, Node inNode, String serviceName) {
        List<Long> chainIds = parseCsvNodeIds(tunnel.getChainNodeIds());
        List<Long> configuredOutputIds = parseCsvNodeIds(tunnel.getOutNodeIds());
        if (configuredOutputIds.isEmpty() && tunnel.getOutNodeId() != null) configuredOutputIds.add(tunnel.getOutNodeId());
        List<Integer> configuredWeights = parseCsvWeights(tunnel.getOutNodeWeights(), configuredOutputIds.size());
        List<Long> outputIds = new ArrayList<>();
        List<Integer> outputWeights = new ArrayList<>();
        for (int i = 0; i < configuredOutputIds.size(); i++) {
            Node candidate = nodeService.getNodeById(configuredOutputIds.get(i));
            if (candidate != null && candidate.getStatus() == 1) {
                outputIds.add(candidate.getId());
                outputWeights.add(configuredWeights.get(i));
            }
        }
        if (outputIds.isEmpty()) return R.err("所有出口节点均离线");
        List<Long> createdRelays = new ArrayList<>();
        try {
            for (Long nodeId : chainIds) {
                GostDto result = GostUtil.AddRelayService(nodeId, serviceName, forward.getOutPort(), tunnel.getProtocol(), null);
                if (!isGostOperationSuccess(result)) throw new IllegalStateException("中继服务创建失败: " + result.getMsg());
                createdRelays.add(nodeId);
            }
            for (Long nodeId : outputIds) {
                Node outNode = nodeService.getNodeById(nodeId);
                if (outNode == null) throw new IllegalStateException("出口节点不存在: " + nodeId);
                R result = createRemoteService(outNode, serviceName, forward, tunnel.getProtocol(), forward.getInterfaceName());
                if (result.getCode() != 0) throw new IllegalStateException(result.getMsg());
                createdRelays.add(nodeId);
            }
            List<String> hops = new ArrayList<>();
            for (Long nodeId : chainIds) {
                Node node = nodeService.getNodeById(nodeId);
                hops.add(formatAddress(node.getServerIp(), forward.getOutPort()));
            }
            String outputAddresses = outputIds.stream().map(nodeService::getNodeById)
                    .filter(Objects::nonNull).map(n -> formatAddress(n.getServerIp(), forward.getOutPort()))
                    .collect(Collectors.joining(","));
            hops.add(outputAddresses);
            GostDto chainResult = GostUtil.AddChains(inNode.getId(), serviceName, hops, tunnel.getProtocol(),
                    tunnel.getInterfaceName(), tunnel.getBalanceStrategy(), outputWeights,
                    tunnel.getMaxFails(), tunnel.getFailTimeout());
            if (!isGostOperationSuccess(chainResult)) throw new IllegalStateException(chainResult.getMsg());
            return R.ok();
        } catch (Exception e) {
            GostUtil.DeleteChains(inNode.getId(), serviceName);
            for (Long nodeId : createdRelays) GostUtil.DeleteRemoteService(nodeId, serviceName);
            GostUtil.DeleteService(inNode.getId(), serviceName);
            return R.err("转发链创建失败: " + e.getMessage());
        }
    }

    private List<Long> parseCsvNodeIds(String csv) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        appendNodeIds(ids, csv);
        return new ArrayList<>(ids);
    }

    private List<Tunnel> getEndpointTunnels(Forward forward) {
        List<Tunnel> result = new ArrayList<>();
        if (forward == null || "direct".equals(forward.getMode()) || isBlank(forward.getTunnelIds())) return result;
        for (String value : forward.getTunnelIds().split(",")) {
            try {
                Tunnel tunnel = tunnelService.getById(Long.parseLong(value.trim()));
                if (tunnel != null && tunnel.getType() == TUNNEL_TYPE_FORWARD_ENDPOINT && tunnel.getStatus() == TUNNEL_STATUS_ACTIVE) {
                    result.add(tunnel);
                }
            } catch (NumberFormatException ignored) { }
        }
        return result;
    }

    private List<Long> applyChainSelection(Tunnel tunnel, Forward forward) {
        List<Long> chainIds = parseCsvNodeIds(tunnel.getChainNodeIds());
        if (chainIds.isEmpty() || forward.getChainHops() == null || forward.getChainHops() <= 0) return chainIds;
        int count = Math.min(forward.getChainHops(), chainIds.size());
        if ("fixed_first".equals(forward.getChainStrategy())) return new ArrayList<>(chainIds.subList(0, count));
        if ("fixed_last".equals(forward.getChainStrategy())) return new ArrayList<>(chainIds.subList(chainIds.size() - count, chainIds.size()));
        return chainIds;
    }

    private R createEndpointRouteServices(Forward forward, List<Tunnel> endpointTunnels, Node inNode, String serviceName) {
        List<Long> chainIds = new ArrayList<>();
        List<List<Long>> outputGroups = new ArrayList<>();
        List<List<Integer>> weightGroups = new ArrayList<>();
        String protocol = null;
        Integer maxFails = 1;
        Integer failTimeout = 30;
        String interfaceName = null;

        for (int i = 0; i < endpointTunnels.size(); i++) {
            Tunnel endpoint = endpointTunnels.get(i);
            if (protocol == null && org.apache.commons.lang3.StringUtils.isNotBlank(endpoint.getProtocol())) protocol = endpoint.getProtocol();
            if (endpoint.getMaxFails() != null) maxFails = endpoint.getMaxFails();
            if (endpoint.getFailTimeout() != null) failTimeout = endpoint.getFailTimeout();
            if (i == 0 && org.apache.commons.lang3.StringUtils.isNotBlank(endpoint.getInterfaceName())) interfaceName = endpoint.getInterfaceName();

            List<Long> configuredOutputs = parseCsvNodeIds(endpoint.getOutNodeIds());
            if (configuredOutputs.isEmpty() && endpoint.getOutNodeId() != null) configuredOutputs.add(endpoint.getOutNodeId());
            List<Integer> configuredWeights = parseCsvWeights(endpoint.getOutNodeWeights(), configuredOutputs.size());
            List<Long> outputIds = new ArrayList<>();
            List<Integer> outputWeights = new ArrayList<>();
            for (int j = 0; j < configuredOutputs.size(); j++) {
                Node candidate = nodeService.getNodeById(configuredOutputs.get(j));
                if (candidate != null && candidate.getStatus() == 1) {
                    outputIds.add(candidate.getId());
                    outputWeights.add(configuredWeights.get(j));
                }
            }
            if (outputIds.isEmpty()) return R.err("转发端点「" + endpoint.getName() + "」没有可用出口节点");
            outputGroups.add(outputIds);
            weightGroups.add(outputWeights);
        }

        // 单跳转发只使用第一个转发端点组的出口节点，不创建中继链。
        if ("single".equals(forward.getMode())) {
            outputGroups = outputGroups.subList(0, 1);
            weightGroups = weightGroups.subList(0, 1);
        } else {
            for (Tunnel endpoint : endpointTunnels) chainIds.addAll(applyChainSelection(endpoint, forward));
        }

        List<Long> createdRelays = new ArrayList<>();
        try {
            for (Long nodeId : chainIds) {
                GostDto result = GostUtil.AddRelayService(nodeId, serviceName, forward.getOutPort(),
                        protocol == null ? "tls" : protocol, null);
                if (!isGostOperationSuccess(result)) throw new IllegalStateException("中继服务创建失败: " + result.getMsg());
                createdRelays.add(nodeId);
            }
            for (List<Long> outputIds : outputGroups) {
                for (Long nodeId : outputIds) {
                    Node outNode = nodeService.getNodeById(nodeId);
                    if (outNode == null) throw new IllegalStateException("出口节点不存在: " + nodeId);
                    R result = createRemoteService(outNode, serviceName, forward, protocol == null ? "tls" : protocol, interfaceName);
                    if (result.getCode() != 0) throw new IllegalStateException(result.getMsg());
                    createdRelays.add(nodeId);
                }
            }

            List<String> hops = new ArrayList<>();
            List<List<Integer>> hopWeights = new ArrayList<>();
            for (Long nodeId : chainIds) {
                Node node = nodeService.getNodeById(nodeId);
                hops.add(formatAddress(node.getServerIp(), forward.getOutPort()));
                hopWeights.add(Collections.singletonList(1));
            }
            for (int i = 0; i < outputGroups.size(); i++) {
                List<Long> outputIds = outputGroups.get(i);
                String outputAddresses = outputIds.stream().map(nodeService::getNodeById)
                        .filter(Objects::nonNull).map(n -> formatAddress(n.getServerIp(), forward.getOutPort()))
                        .collect(Collectors.joining(","));
                hops.add(outputAddresses);
                hopWeights.add(weightGroups.get(i));
            }

            GostDto chainResult = GostUtil.AddChainsWithHopWeights(inNode.getId(), serviceName, hops,
                    protocol == null ? "tls" : protocol, interfaceName, forward.getStrategy(),
                    hopWeights, maxFails, failTimeout);
            if (!isGostOperationSuccess(chainResult)) throw new IllegalStateException(chainResult.getMsg());
            return R.ok();
        } catch (Exception e) {
            GostUtil.DeleteChains(inNode.getId(), serviceName);
            for (Long nodeId : createdRelays) GostUtil.DeleteRemoteService(nodeId, serviceName);
            GostUtil.DeleteService(inNode.getId(), serviceName);
            return R.err("转发端点链路创建失败: " + e.getMessage());
        }
    }

    private void deleteEndpointRouteServices(Forward forward, String serviceName) {
        List<Tunnel> endpointTunnels = getEndpointTunnels(forward);
        if (endpointTunnels.isEmpty()) return;
        Tunnel primary = tunnelService.getById(forward.getTunnelId());
        if (primary != null && primary.getInNodeId() != null) GostUtil.DeleteChains(primary.getInNodeId(), serviceName);
        Set<Long> nodes = new LinkedHashSet<>();
        for (Tunnel endpoint : endpointTunnels) {
            nodes.addAll(applyChainSelection(endpoint, forward));
            nodes.addAll(parseCsvNodeIds(endpoint.getOutNodeIds()));
            if (endpoint.getOutNodeId() != null) nodes.add(endpoint.getOutNodeId());
        }
        for (Long nodeId : nodes) GostUtil.DeleteRemoteService(nodeId, serviceName);
    }

    private List<Integer> parseCsvWeights(String csv, int count) {
        List<Integer> weights = new ArrayList<>();
        String[] values = org.apache.commons.lang3.StringUtils.defaultString(csv).split(",");
        for (int i = 0; i < count; i++) {
            int weight = 1;
            if (i < values.length) try { weight = Integer.parseInt(values[i].trim()); } catch (Exception ignored) { }
            weights.add(Math.max(1, Math.min(weight, 100)));
        }
        return weights;
    }

    private String formatAddress(String ip, Integer port) {
        return ip != null && ip.contains(":") ? "[" + ip + "]:" + port : ip + ":" + port;
    }

    /**
     * 创建链服务
     */
    private R createChainService(Node inNode, String serviceName, String outIp, Integer outPort, String protocol, String interfaceName) {
        String remoteAddr = outIp + ":" + outPort;
        if (outIp.contains(":")) {
            remoteAddr = "[" + outIp + "]:" + outPort;
        }
        GostDto result = GostUtil.AddChains(inNode.getId(), serviceName, remoteAddr, protocol, interfaceName);
        return isGostOperationSuccess(result) ? R.ok() : R.err(result.getMsg());
    }

    /**
     * 创建远程服务
     */
    private R createRemoteService(Node outNode, String serviceName, Forward forward, String protocol, String interfaceName) {
        GostDto result = GostUtil.AddRemoteService(outNode.getId(), serviceName, forward.getOutPort(), forward.getRemoteAddr(), protocol,
                forward.getStrategy(), interfaceName, forward.getTargetWeights(), 1, 30);
        return isGostOperationSuccess(result) ? R.ok() : R.err(result.getMsg());
    }

    /**
     * 创建主服务
     */
    private R createMainService(Node inNode, String serviceName, Forward forward, Integer limiter, Integer tunnelType, Tunnel tunnel, String strategy, String interfaceName) {
        GostDto result = GostUtil.AddService(inNode.getId(), serviceName, forward.getInPort(), limiter, forward.getRemoteAddr(), tunnelType,
                tunnel, strategy, interfaceName, forward.getTargetWeights(), buildForwardMeta(forward, tunnel));
        return isGostOperationSuccess(result) ? R.ok() : R.err(result.getMsg());
    }

    private JSONObject buildForwardMeta(Forward forward, Tunnel tunnel) {
        JSONObject meta = new JSONObject();
        meta.put("flux_forward_id", forward.getId());
        meta.put("flux_target", forward.getRemoteAddr());
        meta.put("flux_real_target", forward.getRemoteAddr());
        meta.put("flux_entry_server", org.apache.commons.lang3.StringUtils.defaultIfBlank(tunnel.getInIp(), tunnel.getName()));
        meta.put("flux_forwarder", "entry-" + forward.getName());
        meta.put("flux_bandwidth_mode", org.apache.commons.lang3.StringUtils.defaultIfBlank(forward.getBandwidthMode(), "none"));
        meta.put("flux_bandwidth_up", toBytesPerSecond(forward.getBandwidthUp()));
        meta.put("flux_bandwidth_down", toBytesPerSecond(forward.getBandwidthDown()));
        meta.put("flux_bandwidth_combined", toBytesPerSecond(forward.getBandwidthCombined()));
        meta.put("flux_max_source_ips", forward.getMaxSourceIps() == null ? 0 : forward.getMaxSourceIps());
        meta.put("flux_max_conn_per_ip", forward.getMaxConnPerIp() == null ? 0 : forward.getMaxConnPerIp());
        meta.put("flux_expire_at", forward.getExpireAt() == null ? 0L : forward.getExpireAt());
        return meta;
    }

    private long toBytesPerSecond(Long mbPerSecond) {
        if (mbPerSecond == null || mbPerSecond <= 0) return 0;
        return mbPerSecond * 1024L * 1024L;
    }

    /**
     * 更新链服务
     */
    private R updateChainService(Node inNode, String serviceName, String outIp, Integer outPort, String protocol, String interfaceName) {
        // 创建新链
        String remoteAddr = outIp + ":" + outPort;
        if (outIp.contains(":")) {
            remoteAddr = "[" + outIp + "]:" + outPort;
        }
        GostDto createResult = GostUtil.UpdateChains(inNode.getId(), serviceName, remoteAddr, protocol, interfaceName);
        if (createResult.getMsg().contains(GOST_NOT_FOUND_MSG)) {
            createResult = GostUtil.AddChains(inNode.getId(), serviceName, remoteAddr, protocol, interfaceName);
        }
        return isGostOperationSuccess(createResult) ? R.ok() : R.err(createResult.getMsg());
    }

    /**
     * 更新远程服务
     */
    private R updateRemoteService(Node outNode, String serviceName, Forward forward, String protocol, String interfaceName) {
        // 创建新远程服务
        GostDto createResult = GostUtil.UpdateRemoteService(outNode.getId(), serviceName, forward.getOutPort(), forward.getRemoteAddr(), protocol,
                forward.getStrategy(), interfaceName, forward.getTargetWeights(), 1, 30);
        if (createResult.getMsg().contains(GOST_NOT_FOUND_MSG)) {
            createResult = GostUtil.AddRemoteService(outNode.getId(), serviceName, forward.getOutPort(), forward.getRemoteAddr(), protocol, forward.getStrategy(), interfaceName);
        }
        return isGostOperationSuccess(createResult) ? R.ok() : R.err(createResult.getMsg());
    }

    /**
     * 更新主服务
     */
    private R updateMainService(Node inNode, String serviceName, Forward forward, Integer limiter, Integer tunnelType, Tunnel tunnel, String strategy, String interfaceName) {
        GostDto result = GostUtil.UpdateService(inNode.getId(), serviceName, forward.getInPort(), limiter, forward.getRemoteAddr(), tunnelType,
                tunnel, strategy, interfaceName, forward.getTargetWeights(), buildForwardMeta(forward, tunnel));

        if (result.getMsg().contains(GOST_NOT_FOUND_MSG)) {
            result = GostUtil.AddService(inNode.getId(), serviceName, forward.getInPort(), limiter, forward.getRemoteAddr(), tunnelType,
                    tunnel, strategy, interfaceName, forward.getTargetWeights(), buildForwardMeta(forward, tunnel));
        }

        return isGostOperationSuccess(result) ? R.ok() : R.err(result.getMsg());
    }

    /**
     * 更新转发状态为错误
     */
    private void updateForwardStatusToError(Forward forward) {
        forward.setStatus(FORWARD_STATUS_ERROR);
        this.updateById(forward);
    }

    /**
     * 获取用户隧道关系
     */
    private UserTunnel getUserTunnel(Integer userId, Integer tunnelId) {
        return userTunnelService.getOne(new QueryWrapper<UserTunnel>()
                .eq("user_id", userId)
                .eq("tunnel_id", tunnelId));
    }

    /**
     * 检查隧道是否发生变化
     */
    private boolean isTunnelChanged(Forward existForward, ForwardUpdateDto updateDto) {
        return !existForward.getTunnelId().equals(updateDto.getTunnelId());
    }

    /**
     * 检查Gost操作是否成功
     */
    private boolean isGostOperationSuccess(GostDto gostResult) {
        return Objects.equals(gostResult.getMsg(), GOST_SUCCESS_MSG);
    }


    /**
     * 检查指定的入口端口是否可用（可排除指定的转发ID）
     */
    private boolean isInPortAvailable(Tunnel tunnel, Integer port, Long excludeForwardId) {
        // 获取入口节点信息
        Node inNode = nodeService.getNodeById(tunnel.getInNodeId());
        if (inNode == null) {
            return false;
        }

        // 检查端口是否在节点允许的范围内
        if (port < inNode.getPortSta() || port > inNode.getPortEnd()) {
            return false;
        }

        // 获取该节点上所有已被占用的端口（包括作为入口和出口使用的端口）
        Set<Integer> usedPorts = getAllUsedPortsOnNode(tunnel.getInNodeId(), excludeForwardId);

        // 检查端口是否已被占用（在节点级别检查，考虑入口和出口端口）
        return !usedPorts.contains(port);
    }

    /**
     * 为隧道分配一个可用的入口端口（可排除指定的转发ID）
     */
    private Integer allocateInPort(Tunnel tunnel, Long excludeForwardId) {
        return allocatePortForNode(tunnel.getInNodeId(), excludeForwardId);
    }

    /**
     * 为隧道分配一个可用的出口端口（可排除指定的转发ID）
     */
    private Integer allocateOutPort(Tunnel tunnel, Long excludeForwardId) {
        List<Long> routeNodes = getRouteNodeIds(tunnel);
        if (routeNodes.isEmpty()) return null;
        int start = 1;
        int end = 65535;
        Map<Long, Set<Integer>> usedByNode = new HashMap<>();
        for (Long nodeId : routeNodes) {
            Node node = nodeService.getNodeById(nodeId);
            if (node == null) return null;
            start = Math.max(start, node.getPortSta());
            end = Math.min(end, node.getPortEnd());
            usedByNode.put(nodeId, getAllUsedPortsOnNode(nodeId, excludeForwardId));
        }
        for (int port = start; port <= end; port++) {
            final int candidate = port;
            if (usedByNode.values().stream().noneMatch(used -> used.contains(candidate))) return port;
        }
        return null;
    }

    /**
     * 为指定节点分配一个可用端口（通用方法）
     *
     * @param nodeId           节点ID
     * @param excludeForwardId 要排除的转发ID
     * @return 可用端口号，如果没有可用端口则返回null
     */
    private Integer allocatePortForNode(Long nodeId, Long excludeForwardId) {
        // 获取节点信息
        Node node = nodeService.getNodeById(nodeId);
        if (node == null) {
            return null;
        }

        // 获取该节点上所有已被占用的端口（包括作为入口和出口使用的端口）
        Set<Integer> usedPorts = getAllUsedPortsOnNode(nodeId, excludeForwardId);

        // 在节点端口范围内寻找未使用的端口
        for (int port = node.getPortSta(); port <= node.getPortEnd(); port++) {
            if (!usedPorts.contains(port)) {
                return port;
            }
        }
        return null;
    }

    /**
     * 获取指定节点上所有已被占用的端口（包括入口和出口端口）
     *
     * @param nodeId           节点ID
     * @param excludeForwardId 要排除的转发ID
     * @return 已占用的端口集合
     */
    private List<Long> getRouteNodeIds(Tunnel tunnel) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        appendNodeIds(ids, tunnel.getChainNodeIds());
        appendNodeIds(ids, tunnel.getOutNodeIds());
        if (ids.isEmpty() && tunnel.getOutNodeId() != null) ids.add(tunnel.getOutNodeId());
        return new ArrayList<>(ids);
    }

    private void appendNodeIds(Set<Long> ids, String csv) {
        if (org.apache.commons.lang3.StringUtils.isBlank(csv)) return;
        for (String value : csv.split(",")) {
            try { ids.add(Long.parseLong(value.trim())); } catch (NumberFormatException ignored) { }
        }
    }

    private Set<Integer> getAllUsedPortsOnNode(Long nodeId, Long excludeForwardId) {
        Set<Integer> usedPorts = new HashSet<>();

        // 1. 收集该节点作为入口时占用的端口
        List<Tunnel> inTunnels = tunnelService.list(new QueryWrapper<Tunnel>().eq("in_node_id", nodeId));
        if (!inTunnels.isEmpty()) {
            Set<Long> inTunnelIds = inTunnels.stream()
                    .map(Tunnel::getId)
                    .collect(Collectors.toSet());

            QueryWrapper<Forward> inQueryWrapper = new QueryWrapper<Forward>().in("tunnel_id", inTunnelIds);
            if (excludeForwardId != null) {
                inQueryWrapper.ne("id", excludeForwardId);
            }

            List<Forward> inForwards = this.list(inQueryWrapper);
            for (Forward forward : inForwards) {
                if (forward.getInPort() != null) {
                    usedPorts.add(forward.getInPort());
                }
            }
        }

        // 2. 收集该节点作为出口时占用的端口
        List<Tunnel> outTunnels = tunnelService.list(new QueryWrapper<Tunnel>().eq("out_node_id", nodeId));
        if (!outTunnels.isEmpty()) {
            Set<Long> outTunnelIds = outTunnels.stream()
                    .map(Tunnel::getId)
                    .collect(Collectors.toSet());

            QueryWrapper<Forward> outQueryWrapper = new QueryWrapper<Forward>().in("tunnel_id", outTunnelIds);
            if (excludeForwardId != null) {
                outQueryWrapper.ne("id", excludeForwardId);
            }

            List<Forward> outForwards = this.list(outQueryWrapper);
            for (Forward forward : outForwards) {
                if (forward.getOutPort() != null) {
                    usedPorts.add(forward.getOutPort());
                }
            }
        }

        List<Tunnel> routeTunnels = tunnelService.list(new QueryWrapper<Tunnel>()
                .like("chain_node_ids", String.valueOf(nodeId)).or().like("out_node_ids", String.valueOf(nodeId)));
        if (!routeTunnels.isEmpty()) {
            Set<Long> routeTunnelIds = routeTunnels.stream().filter(t -> getRouteNodeIds(t).contains(nodeId))
                    .map(Tunnel::getId).collect(Collectors.toSet());
            if (!routeTunnelIds.isEmpty()) {
                QueryWrapper<Forward> routeQuery = new QueryWrapper<Forward>().in("tunnel_id", routeTunnelIds);
                if (excludeForwardId != null) routeQuery.ne("id", excludeForwardId);
                for (Forward forward : this.list(routeQuery)) if (forward.getOutPort() != null) usedPorts.add(forward.getOutPort());
            }
        }
        return usedPorts;
    }


    /**
     * 构建服务名称，优化后减少重复查询
     */
    private String buildServiceName(Long forwardId, Integer userId, UserTunnel userTunnel) {
        int userTunnelId = (userTunnel != null) ? userTunnel.getId() : 0;
        return forwardId + "_" + userId + "_" + userTunnelId;
    }


    @Override
    public R rebuildForwardRoute(Forward forward, Tunnel oldTunnel, Tunnel newTunnel) {
        UserTunnel userTunnel = getUserTunnel(forward.getUserId(), newTunnel.getId().intValue());
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);
        Node oldInNode = nodeService.getNodeById(oldTunnel.getInNodeId());
        if (oldInNode != null) GostUtil.DeleteService(oldInNode.getId(), serviceName);
        deleteRouteServices(oldTunnel, serviceName);
        if (forward.getStatus() != FORWARD_STATUS_ACTIVE) return R.ok();
        NodeInfo newNodes = getRequiredNodes(newTunnel);
        if (newNodes.isHasError()) return R.err(newNodes.getErrorMessage());
        Integer limiter = userTunnel == null ? null : userTunnel.getSpeedId();
        return createGostServices(forward, newTunnel, limiter, newNodes, userTunnel);
    }

    @Override
    public R rebuildForwardRouteForEndpoint(Forward forward, Tunnel oldEndpoint, Tunnel newEndpoint, Tunnel primaryTunnel) {
        UserTunnel userTunnel = getUserTunnel(forward.getUserId(), primaryTunnel.getId().intValue());
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);
        Node oldInNode = nodeService.getNodeById(primaryTunnel.getInNodeId());
        if (oldInNode != null) GostUtil.DeleteService(oldInNode.getId(), serviceName);

        Forward oldForward = new Forward();
        BeanUtils.copyProperties(forward, oldForward);
        oldForward.setTunnelIds(oldEndpoint.getId().toString());
        oldForward.setMode(forward.getMode());
        deleteEndpointRouteServices(oldForward, serviceName);

        if (forward.getStatus() != FORWARD_STATUS_ACTIVE) return R.ok();
        NodeInfo nodeInfo = getRequiredNodes(primaryTunnel);
        if (nodeInfo.isHasError()) return R.err(nodeInfo.getErrorMessage());
        Integer limiter = userTunnel == null ? null : userTunnel.getSpeedId();
        return createGostServices(forward, primaryTunnel, limiter, nodeInfo, userTunnel);
    }

    public void updateForwardA(Forward forward) {
        Tunnel tunnel = validateTunnel(forward.getTunnelId());
        if (tunnel == null) {
            return;
        }
        UserTunnel userTunnel = getUserTunnel(forward.getUserId(), tunnel.getId().intValue());
        NodeInfo nodeInfo = getRequiredNodes(tunnel);
        if (nodeInfo.isHasError()) {
            return;
        }
        Integer limiter;
        if (userTunnel == null) {
            limiter = null;
        } else {
            limiter = userTunnel.getSpeedId();
        }
        updateGostServices(forward, tunnel, limiter, nodeInfo, userTunnel);
    }


    // ========== 内部数据类 ==========

    /**
     * 用户信息封装类
     */
    @Data
    private static class UserInfo {
        private final Integer userId;
        private final Integer roleId;
        private final String userName;
    }

    /**
     * 用户权限检查结果
     */
    @Data
    private static class UserPermissionResult {
        private final boolean hasError;
        private final String errorMessage;
        private final Integer limiter;
        private final UserTunnel userTunnel;

        private UserPermissionResult(boolean hasError, String errorMessage, Integer limiter, UserTunnel userTunnel) {
            this.hasError = hasError;
            this.errorMessage = errorMessage;
            this.limiter = limiter;
            this.userTunnel = userTunnel;
        }

        public static UserPermissionResult success(Integer limiter, UserTunnel userTunnel) {
            return new UserPermissionResult(false, null, limiter, userTunnel);
        }

        public static UserPermissionResult error(String errorMessage) {
            return new UserPermissionResult(true, errorMessage, null, null);
        }
    }

    /**
     * 端口分配结果
     */
    @Data
    private static class PortAllocation {
        private final boolean hasError;
        private final String errorMessage;
        private final Integer inPort;
        private final Integer outPort;

        private PortAllocation(boolean hasError, String errorMessage, Integer inPort, Integer outPort) {
            this.hasError = hasError;
            this.errorMessage = errorMessage;
            this.inPort = inPort;
            this.outPort = outPort;
        }

        public static PortAllocation success(Integer inPort, Integer outPort) {
            return new PortAllocation(false, null, inPort, outPort);
        }

        public static PortAllocation error(String errorMessage) {
            return new PortAllocation(true, errorMessage, null, null);
        }
    }

    /**
     * 节点信息封装类
     */
    @Data
    private static class NodeInfo {
        private final boolean hasError;
        private final String errorMessage;
        private final Node inNode;
        private final Node outNode;

        private NodeInfo(boolean hasError, String errorMessage, Node inNode, Node outNode) {
            this.hasError = hasError;
            this.errorMessage = errorMessage;
            this.inNode = inNode;
            this.outNode = outNode;
        }

        public static NodeInfo success(Node inNode, Node outNode) {
            return new NodeInfo(false, null, inNode, outNode);
        }

        public static NodeInfo error(String errorMessage) {
            return new NodeInfo(true, errorMessage, null, null);
        }
    }

    /**
     * 诊断结果数据类
     */
    @Data
    public static class DiagnosisResult {
        private Long nodeId;
        private String nodeName;
        private String targetIp;
        private Integer targetPort;
        private String description;
        private boolean success;
        private String message;
        private double averageTime;
        private double packetLoss;
        private long timestamp;
    }
}
