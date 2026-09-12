package com.admin.common.utils;

import com.admin.entity.Node;
import org.apache.commons.lang3.StringUtils;

/**
 * 节点通信地址工具。
 * 隧道路由中的 IP 模式只作用于中继/出口节点，不修改入口 IP。
 */
public final class NodeAddressUtils {

    public static final String IP_MODE_IPV4 = "ipv4";
    public static final String IP_MODE_IPV6 = "ipv6";
    public static final String IP_MODE_AUTO = "auto";

    private NodeAddressUtils() {
    }

    public static boolean isRouteIpMode(String value) {
        return StringUtils.isBlank(value)
                || IP_MODE_AUTO.equalsIgnoreCase(value)
                || IP_MODE_IPV4.equalsIgnoreCase(value)
                || IP_MODE_IPV6.equalsIgnoreCase(value);
    }

    public static boolean isIPv4(String value) {
        String text = StringUtils.trimToEmpty(value);
        if (text.isEmpty() || !text.contains(".")) return false;
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)) return false;
            int number;
            try {
                number = Integer.parseInt(part);
            } catch (NumberFormatException ignored) {
                return false;
            }
            if (number > 255 || (part.length() > 1 && part.charAt(0) == '0')) return false;
        }
        return true;
    }

    public static boolean isIPv6(String value) {
        String text = StringUtils.trimToEmpty(value);
        if (text.isEmpty()) return false;
        if (text.contains("%")) text = text.substring(0, text.indexOf('%'));
        if (text.isEmpty() || text.contains(".") || !text.contains(":")) return false;
        if (text.equals("::")) return true;
        String head = text;
        String tail = "";
        int zoneSplit = text.indexOf("::");
        if (zoneSplit >= 0) {
            head = text.substring(0, zoneSplit);
            tail = text.substring(zoneSplit + 2);
        }
        int headGroups = countGroups(head);
        int tailGroups = countGroups(tail);
        if (headGroups < 0 || tailGroups < 0) return false;
        if (zoneSplit >= 0) return headGroups + tailGroups < 8;
        return headGroups == 8;
    }

    private static int countGroups(String value) {
        if (value.isEmpty()) return 0;
        if (value.startsWith(":") || value.endsWith(":")) return -1;
        String[] groups = value.split(":", -1);
        int count = 0;
        for (String group : groups) {
            if (group.isEmpty() || group.length() > 4) return -1;
            if (!group.chars().allMatch(ch ->
                    (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F'))) return -1;
            count++;
        }
        return count;
    }

    public static boolean hasAddress(Node node, String requestedMode) {
        try {
            return StringUtils.isNotBlank(resolveServerAddress(node, requestedMode));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static String resolveServerAddress(Node node, String requestedMode) {
        if (node == null) throw new IllegalArgumentException("节点不存在");
        String mode = StringUtils.trimToEmpty(requestedMode).toLowerCase();

        if (IP_MODE_IPV4.equals(mode)) {
            String address = StringUtils.trimToEmpty(node.getServerIp4());
            if (address.isEmpty() && isIPv4(node.getServerIp())) address = StringUtils.trimToEmpty(node.getServerIp());
            if (address.isEmpty()) {
                throw new IllegalArgumentException("节点 [" + node.getName() + "] 未配置 IPv4 服务器地址");
            }
            return address;
        }

        if (IP_MODE_IPV6.equals(mode)) {
            String address = StringUtils.trimToEmpty(node.getServerIp6());
            if (address.isEmpty() && isIPv6(node.getServerIp())) address = StringUtils.trimToEmpty(node.getServerIp());
            if (address.isEmpty()) {
                throw new IllegalArgumentException("节点 [" + node.getName() + "] 未配置 IPv6 服务器地址");
            }
            return address;
        }

        // 未选择模式时保持旧行为：优先使用原 serverIp，兼容历史节点。
        String address = StringUtils.trimToEmpty(node.getServerIp());
        if (!address.isEmpty()) return address;
        address = StringUtils.trimToEmpty(node.getServerIp4());
        if (!address.isEmpty()) return address;
        address = StringUtils.trimToEmpty(node.getServerIp6());
        if (!address.isEmpty()) return address;
        throw new IllegalArgumentException("节点 [" + node.getName() + "] 未配置服务器地址");
    }

    public static String formatAddress(Node node, String requestedMode, Integer port) {
        return formatAddress(resolveServerAddress(node, requestedMode), port);
    }

    public static String formatAddress(String address, Integer port) {
        if (address == null) address = "";
        String text = address.trim();
        if (text.startsWith("[")) return text + ":" + port;
        if (text.contains(":")) return "[" + text + "]:" + port;
        return text + ":" + port;
    }
}
