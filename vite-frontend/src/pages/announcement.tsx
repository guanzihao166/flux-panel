import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button } from "@heroui/button";
import { Card, CardBody, CardHeader } from "@heroui/card";
import { Input } from "@heroui/input";
import { Chip } from "@heroui/chip";
import { Modal, ModalContent, ModalHeader, ModalBody, ModalFooter } from "@heroui/modal";
import { Spinner } from "@heroui/spinner";
import toast from 'react-hot-toast';

import {
  getAnnouncementAdminList,
  saveAnnouncement,
  deleteAnnouncement,
  normalizeAnnouncementList,
  type Announcement,
} from '@/api';
import { isAdmin } from '@/utils/auth';

const MegaphoneIcon = ({ className }: { className?: string }) => (
  <svg className={className} fill="currentColor" viewBox="0 0 20 20">
    <path fillRule="evenodd" d="M10 1a6 6 0 00-6 6v3.586l-.707.707A1 1 0 003.586 13h12.828a1 1 0 00.707-1.707L16 10.586V7a6 6 0 00-6-6zM8 15a2 2 0 004 0H8z" clipRule="evenodd" />
  </svg>
);

interface AnnouncementForm {
  id?: number;
  title: string;
  content: string;
  status: number;
}

const formatTime = (value?: string | number): string => {
  if (value === undefined || value === null || value === '') return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return date.toLocaleString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hour12: false,
  });
};

