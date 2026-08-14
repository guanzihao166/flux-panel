import { useEffect, useState } from 'react';
import { Button } from "@heroui/button";
import { Modal, ModalContent, ModalHeader, ModalBody, ModalFooter } from "@heroui/modal";
import toast from 'react-hot-toast';

import {
  getPendingAnnouncements,
  dismissAnnouncement,
  normalizeAnnouncementList,
  type Announcement,
} from '@/api';
import { isAdmin } from '@/utils/auth';

// sessionStorage：仅当前浏览器会话不再显示。
// safeLogout 已会清空 sessionStorage，浏览器关闭也会清空，因此退出登录再登录 / 重开会话后会再次弹出。
const SESSION_KEY_PREFIX = 'announcement_session_';
// localStorage：仅作为“不再显示”的本地缓存（权威记录在后端 dismissal，
// 后端 pending 接口不再返回已关闭的公告；此处缓存用于请求返回前的快速过滤）。
const NEVER_KEY_PREFIX = 'announcement_never_';

const isSessionDismissed = (id: number) => !!sessionStorage.getItem(SESSION_KEY_PREFIX + id);
const isNeverDismissed = (id: number) => !!localStorage.getItem(NEVER_KEY_PREFIX + id);

export default function AnnouncementPopup() {
  const [queue, setQueue] = useState<Announcement[]>([]);
  const [index, setIndex] = useState(0);
  const [dismissing, setDismissing] = useState(false);

  const current = queue[index] || null;

  useEffect(() => {
    let cancelled = false;

    const loadPending = async () => {
      // 管理员永不弹窗
      if (isAdmin()) return;

      try {
        const res = await getPendingAnnouncements();
        if (cancelled) return;
        if (res.code !== 0) return;

        // 仅展示本会话尚未处理过的公告，避免页面切换（布局重挂载）时重复弹出
        const pending = normalizeAnnouncementList(res.data).filter(
          (a) => a && a.id != null && !isSessionDismissed(a.id) && !isNeverDismissed(a.id)
        );
        if (pending.length === 0) return;

        setQueue(pending);
        setIndex(0);
      } catch (error) {
        // 请求失败时静默处理，不打扰用户
        console.warn('获取待读公告失败:', error);
      }
    };

    loadPending();
    return () => {
      cancelled = true;
    };
  }, []);

  // 关闭当前公告，继续展示下一条（如有）
  const advance = () => {
    setIndex((prev) => prev + 1);
  };

  // 知道了：仅写入 sessionStorage，当前浏览器会话内不再弹出
  const handleAcknowledge = () => {
    if (!current) return;
    sessionStorage.setItem(SESSION_KEY_PREFIX + current.id, '1');
    advance();
  };

  // 不再显示：以后端 dismiss 为准，成功后再本地缓存，永久不再显示
  const handleNeverShow = async () => {
    if (!current) return;
    setDismissing(true);
    try {
      const res = await dismissAnnouncement(current.id);
      if (res.code === 0) {
        localStorage.setItem(NEVER_KEY_PREFIX + current.id, '1');
        sessionStorage.setItem(SESSION_KEY_PREFIX + current.id, '1');
        advance();
      } else {
        toast.error(res.msg || '操作失败，请稍后重试');
      }
    } catch (error) {
      console.error('关闭公告失败:', error);
      toast.error('操作失败，请稍后重试');
    } finally {
      setDismissing(false);
    }
  };

  return (
    <Modal
      isOpen={!!current}
      hideCloseButton
      isDismissable={false}
      isKeyboardDismissDisabled={true}
      backdrop="blur"
      placement="center"
      size="md"
      scrollBehavior="inside"
    >
      <ModalContent>
        <ModalHeader className="flex flex-col gap-1">
          <div className="flex items-center gap-2">
            <div className="p-1.5 bg-primary-100 dark:bg-primary-500/20 rounded-lg">
              <svg className="w-5 h-5 text-primary" fill="currentColor" viewBox="0 0 20 20">
                <path fillRule="evenodd" d="M10 1a6 6 0 00-6 6v3.586l-.707.707A1 1 0 003.586 13h12.828a1 1 0 00.707-1.707L16 10.586V7a6 6 0 00-6-6zM8 15a2 2 0 004 0H8z" clipRule="evenodd" />
              </svg>
            </div>
            <span className="text-base font-semibold text-foreground">系统公告</span>
          </div>
        </ModalHeader>
        <ModalBody>
          {current && (
            <div className="space-y-3">
              <h3 className="text-base font-semibold text-foreground">{current.title}</h3>
              <div className="text-sm leading-relaxed text-default-700 dark:text-default-300 whitespace-pre-wrap break-words">
                {current.content}
              </div>
            </div>
          )}
        </ModalBody>
        <ModalFooter>
          <Button
            variant="bordered"
            onPress={handleNeverShow}
            isLoading={dismissing}
            isDisabled={dismissing}
          >
            不再显示
          </Button>
          <Button
            color="primary"
            onPress={handleAcknowledge}
            isDisabled={dismissing}
          >
            知道了
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}