export default function AnnouncementPage() {
  const navigate = useNavigate();

  const [list, setList] = useState<Announcement[]>([]);
  const [listLoading, setListLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form, setForm] = useState<AnnouncementForm>({ title: '', content: '', status: 0 });

  const [deleteTarget, setDeleteTarget] = useState<Announcement | null>(null);
  const [deleteLoading, setDeleteLoading] = useState(false);

  // 权限检查：仅管理员可访问
  useEffect(() => {
    if (!isAdmin()) {
      toast.error('权限不足，只有管理员可以访问此页面');
      navigate('/dashboard', { replace: true });
      return;
    }
  }, [navigate]);

  const loadList = useCallback(async () => {
    setListLoading(true);
    try {
      const res = await getAnnouncementAdminList();
      if (res.code === 0) {
        setList(normalizeAnnouncementList(res.data));
      } else {
        toast.error(res.msg || '获取公告列表失败');
      }
    } catch (error) {
      console.error('获取公告列表失败:', error);
      toast.error('获取公告列表失败');
    } finally {
      setListLoading(false);
    }
  }, []);

  useEffect(() => {
    loadList();
  }, [loadList]);

  const resetForm = () => {
    setForm({ title: '', content: '', status: 0 });
  };

  const handleEdit = (item: Announcement) => {
    setForm({
      id: item.id,
      title: item.title || '',
      content: item.content || '',
      status: item.status,
    });
    // 移动端编辑时滚动到表单区域
    requestAnimationFrame(() => {
      document.getElementById('announcement-form')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    });
  };

  // status: 0-保存草稿(新增时) / 保持原状态(编辑时)；1-直接发布
  const handleSave = async (publish: boolean) => {
    if (!form.title.trim()) {
      toast.error('请输入公告标题');
      return;
    }
    if (!form.content.trim()) {
      toast.error('请输入公告内容');
      return;
    }

    setSaving(true);
    try {
      const status = publish ? 1 : (form.id !== undefined ? form.status : 0);
      const res = await saveAnnouncement({
        id: form.id,
        title: form.title.trim(),
        content: form.content,
        status,
      });
      if (res.code === 0) {
        toast.success(publish ? '公告已发布' : (form.id !== undefined ? '公告已更新' : '草稿已保存'));
        resetForm();
        loadList();
      } else {
        toast.error(res.msg || (publish ? '发布失败' : '保存失败'));
      }
    } catch (error) {
      console.error('保存公告失败:', error);
      toast.error(publish ? '发布失败' : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    setDeleteLoading(true);
    try {
      const res = await deleteAnnouncement(deleteTarget.id);
      if (res.code === 0) {
        toast.success('公告已删除');
        // 如果删除的正是正在编辑的公告，清空表单
        if (form.id === deleteTarget.id) {
          resetForm();
        }
        setDeleteTarget(null);
        loadList();
      } else {
        toast.error(res.msg || '删除失败');
      }
    } catch (error) {
      console.error('删除公告失败:', error);
      toast.error('删除失败');
    } finally {
      setDeleteLoading(false);
    }
  };

  return (
    <div className="px-3 lg:px-6 py-2 lg:py-4">
      <div className="grid grid-cols-1 lg:grid-cols-5 gap-4 lg:gap-6">
        {/* 左侧：编辑表单 */}
        <div id="announcement-form" className="lg:col-span-2">
          <Card className="border border-gray-200 dark:border-default-200 shadow-md">
            <CardHeader className="pb-3">
              <div className="flex items-center gap-2">
                <div className="p-1.5 bg-primary-100 dark:bg-primary-500/20 rounded-lg">
                  <MegaphoneIcon className="w-5 h-5 text-primary" />
                </div>
                <div>
                  <h2 className="text-lg font-semibold text-foreground">
                    {form.id !== undefined ? '编辑公告' : '新增公告'}
                  </h2>
                  <p className="text-xs text-default-500">
                    {form.id !== undefined ? `正在编辑 #${form.id}` : '填写标题与正文，可直接发布'}
                  </p>
                </div>
              </div>
            </CardHeader>
            <CardBody className="pt-2 space-y-4">
              <Input
                label="公告标题"
                placeholder="请输入公告标题"
                value={form.title}
                onChange={(e) => setForm((prev) => ({ ...prev, title: e.target.value }))}
                variant="bordered"
                maxLength={100}
              />
              <div className="space-y-1.5">
                <label className="text-sm font-medium text-gray-700 dark:text-gray-300">公告正文</label>
                <textarea
                  value={form.content}
                  onChange={(e) => setForm((prev) => ({ ...prev, content: e.target.value }))}
                  placeholder="请输入公告正文内容"
                  rows={10}
                  className="w-full px-3 py-2.5 rounded-xl border-2 border-default-200 dark:border-default-100 bg-transparent text-sm text-foreground placeholder:text-default-400 outline-none transition-colors focus:border-primary resize-y min-h-[160px]"
                />
              </div>
              <div className="flex flex-wrap items-center gap-2 pt-1">
                <Button
                  color="primary"
                  variant="solid"
                  onPress={() => handleSave(true)}
                  isLoading={saving}
                >
                  {form.id !== undefined ? '更新并发布' : '直接发布'}
                </Button>
                <Button
                  variant="bordered"
                  onPress={() => handleSave(false)}
                  isLoading={saving}
                >
                  {form.id !== undefined ? '保存修改' : '保存草稿'}
                </Button>
                {form.id !== undefined && (
                  <Button variant="light" onPress={resetForm}>
                    取消编辑
                  </Button>
                )}
              </div>
            </CardBody>
          </Card>
        </div>

        {/* 右侧：公告列表 */}
        <div className="lg:col-span-3">
          <Card className="border border-gray-200 dark:border-default-200 shadow-md">
            <CardHeader className="pb-3">
              <div className="flex items-center justify-between w-full">
                <div className="flex items-center gap-2">
                  <svg className="w-5 h-5 text-primary" fill="currentColor" viewBox="0 0 20 20">
                    <path fillRule="evenodd" d="M3 5a2 2 0 012-2h10a2 2 0 012 2v10a2 2 0 01-2 2H5a2 2 0 01-2-2V5zm5-1v12h4V4H8z" clipRule="evenodd" />
                  </svg>
                  <h2 className="text-lg font-semibold text-foreground">公告历史</h2>
                  <span className="px-2 py-0.5 bg-default-100 dark:bg-default-50 text-default-600 rounded-full text-xs">
                    {list.length}
                  </span>
                </div>
                <Button size="sm" variant="light" onPress={loadList}>
                  刷新
                </Button>
              </div>
            </CardHeader>
            <CardBody className="pt-0">
              {listLoading ? (
                <div className="flex items-center justify-center py-16">
                  <Spinner size="lg" label="加载中..." />
                </div>
              ) : list.length === 0 ? (
                <div className="text-center py-16">
                  <MegaphoneIcon className="w-12 h-12 text-default-400 mx-auto mb-3" />
                  <p className="text-default-500">暂无公告，请先在左侧新增</p>
                </div>
              ) : (
                <div className="space-y-3 max-h-[560px] overflow-y-auto pr-1">
                  {list.map((item) => (
                    <div
                      key={item.id}
                      className="border border-gray-200 dark:border-default-100 rounded-lg p-3 lg:p-4 hover:shadow-md transition-shadow"
                    >
                      <div className="flex items-start justify-between gap-3">
                        <div className="flex-1 min-w-0">
                          <div className="flex flex-wrap items-center gap-2 mb-1">
                            <h3 className="font-semibold text-foreground truncate">{item.title}</h3>
                            <Chip
                              size="sm"
                              variant="flat"
                              color={item.status === 1 ? 'success' : 'default'}
                            >
                              {item.status === 1 ? '已发布' : '草稿'}
                            </Chip>
                          </div>
                          <p className="text-xs text-default-500">
                            {item.status === 1 && item.publishedTime
                              ? `发布时间：${formatTime(item.publishedTime)}`
                              : `创建时间：${formatTime(item.createdTime)}`}
                          </p>
                        </div>
                        <div className="flex items-center gap-1 flex-shrink-0">
                          <Button size="sm" variant="light" onPress={() => handleEdit(item)}>
                            编辑
                          </Button>
                          <Button
                            size="sm"
                            variant="light"
                            color="danger"
                            onPress={() => setDeleteTarget(item)}
                          >
                            删除
                          </Button>
                        </div>
                      </div>
                      <p className="mt-2 text-sm text-default-600 dark:text-default-400 whitespace-pre-wrap break-words line-clamp-3">
                        {item.content}
                      </p>
                    </div>
                  ))}
                </div>
              )}
            </CardBody>
          </Card>
        </div>
      </div>

      {/* 删除确认弹窗 */}
      <Modal
        isOpen={!!deleteTarget}
        onClose={() => setDeleteTarget(null)}
        hideCloseButton={false}
        backdrop="blur"
        placement="center"
        size="sm"
      >
        <ModalContent>
          <ModalHeader className="text-base">删除公告</ModalHeader>
          <ModalBody>
            <p className="text-sm text-default-600 dark:text-default-400">
              确定要删除公告「{deleteTarget?.title}」吗？删除后不可恢复。
            </p>
          </ModalBody>
          <ModalFooter>
            <Button variant="light" onPress={() => setDeleteTarget(null)} isDisabled={deleteLoading}>
              取消
            </Button>
            <Button color="danger" onPress={handleDelete} isLoading={deleteLoading}>
              确认删除
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}
